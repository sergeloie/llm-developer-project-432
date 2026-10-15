"""Генерация карточки по документам (шаг 7)."""
from fastapi import APIRouter, HTTPException

from app import schemas
from app.core import db
from app.repositories import jobs as jobs_repo
from app.services import generation as gen_service

router = APIRouter(tags=["generators"])


@router.post("", status_code=202)
async def create(body: schemas.GenerateIn) -> dict:
    payload = {"document_ids": body.document_ids, "hint": body.hint}
    with db.connection() as conn:
        job_id, created = jobs_repo.create_job(conn, payload, kind="generate_card")
        if not created:
            existing = jobs_repo.get_job(conn, job_id)
            return {"id": job_id, "status": existing["status"], "duplicate": True}
    result = await gen_service.generate_for_documents(
        document_ids=body.document_ids, hint=body.hint, job_id=job_id
    )
    status = result.get("status", "awaiting_human")
    return {"id": job_id, "status": status}


@router.get("/{job_id}")
async def get(job_id: str) -> dict:
    with db.connection() as conn:
        job = jobs_repo.get_job(conn, job_id)
    if not job:
        raise HTTPException(404, "задача не найдена")
    return job