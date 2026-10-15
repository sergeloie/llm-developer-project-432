"""Репозиторий задач на живой базе (тестовая БД из conftest)."""
import psycopg

from app.core.config import settings as app_settings
from app.repositories import jobs as repo
from migrations import runner


def _conn(test_db_url: str) -> psycopg.Connection:
    return psycopg.connect(test_db_url, autocommit=True)


def test_create_get_update_idempotency(test_db_url):
    # применяем миграции к тестовой базе (настройки переключаем на неё)
    app_settings.database_url = test_db_url
    applied = runner.run()
    assert applied, "миграции должны были примениться"
    conn = _conn(test_db_url)
    try:
        job_id, created = repo.create_job(conn, {"supplier_text": "текст"}, "key-1")
        assert created is True
        # тот же ключ → та же задача, новой нет
        job_id2, created2 = repo.create_job(conn, {"supplier_text": "текст"}, "key-1")
        assert job_id2 == job_id and created2 is False
        # без ключа — новая
        other, _ = repo.create_job(conn, {"supplier_text": "текст"}, None)
        assert other != job_id

        job = repo.get_job(conn, job_id)
        assert job["status"] == "pending"

        repo.update_status(conn, job_id, "running")
        repo.update_status(conn, job_id, "running")
        job = repo.get_job(conn, job_id)
        assert job["attempts"] == 2

        result = {"title": "x", "confidence": 0.9}
        repo.set_result(conn, job_id, result, "done")
        job = repo.get_job(conn, job_id)
        assert job["status"] == "done"
        assert job["result"]["title"] == "x"
    finally:
        conn.execute("DELETE FROM jobs")
        conn.close()
