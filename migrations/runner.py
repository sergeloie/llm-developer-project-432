"""Раннер SQL-миграций: применяет файлы по порядку, версии хранит в schema_migrations.

Идемпотентен: повторный запуск ничего не применяет заново.

Запуск:  python -m migrations.runner
"""
from pathlib import Path

import psycopg

from app.core.config import settings

MIGRATIONS_DIR = Path(__file__).parent


def _bootstrap(conn: psycopg.Connection) -> None:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version TEXT PRIMARY KEY,
            applied TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )


def applied_versions(conn: psycopg.Connection) -> set[str]:
    rows = conn.execute("SELECT version FROM schema_migrations").fetchall()
    return {r[0] for r in rows}


def pending_files(applied: set[str]) -> list[Path]:
    files = sorted(MIGRATIONS_DIR.glob("[0-9]*_*.sql"))
    return [f for f in files if f.name not in applied]


def run() -> list[str]:
    """Применить все незанесённые миграции; вернуть список имён применённых."""
    with psycopg.connect(settings.database_url, autocommit=False) as conn:
        _bootstrap(conn)
        pending = pending_files(applied_versions(conn))
        applied_names: list[str] = []
        for path in pending:
            sql = path.read_text(encoding="utf-8")
            # каждая миграция — одна транзакция: либо целиком, либо никак
            conn.execute(sql)
            conn.execute(
                "INSERT INTO schema_migrations (version) VALUES (%s)", (path.name,)
            )
            conn.commit()
            applied_names.append(path.name)
            print(f"applied: {path.name}")
        if not pending:
            print("no new migrations")
        return applied_names


if __name__ == "__main__":
    run()
