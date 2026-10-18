"""Точка входа воркера: регистрирует процессы и их шаги.

Блокирующие шаги (база, диск, парсеры) исполняются в пуле потоков —
без activity_executor Temporal их не примет.
"""
import asyncio
from concurrent.futures import ThreadPoolExecutor

from temporalio.client import Client
from temporalio.worker import Worker

from app.core.config import settings
from app.core.logging import get_logger, setup_logging
from app.temporal.activities import (
    critique_activity,
    delete_document_file_activity,
    extract_activity,
    generate_activity,
    parse_document_activity,
    rag_critique_activity,
    rag_finalize_activity,
    rag_generate_activity,
    rag_retrieve_activity,
    set_document_status_activity,
    set_job_status_activity,
)
from app.temporal.workflows import CardWorkflow, ParseDocumentWorkflow, RagCardWorkflow

log = get_logger(__name__)


async def main():
    setup_logging()
    client = await Client.connect(settings.temporal_host)
    with ThreadPoolExecutor(max_workers=settings.temporal_activity_threads) as pool:
        worker = Worker(
            client,
            task_queue=settings.temporal_task_queue,
            workflows=[CardWorkflow, ParseDocumentWorkflow, RagCardWorkflow],
            activities=[
                extract_activity,
                generate_activity,
                critique_activity,
                set_job_status_activity,
                set_document_status_activity,
                parse_document_activity,
                rag_retrieve_activity,
                rag_generate_activity,
                rag_critique_activity,
                rag_finalize_activity,
                delete_document_file_activity,
            ],
            activity_executor=pool,
        )
        log.info("temporal_worker_started", task_queue=settings.temporal_task_queue)
        await worker.run()


if __name__ == "__main__":
    asyncio.run(main())
