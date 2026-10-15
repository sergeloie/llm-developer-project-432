"""Постановка задачи в очередь: строка в базе плюс устойчивый процесс.

Общий шаг для POST /jobs и POST /generate-card. Идентификатор процесса равен
идентификатору задачи в базе, поэтому повторный запрос с тем же ключом
идемпотентности обязан вернуть ту же задачу и её текущий статус, а не второй
проход по пайплайну.
"""
from collections.abc import Awaitable, Callable
from typing import Any

from temporalio.exceptions import WorkflowAlreadyStartedError

from app.core import db
from app.core.config import settings
from app.core.logging import get_logger
from app.repositories import jobs as jobs_repo
from app.temporal.client import temporal_client

log = get_logger(__name__)


async def submit_job(
    payload: dict,
    idempotency_key: str | None,
    workflow_run: Callable[..., Awaitable[Any]],
    args: Callable[[str], list],
) -> dict:
    """Создать или переиспользовать задачу, запустить процесс только для новой.

    args(job_id) — аргументы процесса; идентификатор передаётся первым.
    Гонка (два запроса с одним ключом одновременно) закрыта перехватом
    WorkflowAlreadyStartedError: задача уже есть, процесс уже идёт.
    """
    with db.connection() as conn:
        job_id, created = jobs_repo.create_job(conn, payload, idempotency_key)
        if not created:
            return {"id": job_id, "status": jobs_repo.get_job(conn, job_id)["status"]}
    client = await temporal_client()
    try:
        await client.start_workflow(
            workflow_run,
            args=args(job_id),
            id=job_id,
            task_queue=settings.temporal_task_queue,
        )
    except WorkflowAlreadyStartedError:
        log.info("workflow_already_started", job_id=job_id)
        with db.connection() as conn:
            return {"id": job_id, "status": jobs_repo.get_job(conn, job_id)["status"]}
    return {"id": job_id, "status": "pending"}