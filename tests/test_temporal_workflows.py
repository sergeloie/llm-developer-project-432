"""Регрессия: упавшая activity обязана оставить задачу в терминальном failed.

Раньше CardWorkflow и ParseDocumentWorkflow просто пробрасывали исключение из
execute_activity наружу, не записав статус: задача навсегда зависала в
последнем промежуточном статусе (extracting/parsing), и клиент через
GET /jobs/{id} не видел ни причины, ни финала.
"""
import asyncio

import pytest
import temporalio.workflow as workflow_module
from temporalio.exceptions import ActivityError, ApplicationError

from app.temporal import workflows as wf

APPROVE = '{"verdict": "approve", "issues": []}'


def activity_error(name: str, message: str = "connection refused") -> ActivityError:
    """Так выглядит падение activity для процесса: ActivityError с причиной."""
    error = ActivityError(
        f"activity {name} failed",
        scheduled_event_id=1,
        started_event_id=2,
        identity="tester",
        activity_type=name,
        activity_id=f"{name}-1",
        retry_state=None,
    )
    error.__cause__ = ApplicationError(message, type="APIConnectionError")
    return error


class _Recorder:
    """Подмена execute_activity: тест задаёт, какая стадия падает."""

    def __init__(self, failing: str, results: dict[str, object] | None = None):
        self.failing = failing
        self.results = results or {}
        self.published: list[tuple[str, list]] = []

    def activity_name(self, fn) -> str:
        definition = getattr(fn, "__temporal_activity_definition", None)
        return getattr(definition, "name", None) or getattr(fn, "__name__", str(fn))

    async def __call__(self, fn, args=None, **kwargs):
        name = self.activity_name(fn)
        args = list(args) if args is not None else []
        if name in ("set_job_status_activity", "set_document_status_activity"):
            self.published.append((name, args))
            return None
        if name == self.failing:
            raise activity_error(name)
        if name in self.results:
            return self.results[name]
        return "{}"

    def statuses(self) -> list[str]:
        return [args[1] for name, args in self.published if name == "set_job_status_activity"]

    def status(self) -> str:
        return self.statuses()[-1]

    def reason(self) -> str:
        return self.published[-1][1][-1]


@pytest.fixture
def stub_activity(monkeypatch):
    """Отключает execute_activity/wait_condition: ни Temporal, ни сети не нужно."""

    def install(recorder: _Recorder, instance=None):
        monkeypatch.setattr(workflow_module, "execute_activity", recorder)

        async def fake_wait_condition(predicate):
            if instance is not None:
                instance.approve()
            await asyncio.sleep(0)

        monkeypatch.setattr(workflow_module, "wait_condition", fake_wait_condition)
        return recorder

    return install


def test_card_workflow_publishes_failed_when_extract_exhausts_retries(stub_activity):
    recorder = stub_activity(_Recorder(failing="extract_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.CardWorkflow().run("job-1", "текст поставщика"))

    assert recorder.statuses() == ["extracting", "failed"]
    assert recorder.status() == "failed"


def test_card_workflow_publishes_failed_when_generate_exhausts_retries(stub_activity):
    recorder = stub_activity(_Recorder(failing="generate_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.CardWorkflow().run("job-2", "текст поставщика"))

    assert recorder.statuses() == ["extracting", "generating", "failed"]


def test_card_workflow_publishes_failed_when_critique_exhausts_retries(stub_activity):
    recorder = stub_activity(
        _Recorder(failing="critique_activity", results={"critique_activity": APPROVE})
    )
    with pytest.raises(ActivityError):
        asyncio.run(wf.CardWorkflow().run("job-3", "текст поставщика"))

    assert recorder.statuses() == ["extracting", "generating", "critiquing", "failed"]


def test_card_workflow_failed_reason_comes_from_activity_error(stub_activity):
    recorder = stub_activity(_Recorder(failing="extract_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.CardWorkflow().run("job-4", "текст поставщика"))

    reason = recorder.reason()
    assert "extract_activity" in reason
    assert "connection refused" in reason
    assert len(reason) <= wf.MAX_ERROR_LEN


def test_card_workflow_success_path_does_not_publish_failed(stub_activity):
    recorder = _Recorder(failing="none", results={"critique_activity": APPROVE})
    instance = wf.CardWorkflow()
    stub_activity(recorder, instance=instance)

    draft = asyncio.run(instance.run("job-5", "текст поставщика"))

    assert draft == "{}"
    assert recorder.statuses() == [
        "extracting",
        "generating",
        "critiquing",
        "awaiting_human",
        "approved",
    ]


def test_rag_workflow_publishes_failed(stub_activity):
    recorder = stub_activity(_Recorder(failing="rag_card_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.RagCardWorkflow().run("job-6", ["doc-1"], "блендер"))

    assert recorder.status() == "failed"
    assert "rag_card_activity" in recorder.reason()


def test_parse_workflow_marks_document_failed(stub_activity):
    recorder = stub_activity(_Recorder(failing="parse_document_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.ParseDocumentWorkflow().run("doc-9", "C:/tmp/doc-9", "docx"))

    name, args = recorder.published[-1]
    assert name == "set_document_status_activity"
    assert args[1] == "failed"
    assert "parse_document_activity" in args[2]


def test_retry_policy_keeps_three_attempts():
    assert wf.RETRY.maximum_attempts == 3


def test_failure_reason_is_truncated():
    error = activity_error("extract_activity", "x" * 5000)
    assert len(wf.failure_reason(error)) == wf.MAX_ERROR_LEN