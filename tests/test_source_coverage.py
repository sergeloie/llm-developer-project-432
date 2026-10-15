"""Покрытие источников: производная величина карточки и решение по статусу.

Проверяется решение, а не модель: карточки и тексты фрагментов заданы тестом,
поэтому тест падает только если сломалась логика, а не если модель ответила
иначе. БД не нужна — тексты чанков передаются в функцию решения явно.
"""
import json

from app.core.config import settings
from app.guardrails import GuardReport
from app.schemas.cards import CardDraft
from app.services.rag_pipeline import decide_status

CHUNKS = {
    "c1": "Блендер МиксерПро 800. Мощность: 800 Вт. Объём чаши: 1,5 л.",
    "c2": "Гарантия 24 месяца. Цвет: графитовый.",
}


def _card(**overrides) -> CardDraft:
    data = {
        "title": "МиксерПро 800 — блендер",
        "description": "Погружной блендер.",
        "characteristics": {"Мощность": "800 Вт", "Объём чаши": "1,5 л"},
        "benefits": ["Металлическая ножка"],
        "sources": [{"chunk_id": "c1", "doc_id": "d1", "page": 1}],
        "missing_fields": [],
        "confidence": 0.9,
    }
    data.update(overrides)
    return CardDraft.model_validate(data)


# --- производная величина ---

def test_coverage_is_fraction_of_grounded_characteristics():
    """Частичное совпадение — дробь, а не флаг: 1 из 2 характеристик подтверждён."""
    card = _card(characteristics={"Мощность": "800 Вт", "Материал": "керамика"})
    assert card.derived_source_coverage(CHUNKS) == 0.5


def test_coverage_ignores_case_spacing_and_punctuation():
    card = _card(characteristics={"Мощность": "800  вт.", "Объём": "1.5 л"})
    assert card.derived_source_coverage(CHUNKS) == 1.0


def test_coverage_without_characteristics_is_zero():
    card = _card(characteristics={})
    assert card.derived_source_coverage(CHUNKS) == 0.0


def test_coverage_without_sources_is_zero():
    assert _card(sources=[]).derived_source_coverage(CHUNKS) == 0.0


def test_coverage_ignores_uncited_chunks():
    """Подтверждает только то, на что карточка сослалась, а не весь документ."""
    card = _card(sources=[{"chunk_id": "c2", "doc_id": "d1", "page": 1}])
    assert card.derived_source_coverage(CHUNKS) == 0.0


def test_coverage_without_evidence_texts_is_zero():
    """Без текстов фрагментов подтвердить нечего — иначе метрика врала бы."""
    assert _card().derived_source_coverage() == 0.0
    assert _card().derived_source_coverage({}) == 0.0


def test_coverage_is_not_a_contract_field():
    """Покрытие не достаётся из ответа модели и не попадает в контракт карточки."""
    dumped = _card().model_dump()
    assert "source_coverage" not in dumped
    assert "derived_source_coverage" not in dumped
    assert CardDraft.model_validate({**dumped, "source_coverage": 1.0}).characteristics


# --- решение по статусу ---

def test_full_coverage_and_high_confidence_is_done():
    card = _card()
    assert decide_status(card, "approved", GuardReport(), CHUNKS) == "done"


def test_no_sources_is_needs_review_even_at_max_confidence():
    card = _card(sources=[], confidence=1.0)
    assert decide_status(card, "approved", GuardReport(), CHUNKS) == "needs_review"


def test_high_confidence_with_low_coverage_is_needs_review():
    card = _card(characteristics={"Мощность": "800 Вт", "Цена": "12 990 ₽"})
    assert card.confidence > settings.confidence_human_threshold
    assert decide_status(card, "approved", GuardReport(), CHUNKS) == "needs_review"


def test_low_confidence_still_needs_review():
    card = _card(confidence=0.2)
    assert decide_status(card, "approved", GuardReport(), CHUNKS) == "needs_review"


def test_guardrails_needing_review_still_win():
    guard = GuardReport(suspicious_chunks=["c1", "c2", "c3"])
    assert decide_status(_card(), "approved", guard, CHUNKS) == "needs_review"


def test_unapproved_verdict_is_rejected():
    assert decide_status(_card(), "regenerate", GuardReport(), CHUNKS) == "rejected"
    assert decide_status(None, "rejected", GuardReport(), CHUNKS) == "rejected"


def test_threshold_comes_from_settings():
    card = _card(characteristics={"Мощность": "800 Вт", "Объём чаши": "1,5 л"})
    texts = {"c1": "Мощность: 800 Вт."}
    assert card.derived_source_coverage(texts) == 0.5 < settings.source_coverage_threshold
    assert decide_status(card, "approved", GuardReport(), texts) == "needs_review"


# --- покрытие видно в ответе ---

class _CoverageCursor:
    def __init__(self, rows: dict):
        self.rows = rows
        self.pending: list | None = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql: str, args: tuple = ()) -> None:
        if sql.startswith("SELECT id, status, attempts, result, error"):
            self.pending = list(args) and ["j1", "done", 1, self.rows["result"], None]
        elif sql.startswith("SELECT id, doc_id, text, metadata FROM chunks"):
            self.pending = [(cid, "d1", text, {}) for cid, text in CHUNKS.items()]
        else:
            raise AssertionError(f"неожиданный SQL: {sql}")

    def fetchall(self):
        rows, self.pending = self.pending or [], None
        return rows

    def fetchone(self):
        row, self.pending = self.pending, None
        return row


class _CoverageConnection:
    def cursor(self):
        return _CoverageCursor({"result": json.loads(_card().model_dump_json())})


def test_job_row_carries_coverage_next_to_the_card():
    """Покрытие видно клиенту рядом с карточкой; контракт карточки не меняется."""
    from app.repositories import jobs as jobs_repo

    job = jobs_repo.get_job(_CoverageConnection(), "j1")

    assert job["source_coverage"] == 1.0
    assert "source_coverage" not in job["result"]
    assert job["result"]["sources"][0]["chunk_id"] == "c1"
