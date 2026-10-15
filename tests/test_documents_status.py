"""POST /documents отвечает реальным статусом документа, а не константой.

Дубликат по content_hash — это не «уже проиндексировано»: первый разбор мог
ещё идти, и клиенту нужен текущий статус из базы.
"""
import tempfile

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core import db
from app.routers import documents as documents_router

PDF_BYTES = b"%PDF-1.4 same bytes"


class FakeCursor:
    """Курсор поверх словаря: понимает запросы documents_repo."""

    def __init__(self, store: dict):
        self.store = store
        self.pending: tuple | None = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql: str, args: tuple = ()) -> None:
        if sql.startswith("SELECT id FROM documents"):
            doc_id = self.store["by_hash"].get(args[0])
            self.pending = None if doc_id is None else (doc_id,)
        elif sql.startswith("INSERT INTO documents"):
            doc_id = args[0]
            self.store["by_hash"][args[2]] = doc_id
            self.store["rows"][doc_id] = {"status": "new"}
            self.store["inserts"] += 1
            self.pending = None
        elif sql.startswith("SELECT id, filename, kind, status"):
            doc = self.store["rows"].get(args[0])
            self.pending = (
                None
                if doc is None
                else (args[0], "f.pdf", "pdf", doc["status"], 3, None, "now")
            )
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

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakePool:
    def __init__(self, store: dict):
        self.store = store

    def connection(self):
        return FakeConnection(self.store)


class FakeTemporalClient:
    def __init__(self, starts: list):
        self.starts = starts

    async def start_workflow(self, workflow_run, *, args, id, task_queue):
        self.starts.append({"id": id})


@pytest.fixture
def api(monkeypatch, tmp_path):
    store = {"rows": {}, "by_hash": {}, "inserts": 0}
    starts: list = []

    monkeypatch.setattr(db, "pool", lambda: FakePool(store))
    monkeypatch.setattr(tempfile, "tempdir", str(tmp_path))

    async def fake_temporal_client():
        return FakeTemporalClient(starts)

    monkeypatch.setattr(documents_router, "temporal_client", fake_temporal_client)

    app = FastAPI()
    app.include_router(documents_router.router)
    return {"client": TestClient(app), "store": store, "starts": starts}


def _upload(api) -> dict:
    response = api["client"].post(
        "/documents", files={"file": ("f.pdf", PDF_BYTES, "application/pdf")}
    )
    assert response.status_code == 202
    return response.json()


def test_first_upload_reports_new_status(api):
    answer = _upload(api)
    assert answer["status"] == "new"
    assert len(api["starts"]) == 1


def test_duplicate_reports_current_status_not_placeholder(api):
    first = _upload(api)
    api["store"]["rows"][first["id"]]["status"] = "parsing"

    second = _upload(api)

    assert second["id"] == first["id"]
    assert second["status"] == "parsing"
    assert api["store"]["inserts"] == 1
    assert len(api["starts"]) == 1


def test_duplicate_reports_indexed_when_parse_finished(api):
    first = _upload(api)
    api["store"]["rows"][first["id"]]["status"] = "indexed"

    assert _upload(api)["status"] == "indexed"