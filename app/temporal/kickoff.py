"""Запуск durable-процесса из приложения (Temporal client сверхупрощён лениво)."""
from typing import Any

from temporalio.client import Client

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

_client: Client | None = None


async def get_client() -> Client:
    """Единый ленивый клиент для всех точек старта процессов."""
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


async def start_document_parse(document_id: int, path: str) -> str:
    """Стартовать разбор/индексацию документа — тот же воркер, другой workflow."""
    client = await get_client()
    wf_id = f"document-{document_id}"
    await client.start_workflow(
        "DocumentParseWorkflow",
        args=[document_id, path],
        id=wf_id,
        task_queue=settings.temporal_task_queue,
    )
    log.info("document_workflow_started", workflow_id=wf_id, document_id=document_id)
    return wf_id
