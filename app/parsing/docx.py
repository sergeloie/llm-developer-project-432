"""DOCX: параграфы (заголовок стиля = граница секции) + таблицы (python-docx)."""
from app.parsing.base import DocumentParseError, ParsedDocument, TableUnit, TextUnit
from app.parsing.normalize import normalize_cell, normalize_text

_HEADING_STYLES = ("heading", "заголовок")


def parse_docx(path: str) -> ParsedDocument:
    import docx as pydocx

    try:
        document = pydocx.Document(path)
    except Exception as exc:  # битый zip и пр.
        raise DocumentParseError(f"docx не открыт: {exc}") from exc
    units: list[TextUnit | TableUnit] = []
    section: str | None = None
    heading: str | None = None

    body_items = _iter_body_in_order(document)
    for kind, item in body_items:
        if kind == "paragraph":
            text = normalize_cell(item.text)
            if not text:
                continue
            if _is_heading(item):
                section = text
                if heading is None:
                    heading = text
                continue
            units.append(TextUnit(page=None, section=section, text=text))
        else:  # table
            rows = [
                [normalize_cell(cell.text if hasattr(cell, "text") else cell) for cell in row.cells]
                for row in item.rows
            ]
            rows = [r for r in rows if any(c for c in r)]
            if not rows:
                continue
            units.append(
                TableUnit(page=None, section=section, header=rows[0], rows=rows[1:])
            )
    for u in units:
        if isinstance(u, TextUnit):
            u.text = normalize_text(u.text)
    if not units:
        raise DocumentParseError("в docx не нашлось содержимого")
    return ParsedDocument(units=units, heading=heading)


def _iter_body_in_order(document):
    """Итерация по документу в порядке расположения: параграфы и таблицы."""
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    parent = document.element.body
    for child in parent.iterchildren():
        if child.tag.endswith("}p"):
            yield "paragraph", Paragraph(child, document)
        elif child.tag.endswith("}tbl"):
            yield "table", Table(child, document)


def _is_heading(paragraph) -> bool:
    style = (paragraph.style.name or "").lower()
    return style.startswith(_HEADING_STYLES)
