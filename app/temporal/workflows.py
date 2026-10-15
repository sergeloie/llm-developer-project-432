"""GenerationWorkflow: extract → (generate → critique)* → статус в базу ↓ пауза.

Правила детерминизма workflow-кода: никаких datetime.now/random/HTTP/БД —
все side-effects в activities. Статус предметной области пишется в базу
ОТДЕЛЬНЫМ шагом (activity), а не изнутри пайплайна.

Опасные свойства песочницы (предупреждены в задании):
- обычные импорты внутри workflow-кода ограничены — тяжёлое тянем через
  workflow.unsafe.imports_passed_through();
- шаги без yield (БД, диск, LLM) требуют пула потоков у воркера.
"""
import json
from datetime import timedelta

from temporalio import workflow

with workflow.unsafe.imports_passed_through():
    from app.temporal.activities import (
        critique_activity,
        extract_activity,
        generate_activity,
        set_job_status_activity,
    )

ACTIVITY_TIMEOUT = timedelta(seconds=900)
HEARTBEAT_TIMEOUT = timedelta(minutes=3)  # после смерти воркера шаг переоткрывается через 3 мин, а не по start_to_close
STATUS_TIMEOUT = timedelta(seconds=60)
MAX_ROUNDS = 3  # константа, не Settings: конфиг меняется между реплеями


@workflow.defn
class GenerationWorkflow:
    def __init__(self) -> None:
        self._phase = "created"
        self._decision: str | None = None

    @workflow.run
    async def run(
        self, job_id: str, payload: dict, confidence_threshold: float = 0.7
    ) -> dict:
        rounds = 0
        approved = False
        draft_json: str | None = None
        feedback: list[str] | None = None

        self._phase = "extract"
        facts_json = await workflow.execute_activity(
            extract_activity,
            payload.get("supplier_text", ""),
            start_to_close_timeout=ACTIVITY_TIMEOUT,
            heartbeat_timeout=HEARTBEAT_TIMEOUT,
        )

        while rounds < MAX_ROUNDS:
            rounds += 1
            self._phase = f"generate r{rounds}"
            draft_json = await workflow.execute_activity(
                generate_activity,
                args=[facts_json, feedback],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                heartbeat_timeout=HEARTBEAT_TIMEOUT,
            )
            self._phase = f"critique r{rounds}"
            report_json = await workflow.execute_activity(
                critique_activity,
                args=[facts_json, draft_json],
                start_to_close_timeout=ACTIVITY_TIMEOUT,
                heartbeat_timeout=HEARTBEAT_TIMEOUT,
            )
            report = json.loads(report_json)
            if report.get("verdict") == "approve":
                approved = True
                break
            feedback = report.get("issues", [])

        draft = json.loads(draft_json or "{}")
        if not approved:
            status = "failed_rejected"
        elif float(draft.get("confidence", 0.0)) < confidence_threshold:
            status = "needs_human"
        else:
            status = "done"

        self._phase = "writing_status"
        await workflow.execute_activity(
            set_job_status_activity,
            args=[job_id, status, draft_json, rounds],
            start_to_close_timeout=STATUS_TIMEOUT,
        )

        # Долгая пауза: ожидание сигнала человека. Не занимает потоков.
        self._phase = "awaiting_human_decision"
        await workflow.wait_condition(lambda: self._decision is not None)

        final_status = "approved" if self._decision == "approve" else "rejected"
        self._phase = "writing_final_status"
        await workflow.execute_activity(
            set_job_status_activity,
            args=[job_id, "done" if final_status == "approve" else "failed_rejected", None, None],
            start_to_close_timeout=STATUS_TIMEOUT,
        )
        self._phase = "completed"
        return {"job_id": job_id, "final_status": final_status, "draft": draft_json}

    @workflow.signal
    async def approve(self) -> None:
        self._decision = "approve"

    @workflow.signal
    async def reject(self) -> None:
        self._decision = "reject"

    @workflow.query
    def phase(self) -> str:
        """Текущая фаза процесса; история шагов — в веб-интерфейсе движка."""
        return self._phase
