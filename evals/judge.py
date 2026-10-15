"""LLM-as-judge: faithfulness — подтверждается ли карточка контекстом.

Judge — отдельный узкий промпт на ДЕШЁВОЙ модели: это служебный вызов,
не творческая генерация.
"""

from agents import Agent, AgentOutputSchema, ModelSettings
from pydantic import BaseModel, Field

from app.core.config import settings
from app.core.logging import get_logger
from app.llm.client import model_for
from app.services.json_utils import parse_llm_json

log = get_logger(__name__)


class JudgeVerdict(BaseModel):
    faithfulness: float = Field(description="0.0-1.0: доля утверждений, подтверждённых контекстом")
    reasons: list[str] = Field(default_factory=list)


_judge_agent = Agent(
    name="Judge",
    model=model_for(settings.llm_model_cheap),
    model_settings=ModelSettings(max_tokens=4096, temperature=0.1),
    instructions=(
        "Ты — строгий оценщик карточек товара. Получаешь КОНТЕКСТ (фрагменты "
        "документов) и КАРТОЧКУ. Оцени, насколько каждое утверждение карточки "
        "(характеристики, преимущества, описание) подтверждается контекстом.\n"
        "Верни СТРОГО JSON: {\"faithfulness\": <0.0-1.0>, \"reasons\": [\"...\"]}.\n"
        "faithfulness = доля подтверждённых утверждений. Выдуманное значение — 0. "
        "Никакого markdown."
    ),
    output_type=AgentOutputSchema(JudgeVerdict, strict_json_schema=False),
)


async def judge_faithfulness(context: str, card_json: str) -> JudgeVerdict:
    from app.llm.client import run_agent

    prompt = (
        "КОНТЕКСТ:\n" + context + "\n\nКАРТОЧКА:\n" + card_json
    )
    out = await run_agent(_judge_agent, prompt)
    if isinstance(out, JudgeVerdict):
        return out
    return JudgeVerdict.model_validate(parse_llm_json(out))
