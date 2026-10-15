"""Пайплайн генерации карточки на тексте (шаги 3-4).

extract → (generate → validate → critique → feedback)*, петля переделки
ограничена (max_pipeline_rounds). Ответ модели разбирается из грязного
JSON; невалидный по схеме ответ лечится повторным запросом с текстом
ошибки; одно сломанное поле переделывается точечно, без переделки
всего черновика.

Для тестов все шаги принимают подменяемую функцию `complete`
(сигнатура как у llm.complete) — модель не нужна.
"""
from collections.abc import Awaitable, Callable
from typing import Any

from pydantic import ValidationError

from app import schemas
from app.agents import prompts
from app.core.config import settings
from app.core.logging import get_logger
from app.llm import client as llm
from app.services import parse

log = get_logger(__name__)
CompleteFn = Callable[..., Awaitable[Any]]


async def extract(
    supplier_text: str, complete: CompleteFn = llm.complete
) -> schemas.SupplierFacts:
    system, user = prompts.extractor_prompt(supplier_text)
    resp = await complete(user, role="cheap", system=system)
    return schemas.SupplierFacts.model_validate(parse.extract_json(resp.text))


async def generate(
    facts: schemas.SupplierFacts,
    feedback: list[str] | None = None,
    complete: CompleteFn = llm.complete,
) -> schemas.CardDraft:
    system, user = prompts.generator_prompt(facts, feedback)
    resp = await complete(user, role="main", system=system)
    return await _card_with_repairs(resp.text, complete)


async def critique(
    facts: schemas.SupplierFacts,
    draft: schemas.CardDraft,
    complete: CompleteFn = llm.complete,
) -> schemas.CritiqueReport:
    system, user = prompts.critic_prompt(facts, draft)
    resp = await complete(user, role="cheap", system=system)
    return schemas.CritiqueReport.model_validate(parse.extract_json(resp.text))


async def _card_with_repairs(
    text: str, complete: CompleteFn, *, skip_role_probe: bool = False
) -> schemas.CardDraft:
    """Валидация с лечением: невалидный ответ уходит модели с текстом ошибки."""
    last_error = ""
    for attempt in range(1, settings.max_repair_attempts + 1):
        try:
            data = parse.extract_json(text)
            return _strict_card(data)
        except parse.ModelJsonError as exc:
            last_error = str(exc)
            log.warning("model_json_unparseable", attempt=attempt)
            resp = await complete(
                "Твой предыдущий ответ нельзя разобрать как JSON.\n"
                f"{last_error}\n"
                "Верни тот же ответ, но строго одним валидным JSON-объектом "
                "без markdown и текста вокруг."
            )
            text = resp.text
        except (ValidationError, ValueError) as exc:
            errs = [
                str(e.get("msg", e)) for e in exc.errors()
            ] if isinstance(exc, ValidationError) else [str(exc)]
            log.warning("card_validation_failed", attempt=attempt, errors=errs)
            resp = await complete(
                "Твой предыдущий ответ не прошёл валидацию.\nОшибки:\n- "
                + "\n- ".join(errs)
                + "\nИсправь ТОЛЬКО перечисленные ошибки и верни исправленный "
                "ПОЛНЫЙ JSON в той же форме."
            )
            text = resp.text
    raise parse.ModelJsonError(
        f"лечение не помогло за {settings.max_repair_attempts} попыток: {last_error}"
    )


def _strict_card(data: dict[str, Any]) -> schemas.CardDraft:
    """Валидация контракта + проектное ограничение длины заголовка."""
    if not isinstance(data, dict):
        raise parse.ModelJsonError("ответ не JSON-объект")
    draft = schemas.CardDraft.model_validate(data)
    if len(draft.title) > settings.card_title_max_len:
        raise ValueError(f"заголовок длиннее {settings.card_title_max_len} символов")
    return draft


class TitleTooLong(ValidationError):  # маркер для точечной переделки в тестах
    pass


async def repair_field(
    draft: schemas.CardDraft, field: str, facts: schemas.SupplierFacts
) -> schemas.CardDraft:
    """Точечная переделка одного поля: модель получает текущий черновик."""
    system, _ = prompts.generator_prompt(facts, None)
    prompt = (
        "Текущий черновик карточки:\n"
        + draft.model_dump_json(indent=1)
        + f"\n\nИсправь ТОЛЬКО поле «{field}» — оно нарушает правила карточки."
        " Остальные поля оставь без изменений. Верни ПОЛНЫЙ исправленный JSON."
    )
    resp = await llm.complete(prompt, role="main", system=system)
    fixed = _strict_card(parse.extract_json(resp.text))
    log.info("spot_repair_done", field=field)
    return fixed


async def run_pipeline(
    supplier_text: str, complete: CompleteFn = llm.complete
) -> tuple[schemas.CardDraft | None, int, str, schemas.CritiqueReport | None]:
    """extract → (generate → critique → feedback)* ≤ max_pipeline_rounds.

    Возвращает (draft|None, попыток, вердикт, последний critique-report).
    Вердикт:-approved | rejected_exhausted (петля исчерпана).
    """
    facts = await extract(supplier_text, complete)
    draft: schemas.CardDraft | None = None
    report: schemas.CritiqueReport | None = None
    verdict = "rejected_exhausted"
    attempts = 0
    for attempt in range(1, settings.max_pipeline_rounds + 1):
        attempts = attempt
        feedback = report.issues if report else None
        draft = await generate(facts, feedback, complete)
        report = await critique(facts, draft, complete)
        if report.verdict == "approve":
            verdict = "approved"
            break
        log.info("pipeline_regenerate", attempt=attempt, issues=report.issues[:3])
    return draft, attempts, verdict, report
