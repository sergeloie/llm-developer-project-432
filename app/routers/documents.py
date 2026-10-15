"""Роутер документов поставщиков: multipart-загрузка → разбор процессом → статус."""
from fastapi import APIRouter, HTTPException, UploadFile

from app.core import db
from app.core.config import settings
from app.repositories import documents as docs_repo
from app.services.documents import ALLOWED_EXTENSIONS, MAX_FILE_SIZE
from app.temporal.client import temporal_client
from app.temporal.workflows import ParseDocumentWorkflow

router = APIRouter(tags=["documents"], prefix="/documents")


@router.post("/", status_code=202, include_in_schema=True)
async def upload_document(file: UploadFile):
    ext = "." + (file.filename or "").rsplit(".", 1)[-1].lower()
    if ext not in ALLOWED_EXTENSIONS:
        raise HTTPException(415, f"поддерживаются: {sorted(ALLOWED_EXTENSIONS)}")
    content = await file.read()
    if len(content) > MAX_FILE_SIZE:
        raise HTTPException(413, f"файл больше {settings.upload_max_mb} МБ")
    if not content:
        raise HTTPException(400, "пустой файл")

    with db.connection() as conn:
        doc_id, created = docs_repo.create_document(
            conn, file.filename, content, ALLOWED_EXTENSIONS[ext]
        )

    if created:  # дубликат по хэшу не парсим повторно
        # Файл пишем во временное хранилище: воркер прочитает по пути.
        # В историю процесса уходит путь, а не содержимое файла.
        import tempfile
        from pathlib import Path

        tmp_dir = Path(tempfile.gettempdir()) / "product-card-ai"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        tmp_path = tmp_dir / doc_id
        tmp_path.write_bytes(content)
        client = await temporal_client()
        await client.start_workflow(
            ParseDocumentWorkflow.run,
            args=[doc_id, str(tmp_path), ALLOWED_EXTENSIONS[ext]],
            id=f"parse-{doc_id}",
            task_queue=settings.temporal_task_queue,
        )
    return {"id": doc_id, "status": "new" if created else "already indexed"}


@router.get("")
async def list_documents():
    with db.connection() as conn:
        return docs_repo.list_documents(conn)


@router.get("/{doc_id}")
async def get_document(doc_id: str):
    with db.connection() as conn:
        doc = docs_repo.get_document(conn, doc_id)
    if not doc:
        raise HTTPException(404, "document not found")
    return doc
