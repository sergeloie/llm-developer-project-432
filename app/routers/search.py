"""Поиск по фрагментам (шаг 6): режим + фильтр по документам."""
from fastapi import APIRouter, Query

from app.core import db
from app.repositories import documents as docs_repo
from app.services import search as search_svc

router = APIRouter(tags=["search"])


@router.get("/search")
async def search_endpoint(
    q: str = Query(min_length=1),
    mode: str = Query(default="hybrid", pattern="^(vector|words|hybrid)$"),
    top_k: int = Query(default=10, ge=1, le=50),
    threshold: float | None = None,
    document_id: int | None = None,
) -> dict:
    ids = [document_id] if document_id else None
    hits = await search_svc.search(
        q, mode=mode, top_k=top_k, threshold=threshold, document_ids=ids
    )
    return {"query": q, "mode": mode, "hits": hits}


@router.get("/chunks/{chunk_id}")
async def chunk_endpoint(chunk_id: int) -> dict:
    with db.connection() as conn:
        chunks = docs_repo.chunks_by_ids(conn, [chunk_id])
    if not chunks:
        from fastapi import HTTPException

        raise HTTPException(404, "фрагмент не найден")
    return chunks[0]
