"""Temporal-activities: те же стадии пайплайна из app/services.

Activities выполняются БЕЗ песочницы — здесь можно импортировать конфиг,
LLM-клиент, ходить в сеть.

Стадии карточки (extract, generate, critique) объявлены async: они ждут
модель и на это время отдают управление воркеру. Работы с диском и базой
это не касается — psycopg и парсеры блокирующие, поэтому такие activities
объявлены обычными функциями. Temporal выполняет их в пуле потоков
(см. activity_executor в app/temporal_worker.py) и цикл событий не встаёт.

Данные между workflow и activity ходят JSON-строками: Temporal сериализует
их в историю, Pydantic-валидация — на границе.
"""
import asyncio
import json
from dataclasses import asdict
from pathlib import Path

import structlog
from temporalio import activity

from app.core import db
from app.core.logging import get_logger
from app.guardrails import GuardReport
from app.guardrails.pii import mask_pii
from app.repositories import chunks as chunks_repo
from app.repositories import documents as docs_repo
from app.repositories import jobs as jobs_repo
from app.repositories import traces as traces_repo
from app.schemas.cards import CardDraft, SupplierFacts
from app.services import documents as docs_service
from app.services import pipeline as steps

log = get_logger(__name__)


@activity.defn
async def extract_activity(supplier_text: str) -> str:
    facts = await steps.extract(supplier_text)
    return facts.model_dump_json()


@activity.defn
async def generate_activity(facts_json: str, feedback_json: str | None = None) -> str:
    facts = SupplierFacts.model_validate_json(facts_json)
    feedback = json.loads(feedback_json) if feedback_json else None
    draft = await steps.generate(facts, feedback)
    return draft.model_dump_json()


@activity.defn
async def critique_activity(facts_json: str, draft_json: str) -> str:
    facts = SupplierFacts.model_validate_json(facts_json)
    draft = CardDraft.model_validate_json(draft_json)
    report = await steps.critique(facts, draft)
    return report.model_dump_json()


@activity.defn
def set_job_status_activity(
    job_id: str, status: str, draft_json: str | None = None, error: str | None = None
) -> None:
    """Состояние задачи в таблице jobs: его читает клиент через GET /jobs/{id}.

    История workflow знает то же самое, но она внутренняя: наружу отдаётся
    состояние из базы, поэтому его пишет отдельная activity.
    """
    with db.connection() as conn:
        jobs_repo.set_status(
            conn,
            job_id,
            status,
            result=CardDraft.model_validate_json(draft_json) if draft_json else None,
            error=error,
            bump_attempts=status == "generating",
        )


@activity.defn
def set_document_status_activity(doc_id: str, status: str, error: str | None = None) -> None:
    """Состояние документа в таблице documents: его читает клиент через GET /documents/{id}.

    Ставится из workflow при исчерпании попыток разбора, иначе документ
    навсегда остаётся в new/parsing.
    """
    with db.connection() as conn:
        docs_repo.set_status(conn, doc_id, status, error=error)


@activity.defn
def parse_document_activity(doc_id: str, path: str, kind: str) -> int:
    """Разбор загруженного файла: parse → normalize → chunk → база.

    Временный файл здесь НЕ удаляется: activity может быть повторена, а на
    второй попытке файла уже нет и падает FileNotFoundError вместо реальной
    причины. Уборкой занимается delete_document_file_activity — после того,
    как процесс достиг терминального состояния.
    """
    structlog.contextvars.bind_contextvars(doc_id=doc_id)
    with db.connection() as conn:
        return docs_service.ingest(conn, doc_id, Path(path).read_bytes(), kind)


@activity.defn
def delete_document_file_activity(path: str) -> None:
    """Удалить временный файл разбора. Вызывается процессом в терминальном состоянии."""
    Path(path).unlink(missing_ok=True)


