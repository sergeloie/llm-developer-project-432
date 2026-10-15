from fastapi import APIRouter, HTTPException

from app.core import db

router = APIRouter(tags=["health"])


@router.get("/health")
async def health():
    return {"status": "ok"}


@router.get("/ready")
async def ready():
    try:
        with db.connection() as conn, conn.cursor() as cur:
            cur.execute("SELECT 1")
            cur.fetchone()
            cur.execute("SELECT 1 FROM pg_extension WHERE extname = 'vector'")
            has_vector = cur.fetchone() is not None
    except Exception as exc:
        raise HTTPException(503, f"db connect failed: {exc}") from exc
    if not has_vector:
        raise HTTPException(503, "pgvector extension not installed")
    return {"status": "ok"}
