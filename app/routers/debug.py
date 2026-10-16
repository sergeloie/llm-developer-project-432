"""Отладочный роутер: полная трасса пайплайна по job_id для визуального инспекта.

Один эндпоинт отдаёт и статус задачи (из jobs), и упорядоченные шаги трассы
(из pipeline_traces): документ/чанки, промпты, сырые и распарсенные ответы LLM,
RAG-контекст. Фронтенд /debug-ui строит из этого пошаговый расклад.
"""
from fastapi import APIRouter, HTTPException

from app.core import db
from app.repositories import jobs as jobs_repo
from app.repositories import traces as traces_repo

router = APIRouter(tags=["debug"], prefix="/debug")


@router.get("/{job_id}")
async def get_trace(job_id: str):
    with db.connection() as conn:
        job = jobs_repo.get_job(conn, job_id)
        if job is None:
            raise HTTPException(404, "job not found")
        steps = traces_repo.get_trace(conn, job_id)
    return {"job": job, "steps": steps}
