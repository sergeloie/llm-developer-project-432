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
# загрузить документ (без слэша в конце — иначе 307 редирект и файл не уйдёт)
curl -F file=@data/blender_passport.pdf localhost:8000/documents

# сгенерировать карточку по загруженным документам
curl -X POST localhost:8000/generate-card \
	-H 'Content-Type: application/json' \
	-d '{"document_ids": ["<id>"], "product_hint": "блендер"}'

# сгенерировать карточку ГИБРИДНЫМ ПОИСКОМ (document_ids пуст → применяется порог retrieval_threshold)
curl -X POST localhost:8000/generate-card \
	-H 'Content-Type: application/json' \
	-d '{"document_ids": [], "product_hint": "кофемашина"}'

# посмотреть статус и результат
curl localhost:8000/jobs/$JOB_ID

# метрики: токены, стоимость, latency, доля невалидных ответов, статусы
curl localhost:8000/metrics
```

Два режима генерации карточки (поле запроса к поиску — `product_hint`):
- **По `document_ids` (непустой список)** — берутся ВСЕ чанки перечисленных
  документов, retrieval и порог сходства не применяются (иначе similarity-top-k
  отбрасывает до 75% текста). Это основной сценарий из примеров выше.
- **Без `document_ids` (пустой список)** — запускается гибридный поиск
  (вектор + FTS) по `product_hint` с отсеканием векторной ноги по
  `retrieval_threshold`. Порог и его замер на реальном корпусе — см.
  `docs/retrieval-threshold-measurement.md`.

```bash
# метрики генерации: field_accuracy, citation_hit, faithfulness
make eval
# полный прогон (весь датасет) — долго
make eval FULL=1
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

## Метрики генерации

`make eval` (по умолчанию 2 документа, `make eval FULL=1` — весь золотой
датасет, включая документ с инъекцией и неполную спецификацию) прогоняет
генерацию карточек и считает три метрики:

- **field_accuracy** — доля ожидаемых характеристик, извлечённых из документа
  (ключ+значение совпали после нормализации).
- **citation_hit** — доля карточек, чьи `sources` указывают на чанк с
  ожидаемым текстом (проверяемость цитат).
- **faithfulness** — LLM-as-judge: подтверждаемость карточки контекстом (0–1);
  помечается `NaN`, если модель-судья недоступна.

Стоимость одной карточки считается из `llm_calls` по `PRICE_*` из `.env`;
при локальном сервере моделей цены нулевые, поэтому рядом даны токены.
Самая слабая метрика и причина — под таблицей (берётся из отчёта
`evals/report.json`).

<!-- EVAL_TABLE -->

Прогон `make eval FULL=1` по всему золотому датасету (включая документ с инъекцией
и неполную спецификацию); `qwen/qwen3.5-9b` — генератор, `qwen/qwen3-4b-2507` —
детектор инъекций, критик и судья.

| Документ | field_accuracy | citation_hit | faithfulness | confidence | статус | токены |
|---|---:|---:|---:|---:|---|---:|
| blender_passport.pdf | 1.00 | 1.00 | 0.95 | 0.95 | done | 6769 |
| blender_kp.docx | 0.00 | 1.00 | 0.95 | 0.65 | needs_review | 10603 |
| kettle_spec.xlsx | 0.00 | 1.00 | 0.95 | 0.82 | done | 6896 |
| kettle_manual.pdf (инъекция) | 1.00 | 1.00 | 0.95 | 0.95 | done | 5437 |
| boiler_scan.pdf (скан с инъекцией) | 1.00 | 1.00 | 0.00 | 0.00 | rejected | 4296 |
| **среднее** | **0.60** | **1.00** | **0.76** | **0.67** | — | **~6800** |

Средняя стоимость карточки: **$0.0000** — локальный сервер моделей, в `.env`
`PRICE_*` = 0; при реальном OpenAI/OpenRouter подставятся цены из окружения.
В пересчёте на токены — ≈ 6800 на карточку (генерация тяжёлой моделью,
остальные шаги — лёгкой).

**Самая слабая метрика:** `field_accuracy` = 0.60.
Доля характеристик, извлечённых из документа. Низкая — модель не достала часть
параметров (они лежат вне топ-чанков, в таблицах или в неполной спецификации, где
их действительно нет), либо нормализация сравнения слишком строга к единицам
измерения и пунктуации.

Полный отчёт — `evals/report.json` (он под `.gitignore`, поэтому таблица выше —
единственное место, где метрики видны наставнику).

## Переменные окружения

`.env.example` — единственный список переменных: почти каждая строка там — поле
`Settings` в `app/core/config.py`, имя переменной совпадает с именем поля в
верхнем регистре; исключения — `PG_PORT`, `TEMPORAL_PORT` и `TEMPORAL_UI_PORT`,
это переменные `docker compose`, их приложение не читает. Сам файл `.env`
необязателен: значения по умолчанию совпадают
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
`CARD_TITLE_MAX_LEN` / `CONFIDENCE_HUMAN_THRESHOLD` /
`SOURCE_COVERAGE_THRESHOLD` / `MAX_FIX_ATTEMPTS` /
`MAX_PIPELINE_ROUNDS`, `SUSPICIOUS_CHUNK_LIMIT`, `UPLOAD_MAX_MB`,
`DEBUG_ENDPOINTS_ENABLED` (в проде — `false`: `/debug` и `/debug-ui` отдают
трассу пайплайна без авторизации).
`TEMPORAL_UI_PORT` — порт веб-интерфейса Temporal, тоже переменная `docker compose`,
а не `Settings`.

## Структура

```text
app/
├── main.py            приложение и сквозной request_id
├── core/              конфигурация, база, логи, раннер миграций
├── routers/           HTTP: cards, jobs, documents, generate, workflows, health, metrics, debug
├── services/          сценарии: базовый пайплайн, RAG-пайплайн, разбор документов, контракт результата
├── agents/            промпты extractor, generator, context_generator, fixer, critic
├── llm/               клиент модели и учёт стоимости вызовов
├── rag/               эмбеддинги, поиск, сборка контекста
├── parsers/           pdf, docx, xlsx, нормализация, чанкинг
├── guardrails/        PII и детектор инъекций
├── repositories/      SQL — только здесь
├── schemas/           контракты между слоями
└── temporal/          процессы: разбор, карточка, модерация
db/migrations/         SQL-миграции
evals/                 прогон метрик на золотом датасете
scripts/               bulk-загрузка через API (make bulk), индексация корпуса и замер порога retrieval
tests/                 тесты
data/                  документы поставщиков и золотой датасет
```

## Документы для разработки

- `docs/adr/` — принятые архитектурные решения
