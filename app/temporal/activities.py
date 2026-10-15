"""Activities — вне песочницы: здесь можно БД, LLM, диск.

БД-шаг — sync-функция (исполняется в пуле потоков воркера): соединение
берётся на операцию, а не на всё время шага. LLM-шаги — async.
"""
import json

from temporalio import activity

from app.core import db
from app.core.logging import get_logger
from app.repositories import jobs as jobs_repo
from app.services import pipeline

log = get_logger(__name__)


def _llm_response(mocker_text: str):
    from app.llm.client import LlmResponse

    return LlmResponse(text=mocker_text, model="mock", role="mock")


@activity.defn
async def extract_activity(supplier_text: str) -> str:
    """Extract-роль: факты из текста. Возвращает JSON SupplierFacts."""
    activity.heartbeat()
    facts = await pipeline.extract(supplier_text)
    activity.heartbeat()
    return facts.model_dump_json()


@activity.defn
async def generate_activity(facts_json: str, feedback: list[str] | None = None) -> str:
    """Generate-роль: черновик карточки. JSON CardDraft."""
    from app import schemas

    facts = schemas.SupplierFacts.model_validate_json(facts_json)
    activity.heartbeat()
    draft = await pipeline.generate(facts, feedback)
    activity.heartbeat()
    return draft.model_dump_json()


@activity.defn
async def critique_activity(facts_json: str, draft_json: str) -> str:
    """Critic-роль: вердикт по черновику. JSON CritiqueReport."""
    from app import schemas

    facts = schemas.SupplierFacts.model_validate_json(facts_json)
    draft = schemas.CardDraft.model_validate_json(draft_json)
    activity.heartbeat()
    report = await pipeline.critique(facts, draft)
    activity.heartbeat()
    return report.model_dump_json()


@activity.defn
def set_job_status_activity(
    job_id: str, status: str, result_json: str | None, rounds: int | None
) -> None:
    """Статус предметной области — в базу отдельным коротким обращением.

    Соединение берётся только на операцию записи; модель в это время
    не ждёт соединения.
    """
    with db.connection() as conn:
        if result_json is not None:
            jobs_repo.set_result(conn, job_id, json.loads(result_json), status)
        else:
            jobs_repo.update_status(conn, job_id, status, attempts=rounds)
