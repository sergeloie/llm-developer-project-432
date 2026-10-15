"""Эмбеддинги локально через LM Studio (/v1/embeddings).

Контракт модели embeddinggemma (из карточки модели, не из головы):
запрос и документ кодируются с РАЗНЫМИ task-префиксами:
- документ: "task: search document | "
- запрос:   "task: search result | "
Без префиксов близость релевантных пар проседает и порог отсечения
выметает верные результаты.

Клиент: один httpx-клиент на процесс; тексты уезжают ПАЧКОЙ
(в одном запросе все фрагменты индексации, а не по одному).
"""
import asyncio

import httpx

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

_client: httpx.AsyncClient | None = None


def client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            base_url=settings.llm_base_url,
            timeout=httpx.Timeout(settings.emb_timeout_s, connect=10.0),
        )
    return _client


async def aclose() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


async def _embed(texts: list[str], prefix: str) -> list[list[float]]:
    if not texts:
        return []
    payload = {
        "model": settings.emb_model,
        "input": [prefix + t for t in texts],
    }
    last_exc: Exception | None = None
    # ретраи на 429/5xx/сеть — та же политика, что у LLM-клиента
    for attempt in range(1, settings.llm_max_retries + 1):
        try:
            resp = await client().post("/embeddings", json=payload)
            if resp.status_code != 200:
                raise RuntimeError(f"embeddings http {resp.status_code}: {resp.text[:200]}")
            data = resp.json()
            out = data["data"]
            if len(out) != len(texts):
                raise RuntimeError(
                    f"embeddings: получили {len(out)} векторов на {len(texts)} текстов"
                )
            vectors = [item["embedding"] for item in out]
            for v in vectors:
                if len(v) != settings.emb_dim:
                    raise RuntimeError(
                        f"размерность {len(v)} != {settings.emb_dim}; поправьте EMB_DIM"
                    )
            return vectors
        except (httpx.TimeoutException, httpx.TransportError, RuntimeError) as exc:
            last_exc = exc
            log.warning("embed_retry", attempt=attempt, error=str(exc))
            await asyncio.sleep(min(0.5 * 2**attempt, 8.0) * (1 + attempt * 0.1))
    raise RuntimeError(f"эмбеддинги недоступны: {last_exc}")


async def embed_documents(texts: list[str]) -> list[list[float]]:
    """Векторы для фрагментов (документный префикс)."""
    return await _embed(texts, settings.emb_doc_prefix)


async def embed_query(query: str) -> list[float]:
    """Вектор запроса (префикс запроса — он differs от документного)."""
    return (await _embed([query], settings.emb_query_prefix))[0]
