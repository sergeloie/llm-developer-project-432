from fastapi import APIRouter, HTTPException

from app.core import db
from app.repositories import system as system_repo

router = APIRouter(tags=["health"])


@router.get("/health")
async def health():
    return {"status": "ok"}


@router.get("/ready")
async def ready():
    try:
        with db.connection() as conn:
            db_alive = system_repo.ping(conn)
            has_vector = system_repo.has_vector_extension(conn)
    except Exception as exc:
        raise HTTPException(503, f"db connect failed: {exc}") from exc
    if not db_alive:
        raise HTTPException(503, "db connect failed")
    if not has_vector:
        raise HTTPException(503, "pgvector extension not installed")
    return {"status": "ok"}
