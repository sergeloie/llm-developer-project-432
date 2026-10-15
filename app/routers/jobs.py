"""Фоновый вариант: задача в базе → устойчивый процесс с ручной проверкой."""
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import JSONResponse

from app.core import db
from app.core.config import settings
from app.repositories import jobs as jobs_repo
from app.schemas.jobs import CardRequest
from app.temporal.client import temporal_client
from app.temporal.workflows import CardWorkflow

router = APIRouter(tags=["jobs"])


@router.post("/jobs", status_code=202)
async def create_job(
    body: CardRequest,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
):
    with db.connection() as conn:
        job_id = jobs_repo.create_job(
            conn, {"supplier_text": body.supplier_text}, idempotency_key
        )
    client = await temporal_client()
    await client.start_workflow(
        CardWorkflow.run,
        args=[job_id, body.supplier_text, 3],
        id=job_id,  # по нему же идут сигналы: POST /workflows/{id}/approve
        task_queue=settings.temporal_task_queue,
    )
    return JSONResponse({"id": job_id, "status": "pending"}, status_code=202)


@router.get("/jobs/{job_id}")
async def get_job(job_id: str):
    with db.connection() as conn:
        job = jobs_repo.get_job(conn, job_id)
    if not job:
        raise HTTPException(404, "job not found")
    return job
