"""Синхронный вариант: весь пайплайн внутри запроса."""
from fastapi import APIRouter

from app.schemas.cards import CardDraft
from app.schemas.jobs import CardRequest
from app.services.pipeline import run_pipeline

router = APIRouter(tags=["cards"])


@router.post("/cards", response_model=CardDraft)
async def create_card(body: CardRequest) -> CardDraft:
    draft, _attempts, _verdict, _status = await run_pipeline(body.supplier_text)
    return draft
