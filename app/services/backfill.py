"""Досчёт векторов фрагментам, у которых их нет.

Пригодится после сбоя и после смены модели: повторный запуск
ничего не пересчитывает (вектор уже записан — фрагмент не в pending).

Запуск вручную:  python -m app.services.backfill [document_id]
"""
import asyncio

from app.core import db
from app.core.config import settings
from app.core.logging import get_logger, setup_logging
from app.embeddings import client as emb
from app.repositories import documents as docs_repo

log = get_logger(__name__)


async def backfill(document_id: int | None = None) -> int:
    """Досчитать векторы пачками; вернуть число добавленных. Повторный запуск — 0."""
    total_new = 0
    while True:
        with db.connection() as conn:
            pending = docs_repo.chunk_texts_for_embedding(conn, document_id)
        if not pending:
            break
        ids = [cid for cid, _t in pending]
        texts = [t for _cid, t in pending]
        vectors = await emb.embed_documents(texts)
        with db.connection() as conn:
            docs_repo.store_embeddings(
                conn, list(zip(ids, vectors)), settings.emb_model, settings.emb_dim
            )
            remaining = docs_repo.pending_embed_count(conn)
        total_new += len(ids)
        log.info("backfill_progress", done=total_new, remaining=remaining, document_id=document_id)
    log.info("backfill_complete", total_new=total_new, document_id=document_id)
    return total_new


def main() -> None:
    import sys

    setup_logging()
    db.init_pool()
    doc_id = int(sys.argv[1]) if len(sys.argv) > 1 else None
    try:
        count = asyncio.run(backfill(doc_id))
    finally:
        db.close_pool()  # httpx-клиент эмбеддингов умирает вместе с процессом
    print(f"new vectors: {count}")


if __name__ == "__main__":
    main()
