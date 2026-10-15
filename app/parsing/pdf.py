"""PDF: текст постранично (pypdf). Отсканированный pdf — отказ с причиной.

Стадии: строки → вычистка колонтитулов → склейка пар «имя/значение»
(простые pdf-таблицы приходят из pypdf отдельными строками) → нормализация
→ секция (= строка-заголовок без точки) и текстовые единицы.

Таблицы с геометрией и шапками в pdf — бонусный уровень, базовый путь
их не выделяет; пары «имя: значение» покрывают паспортные таблицы.
"""
import re

from pypdf import PdfReader

from app.parsing.base import DocumentParseError, ParsedDocument, TextUnit
from app.parsing.normalize import normalize_text, strip_headers_footers

_HEADING_STOP = re.compile(r"[.;:]$")


def parse_pdf(path: str) -> ParsedDocument:
    reader = PdfReader(path)
    if not reader.pages:
        raise DocumentParseError("pdf пустой: ни одной страницы")
    pages_lines: list[list[str]] = []
    for page in reader.pages:
        text = page.extract_text() or ""
        pages_lines.append([ln.strip() for ln in text.splitlines()])
    if not any(any(ln for ln in lines) for lines in pages_lines):
        raise DocumentParseError(
            "у pdf нет текстового слоя (страница отсканирована как изображение); "
            "распознавание (OCR) — необязательная ветка, документ к разбору не готов"
        )
    strip_headers_footers(pages_lines)
    # пары «имя/значение» на уровне строк (до выделения секций)
    for lines in pages_lines:
        lines[:] = _pair_label_value(lines)

    units: list[TextUnit] = []
    section: str | None = None
    heading: str | None = None
    for i, lines in enumerate(pages_lines, start=1):
        for ln in lines:
            if not ln:
                continue
            if is_heading(ln):
                section = ln
                if heading is None:
                    heading = ln
                continue
            units.append(TextUnit(page=i, section=section, text=ln))
    merged = _merge_adjacent(units)
    for u in merged:
        u.text = normalize_text(u.text)
    doc = ParsedDocument(units=[u for u in merged if u.text], heading=heading)
    if not doc.units:
        raise DocumentParseError("в pdf не нашлось текста после чистки колонтитулов")
    return doc


def is_heading(line: str) -> bool:
    """Эвристика заголовка секции: короткая, с большой буквы, без точки в конце."""
    UPPER = "А-ЯЁA-Z"
    if len(line) > 70 or len(line) < 3:
        return False
    if _HEADING_STOP.search(line) or line.rstrip().endswith(","):
        return False
    if line.isupper():
        return True
    return bool(re.match(rf"^[{UPPER}0-9«\"]", line)) and ":" not in line


def _pair_label_value(lines: list[str]) -> list[str]:
    """«Мощность», «800 Вт» подряд в одна строка «Мощность: 800 Вт».

    Метка: короткая строка без цифр. Значение: следующая строка до 60 символов,
    которая тоже могла бы стать текстом единицы. Длинные абзацы не трогаем.
    """
    out: list[str] = []
    i = 0
    while i < len(lines):
        ln = lines[i]
        nxt = lines[i + 1] if i + 1 < len(lines) else None
        if (
            nxt
            and ln
            and _is_label(ln)
            and 0 < len(nxt) <= 24
            and not _HEADING_STOP.search(nxt)
            and ":" not in nxt
        ):
            out.append(f"{ln}: {nxt}")
            i += 2
            continue
        out.append(ln)
        i += 1
    return out


def _is_label(s: str) -> bool:
    return 0 < len(s) <= 40 and not any(ch.isdigit() for ch in s) and ":" not in s and not s.rstrip().endswith(",")


def _merge_adjacent(units: list[TextUnit]) -> list[TextUnit]:
    out: list[TextUnit] = []
    for u in units:
        if out:
            prev = out[-1]
            if prev.page == u.page and prev.section == u.section and len(prev.text) < 600:
                prev.text = f"{prev.text} {u.text}".strip()
                continue
        out.append(TextUnit(page=u.page, section=u.section, text=u.text))
    return out
