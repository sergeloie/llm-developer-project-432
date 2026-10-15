"""Разбор выданных файлов: строки достаются, таблицы не рваны, скан — отказ.

Тесты читают файлы из data/ напрямую — без базы, сети и модели.
"""
import pytest

from app.parsing.base import DocumentParseError
from app.services.documents import brand_from_filename, fragments_from_file

DATA = "data"


def _frags(name: str):
    return fragments_from_file(f"{DATA}/{name}", brand=brand_from_filename(name))


def test_pdf_passport_extracts_expected_lines():
    fr = _frags("blender_passport.pdf")
    all_text = "\n".join(f["text"] for f in fr)
    assert f"Мощность: {800} Вт" in all_text
    assert "Артикул: BLD-800" in all_text
    assert "Гарантия: 24 месяца" in all_text
    # страница и секция заполнены у всех фрагментов
    for f in fr:
        assert f["page"] in (1, 2)
        assert f["section"]
    # колонтитул выметён
    assert "supplies@technodom.example" not in all_text


def test_docx_sections_and_table_integrity():
    fr = _frags("blender_kp.docx")
    # секция заголовка таблицы опознана из стиля Heading
    assert any("Прайс-лист" in (f["section"] or "") for f in fr)
    # строка прайса не разорвана: артикул и цена в одном фрагменте
    price_frag = [f for f in fr if "BLD-800" in f["text"]]
    assert price_frag
    assert "2190" in price_frag[0]["text"] or "2 190" in price_frag[0]["text"]
    table_frags = [f for f in fr if f["kind"] == "table"]
    assert table_frags
    for f in table_frags:
        assert "Артикул" in f["text"]  # шапка в каждой части
    # артикулы попали в метаданные или текст
    assert any(f["article"] and f["article"].startswith("BLD") for f in fr)


def test_xlsx_rows_as_text_with_header():
    fr = _frags("kettle_spec.xlsx")
    assert len(fr) >= 1
    text = "\n".join(f["text"] for f in fr)
    # формат «артикул: параметр значение»
    assert "KTL-1700:" in text
    assert "2200" in text  # мощность
    # строка целиком: артикул и цена в одном фрагменте
    ktl = next(f for f in fr if "KTL-1700" in f["text"])
    assert "1 890" in ktl["text"] or "1890" in ktl["text"]
    assert any(f["article"] == "KTL-1700" for f in fr)
    # заголовки колонок в фрагменте есть
    assert "Мощность, Вт" in ktl["text"]


def test_scanned_pdf_fails_with_reason():
    with pytest.raises(DocumentParseError) as e:
        _frags("boiler_scan.pdf")
    assert "текстового слоя" in str(e.value)


def test_hyphenation_and_ligatures_normalized():
    from app.parsing.normalize import normalize_text

    assert normalize_text("мощ-\nность") == "мощность"
    assert normalize_text("ог-\nнеупорный") == "огнеупорный"
    assert normalize_text("а\u00a0б") == "а б"
