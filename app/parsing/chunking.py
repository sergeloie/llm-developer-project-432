"""Резка на фрагменты.

Текст — фиксированный размер с перекрытием (утверждение на границе
не теряется). Таблица — по строкам: строку рвать нельзя, каждая часть
большой таблицы несёт заголовки колонок. У каждого фрагмента —
метаданные: страница, секция, артикул, бренд.
"""
import re
from dataclasses import dataclass
from typing import Any

from app.parsing.base import ParsedDocument, TableUnit, TextUnit

ARTICLE_RE = re.compile(r"\b[A-ZА-ЯЁ]{2,6}[-–]\d{2,6}\b")  # BLD-800 / KTL-1700


@dataclass
class Fragment:
    ord: int
    page: int | None
    section: str | None
    kind: str  # text | table
    article: str | None
    brand: str | None
    text: str


def table_row_text(header: list[str], row: list[str]) -> str | None:
    """Строка таблицы текстом: «ART: колонка значение; …» (первая — ключ)."""
    pairs: list[str] = []
    for h, v in zip(header[1:], row[1:]):
        if h and v:
            pairs.append(f"{h}: {v}")
    key = row[0] if row else ""
    if not pairs and not key:
        return None
    joined = "; ".join(pairs)
    if key and joined:
        return f"{key}: {joined}"
    return key or (joined or None)


def make_fragments(
    doc: ParsedDocument,
    *,
    chunk_size: int,
    chunk_overlap: int,
    brand: str | None = None,
) -> list[dict[str, Any]]:
    frags: list[Fragment] = []
    for u in doc.units:
        if isinstance(u, TextUnit):
            for piece in _split_text(u.text, chunk_size, chunk_overlap):
                fr = Fragment(
                    ord=0,
                    page=u.page,
                    section=u.section,
                    kind="text",
                    article=_find_article(piece + " " + (doc.heading or "")),
                    brand=brand,
                    text=piece,
                )
                frags.append(fr)
        else:  # TableUnit — строки без разрыва, шапка в каждой части
            head_text = " | ".join(h for h in u.header if h)
            # корпус части: шапка + накопленные строки, до предела
            body_lines: list[str] = []
            size = len(head_text)
            for row in u.rows:
                line = table_row_text(u.header, row)
                if line is None:
                    continue
                line_len = len(line) + 1
                if body_lines and size + line_len > chunk_size:
                    frags.append(_table_frag(head_text, body_lines, u, brand))
                    body_lines, size = [], len(head_text)
                body_lines.append(line)
                size += line_len
            if body_lines:
                frags.append(_table_frag(head_text, body_lines, u, brand))
    for i, fr in enumerate(frags, start=1):
        fr.ord = i
    return [
        {
            "ord": fr.ord,
            "page": fr.page,
            "section": fr.section,
            "kind": fr.kind,
            "article": fr.article,
            "brand": fr.brand,
            "text": fr.text,
        }
        for fr in frags
    ]


def _table_frag(head: str, lines: list[str], u: TableUnit, brand: str | None) -> Fragment:
    text = head + "\n" + "\n".join(lines) if head else "\n".join(lines)
    return Fragment(
        ord=0,
        page=u.page,
        section=u.section,
        kind="table",
        article=_find_article(text),
        brand=brand,
        text=text,
    )


def _find_article(text: str) -> str | None:
    m = ARTICLE_RE.search(text or "")
    return m.group(0) if m else None


def _split_text(text: str, size: int, overlap: int) -> list[str]:
    if not text:
        return []
    if len(text) <= size:
        return [text]
    chunks: list[str] = []
    start = 0
    while start < len(text):
        end = min(start + size, len(text))
        # резать по границе предложения/пробела, если возможно
        if end < len(text):
            window = text[start:end]
            cut = max(window.rfind(". "), window.rfind("\n"), window.rfind("; "))
            if cut > size // 2:
                end = start + cut + 1
        chunks.append(text[start:end].strip())
        if end >= len(text):
            break
        start = max(end - overlap, start + 1)
    return [c for c in chunks if c]
