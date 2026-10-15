"""Фоновый вариант: задача в базе + durable-процесс в Temporal (шаги 3, 7)."""
from fastapi import APIRouter, Header, HTTPException

from app import schemas
from app.core import db
from app.repositories import jobs as jobs_repo

router = APIRouter(tags=["jobs"])


@router.post("/jobs", status_code=202)
async def create_job(
    body: schemas.CardIn,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
) -> dict:
    payload = {"supplier_text": body.supplier_text, "hint": body.hint}
    with db.connection() as conn:
        job_id, created = jobs_repo.create_job(conn, payload, idempotency_key)
        if not created:
            existing = jobs_repo.get_job(conn, job_id)
            return {"id": job_id, "status": existing["status"], "duplicate": True}
    from app.temporal.kickoff import start_generation

    await start_generation(job_id, payload)
    return {"id": job_id, "status": "pending"}


@router.get("/jobs/{job_id}")
async def get_job(job_id: str) -> dict:
    with db.connection() as conn:
        job = jobs_repo.get_job(conn, job_id)
    if not job:
        raise HTTPException(404, "задача не найдена")
    return job
