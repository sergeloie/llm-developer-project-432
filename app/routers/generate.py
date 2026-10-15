"""Генерация карточки: POST /generate-card {document_ids, product_hint?} → job."""
from fastapi import APIRouter, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.core import db
from app.core.config import settings
from app.repositories import jobs as jobs_repo
from app.temporal.client import temporal_client
from app.temporal.workflows import RagCardWorkflow

router = APIRouter(tags=["generate"])


class GenerateRequest(BaseModel):
    document_ids: list[str] = Field(default_factory=list)
    product_hint: str = Field(
        default="", description="Что за товар (например: «блендер МиксерПро 800»)"
    )


@router.post("/generate-card", status_code=202)
async def generate_card(
    body: GenerateRequest,
    idempotency_key: str | None = Header(default=None, alias="Idempotency-Key"),
):
    with db.connection() as conn:
        job_id = jobs_repo.create_job(
            conn,
            {"document_ids": body.document_ids, "product_hint": body.product_hint},
            idempotency_key,
        )
    client = await temporal_client()
    await client.start_workflow(
        RagCardWorkflow.run,
        args=[job_id, body.document_ids, body.product_hint],
        id=job_id,  # идентификатор процесса = идентификатор задачи в базе
        task_queue=settings.temporal_task_queue,
    )
    return JSONResponse({"id": job_id, "status": "pending"}, status_code=202)
