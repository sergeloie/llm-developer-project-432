"""Синхронный вариант: пайплайн целиком внутри запроса (шаг 3/4)."""
from fastapi import APIRouter, HTTPException

from app import schemas
from app.services import pipeline

router = APIRouter(tags=["cards"])


@router.post("/cards", response_model=schemas.CardDraft)
async def create_card(body: schemas.CardIn) -> schemas.CardDraft:
    draft, _attempts, _verdict = await pipeline.run_pipeline(body.supplier_text)
    if draft is None:
        raise HTTPException(502, "модель не смогла собрать карточку")
    return draft
