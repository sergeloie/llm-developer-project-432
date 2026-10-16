"""RAG-пайплайн: документы → retrieval → контекст → карточка с цитированием.

Второй уровень анти-галлюцинаций: валидация sources — каждый chunk_id
обязан существовать в БД и входить в поданный контекст.
"""
from collections.abc import Mapping

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
    if doc_ids:
        # Генерация по явно заданным документам: берём ВСЕ их чанки, без порога
        # сходства. Иначе similarity-top-k (threshold=0.35 + AND-FTS) отбрасывает
        # до 75% текста, и модель достраивает карточку из имени файла (баг с
        # heater_passport). Контекст режется по лимиту символов уже в build_context.
        with db.connection() as conn:
            chunks = chunks_repo.get_chunks_by_doc_ids(conn, doc_ids)
        chunks, report = await guard_context(chunks)
        report.retrieval_scores = {}
        return build_context(chunks), report

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


CRITIC_CONTRACT = (
    "КОНТРАКТ: что заполняет система, а за что отвечаешь ты.\n"
    "- sources: doc_id, page и score подставляет система после генерации "
    "(модель их не видит). Их наличие и любые их значения НЕ дефект; "
    "исправить их генератор не может — не пиши их в issues.\n"
    "- sources: твоя ответственность только chunk_id, и он должен "
    "встречаться в КОНТЕКСТЕ как [chunk_id | ...]. Дефект — chunk_id, "
    "которого в контексте нет.\n"
    "- missing_fields: непустой список ожидаем, если чего-то нет в контексте; "
    "пустой список сам по себе не дефект.\n"
    "- Не пиши проблемой то, что правилу соответствует (например длину title, "
    "котая укладывается в 60 символов).\n"
    "Дефектов нет — verdict approve."
)


def critic_prompt(context: str, draft: CardDraft) -> str:
    """Промпт критика: правила + контракт + контекст + черновик."""
    return (
        "Проверь черновик карточки против КОНТЕКСТА с источниками [chunk_id].\n"
        "Правила:\n"
        "1. title не длиннее 60 символов\n"
        "2. Характеристики подтверждаются контекстом (сверяй значения)\n"
        "3. description и benefits не содержат воды и фактов вне контекста\n"
        "4. sources указывают на чанки, где данные реально есть\n"
        + CRITIC_CONTRACT
        + "\n\nКОНТЕКСТ:\n"
        + context
        + "\n\nЧерновик:\n"
        + draft.model_dump_json(indent=2)
    )


async def critique_with_context(context: str, draft: CardDraft) -> CritiqueReport:
    """Critic: каждое утверждение опирается на заявленный источник.

    Контракт из CRITIC_CONTRACT обязателен: без него критик считает
    дефектом то, что подставила система, и петля переделки выжигает
    все круги на споре с самим собой, а карточка уходит в rejected.
    """
    out = await run_agent(critic_agent, critic_prompt(context, draft))
    if isinstance(out, CritiqueReport):
        return out
    return CritiqueReport.model_validate(parse_llm_json(out))


def cited_chunk_texts(draft: CardDraft) -> dict[str, str]:
    """Тексты фрагментов, на которые ссылается карточка: chunk_id → текст.

    Нужен, чтобы посчитать покрытие: значение характеристики подтверждает
    именно текст чанка, а не сам факт ссылки на него.
    """
    ids = sorted({ref.chunk_id for ref in draft.sources})
    if not ids:
        return {}
    with db.connection() as conn:
        known = chunks_repo.get_chunks_by_ids(conn, ids)
    return {chunk_id: chunk["text"] for chunk_id, chunk in known.items()}


def decide_status(
    draft: CardDraft | None,
    verdict: str,
    guard_report: GuardReport,
    chunk_texts: Mapping[str, str] | None = None,
) -> str:
    """Терминальный статус карточки: rejected / needs_review / done.

    Нулевое покрытие источников — не готовая работа ни при какой самооценке
    модели: карточка без ссылок и без missing_fields утверждает, что не
    нашлось ни данных, ни пробелов, а это обратное тому, что обещает контракт.
    Дальше решают guardrails, покрытие источников и уверенность модели.
    """
    if draft is None or verdict != "approved":
        return "rejected"
    coverage = draft.derived_source_coverage(chunk_texts)
    log.info(
        "rag_source_coverage",
        coverage=round(coverage, 3),
        threshold=settings.source_coverage_threshold,
        characteristics=len(draft.characteristics),
        sources=len(draft.sources),
    )
    if (
        not draft.sources
        or guard_report.needs_review
        or coverage < settings.source_coverage_threshold
        or draft.confidence < settings.confidence_human_threshold
    ):
        return "needs_review"
    return "done"


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

    status = decide_status(
        draft,
        verdict,
        guard_report,
        cited_chunk_texts(draft) if draft is not None and verdict == "approved" else None,
    )
    return draft, attempts, verdict, status, guard_report, context
