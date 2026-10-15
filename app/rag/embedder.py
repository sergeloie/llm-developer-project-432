"""Локальные embeddings: sentence-transformers, модель embeddinggemma-300m.

Готча модели: запросы и документы эмбеддятся с РАЗНЫМИ префиксами —
без них качество проседает:
  query:    "task: search result | query: <текст>"
  document: "task: search result | title: none | text: <текст>"
Модель грузится лениво (первый вызов), эмбеддинги считаются батчами.
"""
from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

QUERY_PREFIX = "task: search result | query: "
DOC_PREFIX = "task: search result | title: none | text: "

_model = None


def _get_model():
    global _model
    if _model is None:
        from sentence_transformers import SentenceTransformer

        log.info("embed_model_loading", model=settings.embed_model, device=settings.embed_device)
        _model = SentenceTransformer(settings.embed_model, device=settings.embed_device)
    return _model


def embed_query(text: str) -> list[float]:
    return _get_model().encode(QUERY_PREFIX + text, normalize_embeddings=True).tolist()


def embed_documents(texts: list[str], batch_size: int = 32) -> list[list[float]]:
    if not texts:
        return []
    model = _get_model()
    vectors = model.encode(
        [DOC_PREFIX + t for t in texts],
        batch_size=batch_size,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return vectors.tolist()
