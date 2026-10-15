from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core import db
from app.core.logging import get_logger, setup_logging
from app.routers import cards, documents, jobs, search, workflows

log = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    setup_logging()
    db.init_pool()
    log.info("app_started", database=db.ping()["database"])
    yield
    db.close_pool()
    log.info("app_stopped")


app = FastAPI(title="card-service", lifespan=lifespan)
app.include_router(cards.router)
app.include_router(jobs.router)
app.include_router(documents.router)
app.include_router(search.router)
app.include_router(workflows.router)
# Роутеры шагов 7+ (генерация по документам, поиск) подключаются на своих шагах.


@app.get("/health")
async def health():
    """Живость процесса: отвечает без базы."""
    return {"status": "ok"}


@app.get("/ready")
async def ready():
    """Готовность: база отвечает, расширение vector активно."""
    return {"status": "ok", **db.ping()}