@activity.defn
def rag_retrieve_activity(job_id: str, document_ids: list[str], product_hint: str = "") -> dict:
    """Первый шаг RAG-карточки: чанки → retrieval → трасса.

    Отдельный шаг: если воркер упадёт на генерации, повторный прогон начнёт не
    с нуля, а с этого шага — контекст уже в истории процесса. Трасса пишется
    замаскированной: контакты из КП не уходят в соседнюю таблицу и не отдаются
    по HTTP без авторизации (/debug).
    """
    from app.services import rag_pipeline

    structlog.contextvars.bind_contextvars(job_id=job_id)
    try:
        with db.connection() as conn:
            raw_chunks = chunks_repo.get_chunks_by_doc_ids(conn, document_ids or [])
            safe_chunks = [
                {**ch, "text": mask_pii(ch.get("text", ""))[0]} for ch in raw_chunks
            ]
            traces_repo.insert_step(
                conn,
                job_id=job_id,
                step="document_chunks",
                agent="parser",
                parsed_output={
                    "document_ids": document_ids,
                    "chunks": safe_chunks,
                },
            )
    except Exception as trace_exc:
        log.warning("trace_accounting_failed", error=repr(trace_exc))

    query = product_hint or "карточка товара характеристики"
    context, guard = asyncio.run(rag_pipeline.retrieve_context_async(query, document_ids or None))
    if guard.suspicious_chunks:
        with db.connection() as conn:
            blocked = docs_repo.mark_chunks_suspicious(conn, guard.suspicious_chunks)
        log.info(
            "guard_flagged_chunks", job_id=job_id, flagged=blocked,
            chunk_ids=guard.suspicious_chunks,
        )
    try:
        with db.connection() as conn:
            traces_repo.insert_step(
                conn,
                job_id=job_id,
                step="rag_context",
                agent="retrieval",
                raw_completion=context,
            )
    except Exception as trace_exc:
        log.warning("trace_accounting_failed", error=repr(trace_exc))
    return {
        "context": context,
        "guard_report": asdict(guard),
        "suspicious_chunks": guard.suspicious_chunks,
    }


@activity.defn
def rag_generate_activity(
    context: str, feedback_json: str | None, scores_json: str | None
) -> str:
    """Один шаг генерации черновика. Отдельный шаг = устойчивость к рестарту.

    Возвращает JSON карточки либо пустую строку, если модель упорно ссылается
    на выдуманные/несуществующие источники (SourceValidationError): карточку
    доверять нельзя, процесс уходит в needs_review, а не failed.
    """
    from app.services import rag_pipeline

    feedback = json.loads(feedback_json) if feedback_json else None
    scores = json.loads(scores_json) if scores_json else None
    try:
        draft = asyncio.run(rag_pipeline.generate_from_context(context, feedback, scores))
    except rag_pipeline.SourceValidationError as exc:
        log.warning("rag_source_validation_failed", error=str(exc)[:300])
        return ""
    return draft.model_dump_json()


@activity.defn
def rag_critique_activity(context: str, draft_json: str) -> str:
    """Один шаг критики черновика. Отдельный шаг = устойчивость к рестарту."""
    from app.services import rag_pipeline

    structlog.contextvars.bind_contextvars()
    draft = CardDraft.model_validate_json(draft_json)
    report = asyncio.run(rag_pipeline.critique_with_context(context, draft))
    return report.model_dump_json()


@activity.defn
def rag_finalize_activity(
    job_id: str, draft_json: str, guard_report_json: str, verdict: str
) -> tuple[str, str]:
    """Финализация: фильтр выхода PII, терминальный статус, запись в базу.

    Возвращает (JSON карточки, статус). Пустой draft_json → needs_review
    (выдуманные источники): карточку показываем человеку, задачу не валим в failed.
    """
    from app.services import rag_pipeline

    structlog.contextvars.bind_contextvars(job_id=job_id)
    guard = GuardReport(**json.loads(guard_report_json))
    if not draft_json:
        with db.connection() as conn:
            jobs_repo.set_status(
                conn, job_id, "needs_review", bump_attempts=True, guard_report=asdict(guard)
            )
        return "", "needs_review"
    draft = CardDraft.model_validate_json(draft_json)
    cleaned, issues = rag_pipeline.guard_output(draft.model_dump_json())
    if issues:
        log.warning("guard_output_issues", issues=issues)
    draft = CardDraft.model_validate_json(cleaned)
    cited = rag_pipeline.cited_chunk_texts(draft)
    status = rag_pipeline.decide_status(
        draft, verdict, guard, cited if verdict == "approved" else None
    )
    with db.connection() as conn:
        jobs_repo.set_status(
            conn,
            job_id,
            status,
            result=draft,
            bump_attempts=True,
            guard_report=asdict(guard),
        )
    return draft.model_dump_json(), status
