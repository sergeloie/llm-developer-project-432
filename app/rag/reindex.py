"""Переиндексация: досчитать embeddings для чанков без векторов.

Запуск: python -m app.rag.reindex (или make reindex).
"""
from app.core import db
from app.core.logging import get_logger, setup_logging
from app.rag.embedder import embed_documents
from app.repositories import chunks as chunks_repo
from app.repositories import documents as docs_repo

log = get_logger(__name__)


def index_pending_chunks(conn, doc_id: str | None = None, batch: int = 256) -> int:
    """Досчитать embeddings для чанков без векторов (опционально одного документа).

    Работает на переданном соединении — используется и ingestion'ом, и reindex_all.
    """
    total = 0
    while True:
        pending = chunks_repo.chunks_without_embedding(conn, limit=batch, doc_id=doc_id)
        if not pending:
            break
        ids = [c[0] for c in pending]
        texts = [c[1] for c in pending]
        vectors = embed_documents(texts)
        chunks_repo.upsert_embeddings(conn, list(zip(ids, vectors)))
        total += len(ids)
        log.info("reindex_batch", batch=len(ids), total=total, doc_id=doc_id or "all")
    if total:
        # документ с неполными векторами возвращается в выдачу поиска
        docs_repo.reconcile_indexed(conn, doc_id)
    return total


def reindex_all(batch: int = 256) -> int:
    with db.connection() as conn:
        return index_pending_chunks(conn, batch=batch)


if __name__ == "__main__":
    setup_logging()
    try:
        n = reindex_all()
    finally:
        db.close_pool()
    print(f"Переиндексировано чанков: {n}")
