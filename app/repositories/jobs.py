"""Repository-слой: таблица jobs в Postgres. Единственный владелец SQL."""
import json
import uuid
from datetime import UTC, datetime

import psycopg

from app.schemas.cards import CardDraft


def _now() -> str:
    return datetime.now(UTC).isoformat()


def create_job(conn: psycopg.Connection, payload: dict, idempotency_key: str | None) -> str:
    """INSERT pending. При повторном idempotency_key возвращает существующий id."""
    with conn.cursor() as cur:
        if idempotency_key:
            cur.execute(
                "SELECT id FROM jobs WHERE idempotency_key = %s", (idempotency_key,)
            )
            row = cur.fetchone()
            if row:
                return row[0]
        job_id = uuid.uuid4().hex[:12]
        cur.execute(
            "INSERT INTO jobs (id, idempotency_key, payload) VALUES (%s, %s, %s)",
            (job_id, idempotency_key, json.dumps(payload, ensure_ascii=False)),
        )
        return job_id


def set_status(
    conn: psycopg.Connection, job_id: str, status: str, *, result: CardDraft | None = None,
    error: str | None = None, bump_attempts: bool = False,
) -> None:
    sets = ["status = %s", "updated_at = %s"]
    args: list = [status, _now()]
    if result is not None:
        sets.append("result = %s")
        args.append(result.model_dump_json())
    if error is not None:
        sets.append("error = %s")
        args.append(error)
    if bump_attempts:
        sets.append("attempts = attempts + 1")
    args.append(job_id)
    with conn.cursor() as cur:
        cur.execute(f"UPDATE jobs SET {', '.join(sets)} WHERE id = %s", args)


def get_job(conn: psycopg.Connection, job_id: str) -> dict | None:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, status, attempts, result, error FROM jobs WHERE id = %s",
            (job_id,),
        )
        row = cur.fetchone()
    if not row:
        return None
    job = {"id": row[0], "status": row[1], "attempts": row[2], "error": row[4]}
    if row[3]:
        # psycopg3 возвращает JSONB уже как dict, а не строку
        try:
            draft = (
                CardDraft.model_validate(row[3])
                if isinstance(row[3], dict)
                else CardDraft.model_validate_json(row[3])
            )
            job["result"] = draft.model_dump()
        except Exception:
            job["result"] = None
    return job
