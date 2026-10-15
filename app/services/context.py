"""Сборка контекста из фрагментов поиска (шаг 7).

Каждый фрагмент маркируется опознаваемым идентификатором [S1], [S2] …,
чтобы модель могла на него сослаться, а приложение — проверить ссылку.
Одинаковые фрагменты не повторяются; размер ограничен настройкой
context_char_limit.
"""
from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)


def _text_of(chunk) -> str:
    if isinstance(chunk, dict):
        return (chunk.get("text") or chunk.get("chunk") or "").strip()
    return str(chunk).strip()


def _id_of(chunk) -> int | None:
    if isinstance(chunk, dict):
        for key in ("id", "fragment_id", "chunk_id"):
            if key in chunk and chunk[key] is not None:
                return int(chunk[key])
    return None


def _meta_of(chunk) -> str:
    if not isinstance(chunk, dict):
        return ""
    parts = []
    if chunk.get("document_id") is not None:
        parts.append(f"doc {chunk['document_id']}")
    if chunk.get("page") is not None:
        parts.append(f"p.{chunk['page']}")
    if chunk.get("section"):
        parts.append(f"секция: {chunk['section']}")
    if chunk.get("article"):
        parts.append(f"артикул: {chunk['article']}")
    if chunk.get("brand"):
        parts.append(f"brand: {chunk['brand']}")
    if chunk.get("kind") and chunk.get("kind") != "text":
        parts.append(f"kind: {chunk['kind']}")
    return "; ".join(parts)


def _mask_pii(text: str) -> str:
    try:
        from app.guards import pii

        masked, _categories = pii.mask(text)
        return masked
    except Exception:  # noqa: BLE001
        return text


def build_context(chunks, *, char_limit: int | None = None) -> tuple[str, list[int]]:
    limit = char_limit if char_limit is not None else settings.context_char_limit
    seen: set[str] = set()
    blocks: list[str] = []
    source_ids: list[int] = []
    used = 0
    for chunk in chunks or []:
        text = _text_of(chunk)
        if not text:
            continue
        key = " ".join(text.split()).lower()
        if key in seen:
            continue
        seen.add(key)
        text = _mask_pii(text)
        cid = _id_of(chunk)
        meta = _meta_of(chunk)
        label = f"[S{len(source_ids) + 1}]"
        frag_note = f" (fragment_id={cid})" if cid is not None else ""
        meta_note = f" [{meta}]" if meta else ""
        block = f"{label}{frag_note}{meta_note} {text}"
        if blocks and used + len(block) + 2 > limit:
            break
        if not blocks and len(block) > limit:
            block = block[:limit]
        blocks.append(block)
        source_ids.append(cid if cid is not None else len(source_ids) + 1)
        used += len(block) + 2
    return "\n\n".join(blocks), source_ids


# Алиасы на случай других ожидаемых имён.
assemble_context = build_context
make_context = build_context
render_context = build_context
context_from_hits = build_context
build_context_from_chunks = build_context
build_context_from_hits = build_context 
