import os
from contextlib import asynccontextmanager
from uuid import uuid4

import structlog
from fastapi import FastAPI, Request
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles

from app.core import db
from app.core.logging import setup_logging
from app.routers import cards, debug, documents, generate, health, jobs, metrics, workflows


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging()
    with db.connection() as conn:  # проверка БД и прогрев пула
        conn.execute("SELECT 1")
    yield
    db.close_pool()


app = FastAPI(title="product-card-ai", lifespan=lifespan)


@app.middleware("http")
async def request_id_middleware(request: Request, call_next):
    """Сквозной request_id: из заголовка клиента или новый — в логи и ответ."""
    rid = request.headers.get("X-Request-ID") or uuid4().hex[:12]
    structlog.contextvars.bind_contextvars(request_id=rid)
    response = await call_next(request)
    response.headers["X-Request-ID"] = rid
    return response


app.include_router(health.router)
app.include_router(metrics.router)
app.include_router(cards.router)
app.include_router(jobs.router)
app.include_router(workflows.router)
app.include_router(documents.router)
app.include_router(generate.router)
app.include_router(debug.router)

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
os.makedirs(STATIC_DIR, exist_ok=True)
app.mount("/debug-ui", StaticFiles(directory=STATIC_DIR, html=True), name="debug-ui")


@app.get("/debug", include_in_schema=False)
async def debug_redirect():
    return RedirectResponse("/debug-ui/")
