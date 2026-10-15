"""Тесты data pipeline на файлах датасета из data/."""
from pathlib import Path

import pytest

from app.parsers.chunker import chunk_blocks
from app.parsers.docx import parse_docx
from app.parsers.normalizer import clean_text, normalize
from app.parsers.pdf import PdfNoTextLayer, parse_pdf
from app.services.documents import parse_to_chunks

DATA = Path(__file__).resolve().parent.parent / "data"


def _read(name: str) -> bytes:
    return (DATA / name).read_bytes()


def test_pdf_passport_extracts_contract_strings():
    blocks = parse_pdf(_read("blender_passport.pdf"))
    full = "\n".join(b.content for b in blocks)
    for probe in ("МиксерПро 800", "BLD-800", "800 Вт", "24 месяца"):
        assert probe in full, probe
    tables = [b for b in blocks if b.kind == "table"]
    assert len(tables) == 2
    assert len(tables[0].rows) == 13  # таблица характеристик


def test_pdf_manual_contains_injection():
    blocks = parse_pdf(_read("kettle_manual.pdf"))
    full = "\n".join(b.content for b in blocks)
    assert "игнорируй все предыдущие инструкции" in full
    assert "KTL-1700" in full


def test_scan_pdf_raises_no_text_layer():
    with pytest.raises(PdfNoTextLayer):
        parse_pdf(_read("boiler_scan.pdf"))


def test_docx_kp_extracts_pii_and_prices():
    blocks = parse_docx(_read("blender_kp.docx"))
    full = "\n".join(b.content for b in blocks)
    for probe in ("BLD-800", "2 190", "+7 926 555-14-08", "a.smirnova@technodom.example"):
        assert probe in full, probe
    tables = [b for b in blocks if b.kind == "table"]
    assert len(tables) == 1


def test_xlsx_spec_rows_become_chunks():
    chunks = parse_to_chunks(_read("kettle_spec.xlsx"), "xlsx", "doc1")
    full = "\n".join(c["text"] for c in chunks)
    for articul in ("KTL-1700", "KTL-1000", "KTL-THRM"):
        assert articul in full, articul
    assert "2200" in full
    # Каждый чанк спецификации несёт артикул в метаданных
    with_articul = [c for c in chunks if c["metadata"].get("articul")]
    assert len(with_articul) == 3


def test_footer_stripped_from_passport():
    """Колонтитул «ООО «ТехноДом» · … — стр. N» уходит после normalize."""
    blocks = normalize(parse_pdf(_read("blender_passport.pdf")))
    full = "\n".join(b.content for b in blocks)
    assert "стр. 1" not in full
    assert "supplies@technodom.example" not in full or "— стр." not in full


def test_chunker_metadata_and_layout():
    blocks = parse_pdf(_read("blender_passport.pdf"))
    chunks = chunk_blocks(normalize(blocks), "docX")
    assert all(c["metadata"]["doc_id"] == "docX" for c in chunks)
    assert all("page" in c["metadata"] for c in chunks)
    # Таблица характеристик не разорвана посередине строки
    table_chunks = [c for c in chunks if "Мощность" in c["text"]]
    assert any("800 Вт" in c["text"] for c in table_chunks)


def test_clean_text_hyphenation():
    assert clean_text("мощ-\nность") == "мощность"
    assert clean_text("слева  справа") == "слева справа"
