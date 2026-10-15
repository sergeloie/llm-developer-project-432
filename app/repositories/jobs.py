"""SQL живёт только здесь. Таблица jobs: статусная машина, идемпотентность."""
import json
import uuid
from typing import Any

import psycopg

# Состояния фоновых задач (шаги 3-4).
# pending → running → done | needs_human (низкая уверенность)
#                    ↘ failed_rejected (петля исчерпана) | failed_error
STATUSES = {
    "pending",
    "running",
    "done",
    "needs_human",
    "failed_rejected",
    "failed_error",
    "parsing",
    "indexing",
    "searching",
    "generating",
    "verifying",
    "awaiting_human",
    "approved",
    "rejected",
}


def create_job(
    conn: psycopg.Connection,
    payload: dict[str, Any],
    idempotency_key: str | None = None,
    kind: str = "generate_text",
) -> tuple[str, bool]:
    """Создать задачу. Вернёт (id, freshly_created).

    Повторный запрос с тем же ключом идемпотентности возвращает первую задачу,
    новую не создаёт (нет двойного клика — нет двойной генерации и счёта).
    """
    if idempotency_key:
        row = conn.execute(
            "SELECT id, status FROM jobs WHERE idempotency_key = %s", (idempotency_key,)
        ).fetchone()
        if row:
            return row[0], False
    job_id = f"job-{uuid.uuid4().hex[:12]}"
    conn.execute(
        "INSERT INTO jobs (id, idempotency_key, kind, status, payload)"
        " VALUES (%s, %s, %s, 'pending', %s)",
        (job_id, idempotency_key, kind, json.dumps(payload, ensure_ascii=False)),
    )
    return job_id, True


def get_job(conn: psycopg.Connection, job_id: str) -> dict[str, Any] | None:
    row = conn.execute(
        "SELECT id, status, attempts, result, error, created_at, updated_at"
        " FROM jobs WHERE id = %s",
        (job_id,),
    ).fetchone()
    if not row:
        return None
    return {
        "id": row[0],
        "status": row[1],
        "attempts": row[2],
        "result": row[3],
        "error": row[4],
        "created_at": row[5].isoformat() if row[5] else None,
        "updated_at": row[6].isoformat() if row[6] else None,
    }


def update_status(
    conn: psycopg.Connection,
    job_id: str,
    status: str,
    *,
    result: dict[str, Any] | None = None,
    error: str | None = None,
    attempts: int | None = None,
) -> None:
    if status not in STATUSES:
        raise ValueError(f"неизвестный статус: {status}")
    conn.execute(
        "UPDATE jobs SET status = %s, result = COALESCE(%s, result),"
        " error = %s, attempts = COALESCE(%s, attempts + 1),"
        " updated_at = now() WHERE id = %s",
        (status, json.dumps(result, ensure_ascii=False) if result else None, error, attempts, job_id),
    )


def set_result(conn: psycopg.Connection, job_id: str, result: dict[str, Any], status: str) -> None:
    """Финальная запись результата (атtempt не наращивается — это не ретрай)."""
    if status not in STATUSES:
        raise ValueError(f"неизвестный статус: {status}")
    conn.execute(
        "UPDATE jobs SET status = %s, result = %s, error = NULL, updated_at = now()"
        " WHERE id = %s",
        (status, json.dumps(result, ensure_ascii=False), job_id),
    )
