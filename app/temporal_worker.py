"""Точка входа воркера устойчивого исполнения.

Требования из задания:
- шаги, не отдающие управление (БД), исполняются в пуле потоков;
- тяжёлые импорты — под workflow.imports_passed_through (в activities.py).
"""
import asyncio
from concurrent.futures import ThreadPoolExecutor

from temporalio.client import Client
from temporalio.worker import Worker

from app.core import db
from app.core.config import settings
from app.core.logging import get_logger, setup_logging
from app.temporal.activities import (
    critique_activity,
    extract_activity,
    generate_activity,
    set_job_status_activity,
)
from app.temporal.doc_activities import (
    backfill_vectors_activity,
    doc_info_activity,
    parse_document_activity,
    set_doc_status_activity,
    store_fragments_activity,
)
from app.temporal.doc_workflows import DocumentParseWorkflow
from app.temporal.workflows import GenerationWorkflow

log = get_logger(__name__)


async def main() -> None:
    setup_logging()
    db.init_pool()
    try:
        client = await Client.connect(settings.temporal_host)
        worker = Worker(
            client,
            task_queue=settings.temporal_task_queue,
            workflows=[GenerationWorkflow, DocumentParseWorkflow],
            activities=[
                extract_activity,
                generate_activity,
                critique_activity,
                # sync-activity: пул потоков обязателен
                set_job_status_activity,
                parse_document_activity,
                store_fragments_activity,
                backfill_vectors_activity,
                set_doc_status_activity,
                doc_info_activity,
            ],
            activity_executor=ThreadPoolExecutor(max_workers=8),
        )
        log.info("temporal_worker_started", task_queue=settings.temporal_task_queue)
        await worker.run()
    finally:
        db.close_pool()


if __name__ == "__main__":
    asyncio.run(main())
