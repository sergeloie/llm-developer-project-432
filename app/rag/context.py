"""Сборка промпт-контекста из чанков retrieval.

Каждый чанк оформляется источником: [chunk_id | файл | стр. N | секция].
Контекст сопровождается жёсткой инструкцией «отвечай только по контексту».
"""

from app.core.config import settings

CONTEXT_INSTRUCTION = """Ты получаешь КОНТЕКСТ — фрагменты документов поставщика.
Правила:
1. Отвечай ТОЛЬКО на основе контекста. Нет данных — не выдумывай, добавь поле в missing_fields.
2. Каждое утверждение карточки подтверждай источником: chunk_id из скобки [chunk_id | ...].
3. В sources перечисли chunk_id, на которые опирался.
4. Уверенность (confidence) оценивай честно: мало данных — ниже.
"""


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
