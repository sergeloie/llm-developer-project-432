"""Парсер PDF: pdfplumber, постраничный текст + таблицы.

Каждая структурная единица (блок) знает свою страницу — метаданные чанка.
"""
from dataclasses import dataclass, field

import pdfplumber


@dataclass
class Block:
    """Структурная единица документа: параграф текста или таблица."""
    kind: str                 # "text" | "table"
    content: str
    page: int
    section: str = ""         # последний заголовок выше по документу
    rows: list[list[str]] = field(default_factory=list)  # для kind="table"


def parse_pdf(data: bytes) -> list[Block]:
    blocks: list[Block] = []
    import io

    with pdfplumber.open(io.BytesIO(data)) as pdf:
        has_any_text = False
        for page_no, page in enumerate(pdf.pages, start=1):
            text = page.extract_text() or ""
            if text.strip():
                has_any_text = True
            for para in _split_paragraphs(text):
                section = _heading(para)
                if section:
                    blocks.append(Block("text", para, page_no, section=section))
                else:
                    blocks.append(Block("text", para, page_no))
            for table in page.extract_tables():
                rows = [[(cell or "").strip() for cell in row] for row in table]
                blocks.append(Block("table", _table_to_text(rows), page_no, rows=rows))
        if not has_any_text and not blocks:
            raise PdfNoTextLayer("PDF без текстового слоя (скан?): нужен OCR")
    return blocks


class PdfNoTextLayer(Exception):
    """В PDF нет извлекаемого текста — только картинки."""


def _split_paragraphs(text: str) -> list[str]:
    paras = [p.strip() for p in text.split("\n\n") if p.strip()]
    if paras:
        return paras
    p = text.strip()
    return [p] if p else []


def _heading(para: str) -> str:
    """Эвристика заголовка: ВСЕ КАПС или короткая строка без точки."""
    if para.isupper() and 3 < len(para) < 80:
        return para.title()
    if len(para) < 60 and not para.endswith((".", ";", ",")) and para.count(" ") <= 6:
        return para
    return ""


def _table_to_text(rows: list[list[str]]) -> str:
    """Таблица → линейный текст «ключ: значение» / «колонка: значение»."""
    if not rows:
        return ""
    header = rows[0]
    lines = []
    for row in rows[1:]:
        if len(header) == 2 and header[0].lower() in ("параметр", "характеристика"):
            # Таблица «параметр — значение»: сохраняем как есть
            lines.append(f"{row[0]}: {row[1] if len(row) > 1 else ''}")
        else:
            cells = [f"{h}={v}" for h, v in zip(header, row) if v]
            lines.append("; ".join(cells))
    return "\n".join(lines)
