"""Сборка промпт-контекста из чанков retrieval.

Каждый чанк оформляется источником: [chunk_id | файл | стр. N | секция].
Контекст сопровождается жёсткой инструкцией «отвечай только по контексту».
"""

from app.core.config import settings


def build_context(chunks: list[dict], *, max_chars: int | None = None) -> str:
    """Чанки → текст контекста с шапками-источниками, лимит по символам.

    max_chars по умолчанию — settings.context_char_limit.
    """
    limit = settings.context_char_limit if max_chars is None else max_chars
    parts: list[str] = []
    total = 0
    seen: set[str] = set()
    for ch in chunks:
        if ch["chunk_id"] in seen:
            continue
        seen.add(ch["chunk_id"])
        meta = ch["metadata"]
        header = f"[{ch['chunk_id']} | {ch.get('filename', meta.get('doc_id', ''))}"
        if meta.get("page"):
            header += f" | стр. {meta['page']}"
        if meta.get("section"):
            header += f" | {meta['section']}"
        header += "]"
        body = header + "\n" + ch["text"]
        if total + len(body) > limit and parts:
            break
        parts.append(body)
        total += len(body)
    return "\n\n".join(parts)


def context_chunk_ids(context: str) -> set[str]:
    """chunk_id, реально вошедшие в контекст (для валидации источников)."""
    import re

    return set(re.findall(r"^\[([a-z0-9]+) \|", context, flags=re.MULTILINE))
