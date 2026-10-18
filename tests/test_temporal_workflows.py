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
    recorder = stub_activity(_Recorder(failing="rag_retrieve_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.RagCardWorkflow().run("job-6", ["doc-1"], "блендер"))

    assert recorder.status() == "failed"
    assert "rag_retrieve_activity" in recorder.reason()


def _rag_results(generate_json: str, finalize: tuple[str, str]) -> dict:
    """Результаты activity для RAG-карточки: retrieval всегда отдаёт контекст.

    rag_retrieve_activity возвращает dict (в рантайме Temporal сериализует его
    туда-обратно), поэтому в recorder'е — настоящий dict, а не JSON-строка.
    """
    guard = {
        "masked_pii": 0,
        "suspicious_chunks": [],
        "blocked_reasons": [],
        "retrieval_scores": {},
    }
    return {
        "rag_retrieve_activity": {
            "context": "ctx",
            "guard_report": guard,
            "suspicious_chunks": [],
        },
        "rag_generate_activity": generate_json,
        "rag_critique_activity": APPROVE,
        "rag_finalize_activity": finalize,
    }


def test_rag_workflow_success_path_awaits_human(stub_activity):
    instance = wf.RagCardWorkflow()
    results = _rag_results('{"title": "Блендер"}', ('{"title": "Блендер"}', "done"))
    recorder = stub_activity(_Recorder(failing="none", results=results), instance=instance)

    draft = asyncio.run(instance.run("job-7", ["doc-2"], "блендер"))

    assert draft == '{"title": "Блендер"}'
    assert recorder.statuses() == [
        "retrieving",
        "generating",
        "critiquing",
        "awaiting_human",
        "approved",
    ]


def test_rag_workflow_fabricated_sources_skips_human(stub_activity):
    """Выдуманные источники → needs_review: карточку не ждём, human не нужен."""
    results = _rag_results("", ("", "needs_review"))
    recorder = stub_activity(_Recorder(failing="none", results=results))

    draft = asyncio.run(wf.RagCardWorkflow().run("job-8", ["doc-3"], "блендер"))

    assert draft == ""
    # retrieve → generate, затем короткий выход: нет критики и нет ожидания человека.
    assert recorder.statuses() == ["retrieving", "generating"]
    assert "awaiting_human" not in recorder.statuses()


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


# --- временный файл разбора живёт до терминального состояния ---


class _CallLog(_Recorder):
    """_Recorder, который запоминает ВСЕ вызовы activity, а не только статусы."""

    def __init__(self, failing: str, results: dict[str, object] | None = None):
        super().__init__(failing, results)
        self.calls: list[tuple[str, list]] = []

    async def __call__(self, fn, args=None, **kwargs):
        self.calls.append((self.activity_name(fn), list(args) if args is not None else []))
        return await super().__call__(fn, args, **kwargs)

    def order(self) -> list[str]:
        return [name for name, _args in self.calls]


def test_parse_file_deleted_only_after_terminal_failure(stub_activity):
    recorder = stub_activity(_CallLog(failing="parse_document_activity"))
    with pytest.raises(ActivityError):
        asyncio.run(wf.ParseDocumentWorkflow().run("doc-10", "C:/tmp/doc-10", "docx"))

    order = recorder.order()
    assert order.index("delete_document_file_activity") > order.index("parse_document_activity")
    assert order[-1] == "delete_document_file_activity"


def test_parse_file_deleted_after_success(stub_activity):
    recorder = stub_activity(
        _CallLog(failing="none", results={"parse_document_activity": 3})
    )
    assert asyncio.run(
        wf.ParseDocumentWorkflow().run("doc-11", "C:/tmp/doc-11", "pdf")
    ) == 3
    assert recorder.order() == ["parse_document_activity", "delete_document_file_activity"]


def test_parse_activity_keeps_file_so_retry_can_read_it(monkeypatch, tmp_path):
    """Падение попытки не удаляет файл: вторая попытка читает те же байты."""
    from contextlib import nullcontext

    from app.temporal import activities as acts

    path = tmp_path / "doc-12.bin"
    path.write_bytes(b"%PDF-1.4 payload")

    def boom(conn, doc_id, content, kind):
        raise RuntimeError("parse boom")

    monkeypatch.setattr(acts.docs_service, "ingest", boom)
    monkeypatch.setattr(acts.db, "connection", lambda: nullcontext(object()))

    with pytest.raises(RuntimeError):
        acts.parse_document_activity("doc-12", str(path), "pdf")
    assert path.exists()


def test_delete_document_file_activity_removes_the_file(tmp_path):
    from app.temporal import activities as acts

    path = tmp_path / "doc-13.bin"
    path.write_bytes(b"x")
    acts.delete_document_file_activity(str(path))
    assert not path.exists()
    acts.delete_document_file_activity(str(path))  # повторный вызов не падает


def test_worker_registers_every_declared_activity():
    """Activity, забытый при регистрации, падает в рантайме как NotFoundError.

    Workflow может быть готов, а шаг — не зарегистрирован: проверка ставится на
    импорт, а не на первый запуск в проде.
    """
    from app import temporal_worker
    from app.temporal import activities as acts

    def declared(module) -> set[str]:
        return {
            name
            for name, obj in vars(module).items()
            if getattr(obj, "__temporal_activity_definition", None) is not None
        }

    assert declared(acts) == declared(temporal_worker)