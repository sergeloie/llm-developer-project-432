"""Стадии пайплайна: extract → generate → critique.

Каждая функция — один агент-шаг. Один и тот же код работает в двух runtime:
- FastAPI-эндпоинт,
- шаг устойчивого процесса (activity).
Это и есть выигрыш слоистой архитектуры: стадия не знает, кто её вызвал.

generate идёт через validate_or_retry: ответ модели жёстко валидируется
контрактом CardDraft, невалидный ответ чинится повторным запросом с ошибкой.
"""
from app.agents.prompts import critic_agent, extractor_agent
from app.core.logging import get_logger
from app.llm.client import run_agent
from app.schemas.cards import CardDraft, CritiqueReport, SupplierFacts
from app.services.json_utils import parse_llm_json
from app.services.structured import confidence_gate, validate_or_retry

log = get_logger(__name__)


async def extract(supplier_text: str) -> SupplierFacts:
    log.info("step_extract_start")
    out = await run_agent(extractor_agent, supplier_text)
    if isinstance(out, SupplierFacts):
        return out
    return SupplierFacts.model_validate(parse_llm_json(out))


async def generate(facts: SupplierFacts, feedback: list[str] | None = None) -> CardDraft:
    prompt = "Факты о товаре:\n" + facts.model_dump_json(indent=2)
    prefix = ""
    if feedback:
        prefix = "Замечания критика, учти:\n" + "\n".join(f"- {i}" for i in feedback) + "\n\n"
    log.info("step_generate_start", feedback=bool(feedback))
    return await validate_or_retry(prompt, feedback_prefix=prefix)


async def critique(facts: SupplierFacts, draft: CardDraft) -> CritiqueReport:
    prompt = (
        "Факты о товаре:\n"
        + facts.model_dump_json(indent=2)
        + "\n\nЧерновик карточки для проверки:\n"
        + draft.model_dump_json(indent=2)
    )
    log.info("step_critique_start")
    out = await run_agent(critic_agent, prompt)
    if isinstance(out, CritiqueReport):
        return out
    return CritiqueReport.model_validate(parse_llm_json(out))


async def run_pipeline(supplier_text: str, max_attempts: int = 3):
    """Полный цикл: extract → (generate → critique → feedback)* → итог.

    Возвращает (итоговый черновик, число генераций, вердикт, финальный статус).
    Финальный статус учитывает confidence-gate: низкая уверенность → needs_review.
    """
    facts = await extract(supplier_text)
    feedback: list[str] = []
    attempts = 0
    draft = None
    verdict = "rejected"
    while attempts < max_attempts:
        attempts += 1
        draft = await generate(facts, feedback or None)
        report = await critique(facts, draft)
        if report.verdict == "approve":
            verdict = "approved"
            break
        feedback = report.issues
        log.info("pipeline_regenerate", attempt=attempts, issues=report.issues)
    status = confidence_gate(draft) if verdict == "approved" and draft else "rejected"
    return draft, attempts, verdict, status
