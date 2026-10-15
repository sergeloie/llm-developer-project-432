"""Всё соединение с базой — через пул уровня приложения и связь на операцию.

Правило проекта:
- `pool()` — пул живёт столько же, сколько процесс; создаётся на старте (init_pool).
- `connection()` — соединение берётся НА ОПЕРАЦИЮ, контекстным менеджером:
  взял → поработал → вернул в пул. Долгие шаги процесса соединение не держат.

SQL живёт только в слое репозиториев.
"""
import psycopg
from psycopg_pool import ConnectionPool

from app.core.config import settings

_pool: ConnectionPool | None = None


def init_pool() -> ConnectionPool:
    """Создать и открыть пул. Идемпотентно; вызывается на старте процесса."""
    global _pool
    if _pool is None or _pool.closed:
        _pool = ConnectionPool(
            settings.database_url,
            min_size=settings.db_pool_min,
            max_size=settings.db_pool_max,
            open=True,
            name="app-pool",
        )
    return _pool


def pool() -> ConnectionPool | None:
    return _pool


def close_pool() -> None:
    global _pool
    if _pool is not None and not _pool.closed:
        _pool.close()
    _pool = None


class connection:
    """Контекстный менеджер: соединение из пула на время операции.

    with connection() as conn:
        repo.get_job(conn, job_id)
    """

    def __enter__(self) -> psycopg.Connection:
        self._cm = pool().connection()
        conn = self._cm.__enter__()
        return conn

    def __exit__(self, exc_type, exc, tb) -> None:
        self._cm.__exit__(exc_type, exc, tb)


def ping() -> dict:
    """Проверка готовности: БД отвечает, расширение vector активно."""
    with connection() as conn:
        conn.execute("SELECT 1")
        row = conn.execute(
            "SELECT extname FROM pg_extension WHERE extname = 'vector'"
        ).fetchone()
    return {"database": "ok", "vector": "active" if row else "MISSING"}
