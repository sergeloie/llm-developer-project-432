"""Запуск durable-процесса из приложения (Temporal client сверхупрощён лениво)."""
from typing import Any

from temporalio.client import Client

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

_client: Client | None = None


async def get_client() -> Client:
    global _client
    if _client is None:
        _client = await Client.connect(settings.temporal_host)
    return _client


async def start_generation(job_id: str, payload: dict[str, Any]) -> str:
    """Стартовать GenerationWorkflow; вернуть workflow id."""
    client = await get_client()
    wf_id = f"generation-{job_id}"
    await client.start_workflow(
        "GenerationWorkflow",
        args=[job_id, payload, settings.confidence_human_threshold],
        id=wf_id,
        task_queue=settings.temporal_task_queue,
    )
    log.info("workflow_started", workflow_id=wf_id, job_id=job_id)
    return wf_id
