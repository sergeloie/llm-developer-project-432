"""Поиск: векторный, лексический, гибрид — над чанками в базе."""
import math

from app.core import db
from app.core.config import settings
from app.core.logging import get_logger
from app.embeddings import client as emb
from app.repositories import documents as docs_repo

log = get_logger(__name__)

VECTOR = "vector"
WORDS = "words"
HYBRID = "hybrid"


async def search(
    query: str,
    *,
    mode: str = HYBRID,
    top_k: int | None = None,
    threshold: float | None = None,
    document_ids: list[int] | None = None,
) -> list[dict]:
    """Единая точка поиска для обработчиков и пайплайна.

    Порог по умолчанию — калиброванное значение из настроек
    (значение и калибровка записаны в PROGRESS.md).
    """
    threshold = settings.search_relevance_threshold if threshold is None else threshold
    top_k = top_k or settings.search_top_k
    with db.connection() as conn:
        if mode == WORDS:
            return docs_repo.search_words(
                conn, query, top_k=top_k, document_ids=document_ids
            )
        v = await emb.embed_query(query)
        if mode == VECTOR:
            return docs_repo.search_vector(
                conn, v, top_k=top_k, threshold=threshold, document_ids=document_ids
            )
        if mode == HYBRID:
            result = docs_repo.search_hybrid(
                conn, query, v, top_k=top_k, threshold=threshold, document_ids=document_ids
            )
            log.info("hybrid_search", merged=len(result))
            return result
        raise ValueError(f"неизвестный режим поиска: {mode}")


def rrf_fuse(rankings: list[list[int]], k: int = 60) -> list[int]:
    """Чистая функция слияния для тестов: id по убыванию RRF-вклада."""
    scores: dict[int, float] = {}
    for ranking in rankings:
        for pos, cid in enumerate(ranking, start=1):
            scores[cid] = scores.get(cid, 0.0) + 1.0 / (k + pos)
    return [cid for cid, _ in sorted(scores.items(), key=lambda kv: kv[1], reverse=True)]


def cosine(a: list[float], b: list[float]) -> float:
    """Косинусная близость — для тестов на заранее заданных векторах."""
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)
