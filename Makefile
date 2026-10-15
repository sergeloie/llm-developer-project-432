.PHONY: help install setup lint lint-fix test infra migrate api worker reindex eval bulk

help:
	@echo "make install         - установить зависимости из uv.lock"
	@echo "make setup           - install + миграции"
	@echo "make lint            - ruff"
	@echo "make lint-fix        - ruff с правкой"
	@echo "make test            - pytest"
	@echo "make infra           - поднять postgres(pgvector) + temporal (docker compose)"
	@echo "make migrate         - применить db/migrations/*.sql"
	@echo "make api             - FastAPI на :8000"
	@echo "make worker          - воркер устойчивых процессов (workflows + activities)"
	@echo "make reindex         - досчитать embeddings для чанков без векторов"
	@echo "make eval            - прогон evals (2 документа; FULL=1 - весь датасет)"
	@echo "make bulk            - загрузить все файлы из data/ через POST /documents"

install:
	uv sync --frozen

setup: install migrate

lint:
	uv run ruff check .

lint-fix:
	uv run ruff check --fix .

test:
	uv run pytest -q

infra:
	docker compose -p product-card-ai up -d

migrate:
	uv run python -m app.core.migrate

api:
	uv run uvicorn app.main:app --reload --port 8000

worker:
	uv run python -m app.temporal_worker

reindex:
	uv run python -m app.rag.reindex

eval:
	uv run python -m evals.runner $(if $(FULL),--full,)

bulk:
	uv run python scripts/bulk_load.py
