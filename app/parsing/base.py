"""Общие типы разбора: каждая структурная единица знает свою страницу и секцию."""
from dataclasses import dataclass, field


class DocumentParseError(Exception):
    """Документ нельзя разобрать (причина — в сообщении)."""


@dataclass
class TableUnit:
    page: int | None
    section: str | None
    header: list[str]
    rows: list[list[str]]


@dataclass
class TextUnit:
    page: int | None
    section: str | None
    text: str


@dataclass
class ParsedDocument:
    units: list[TextUnit | TableUnit] = field(default_factory=list)
    heading: str | None = None  # первая осмысленная строка — имя документа/товара
