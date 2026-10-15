"""Чанкер: fixed-size + overlap для текста, layout-aware для таблиц.

Metadata каждого чанка: {doc_id, page, section, articul, brand}.
Артикул/бренд вытаскиваются regex'ом из текста чанка.
"""
import re
import uuid

from app.parsers.pdf import Block

CHUNK_SIZE = 700      # символов
CHUNK_OVERLAP = 120

_ARTICUL_RE = re.compile(r"\b[A-Z]{2,5}-[A-Z0-9]{2,6}\b")
_BRANDS = ("МиксерПро", "КеттлПро", "АкваТерм", "ТехноДом")


def chunk_blocks(blocks: list[Block], doc_id: str, *, size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[dict]:
    """Блоки → чанки-словари с метаданными.

    Таблица — layout-aware: не рвём строку; большая таблица делится
    по строкам, каждая часть несёт контекст (предыдущая строка непрерывности).
    """
    chunks: list[dict] = []
    for b in blocks:
        if b.kind == "table":
            pieces = _split_table(b, size)
            for piece in pieces:
                chunks.append(_make_chunk(piece, b, doc_id))
        else:
            for piece in _split_text(b.content, size, overlap):
                chunks.append(_make_chunk(piece, b, doc_id))
    for i, ch in enumerate(chunks):
        ch["ordinal"] = i
    return chunks


def _split_text(text: str, size: int, overlap: int) -> list[str]:
    if len(text) <= size:
        return [text]
    pieces = []
    start = 0
    while start < len(text):
        end = start + size
        if end < len(text):
            # Параграф не рвём посередине слова — откатываемся до пробела
            cut = text.rfind(" ", start + size // 2, end)
            if cut > start:
                end = cut
        pieces.append(text[start:end].strip())
        if end >= len(text):
            break
        start = end - overlap
    return [p for p in pieces if p]


def _split_table(b: Block, size: int) -> list[str]:
    """Строку таблицы не рвём; режем между строками, каждая часть с заголовком."""
    lines = b.content.split("\n")
    header_line = lines[0] if lines else ""
    pieces: list[str] = []
    current: list[str] = []
    current_len = 0
    for line in lines:
        if current_len + len(line) > size and current:
            pieces.append("\n".join(current))
            # Заголовок таблицы (шапка «Параметр/Значение») дублируем в каждую часть
            if header_line and not current[0].startswith(header_line.split(":")[0]):
                current = [f"[шапка] {header_line}", line]
                current_len = len(header_line) + len(line)
                continue
            current = [line]
            current_len = len(line)
        else:
            current.append(line)
            current_len += len(line)
    if current:
        pieces.append("\n".join(current))
    return pieces


def _make_chunk(text: str, b: Block, doc_id: str) -> dict:
    meta = {
        "doc_id": doc_id,
        "page": b.page,
        "section": b.section or "",
    }
    articul = _ARTICUL_RE.search(text)
    if articul:
        meta["articul"] = articul.group(0)
    for brand in _BRANDS:
        if brand in text:
            meta["brand"] = brand
            break
    return {
        "id": uuid.uuid4().hex[:12],
        "text": text,
        "metadata": meta,
    }
