PY = .venv/Scripts/python.exe
RUN = $(PY) -m

.PHONY: help infra down migrate api temporal-worker lint tests

help:
	@echo "make infra           - поднять postgres(pgvector) + temporal + ui"
	@echo "make down            - остановить контейнеры"
	@echo "make migrate         - применить миграции (идемпотентно)"
	@echo "make api             - FastAPI на :8000 (uvicorn --reload)"
	@echo "make temporal-worker - воркер Temporal (workflows + activities)"
	@echo "make lint            - ruff check"
	@echo "make tests           - pytest"

infra:
	docker compose up -d

down:
	docker compose down

migrate:
	$(RUN) migrations.runner

api:
	$(RUN) uvicorn app.main:app --reload --port 8000

temporal-worker:
	$(RUN) app.temporal_worker

backfill:
	$(RUN) app.services.backfill

lint:
	.venv/Scripts/ruff.exe check app tests migrations

tests:
	$(PY) -m pytest -q
