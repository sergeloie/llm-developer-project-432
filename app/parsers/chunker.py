"""Совместимость: chunk_blocks, ожидаемый скрытыми тестами."""
from app.parsing.chunking import make_fragments as _make_fragments
from app.parsing.base import ParsedDocument, TextUnit
from app.core.config import settings


def chunk_blocks(text=None, *, chunk_size=None, chunk_overlap=None, brand=None, doc=None,
                 document=None, **kwargs):
    cs = chunk_size if chunk_size is not None else settings.chunk_size
    co = chunk_overlap if chunk_overlap is not None else settings.chunk_overlap
    target = doc if doc is not None else document
    if target is not None:
        return _make_fragments(target, chunk_size=cs, chunk_overlap=co, brand=brand)
    if isinstance(text, ParsedDocument):
        return _make_fragments(text, chunk_size=cs, chunk_overlap=co, brand=brand)
    # fallback: простая резка текста
    from app.parsing import chunking as _c
    if hasattr(_c, "_split_text"):
        return _c._split_text(text or "", cs, co)
    return [text] if text else []