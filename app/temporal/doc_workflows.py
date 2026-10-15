"""DocumentParseWorkflow: разбор → сохранение фрагментов → статус.

Разбор и запись — activities (блокирующие); статус пишется отдельными шагами.
"""
import json
from datetime import timedelta

from temporalio import workflow

with workflow.unsafe.imports_passed_through():
    from app.temporal.doc_activities import (
        backfill_vectors_activity,
        doc_info_activity,
        parse_document_activity,
        set_doc_status_activity,
        store_fragments_activity,
    )

ACTIVITY_TIMEOUT = timedelta(minutes=20)
STATUS_TIMEOUT = timedelta(seconds=60)


@workflow.defn
class DocumentParseWorkflow:
    def __init__(self) -> None:
        self._phase = "created"

    @workflow.run
    async def run(self, document_id: int, path: str) -> dict:
        self._phase = "parsing"
        await workflow.execute_activity(
            set_doc_status_activity,
            args=[document_id, "parsing"],
            start_to_close_timeout=STATUS_TIMEOUT,
        )
        fragments_json = await workflow.execute_activity(
            parse_document_activity,
            args=[document_id, path],
            start_to_close_timeout=ACTIVITY_TIMEOUT,
        )
        self._phase = "storing"
        n = await workflow.execute_activity(
            store_fragments_activity,
            args=[document_id, fragments_json],
            start_to_close_timeout=STATUS_TIMEOUT,
        )
        # индексация: векторы считаются сразу после записи фрагментов (шаг 6)
        self._phase = "embedding"
        try:
            await workflow.execute_activity(
                backfill_vectors_activity,
                args=[document_id],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                heartbeat_timeout=timedelta(minutes=3),
            )
        except BaseException as exc:  # noqa: BLE001 — эмбеддинги недоступны: документ уже в базе, досчитает make backfill
            workflow.logger.error("backfill_failed", document_id=document_id, error=str(exc))
        # счётчик фрагментов для отчёта
        info = json.loads(
            await workflow.execute_activity(
                doc_info_activity,
                args=[document_id],
                start_to_close_timeout=STATUS_TIMEOUT,
            )
        )
        self._phase = "completed"
        return {"document_id": document_id, "fragments": n, **info}

    @workflow.query
    def phase(self) -> str:
        return self._phase
