"""Раннер SQL-миграций: применяет db/migrations/*.sql по порядку.

Отслеживает применённые версии в таблице schema_migrations.
Запуск: python -m app.core.migrate (или make migrate).
"""
from pathlib import Path

import psycopg

from app.core import db

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent.parent / "db" / "migrations"

_BOOTSTRAP = """
CREATE TABLE IF NOT EXISTS schema_migrations (
    version     TEXT PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
)
"""


def applied_versions(conn: psycopg.Connection) -> set[str]:
    conn.execute(_BOOTSTRAP)
    with conn.cursor() as cur:
        cur.execute("SELECT version FROM schema_migrations")
        return {row[0] for row in cur.fetchall()}


def run_migrations() -> list[str]:
    files = sorted(MIGRATIONS_DIR.glob("*.sql"))
    with db.connection() as conn:
        done = applied_versions(conn)
        applied_now: list[str] = []
        for path in files:
            version = path.stem
            if version in done:
                continue
            sql = path.read_text(encoding="utf-8")
            with conn.transaction():
                conn.execute(sql)
                conn.execute(
                    "INSERT INTO schema_migrations (version) VALUES (%s)", (version,)
                )
            applied_now.append(version)
        return applied_now


if __name__ == "__main__":
    try:
        applied = run_migrations()
    finally:
        db.close_pool()  # одноразовый скрипт: пул закрывается вместе с работой
    if applied:
        print(f"Применены миграции: {', '.join(applied)}")
    else:
        print("Новых миграций нет")
