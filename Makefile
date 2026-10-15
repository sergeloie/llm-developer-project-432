.PHONY: help install setup lint lint-fix test infra infra-down migrate api worker reindex eval bulk

help:
	@echo "make install         - установить зависимости из uv.lock"
	@echo "make setup           - install + миграции"
	@echo "make lint            - ruff"
	@echo "make lint-fix        - ruff с правкой"
	@echo "make test            - pytest"
	@echo "make infra           - поднять postgres(pgvector) + temporal (docker compose)"
	@echo "make infra-down      - остановить контейнеры и удалить тома (база и индексы пропадут)"
	@echo "make migrate         - применить db/migrations/*.sql"
	@echo "make api             - FastAPI на :8000"
	@echo "make worker          - воркер устойчивых процессов (workflows + activities)"
	@echo "make reindex         - досчитать embeddings для чанков без векторов"
	@echo "make eval            - прогон evals (2 документа; make eval FULL=1 - весь датасет)"
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

# Полный снос для прогона с нуля: -v удаляет том pgdata вместе с индексами.
infra-down:
	docker compose -p product-card-ai down -v

migrate:
	uv run python -m app.core.migrate

api:
	uv run uvicorn app.main:app --reload --port 8000

worker:
	uv run python -m app.temporal_worker

reindex:
	uv run python -m app.rag.reindex

# FULL задаётся как переменная команды: make eval FULL=1. Префикс окружения
# FULL=1 make eval не разбирается ни PowerShell, ни cmd. Проверка $(if $(FULL=1))
# из make 4.x здесь не годится: GnuWin32 make 3.81 молча даёт пусто, поэтому
# любое непустое значение FULL считается включённым полным прогоном.
eval:
	uv run python -m evals.runner $(if $(FULL),--full,)

bulk:
	uv run python scripts/bulk_load.py
