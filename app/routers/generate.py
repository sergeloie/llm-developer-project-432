"""Генерация карточки: POST /generate-card {document_ids, product_hint?} → job."""
from fastapi import APIRouter, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.services.jobs import submit_job
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
    answer = await submit_job(
        {"document_ids": body.document_ids, "product_hint": body.product_hint},
        idempotency_key,
        RagCardWorkflow.run,
        lambda job_id: [job_id, body.document_ids, body.product_hint],
    )
    return JSONResponse(answer, status_code=202)