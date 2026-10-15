"""RAG-пайплайн: документы → retrieval → контекст → карточка с цитированием.

Второй уровень анти-галлюцинаций: валидация sources — каждый chunk_id
обязан существовать в БД и входить в поданный контекст.
"""
from pydantic import ValidationError

from app.agents.prompts import context_generator_agent, critic_agent
from app.core import db
from app.core.config import settings
from app.core.logging import get_logger
from app.guardrails import GuardReport, guard_context, guard_output
from app.llm.client import run_agent
from app.rag.context import CONTEXT_INSTRUCTION, build_context, context_chunk_ids
from app.rag.retrieval import search
from app.repositories import chunks as chunks_repo
from app.schemas.cards import CardDraft, CritiqueReport
from app.services.json_utils import LLMJsonError, parse_llm_json

log = get_logger(__name__)


class SourceValidationError(Exception):
    """Модель сослалась на несуществующий/неподанный chunk_id."""


async def retrieve_context_async(
    query: str, doc_ids: list[str] | None = None
) -> tuple[str, GuardReport]:
    """Retrieval → guardrails → сборка контекста. Возвращает (контекст, отчёт)."""
    chunks = search(query, mode="hybrid", doc_ids=doc_ids, top_k=settings.retrieval_top_k)
    chunks, report = await guard_context(chunks)
    report.retrieval_scores = {
        ch["chunk_id"]: ch.get("score") if ch.get("score") is not None else ch.get("rrf_score")
        for ch in chunks
    }
    return build_context(chunks), report


async def generate_from_context(
    context: str, feedback: list[str] | None = None, scores: dict[str, float] | None = None
) -> CardDraft:
    """Генерация карточки по контексту с ретраями валидации и источников."""
    prefix = ""
    if feedback:
        prefix = "Замечания критика, учти:\n" + "\n".join(f"- {i}" for i in feedback) + "\n\n"
    prompt = CONTEXT_INSTRUCTION + "\nКОНТЕКСТ:\n" + context
    allowed = context_chunk_ids(context)

    current_prompt = prefix + prompt
    last_error: Exception | None = None
    for attempt in range(settings.max_fix_attempts + 1):
        out = await run_agent(context_generator_agent, current_prompt)
        try:
            draft = _coerce_card(out)
            known = _validate_sources(draft, allowed)
            _enrich_sources(draft, known, scores or {})
            return draft
        except (LLMJsonError, ValidationError, SourceValidationError) as exc:
            last_error = exc
            log.warning("rag_card_invalid", attempt=attempt + 1, error=str(exc)[:300])
            current_prompt = (
                f"Твой предыдущий ответ отклонён. Причина:\n{exc}\n\n"
                f"Исправь и верни валидный JSON карточки. Разрешённые chunk_id "
                f"для sources: {sorted(allowed)}. Исходный запрос:\n{current_prompt}"
            )
    raise SourceValidationError(f"карточка не прошла валидацию: {last_error}")


def _coerce_card(out) -> CardDraft:
    """Ответ агента → CardDraft.

    SDK может вернуть уже провалидированный объект (output_type),
    строку-JSON или строку-python-repr — все пути ведут к контракту.
    """
    if isinstance(out, CardDraft):
        return out
    data = parse_llm_json(out)
    return CardDraft.model_validate(data)


def _validate_sources(draft: CardDraft, allowed: set[str]) -> dict[str, dict]:
    """Источники карточки: существуют в БД и были в контексте.

    Возвращает chunk_id → строка документа, чтобы источник можно было дополнить
    без повторного запроса.
    """
    if not draft.sources:
        return {}  # пустые sources допустимы (модель не обязана цитировать всё)
    claimed = {s.chunk_id for s in draft.sources}
    unknown = claimed - allowed
    if unknown:
        raise SourceValidationError(
            f"выдуманные источники (не было в контексте): {sorted(unknown)}"
        )
    with db.connection() as conn:
        known = chunks_repo.get_chunks_by_ids(conn, sorted(claimed))
    missing = claimed - set(known)
    if missing:
        raise SourceValidationError(f"источники не существуют в БД: {sorted(missing)}")
    return known


def _enrich_sources(
    draft: CardDraft, known: dict[str, dict], scores: dict[str, float]
) -> None:
    """Дополнить источники парой «документ + фрагмент» и оценкой retrieval.

    Модель не видит ни doc_id, ни score, и выдумывать их не должна: их берём из
    БД и из результата retrieval. Источник, у которого чанк не принадлежит
    документу, таким образом больше нельзя выдать за настоящий.
    """
    for ref in draft.sources:
        info = known.get(ref.chunk_id)
        if info is None:
            continue
        if not ref.doc_id:
            ref.doc_id = info["doc_id"]
        page = (info.get("metadata") or {}).get("page")
        if ref.page is None and page is not None:
            ref.page = int(page)
        if ref.score is None:
            score = scores.get(ref.chunk_id)
            if score is not None:
                ref.score = round(float(score), 4)


async def critique_with_context(context: str, draft: CardDraft) -> CritiqueReport:
    """Critic: каждое утверждение опирается на заявленный источник."""
    prompt = (
        "Проверь черновик карточки против КОНТЕКСТА с источниками [chunk_id].\n"
        "Правила:\n"
        "1. title не длиннее 60 символов\n"
        "2. Характеристики подтверждаются контекстом (сверяй значения)\n"
        "3. description и benefits не содержат воды и фактов вне контекста\n"
        "4. sources указывают на чанки, где данные реально есть\n\n"
        "КОНТЕКСТ:\n" + context + "\n\nЧерновик:\n" + draft.model_dump_json(indent=2)
    )
    out = await run_agent(critic_agent, prompt)
    if isinstance(out, CritiqueReport):
        return out
    return CritiqueReport.model_validate(parse_llm_json(out))


async def run_rag_pipeline(
    query: str, doc_ids: list[str] | None = None, max_attempts: int | None = None
):
    """Полный RAG-цикл: retrieval → guardrails → (generate → critique)* → guard_output.

    max_attempts по умолчанию — settings.max_pipeline_rounds.
    Возвращает (draft, attempts, verdict, status, guard_report, context):
    context — тот самый текст, по которому строилась карточка, а не отчёт
    guardrails: без него потребитель не может сверить источники с тем,
    что реально ушло в модель.
    """
    rounds = settings.max_pipeline_rounds if max_attempts is None else max_attempts
    context, guard_report = await retrieve_context_async(query, doc_ids)
    feedback: list[str] = []
    attempts = 0
    draft = None
    verdict = "rejected"
    while attempts < rounds:
        attempts += 1
        draft = await generate_from_context(
            context, feedback or None, guard_report.retrieval_scores
        )
        report = await critique_with_context(context, draft)
        if report.verdict == "approve":
            verdict = "approved"
            break
        feedback = report.issues
        log.info("rag_pipeline_regenerate", attempt=attempts, issues=report.issues)

    if verdict == "approved" and draft is not None:
        # фильтр выхода: следы PII/инъекции в карточке
        cleaned, issues = guard_output(draft.model_dump_json())
        if issues:
            log.warning("guard_output_issues", issues=issues)
        draft = CardDraft.model_validate_json(cleaned)

    status = "rejected"
    if verdict == "approved" and draft is not None:
        if guard_report.needs_review or draft.confidence < settings.confidence_human_threshold:
            status = "needs_review"
        else:
            status = "done"
    return draft, attempts, verdict, status, guard_report, context
