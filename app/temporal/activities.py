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
from pathlib import Path

import structlog
from temporalio import activity

from app.core import db
from app.core.logging import get_logger
from app.repositories import jobs as jobs_repo
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
def parse_document_activity(doc_id: str, path: str, kind: str) -> int:
    """Разбор загруженного файла: parse → normalize → chunk → база."""
    structlog.contextvars.bind_contextvars(doc_id=doc_id)
    try:
        with db.connection() as conn:
            return docs_service.ingest(conn, doc_id, Path(path).read_bytes(), kind)
    finally:
        Path(path).unlink(missing_ok=True)


@activity.defn
def rag_card_activity(job_id: str, document_ids: list[str], product_hint: str = "") -> str:
    """Полный RAG-пайплайн: retrieval → генерация → критик → статус в базе.

    Пайплайн async, activity блокирующая: она уже исполняется в отдельном
    потоке, поэтому цикл событий поднимается здесь своим asyncio.run.
    """
    from app.services import rag_pipeline

    structlog.contextvars.bind_contextvars(job_id=job_id)
    # Соединение берётся на каждую запись статуса, а не на весь пайплайн: держать
    # его занятым, пока отвечает модель, значит занять место в пуле на минуты.
    try:
        with db.connection() as conn:
            jobs_repo.set_status(conn, job_id, "retrieving")
        query = product_hint or "карточка товара характеристики"
        draft, attempts, _verdict, status, guard = asyncio.run(
            rag_pipeline.run_rag_pipeline(query, document_ids or None)
        )
        with db.connection() as conn:
            jobs_repo.set_status(conn, job_id, status, result=draft, bump_attempts=True)
        log.info(
            "rag_job_finished", job_id=job_id, status=status, attempts=attempts,
            masked_pii=guard.masked_pii, suspicious=len(guard.suspicious_chunks),
        )
        return draft.model_dump_json()
    except Exception as exc:
        with db.connection() as conn:
            jobs_repo.set_status(conn, job_id, "failed", error=repr(exc))
        log.error("rag_generate_failed", job_id=job_id, error=repr(exc))
        raise
