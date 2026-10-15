"""Embeddings: OpenAI-совместимый API (LM Studio) или sentence-transformers.

Бэкенд выбирает settings.embed_backend (EMBED_BACKEND): lmstudio — локальный
OpenAI-совместимый API, модель уже загружена и ничего не скачивается;
hf — SentenceTransformer, качает веса при первом запуске.
Документы считаются пачкой: один embeddings.create на всю пачку входов.
Ошибка вызова поднимается наружу — тихий нулевой вектор недопустим.
"""
from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

QUERY_PREFIX = "task: search result | query: "
DOC_PREFIX = "task: search result | title: none | text: "

LMSTUDIO_BACKENDS = {"lmstudio", "openai", "openai-compatible", "oai"}

_model = None
_client = None


def _use_lmstudio_embeddings() -> bool:
    return settings.embed_backend.strip().lower() in LMSTUDIO_BACKENDS


def _get_model():
    global _model
    if _model is None:
        if _use_lmstudio_embeddings():
            log.info("embed_backend", backend="lmstudio", model=settings.embed_model)
            return None
        from sentence_transformers import SentenceTransformer

        log.info("embed_model_loading", model=settings.embed_model, device=settings.embed_device)
        _model = SentenceTransformer(settings.embed_model, device=settings.embed_device)
    return _model


def _lmstudio_client():
    global _client
    if _client is None:
        from openai import OpenAI

        _client = OpenAI(
            base_url=settings.embed_base_url or settings.llm_base_url,
            api_key=settings.embed_api_key or settings.llm_api_key,
            timeout=settings.embed_timeout_s,
        )
    return _client


def _normalize(vec: list[float]) -> list[float]:
    norm = sum(x * x for x in vec) ** 0.5
    if norm == 0:
        return vec
    return [x / norm for x in vec]


def _embed_text(text: str, normalized: bool = True) -> list[float]:
    if _use_lmstudio_embeddings():
        client = _lmstudio_client()
        response = client.embeddings.create(model=settings.embed_model, input=text)
        vector = response.data[0].embedding
        if normalized:
            return _normalize(vector)
        return vector
    model = _get_model()
    return model.encode(QUERY_PREFIX + text if normalized else text, normalize_embeddings=normalized).tolist()


def embed_query(text: str) -> list[float]:
    if _use_lmstudio_embeddings():
        return _embed_text(QUERY_PREFIX + text, normalized=True)
    return _get_model().encode(QUERY_PREFIX + text, normalize_embeddings=True).tolist()


def _embed_batch(texts: list[str]) -> list[list[float]]:
    """Один embeddings.create на всю пачку; порядок ответов — по index."""
    client = _lmstudio_client()
    response = client.embeddings.create(
        model=settings.embed_model, input=[DOC_PREFIX + t for t in texts]
    )
    ordered = sorted(response.data, key=lambda item: item.index)
    if len(ordered) != len(texts):
        raise RuntimeError(
            f"embeddings.create вернул {len(ordered)} векторов на {len(texts)} входов"
        )
    log.info("embed_batch", backend="lmstudio", inputs=len(texts), model=settings.embed_model)
    return [_normalize(item.embedding) for item in ordered]


def embed_documents(texts: list[str], batch_size: int = 32) -> list[list[float]]:
    if not texts:
        return []
    if _use_lmstudio_embeddings():
        return _embed_batch(texts)
    model = _get_model()
    vectors = model.encode(
        [DOC_PREFIX + t for t in texts],
        batch_size=batch_size,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return vectors.tolist()
