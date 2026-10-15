"""Идемпотентность POST /jobs и POST /generate-card: без БД и без Temporal.

Подменены ровно две зависимости — соединение и клиент процесса, — поэтому
проверяется логика роутера и репозитория целиком: сколько строк в jobs
появилось, каким кодом пришёл ответ и что повторный запрос не перезапускает
процесс.
"""
from contextlib import contextmanager

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from temporalio.exceptions import WorkflowAlreadyStartedError

from app.routers import generate as generate_router
from app.routers import jobs as jobs_router
from app.services import jobs as jobs_service


class FakeCursor:
    """Курсор поверх словаря: понимает ровно те запросы, которые пишет jobs_repo."""

    def __init__(self, store: dict):
        self.store = store
        self.pending: tuple | None = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql: str, args: tuple = ()) -> None:
        if sql.startswith("SELECT id FROM jobs"):
            job_id = self.store["by_key"].get(args[0])
            self.pending = None if job_id is None else (job_id,)
        elif sql.startswith("INSERT INTO jobs"):
            job_id, key, _payload = args
            self.store["rows"][job_id] = {"idempotency_key": key, "status": "pending"}
            self.store["by_key"][key] = job_id
            self.store["inserts"] += 1
        elif sql.startswith("SELECT id, status, attempts, result, error"):
            job_id = args[0]
            row = self.store["rows"].get(job_id)
            self.pending = None if row is None else (job_id, row["status"], 0, None, None)
        else:
            raise AssertionError(f"неожиданный SQL: {sql}")

    def fetchone(self):
        row, self.pending = self.pending, None
        return row


class FakeConnection:
    def __init__(self, store: dict):
        self.store = store

    def cursor(self):
        return FakeCursor(self.store)


class FakeTemporalClient:
    """Клиент процесса: считает старты и умеет сыграть гонку."""

    def __init__(self, starts: list, already_started: bool):
        self.starts = starts
        self.already_started = already_started

    async def start_workflow(self, workflow_run, *, args, id, task_queue):
        if self.already_started:
            raise WorkflowAlreadyStartedError(id, "Workflow")
        self.starts.append({"workflow": workflow_run, "args": args, "id": id})


@pytest.fixture
def api(monkeypatch):
    store = {"rows": {}, "by_key": {}, "inserts": 0}
    starts: list = []
    state = {"already_started": False}

    @contextmanager
    def fake_connection():
        yield FakeConnection(store)

    async def fake_temporal_client():
        return FakeTemporalClient(starts, state["already_started"])

    monkeypatch.setattr(jobs_service.db, "connection", fake_connection)
    monkeypatch.setattr(jobs_service, "temporal_client", fake_temporal_client)

    app = FastAPI()
    app.include_router(jobs_router.router)
    app.include_router(generate_router.router)
    yield {"client": TestClient(app), "store": store, "starts": starts, "state": state}


def test_generate_card_same_key_returns_same_job(api):
    key = "probe-idem-generate"
    body = {"document_ids": ["410369359818"], "product_hint": "блендер"}

    first = api["client"].post("/generate-card", json=body, headers={"Idempotency-Key": key})
    second = api["client"].post("/generate-card", json=body, headers={"Idempotency-Key": key})

    assert first.status_code == 202
    assert second.status_code == 202
    assert first.json()["id"] == second.json()["id"]
    assert second.json()["status"] == "pending"
    assert api["store"]["inserts"] == 1
    assert len(api["starts"]) == 1


def test_generate_card_without_key_creates_new_job(api):
    body = {"document_ids": []}
    first = api["client"].post("/generate-card", json=body)
    second = api["client"].post("/generate-card", json=body)

    assert first.status_code == second.status_code == 202
    assert first.json()["id"] != second.json()["id"]
    assert api["store"]["inserts"] == 2


def test_jobs_same_key_returns_same_job(api):
    key = "probe-idem-jobs"
    body = {"supplier_text": "Блендер МиксерПро 800, мощность 800 Вт"}

    first = api["client"].post("/jobs", json=body, headers={"Idempotency-Key": key})
    second = api["client"].post("/jobs", json=body, headers={"Idempotency-Key": key})

    assert first.status_code == 202
    assert second.status_code == 202
    assert first.json()["id"] == second.json()["id"]
    assert api["store"]["inserts"] == 1
    assert len(api["starts"]) == 1


def test_second_response_carries_current_status(api):
    key = "probe-idem-status"
    api["client"].post("/jobs", json={"supplier_text": "x"}, headers={"Idempotency-Key": key})
    job_id = next(iter(api["store"]["rows"]))
    api["store"]["rows"][job_id]["status"] = "awaiting_human"

    again = api["client"].post(
        "/jobs", json={"supplier_text": "x"}, headers={"Idempotency-Key": key}
    )

    assert again.status_code == 202
    assert again.json() == {"id": job_id, "status": "awaiting_human"}
    assert len(api["starts"]) == 1


def test_race_with_already_started_workflow_is_not_5xx(api):
    """Гонка: строка уже создана, процесс уже запущен другим запросом."""
    key = "probe-idem-race"
    body = {"supplier_text": "чайник"}
    api["state"]["already_started"] = True

    first = api["client"].post("/jobs", json=body, headers={"Idempotency-Key": key})
    second = api["client"].post("/jobs", json=body, headers={"Idempotency-Key": key})

    assert first.status_code == 202
    assert second.status_code == 202
    assert first.json()["id"] == second.json()["id"]
    assert api["store"]["inserts"] == 1
    assert api["starts"] == []


def test_distinct_keys_start_distinct_workflows(api):
    body = {"document_ids": []}
    api["client"].post("/generate-card", json=body, headers={"Idempotency-Key": "k1"})
    api["client"].post("/generate-card", json=body, headers={"Idempotency-Key": "k2"})

    started_ids = [start["id"] for start in api["starts"]]
    assert len(started_ids) == 2
    assert len(set(started_ids)) == 2
    assert all(isinstance(value, str) and len(value) == 12 for value in started_ids)
    assert set(started_ids) == set(api["store"]["rows"])