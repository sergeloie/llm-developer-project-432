# product-card-ai

Сервис принимает документы поставщиков (pdf, docx, xlsx), строит по ним поисковый индекс
и генерирует черновик карточки товара — с указанием источников, списком недостающих
данных и уровнем уверенности.

## Требования

- Python 3.12
- Docker и Docker Compose
- OpenAI-совместимый сервер моделей: LM Studio, Ollama или OpenRouter

Модель эмбеддингов выбирает `EMBED_BACKEND`:

- `lmstudio` (по умолчанию) — эмбеддинги считает тот же локальный
  OpenAI-совместимый сервер моделей, модель `EMBED_MODEL` в нём уже загружена,
  ничего не скачивается;
- `hf` — `sentence-transformers` грузит веса сам при первом запуске; для
  `google/embeddinggemma-300m` это примерно 600 МБ.

Размерность вектора (`EMBED_DIM`, по умолчанию 768) зашита в миграцию
`db/migrations/003_vectors.sql` как `vector(768)`: сменили модель эмбеддингов —
пишите миграцию, меняющую тип колонки, и меняйте `EMBED_DIM` вместе с ней.

## Запуск

```bash
cp .env.example .env # и вписать адрес своего сервера моделей
make infra           # postgres с pgvector и движок процессов
make setup           # зависимости и миграции
make api             # http://localhost:8000
make worker          # разбор документов и генерация карточек
make bulk            # загрузить все документы поставщиков из data/ через API
```

Проверка, что всё поднялось:

```bash
curl localhost:8000/health # жив ли процесс
curl localhost:8000/ready  # доступна ли база и включено ли расширение vector
```

Полный прогон с нуля заканчивается сносом инфраструктуры — тома удаляются
вместе с базой и индексом:

```bash
make infra-down # контейнеры остановлены, том pgdata удалён
```

## Работа с сервисом

```bash
# загрузить документ
curl -F file=@data/blender_passport.pdf localhost:8000/documents/

# сгенерировать карточку по загруженным документам
curl -X POST localhost:8000/generate-card \
	-H 'Content-Type: application/json' \
	-d '{"document_ids": ["<id>"], "product_hint": "блендер"}'

# посмотреть статус и результат
curl localhost:8000/jobs/$JOB_ID

# метрики: токены, стоимость, latency, доля невалидных ответов, статусы
curl localhost:8000/metrics
```

## Разработка

```bash
make help        # список целей
make test        # pytest
make lint        # ruff
make lint-fix    # ruff с правкой
make migrate     # применить db/migrations/*.sql
make eval        # метрики генерации на золотом датасете (2 документа)
make eval FULL=1 # метрики по всему золотому датасету
make reindex     # досчитать эмбеддинги для чанков без векторов
make bulk        # загрузить все файлы из data/ через POST /documents
make infra-down  # остановить контейнеры и удалить тома
```

`FULL` задаётся как переменная команды — `make eval FULL=1`, а не префиксом
`FULL=1 make eval`: префикс не разбирается ни PowerShell, ни cmd. Любое
непустое значение `FULL` включает полный прогон.

## Переменные окружения

`.env.example` — единственный список переменных: каждая строка там — поле
`Settings` в `app/core/config.py`, имя переменной совпадает с именем поля в
верхнем регистре. Сам файл `.env` необязателен: значения по умолчанию совпадают
с `docker compose` и с локальным сервером моделей на `127.0.0.1:1234`, поэтому
сервис поднимается и без него.

**Менять нужно**, если окружение отличается от локального:

| Переменная | Когда |
|---|---|
| `LLM_BASE_URL` | сервер моделей слушает не `http://127.0.0.1:1234/v1` |
| `LLM_API_KEY` | сервер требует настоящий ключ (OpenRouter), а не `lm-studio` |
| `LLM_MODEL`, `LLM_MODEL_CHEAP` | сервер не знает `qwen/qwen3.5-9b` / `qwen/qwen3-4b-2507` |
| `DATABASE_URL`, `PG_PORT` | Postgres поднят не на 5433 (второе — для самого compose) |
| `TEMPORAL_HOST`, `TEMPORAL_PORT` | движок процессов не на 7233 |
| `EMBED_BACKEND` | сервер моделей не умеет эмбеддинги — тогда `hf` и веса качаются сами |
| `EMBED_MODEL`, `EMBED_DIM` | другая модель эмбеддингов; вместе с ними нужна миграция типа колонки |

**Необязательно** — рабочие значения по умолчанию: `LLM_TIMEOUT_S`,
`LLM_MAX_RETRIES`, `LLM_MAX_TOKENS`, `LLM_TEMPERATURE`, `PRICE_DEFAULT_INPUT` /
`PRICE_DEFAULT_OUTPUT` и `PRICE_<МОДЕЛЬ>_INPUT` / `PRICE_<МОДЕЛЬ>_OUTPUT`
(без цен стоимость в метриках считается нулевой), `DB_POOL_MIN` / `DB_POOL_MAX`,
`TEMPORAL_TASK_QUEUE`, `TEMPORAL_ACTIVITY_THREADS`, `EMBED_BASE_URL` /
`EMBED_API_KEY` (пусто — берутся из `LLM_*`), `EMBED_TIMEOUT_S`, `EMBED_DEVICE`
(`cpu`; `mps` не работает в дочерних процессах), `RETRIEVAL_TOP_K` /
`RETRIEVAL_THRESHOLD`, `CHUNK_SIZE` / `CHUNK_OVERLAP` / `CONTEXT_CHAR_LIMIT`,
`CARD_TITLE_MAX_LEN` / `CONFIDENCE_HUMAN_THRESHOLD` / `MAX_FIX_ATTEMPTS` /
`MAX_PIPELINE_ROUNDS`, `SUSPICIOUS_CHUNK_LIMIT`, `UPLOAD_MAX_MB`,
`TEMPORAL_UI_PORT`.

## Структура

```text
app/
├── main.py            приложение и сквозной request_id
├── core/              конфигурация, база, логи, раннер миграций
├── routers/           HTTP: cards, jobs, documents, generate, workflows, health, metrics
├── services/          сценарии: базовый пайплайн, RAG-пайплайн, разбор документов, контракт результата
├── agents/            промпты extractor, generator, critic
├── llm/               клиент модели и учёт стоимости вызовов
├── rag/               эмбеддинги, поиск, сборка контекста
├── parsers/           pdf, docx, xlsx, нормализация, чанкинг
├── guardrails/        PII и детектор инъекций
├── repositories/      SQL — только здесь
├── schemas/           контракты между слоями
└── temporal/          процессы: разбор, карточка, модерация
db/migrations/         SQL-миграции
evals/                 прогон метрик на золотом датасете
scripts/               массовая загрузка данных через API (make bulk)
tests/                 тесты
data/                  документы поставщиков и золотой датасет
```

## Документы для разработки

- `docs/adr/` — принятые архитектурные решения
