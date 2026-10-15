"""Загрузка документов поставщиков (шаг 5): файл → 202 + id, статус по чтению."""
import hashlib
import shutil
from pathlib import Path

from fastapi import APIRouter, HTTPException, UploadFile

from app.core import db
from app.core.config import settings
from app.repositories import documents as docs_repo

router = APIRouter(tags=["documents"])

UPLOAD_DIR = Path("uploads")


def _save_upload(upload: UploadFile) -> tuple[Path, str]:
    """Сохранить файл на диск; вернуть (путь, sha256)."""
    UPLOAD_DIR.mkdir(exist_ok=True)
    hasher = hashlib.sha256()
    tmp = UPLOAD_DIR / f".tmp-{upload.filename}"
    with tmp.open("wb") as f:
        while chunk := upload.file.read(1 << 20):
            hasher.update(chunk)
            f.write(chunk)
    sha = hasher.hexdigest()
    final = UPLOAD_DIR / f"{sha[:16]}-{Path(upload.filename).name}"
    shutil.move(str(tmp), str(final))
    return final, sha


@router.post("/documents", status_code=202)
async def upload_document(upload: UploadFile) -> dict:
    filename = upload.filename or "file"
    ext = Path(filename).suffix.lower()
    if ext not in settings.upload_allowed_ext:
        raise HTTPException(415, f"формат {ext} не поддерживается")
    file_path, sha = _save_upload(upload)
    size = file_path.stat().st_size
    if size > settings.upload_max_mb << 20:
        file_path.unlink(missing_ok=True)
        raise HTTPException(413, "файл слишком большой")
    with db.connection() as conn:
        doc_id, created = docs_repo.create_document(
            conn, filename, sha, size, str(file_path)
        )
        existing = docs_repo.get_document_with_count(conn, doc_id)
    if not created:
        return {"id": doc_id, "status": existing["status"], "duplicate": True}
    from app.temporal.doc_kickoff import start_document_parse

    await start_document_parse(doc_id, str(file_path))
    return {"id": doc_id, "status": "new"}


@router.get("/documents/{doc_id}")
async def document_status(doc_id: int) -> dict:
    with db.connection() as conn:
        info = docs_repo.get_document_with_count(conn, doc_id)
    if not info:
        raise HTTPException(404, "документ не найден")
    return info


@router.get("/documents")
async def documents_list() -> dict:
    with db.connection() as conn:
        docs = docs_repo.list_documents(conn)
    return {"documents": docs}
