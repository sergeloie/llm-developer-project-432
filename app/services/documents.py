"""Сервис документа: разбор по формату → нормализация → резка → сохранение.

Разбор — шаг блокирующий (CPU/диск) и исполняется вне песочницы в потоке.
Состояния документа: new → parsing → indexed | failed(причина).
"""
from pathlib import Path

from app.core.config import settings
from app.parsing import chunking
from app.parsing.base import DocumentParseError, ParsedDocument
from app.parsing.docx import parse_docx
from app.parsing.pdf import parse_pdf
from app.parsing.xlsx import parse_xlsx

PARSERS = {".pdf": parse_pdf, ".docx": parse_docx, ".xlsx": parse_xlsx}


def parse_file(path: str) -> tuple[ParsedDocument, str]:
    """Разобрать файл по расширению. Возврат (units, kind)."""
    ext = Path(path).suffix.lower()
    parser = PARSERS.get(ext)
    if parser is None:
        raise DocumentParseError(f"формат {ext} не поддерживается")
    doc = parser(path)
    kind = ext.lstrip(".")
    return doc, kind


def fragments_from_file(path: str, brand: str | None = None) -> list[dict]:
    """Полный конвейер разбора до фрагментов (без записи в базу)."""
    doc, _kind = parse_file(path)
    return chunking.make_fragments(
        doc,
        chunk_size=settings.chunk_size,
        chunk_overlap=settings.chunk_overlap,
        brand=brand,
    )


def brand_from_filename(filename: str) -> str | None:
    """Бренд из имени файла: blender_kp.docx → blender (до первого '_' или '.')."""
    stem = Path(filename).stem
    part = stem.split("_")[0]
    part = part.split("-")[0]
    return part or None

parse_to_chunks = fragments_from_file
