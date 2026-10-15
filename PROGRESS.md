# PROGRESS.md — журнал работы над llm-developer-project-432

Формат: записи по шагам. Другой агент может продолжить работу с последней записи.
См. также `PLAN.md` — детальный план и структура модулей.

## Контекст

- Репозиторий: `C:\DevProjects\llm-developer-project-432` (Hexlet, Py3.11+). venv: `.venv` (Git Bash: `source .venv/Scripts/activate`).
- Задание: `C:\Users\ryzen-admin\AppData\Local\Temp\opencode\llm-developer-project-432\task\step1..10.md` (+ теория, + эталонный `example`).
- Пользователь не знает Python — работать автономно, не задавать вопросов.
- LM Studio 127.0.0.1:1234 (OpenAI-compatible, возвращает `usage`): чат `qwen/qwen3.5-9b` (main), `qwen/qwen3-4b-2507` (cheap); эмбеддинги `text-embedding-embeddinggemma-300m` (dim=768, батч ок).
- Docker 28.3, запускается через `make infra`.

## Принятые решения (сводка, обоснование в дневнике)

1. Миграции: SQL-файлы + раннер `migrations/runner.py`, версии в `schema_migrations` (рекомендация step2).
2. LLM-клиент: собственный на httpx над LM Studio `/v1/chat/completions` (не SDK): прямой доступ к `usage`, две модели, простой вызов. Ретраи только 429/5xx/сеть.
3. Эмбеддинги: LM Studio `/v1/embeddings` (без torch). Префиксы embeddinggemma: `task: search document | ` / `task: search result | `.
4. Две модели: main=`qwen/qwen3.5-9b`, cheap=`qwen/qwen3-4b-2507` (критик/судья/детектор).
5. RabbitMQ/Celery НЕ переносим — фоновую работу везёт Temporal (step3 прямо разрешает).
6. Парсеры: pdf=pypdf, docx=python-docx, xlsx=openpyxl; таблицы из pdf — бонус.
7. Тесты не требуют LM Studio (mock), кроме явно помеченных integration-прогонов вручную.
8. БД-слой синхронный (psycopg sync + ConnectionPool); FastAPI-эндпоинты с БД — `def` (FastAPI сам в threadpool); Temporal activity для БД — sync ( ThreadPoolExecutor), для LLM — async.

## Дневник

### 2026-10-15. Шаг 2 (каркас) — ВЫПОЛНЕН
- Зависимости поставлены в `.venv` (fastapi, uvicorn, pydantic, pydantic-settings, psycopg[binary,pool], temporalio, httpx, structlog, pypdf, python-docx, openpyxl, pytest, pytest-asyncio, ruff).
- `docker-compose.yml`: pgvector/pgvector:pg17 (БД `card`, init-скрипт создаёт `temporal` и `temporal_visibility`), temporalio/auto-setup:1.29.7 (:7233), temporalio/ui:2.55.0 (:8233). Все три контейнера running, UI на http://localhost:8233 открывается (HTTP 200).
- Миграции: `migrations/0001_init.sql` (CREATE EXTENSION vector, schema_migrations, jobs) + `migrations/runner.py`. Первый запуск: `applied: 0001_init.sql`, второй: `no new migrations` — идемпотентность подтверждена.
- Пул: `app/core/db.py` — `init_pool()`/`pool()`/`close_pool()`/`connection()` (контекстный менеджер, соединение на операцию) + `ping()` (БД + проверка расширения vector).
- `app/core/config.py` (все настройки из .env), `app/core/logging.py` (structlog, bind_contextvars), `app/main.py`: `/health` (живость, без БД) и `/ready` (`vector: active` — проверено TestClient).
- Выданные документы перенесены AS IS: 5 файлов + `golden_cards.json` → `data/`, 100 файлов булк-набора → `data/bulk/` (скопированы пока в оба места; исходная `docs/` будет удалена после коммита, чтобы «as is» сохранился).
- `.env` и `.env.example` (LLM Lab, модели, цены, порог поиска, лимиты, защита), `Makefile` (infra/down/migrate/api/temporal-worker/lint/tests), `tests/conftest.py` (пересоздаёт `card_test`, включает vector).
- ruff: All checks passed.
- СЛЕДУЮЩИЙ ШАГ: шаг 3 (LLM-клиент, пайплайн, jobs-репозиторий, Temporal-процесс) — по PLAN.md раздел «Шаг 3».

### Грабли/заметки
- Temporal image auto-setup 1.29.7: HTTP `/health` на 7233 не существует (пустой ответ) — живость проверяем через UI :8233 (200) и логи контейнера; gRPC-коннект проверится на шаге 3 при подключении воркера.
- psycopg3 `conn.execute(multi_statement_sql)` работает (без параметров) — раннер гоняет файл целиком в одной транзакции.
- `.venv` в Windows: binaries в `.venv/Scripts/`, включая `ruff.exe` (в Makefile пути прописаны).

### Блокеры
- (нет)
