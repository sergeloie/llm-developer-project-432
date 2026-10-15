"""Валидация ответа LLM по строгому контракту + лечение невалидных ответов.

Три уровня надёжности structured output:
1. parse_llm_json — достаём JSON из «грязного» ответа модели.
2. validate_or_retry — при ValidationError шлём ошибку обратно модели (<=2 раз).
3. regenerate_field — чиним одно сломанное поле, не перегенерируя карточку.
"""
from pydantic import ValidationError

from app.agents.prompts import fixer_agent, generator_agent
from app.core.config import settings
from app.core.logging import get_logger
from app.llm.client import run_agent
from app.schemas.cards import CardDraft
from app.services.json_utils import LLMJsonError, parse_llm_json

log = get_logger(__name__)


class CardValidationError(Exception):
    """Карточку не удалось валидировать за отведённые попытки."""


async def validate_or_retry(prompt: str, feedback_prefix: str = "") -> CardDraft:
    """Сгенерировать карточку и провалидировать; при ошибке — повтор с текстом ошибки.

    feedback_prefix — замечания критика (если это регенерация пайплайна).
    Бросает CardValidationError после settings.max_fix_attempts неудачных попыток.
    """
    current_prompt = feedback_prefix + prompt
    last_error: Exception | None = None
    for attempt in range(settings.max_fix_attempts + 1):
        out = await run_agent(generator_agent, current_prompt)
        if isinstance(out, CardDraft):
            return out
        try:
            data = parse_llm_json(out)
            return CardDraft.model_validate(data)
        except (LLMJsonError, ValidationError) as exc:
            last_error = exc
            log.warning(
                "card_validation_failed", attempt=attempt + 1, error=str(exc)[:300]
            )
            current_prompt = (
                f"Твой предыдущий ответ невалиден. Ошибка валидации:\n{exc}\n\n"
                f"Исправь и верни СТРОГО валидный JSON карточки по контракту. "
                f"Предыдущий запрос:\n{current_prompt}"
            )
    raise CardValidationError(f"карточка не прошла валидацию: {last_error}")


async def regenerate_field(draft: CardDraft, field: str, error: str) -> CardDraft:
    """Точечная регенерация: чиним одно поле, остальное остаётся как есть.

    Дешевле полной регенерации: модель получает текущий черновик
    и конкретную ошибку.
    """
    prompt = (
        "В черновике карточки поле "
        f"{field!r} не прошло валидацию:\n{error}\n\n"
        "Текущий черновик (JSON):\n"
        + draft.model_dump_json(indent=2)
        + "\n\nПерегенерируй ТОЛЬКО это поле, остальное верни без изменений. "
        "Ответ — полный JSON карточки."
    )
    out = await run_agent(fixer_agent, prompt)
    if isinstance(out, CardDraft):
        return out
    data = parse_llm_json(out)
    return CardDraft.model_validate(data)


def confidence_gate(draft: CardDraft) -> str:
    """Низкая уверенность → карточка требует ручной проверки."""
    return "needs_review" if draft.confidence < settings.confidence_human_threshold else "done"
