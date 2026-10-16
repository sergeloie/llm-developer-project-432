"""Repository-слой: таблица jobs в Postgres. Единственный владелец SQL."""
import json
import uuid
from datetime import UTC, datetime

import psycopg

from app.repositories import chunks as chunks_repo
from app.schemas.cards import CardDraft


def _now() -> str:
    return datetime.now(UTC).isoformat()


def create_job(
    conn: psycopg.Connection, payload: dict, idempotency_key: str | None
) -> tuple[str, bool]:
    """INSERT pending. Возвращает (id, created?).

    created=False — строка с таким idempotency_key уже была, вернулся её id.
    Роутер по флагу решает, запускать ли процесс: у процесса тот же id,
    повторный старт упал бы с WorkflowAlreadyStartedError.
    """
    with conn.cursor() as cur:
        if idempotency_key:
            cur.execute(
                "SELECT id FROM jobs WHERE idempotency_key = %s", (idempotency_key,)
            )
            row = cur.fetchone()
            if row:
                return row[0], False
        job_id = uuid.uuid4().hex[:12]
        cur.execute(
            "INSERT INTO jobs (id, idempotency_key, payload) VALUES (%s, %s, %s)",
            (job_id, idempotency_key, json.dumps(payload, ensure_ascii=False)),
        )
        return job_id, True


def set_status(
    conn: psycopg.Connection, job_id: str, status: str, *, result: CardDraft | None = None,
    error: str | None = None, bump_attempts: bool = False,
    guard_report: dict | None = None,
) -> None:
    sets = ["status = %s", "updated_at = %s"]
    args: list = [status, _now()]
    if result is not None:
        sets.append("result = %s")
        args.append(result.model_dump_json())
    if error is not None:
        sets.append("error = %s")
        args.append(error)
    if guard_report is not None:
        sets.append("guard_report = %s")
        args.append(json.dumps(guard_report, ensure_ascii=False))
    if bump_attempts:
        sets.append("attempts = attempts + 1")
    args.append(job_id)
    with conn.cursor() as cur:
        cur.execute(f"UPDATE jobs SET {', '.join(sets)} WHERE id = %s", args)


def get_job(conn: psycopg.Connection, job_id: str) -> dict | None:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, status, attempts, result, error, guard_report "
            "FROM jobs WHERE id = %s",
            (job_id,),
        )
        row = cur.fetchone()
    if not row:
        return None
    job = {
        "id": row[0],
        "status": row[1],
        "attempts": row[2],
        "error": row[4],
        "guard_report": row[5],
    }
    if row[3]:
        # psycopg3 возвращает JSONB уже как dict, а не строку
        try:
            draft = (
                CardDraft.model_validate(row[3])
                if isinstance(row[3], dict)
                else CardDraft.model_validate_json(row[3])
            )
        except Exception:
            job["result"] = None
        else:
            job["result"] = draft.model_dump()
            job["source_coverage"] = source_coverage(conn, draft)
    return job


def source_coverage(conn: psycopg.Connection, draft: CardDraft) -> float:
    """Покрытие источников карточки — рядом с ней, а не внутри её контракта.

    Карточка в jobs.result остаётся ровно тем, что сгенерировала модель; доля
    подтверждённых характеристик — вывод системы, и клиент должен видеть её
    рядом с карточкой, а не внутри неё.
    """
    ids = sorted({ref.chunk_id for ref in draft.sources})
    texts = chunks_repo.get_chunks_by_ids(conn, ids) if ids else {}
    return round(draft.derived_source_coverage(texts), 3)
