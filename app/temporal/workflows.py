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
from temporalio.exceptions import ActivityError

with workflow.unsafe.imports_passed_through():
    # Pydantic-модели используются только для парсинга JSON внутри workflow:
    # валидация детерминирована, сетевых вызовов нет — песочницу отключаем.
    from app.schemas.cards import CritiqueReport

    # Activities импортируются под passthrough: их модуль тянет LLM-клиент
    # и openai/httpx2, которым в песочнице не место. Workflow использует
    # только ссылки на функции — исполнение всё равно вне песочницы.
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

ACTIVITY_TIMEOUT = timedelta(seconds=300)
PARSE_TIMEOUT = timedelta(minutes=10)
STATUS_TIMEOUT = timedelta(seconds=30)

# Повтор на стороне процесса: сетевой сбой или перезапуск воркера не теряет работу.
# Три попытки, дальше процесс падает с ошибкой и она видна в истории.
RETRY = RetryPolicy(maximum_attempts=3)

# Число кругов (generate → critique) в RAG-карточке. Зеркалит
# settings.max_pipeline_rounds: в workflow только константы, не settings
# (конфиг меняется между реплеями и ломает воспроизведение истории).
RAG_PIPELINE_ROUNDS = 3

MAX_ERROR_LEN = 480


def failure_reason(exc: ActivityError) -> str:
    """Причина падения activity строкой для клиента.

    Берётся из самой ошибки процесса (ActivityError), а не собирается в
    workflow-коде, поэтому повторяется при реплее: и текст ошибки, и её
    цепочка приходят из истории, где уже записаны.
    """
    cause = getattr(exc, "cause", None)
    inner = cause if isinstance(cause, BaseException) else exc
    kind = type(inner).__name__
    return f"{exc.activity_type}: {kind}: {inner}"[:MAX_ERROR_LEN]


@workflow.defn
class ParseDocumentWorkflow:
    """Загруженный файл → фрагменты в базе. Один шаг, но переживает рестарт.

    Временный файл живёт до терминального состояния: пока идут повторы activity,
    файл на месте и вторая попытка читает те же байты, что и первая.
    """

    @workflow.run
    async def run(self, doc_id: str, path: str, kind: str) -> int:
        try:
            try:
                return await workflow.execute_activity(
                    parse_document_activity,
                    args=[doc_id, path, kind],
                    start_to_close_timeout=PARSE_TIMEOUT,
                    retry_policy=RETRY,
                )
            except ActivityError as exc:
                await workflow.execute_activity(
                    set_document_status_activity,
                    args=[doc_id, "failed", failure_reason(exc)],
                    start_to_close_timeout=STATUS_TIMEOUT,
                    retry_policy=RETRY,
                )
                raise
        finally:
            await workflow.execute_activity(
                delete_document_file_activity,
                args=[path],
                start_to_close_timeout=STATUS_TIMEOUT,
                retry_policy=RETRY,
            )


@workflow.defn
class RagCardWorkflow:
    """Карточка по документам: retrieval → (generate → critique)* → человек.

    Каждый шаг — отдельная activity, поэтому падение воркера не сбрасывает весь
    прогон: пройденные шаги уже в истории процесса и повторно не считаются.
    Готовая (done) карточка ждёт человека (сигналы approve/request_changes),
    как CardWorkflow, — автоподтверждение модели отключено.
    """

    def __init__(self) -> None:
        self._status = "starting"
        self._human_decision: str | None = None

    async def _publish(
        self,
        job_id: str,
        status: str,
        draft_json: str | None = None,
        error: str | None = None,
    ) -> None:
        self._status = status
        await workflow.execute_activity(
            set_job_status_activity,
            args=[job_id, status, draft_json, error],
            start_to_close_timeout=STATUS_TIMEOUT,
            retry_policy=RETRY,
        )

    @workflow.run
    async def run(self, job_id: str, document_ids: list[str], product_hint: str = "") -> str:
        try:
            await self._publish(job_id, "retrieving")
            payload = await workflow.execute_activity(
                rag_retrieve_activity,
                args=[job_id, document_ids, product_hint],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                retry_policy=RETRY,
            )
            context = payload["context"]
            guard_report_json = json.dumps(payload["guard_report"], ensure_ascii=False)
            scores_json = json.dumps(
                payload["guard_report"].get("retrieval_scores") or {}, ensure_ascii=False
            )

            attempts = 0
            draft_json = ""
            verdict = "rejected"
            feedback_json = None
            while attempts < RAG_PIPELINE_ROUNDS:
                attempts += 1
                await self._publish(job_id, "generating")
                draft_json = await workflow.execute_activity(
                    rag_generate_activity,
                    args=[context, feedback_json, scores_json],
                    start_to_close_timeout=ACTIVITY_TIMEOUT,
                    retry_policy=RETRY,
                )
                if not draft_json:
                    # Выдуманные источники: карточку доверять нельзя, но задачу
                    # не валим в failed — человек посмотрит (needs_review).
                    break
                await self._publish(job_id, "critiquing")
                report = CritiqueReport.model_validate_json(
                    await workflow.execute_activity(
                        rag_critique_activity,
                        args=[context, draft_json],
                        start_to_close_timeout=ACTIVITY_TIMEOUT,
                        retry_policy=RETRY,
                    )
                )
                if report.verdict == "approve":
                    verdict = "approved"
                    break
                feedback_json = json.dumps(report.issues, ensure_ascii=False)

            final_json, status = await workflow.execute_activity(
                rag_finalize_activity,
                args=[job_id, draft_json, guard_report_json, verdict],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                retry_policy=RETRY,
            )
            if not draft_json:
                # needs_review уже записан в finalize; ждать человека не нужно.
                return final_json
            if status == "done":
                await self._publish(job_id, "awaiting_human", final_json)
                await workflow.wait_condition(lambda: self._human_decision is not None)
                decision = "approved" if self._human_decision == "approve" else "rejected"
                await self._publish(job_id, decision, final_json)
                return final_json
            return final_json  # needs_review / rejected — терминальные
        except ActivityError as exc:
            await self._publish(job_id, "failed", None, failure_reason(exc))
            raise

    @workflow.signal
    def approve(self) -> None:
        self._human_decision = "approve"

    @workflow.signal
    def request_changes(self) -> None:
        self._human_decision = "reject"

    @workflow.query
    def current_status(self) -> str:
        return self._status


@workflow.defn
class CardWorkflow:
    """extract → (generate → critique)* → сигнал оператора → финал.

    Идентификатор процесса равен идентификатору задачи в базе, поэтому
    состояние читается и через GET /jobs/{id}, и запросом к процессу.
    """

    def __init__(self) -> None:
        self._status = "starting"
        self._human_decision: str | None = None

    async def _publish(
        self,
        job_id: str,
        status: str,
        draft_json: str | None = None,
        error: str | None = None,
    ) -> None:
        self._status = status
        await workflow.execute_activity(
            set_job_status_activity,
            args=[job_id, status, draft_json, error],
            start_to_close_timeout=STATUS_TIMEOUT,
            retry_policy=RETRY,
        )

    async def _stages(self, job_id: str, supplier_text: str, max_attempts: int) -> str:
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
        return draft_json

    @workflow.run
    async def run(self, job_id: str, supplier_text: str, max_attempts: int = 3) -> str:
        try:
            draft_json = await self._stages(job_id, supplier_text, max_attempts)
        except ActivityError as exc:
            await self._publish(job_id, "failed", None, failure_reason(exc))
            raise

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
