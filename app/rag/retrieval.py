"""Retrieval-фасад: единая точка поиска с режимами и фильтрами.

Режимы: vector (семантика), fts (точные слова), hybrid (RRF — по умолчанию).
Фильтры по метаданным: doc_ids, brand.
"""
from app.core import db
from app.core.config import settings
from app.repositories import chunks as chunks_repo


def search(
    query: str,
    *,
    mode: str = "hybrid",
    doc_ids: list[str] | None = None,
    brand: str | None = None,
    top_k: int | None = None,
) -> list[dict]:
    """Поиск чанков. Возвращает список {chunk_id, doc_id, text, metadata, filename, score}."""
    searchers = {
        "vector": chunks_repo.search_vector,
        "fts": chunks_repo.search_fts,
        "hybrid": chunks_repo.search_hybrid,
    }
    if mode not in searchers:
        raise ValueError(f"неизвестный режим: {mode}")
    with db.connection() as conn:
        results = searchers[mode](conn, query, top_k=top_k, doc_ids=doc_ids)
    if brand:
        results = [r for r in results if r["metadata"].get("brand") == brand]
    return results


if __name__ == "__main__":
    import sys

    from app.core.logging import setup_logging

    setup_logging()
    q = sys.argv[1] if len(sys.argv) > 1 else "мощность блендера"
    m = sys.argv[2] if len(sys.argv) > 2 else "hybrid"
    print(f"=== запрос: {q!r}, режим: {m}")
    for r in search(q, mode=m, top_k=settings.retrieval_top_k):
        meta = r["metadata"]
        print(
            f"  {r['chunk_id']} [{r['filename']}] p.{meta.get('page')} "
            f"score={r.get('score') or r.get('rrf_score'):.3f} | {r['text'][:70]!r}"
        )
