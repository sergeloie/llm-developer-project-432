"""Тестовая база для pytest: отдельная БД в том же pgvector-контейнере.

Тесты используют базу `card_test`: она пересоздаётся на конфетуре сессии,
векторное расширение включается в ней отдельно.
"""
import os
import socket
import time

import psycopg
import pytest


def _db_url(base: str, dbname: str) -> str:
    rest = base.rsplit("/", 1)[0]
    return f"{rest}/{dbname}"


def _wait_port(host: str, port: int, timeout_s: float = 60.0) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            with socket.create_connection((host, port), timeout=2):
                return
        except OSError:
            time.sleep(0.5)
    raise TimeoutError(f"postgres не отвечает на {host}:{port}")


@pytest.fixture(scope="session")
def test_db_url():
    """URL тестовой БД; докер-инфра должна быть поднята (make infra)."""
    base = os.environ.get("DATABASE_URL", "postgresql://card:card@localhost:5432/card")
    host, port = "localhost", 5432
    _wait_port(host, port)
    test_name = "card_test"
    admin = psycopg.connect(_db_url(base, "card"), autocommit=True)
    try:
        admin.execute(f'DROP DATABASE IF EXISTS "{test_name}"')
        admin.execute(f'CREATE DATABASE "{test_name}"')
        conn = psycopg.connect(_db_url(base, test_name), autocommit=True)
        try:
            conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        finally:
            conn.close()
    finally:
        admin.close()
    return _db_url(base, test_name)
