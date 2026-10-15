"""Процессы устойчивого исполнения: разбор документа, RAG-карточка, модерация.

Правила детерминизма workflow-кода:
- никаких datetime.now / random / HTTP / чтения конфига — только логика;
- все side-effects — в activities (app/temporal/activities.py);
- таймауты и лимиты — константы модуля, не settings (конфиг меняется
  между реплеями и ломает воспроизведение истории).

Песочница воркфлоу запрещает тяжёлые импорты, поэтому app.schemas
проходит через workflow.unsafe.imports_passed_through().
"""
import json
from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy

with workflow.unsafe.imports_passed_through():
    # Pydantic-модели используются только для парсинга JSON внутри workflow:
    # валидация детерминирована, сетевых вызовов нет — песочницу отключаем.
    from app.schemas.cards import CritiqueReport

    # Activities импортируются под passthrough: их модуль тянет LLM-клиент
    # и openai/httpx2, которым в песочнице не место. Workflow использует
    # только ссылки на функции — исполнение всё равно вне песочницы.
    from app.temporal.activities import (
        critique_activity,
        extract_activity,
        generate_activity,
        parse_document_activity,
        rag_card_activity,
        set_job_status_activity,
    )

ACTIVITY_TIMEOUT = timedelta(seconds=300)
PARSE_TIMEOUT = timedelta(minutes=10)
RAG_TIMEOUT = timedelta(minutes=15)
STATUS_TIMEOUT = timedelta(seconds=30)

# Повтор на стороне процесса: сетевой сбой или перезапуск воркера не теряет работу.
# Три попытки, дальше процесс падает с ошибкой и она видна в истории.
RETRY = RetryPolicy(maximum_attempts=3)


@workflow.defn
class ParseDocumentWorkflow:
    """Загруженный файл → фрагменты в базе. Один шаг, но переживает рестарт."""

    @workflow.run
    async def run(self, doc_id: str, path: str, kind: str) -> int:
        return await workflow.execute_activity(
            parse_document_activity,
            args=[doc_id, path, kind],
            start_to_close_timeout=PARSE_TIMEOUT,
            retry_policy=RETRY,
        )


@workflow.defn
class RagCardWorkflow:
    """Карточка по документам: поиск, генерация, критик. Статус — в базе."""

    @workflow.run
    async def run(self, job_id: str, document_ids: list[str], product_hint: str = "") -> str:
        return await workflow.execute_activity(
            rag_card_activity,
            args=[job_id, document_ids, product_hint],
            start_to_close_timeout=RAG_TIMEOUT,
            retry_policy=RETRY,
        )


@workflow.defn
class CardWorkflow:
    """extract → (generate → critique)* → сигнал оператора → финал.

    Идентификатор процесса равен идентификатору задачи в базе, поэтому
    состояние читается и через GET /jobs/{id}, и запросом к процессу.
    """

    def __init__(self) -> None:
        self._status = "starting"
        self._human_decision: str | None = None

    async def _publish(self, job_id: str, status: str, draft_json: str | None = None) -> None:
        self._status = status
        await workflow.execute_activity(
            set_job_status_activity,
            args=[job_id, status, draft_json, None],
            start_to_close_timeout=STATUS_TIMEOUT,
            retry_policy=RETRY,
        )

    @workflow.run
    async def run(self, job_id: str, supplier_text: str, max_attempts: int = 3) -> str:
        await self._publish(job_id, "extracting")
        facts_json = await workflow.execute_activity(
            extract_activity,
            supplier_text,
            start_to_close_timeout=ACTIVITY_TIMEOUT,
            retry_policy=RETRY,
        )

        feedback: list[str] = []
        attempts = 0
        draft_json = "{}"
        while attempts < max_attempts:
            attempts += 1
            await self._publish(job_id, "generating")
            draft_json = await workflow.execute_activity(
                generate_activity,
                args=[facts_json, json.dumps(feedback, ensure_ascii=False) if feedback else None],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                retry_policy=RETRY,
            )
            await self._publish(job_id, "critiquing")
            report = CritiqueReport.model_validate_json(
                await workflow.execute_activity(
                    critique_activity,
                    args=[facts_json, draft_json],
                    start_to_close_timeout=ACTIVITY_TIMEOUT,
                    retry_policy=RETRY,
                )
            )
            if report.verdict == "approve":
                break
            feedback = report.issues

        await self._publish(job_id, "awaiting_human", draft_json)
        await workflow.wait_condition(lambda: self._human_decision is not None)

        final = "approved" if self._human_decision == "approve" else "rejected"
        await self._publish(job_id, final, draft_json)
        return draft_json

    @workflow.signal
    def approve(self) -> None:
        self._human_decision = "approve"

    @workflow.signal
    def request_changes(self) -> None:
        self._human_decision = "regenerate"

    @workflow.query
    def current_status(self) -> str:
        return self._status
