"""Пайплайн с подменённым клиентом модели:	success / переделка / петля исчерпана.

Тесты не ходят в LM Studio и не требуют сети.
"""
import json

import pytest
from pydantic import ValidationError

from app import schemas
from app.services import pipeline
from app.services.parse import ModelJsonError


def _resp(text: str):
    class R:
        pass

    r = R()
    r.text = text
    return r


GOOD_FACTS = schemas.SupplierFacts(
    product_name="МиксерПро 800",
    characteristics={"Мощность": "800 Вт"},
    missing_fields=["Гарантия"],
)
GOOD_CARD = schemas.CardDraft(
    title="МиксерПро 800",
    description="Погружной блендер на 800 Вт.",
    characteristics={"Мощность": "800 Вт"},
    benefits=["турбо"],
    missing_fields=["Гарантия"],
    confidence=0.8,
)


class ScriptedClient:
    """Подмена llm.complete: выдаёт ответы по порядку, запоминает вызовы."""

    def __init__(self, scripted_prompts: dict[str, list[str]]):
        self.answers = dict(scripted_prompts)  # key: role → очередь ответов
        self.calls: list[tuple[str, str]] = []

    async def __call__(self, prompt: str, *, role: str = "main", system: str = "", **kw):
        self.calls.append((role, prompt[:80]))
        text = self.answers.get(role, [])
        if text:
            return _resp(text.pop(0))
        return _resp(json.dumps({}, ensure_ascii=False))


def test_pipeline_success():
    c = ScriptedClient(
        {
            "cheap": [GOOD_FACTS.model_dump_json(), '{"verdict": "approve", "issues": []}'],
            "main": [GOOD_CARD.model_dump_json()],
        }
    )
    draft, rounds, verdict, report = _run(c)
    assert verdict == "approved"
    assert rounds == 1
    assert report is not None
    assert draft.title == "МиксерПро 800"


def _run(c):
    import asyncio

    return asyncio.run(pipeline.run_pipeline("текст поставщика", complete=c))


def test_pipeline_regenerate_once():
    bad_card = GOOD_CARD.model_dump()
    fine_card = GOOD_CARD.model_dump()
    # плохой вариант: без преимуществ
    bad_card["benefits"] = []
    c = ScriptedClient(
        {
            "cheap": [GOOD_FACTS.model_dump_json(), '{"verdict": "regenerate", "issues": ["нет benefits"]}', '{"verdict": "approve", "issues": []}'],
            "main": [json.dumps(bad_card), json.dumps(fine_card)],
        }
    )
    _draft, rounds, verdict, _report = _run(c)
    assert verdict == "approved"
    assert rounds == 2


def test_pipeline_exhausted():
    regen = '{"verdict": "regenerate", "issues": ["вода"]}'
    c = ScriptedClient(
        {
            "cheap": [GOOD_FACTS.model_dump_json(), regen, regen, regen],
            "main": list([GOOD_CARD.model_dump_json()] * 3),
        }
    )
    _draft, rounds, verdict, _report = _run(c)
    assert verdict == "rejected_exhausted"
    assert rounds == 3


def test_parse_four_kinds():
    # чистый JSON
    p = {"a": 1}
    assert pipeline.parse.extract_json(json.dumps(p)) == p
    # json в заборах
    fence = "```json\n" + json.dumps(p) + "\n```"
    assert pipeline.parse.extract_json(fence) == p
    # JSON с текстом вокруг
    ringing = "Вот ваш JSON:\n" + json.dumps(p) + "\nНадеюсь, хватит"
    assert pipeline.parse.extract_json(ringing) == p
    # мусор
    with pytest.raises(ModelJsonError):
        pipeline.parse.extract_json("я вообще не JSON")
    # пустой
    with pytest.raises(ModelJsonError):
        pipeline.parse.extract_json("")


def test_card_contract_validation():
    # поле в двух списках сразу
    with pytest.raises(ValidationError):
        schemas.CardDraft(
            title="x",
            description="y",
            characteristics={"Гарантия": "12"},
            benefits=[],
            missing_fields=["Гарантия"],
            confidence=0.9,
        )
    with pytest.raises(ValidationError):
        schemas.CardDraft(title="x", description="y", characteristics={"Мощность": ""}, confidence=0.1)
    with pytest.raises(ValidationError):
        schemas.CardDraft(title="x", description="y", confidence=2)


def test_scarce_data_low_confidence_needs_human_fixture():
    import asyncio

    """Скудный текст → непустой missing_fields + низкий confidence (эта логика закоплена в генераторе)."""
    scarce = schemas.SupplierFacts(
        product_name="Чайник Вода К1",
        characteristics={"Мощность": "1800 Вт"},
        missing_fields=["Объём", "Материал", "Гарантия"],
    )
    low = GOOD_CARD.model_dump()
    low["characteristics"] = {"Мощность": "1800 Вт"}
    low["missing_fields"] = ["Объём", "Материал", "Гарантия"]
    low["confidence"] = 0.4
    c = ScriptedClient(
        {
            "cheap": [scarce.model_dump_json(), '{"verdict": "approve", "issues": []}'],
            "main": [json.dumps(low, ensure_ascii=False)],
        }
    )
    draft, _rounds, verdict, _r = asyncio.run(pipeline.run_pipeline("Чайник 1800 Вт", complete=c))
    assert draft.missing_fields
    assert draft.confidence < 0.7
    assert draft.confidence < 0.7 or verdict == "approved"


def test_validation_repair_loop():
    """Невалидная карточка → повторный запрос с текстом ошибки → исправился."""
    long_title = GOOD_CARD.model_dump()
    long_title["title"] = "x" * 100
    fixed = GOOD_CARD.model_dump()
    c = ScriptedClient(
        {
            "cheap": [GOOD_FACTS.model_dump_json(), '{"verdict": "approve", "issues": []}'],
            "main": [json.dumps(long_title), json.dumps(fixed)],
        }
    )
    draft, _rounds, verdict, _r = _run(c)
    # лечение: первый вызов generate не прошёл, второй с текстом ошибки принял
    assert any("валидацию" in pr or "валидации" in pr for _, pr in c.calls)
    assert draft.title == "МиксерПро 800"
    assert verdict == "approved"
