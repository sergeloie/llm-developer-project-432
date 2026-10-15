"""XLSX: спецификация «артикул → параметры». Шапка — первая строка, обязательна."""
from openpyxl import load_workbook

from app.parsing.base import DocumentParseError, ParsedDocument, TableUnit
from app.parsing.normalize import normalize_cell


def parse_xlsx(path: str) -> ParsedDocument:
    try:
        wb = load_workbook(path, data_only=True, read_only=True)
    except Exception as exc:
        raise DocumentParseError(f"xlsx не открыт: {exc}") from exc
    units: list[TableUnit] = []
    for ws in wb.worksheets:
        rows = [[normalize_cell(v) for v in row] for row in ws.iter_rows(values_only=True)]
        # срезать полностью пустые строки
        rows = [r for r in rows if any(c for c in r)]
        if not rows:
            continue
        header = rows[0]
        if not _looks_like_header(header):
            raise DocumentParseError(
                f"лист «{ws.title}»: первая строка не выглядит шапкой спецификации "
                f"({header[:3]!r}…); без заголовков колонок таблица бесполезна"
            )
        data = [r[: len(header)] + [""] * max(0, len(header) - len(r)) for r in rows[1:]]
        units.append(TableUnit(page=None, section=ws.title, header=header, rows=data))
    if not units:
        raise DocumentParseError("в xlsx нет непустых листов")
    return ParsedDocument(units=units, heading=units[0].section)


def _looks_like_header(header: list[str]) -> bool:
    """Шапка: как минимум 2 заполненных колонки и нет числовых значений."""
    filled = [h for h in header if h]
    if len(filled) < 2:
        return False
    numeric = sum(1 for h in filled if _is_number(h))
    return numeric <= 1


def _is_number(s: str) -> bool:
    cleaned = s.replace(" ", "").replace(",", ".").replace("%", "")
    try:
        float(cleaned)
        return True
    except ValueError:
        return False
