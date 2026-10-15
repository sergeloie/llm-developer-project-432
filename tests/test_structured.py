"""Тесты structured output: контракт CardDraft, валидация с ретраем, регенерация поля."""
import asyncio

import pytest
from pydantic import ValidationError

from app.schemas.cards import CardDraft, Seo, SourceRef
from app.services import structured


def _valid_card(**overrides) -> dict:
    base = {
        "title": "МиксерПро 800 — блендер",
        "description": "Погружной блендер с металлической ножкой.",
        "characteristics": {"Мощность": "800 Вт"},
        "benefits": ["Металлическая ножка"],
        "seo": {"title": "Блендер МиксерПро 800", "description": "Мощный блендер", "keywords": ["блендер"]},
        "sources": [{"chunk_id": "c1", "doc_id": "d1", "page": 3, "score": 0.9}],
        "missing_fields": ["Гарантия"],
        "confidence": 0.8,
    }
    base.update(overrides)
    return base


# --- контракт схемы ---

def test_title_over_60_rejected():
    with pytest.raises(ValidationError, match="60"):
        CardDraft.model_validate(_valid_card(title="А" * 61))


def test_confidence_bounds():
    with pytest.raises(ValidationError):
        CardDraft.model_validate(_valid_card(confidence=1.5))
    with pytest.raises(ValidationError):
        CardDraft.model_validate(_valid_card(confidence=-0.1))


def test_characteristics_empty_values_dropped():
    card = CardDraft.model_validate(
        _valid_card(characteristics={"Мощность": "800 Вт", "Гарантия": "  "})
    )
    assert "Гарантия" not in card.characteristics


def test_missing_fields_cannot_duplicate_characteristics():
    with pytest.raises(ValidationError):  # текст ошибки на любом языке
        CardDraft.model_validate(
            _valid_card(characteristics={"Мощность": "800 Вт"}, missing_fields=["Мощность"])
        )


def test_source_ref_defaults():
    ref = SourceRef(chunk_id="c1")
    assert ref.doc_id == "" and ref.page is None and ref.score is None


# --- validate_or_retry ---

def _patch_run(monkeypatch, scenario):
    import json

    calls = []

    async def fake_run(agent, prompt):
        calls.append(prompt)
        result = scenario[len(calls) - 1]
        # dict сериализуем как JSON — как это выглядело бы от LLM
        return json.dumps(result, ensure_ascii=False) if isinstance(result, dict) else result

    monkeypatch.setattr(structured, "run_agent", fake_run)
    return calls


def test_validate_or_retry_first_try(monkeypatch):
    calls = _patch_run(monkeypatch, [_valid_card()])
    card = asyncio.run(structured.validate_or_retry("Факты..."))
    assert card.title.startswith("МиксерПро")
    assert len(calls) == 1


def test_validate_or_retry_sends_error_back(monkeypatch):
    bad = _valid_card(title="А" * 80)  # сломанный title
    good = _valid_card()
    calls = _patch_run(monkeypatch, [bad, good])
    card = asyncio.run(structured.validate_or_retry("Факты..."))
    assert len(calls) == 2
    assert "60" in calls[1]  # текст ошибки ушёл в повторный промпт
    assert card.title == good["title"]


def test_validate_or_retry_gives_up_after_max(monkeypatch):
    bad = _valid_card(title="А" * 80)
    _patch_run(monkeypatch, [bad, bad, bad])  # 1 попытка + 2 исправления
    with pytest.raises(structured.CardValidationError):
        asyncio.run(structured.validate_or_retry("Факты..."))


def test_validate_or_retry_handles_garbage(monkeypatch):
    _patch_run(monkeypatch, ["не JSON вообще", _valid_card()])
    card = asyncio.run(structured.validate_or_retry("Факты..."))
    assert card.confidence == 0.8


# --- частичная регенерация ---

def test_regenerate_field_only_fixes_one(monkeypatch):
    original = CardDraft.model_validate(_valid_card())
    fixed = CardDraft.model_validate(_valid_card(title="Короткий заголовок"))

    async def fake_run(agent, prompt):
        assert "title" in prompt  # в промпте названо сломанное поле
        return fixed.model_dump_json()

    monkeypatch.setattr(structured, "run_agent", fake_run)
    result = asyncio.run(
        structured.regenerate_field(original, "title", "заголовок длиннее 60 символов")
    )
    assert result.title == "Короткий заголовок"
    assert result.description == original.description  # остальное не тронуто


# --- confidence gate ---

def test_confidence_gate():
    low = CardDraft.model_validate(_valid_card(confidence=0.3))
    high = CardDraft.model_validate(_valid_card(confidence=0.87))
    assert structured.confidence_gate(low) == "needs_review"
    assert structured.confidence_gate(high) == "done"


def test_seo_block_defaults():
    seo = Seo()
    assert seo.title == "" and seo.keywords == []
