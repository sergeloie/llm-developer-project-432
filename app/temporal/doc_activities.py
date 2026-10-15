"""Activities документного конвейера (вне песочницы: диск, CPU, БД)."""
import json

from temporalio import activity

from app.core import db
from app.core.logging import get_logger
from app.repositories import documents as docs_repo
from app.services import documents as docs_service

log = get_logger(__name__)


@activity.defn
async def parse_document_activity(document_id: int, path: str) -> str:
    """Блокирующий разбор файла (CPU/диск) → фрагменты (JSON-список).

    Асинхронная defn с asyncio.to_thread: не занимает event-loop воркера.
    Ошибка разбора → документ получает статус failed с причиной (не тихий пропуск).
    """
    import asyncio

    def _blocking() -> str:
        try:
            fragments = docs_service.fragments_from_file(path)
            return json.dumps(fragments, ensure_ascii=False)
        except Exception as exc:
            msg = str(exc)[:500]
            with db.connection() as conn:
                docs_repo.set_document_status(conn, document_id, "failed", msg)
            raise ParseFailed(msg) from exc

    try:
        return await asyncio.to_thread(_blocking)
    except ParseFailed as exc:
        activity.logger.error("document_parse_failed", document_id=document_id, error=str(exc))
        raise


class ParseFailed(Exception):
    pass


@activity.defn
def store_fragments_activity(document_id: int, fragments_json: str) -> int:
    """Сохранить фрагменты и пометить документ проиндексированным."""
    fragments = json.loads(fragments_json)
    with db.connection() as conn:
        n = docs_repo.replace_chunks(conn, document_id, fragments)
        docs_repo.set_document_status(conn, document_id, "indexed", None)
    return n


@activity.defn
def set_doc_status_activity(document_id: int, status: str, error: str | None = None) -> None:
    with db.connection() as conn:
        docs_repo.set_document_status(conn, document_id, status, error)


@activity.defn
def doc_info_activity(document_id: int) -> str:
    """Счётчик фрагментов — для завершающего отчёта процесса."""
    with db.connection() as conn:
        info = docs_repo.get_document_with_count(conn, document_id)
    return json.dumps(info, ensure_ascii=False)
