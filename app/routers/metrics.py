"""GET /metrics: агрегаты по llm_calls, jobs и documents текстом."""
from fastapi import APIRouter
from fastapi.responses import PlainTextResponse

from app.core import db
from app.repositories import metrics as metrics_repo
from app.services.metrics import render

router = APIRouter(tags=["metrics"])


@router.get("/metrics", response_class=PlainTextResponse)
async def metrics() -> str:
    with db.connection() as conn:
        return render(metrics_repo.snapshot(conn))