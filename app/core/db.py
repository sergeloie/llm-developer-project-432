"""Доступ к базе: пул уровня приложения, соединение уровня операции.

Пул создаётся один раз на процесс и живёт столько же, сколько процесс. Операция
берёт из него соединение и возвращает после работы — соединение на каждый вызов
кончается тем, что база упирается в лимит подключений на десятке параллельных
операций. Схемой управляют миграции (app/core/migrate.py).

Пул создаётся при первом обращении, а не на импорте: воркер процессов, миграции и
прогон метрик — это разные процессы, и каждому нужен свой пул, но ни одному не нужен
пул чужого модуля, случайно поднятый импортом.
"""
from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from psycopg_pool import ConnectionPool

from app.core.config import settings

_pool: ConnectionPool | None = None


def pool() -> ConnectionPool:
    """Пул соединений процесса."""
    global _pool
    if _pool is None:
        _pool = ConnectionPool(
            settings.database_url,
            min_size=settings.db_pool_min,
            max_size=settings.db_pool_max,
            kwargs={"autocommit": True},
            open=True,
        )
    return _pool


@contextmanager
def connection() -> Iterator[psycopg.Connection]:
    """Соединение на одну операцию: взято из пула, возвращено в пул."""
    with pool().connection() as conn:
        yield conn


def close_pool() -> None:
    """Освобождение пула на остановке процесса."""
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None
