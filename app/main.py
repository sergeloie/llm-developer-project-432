from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core import db
from app.core.logging import get_logger, setup_logging

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


@app.get("/health")
async def health():
    """Живость процесса: отвечает без базы."""
    return {"status": "ok"}


@app.get("/ready")
async def ready():
    """Готовность: база отвечает, расширение vector активно."""
    return {"status": "ok", **db.ping()}


# Роутеры подключаются на своих шагах.
