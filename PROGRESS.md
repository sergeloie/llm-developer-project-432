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

### 2026-10-15. Шаг 3 (LLM-клиент, пайплайн, фоновый job, Temporal) — ВЫПОЛНЕН
- `app/llm/client.py`: `complete(prompt, role, system, ...)` — единственная граница провайдера. Ретраи ТОЛЬКО на 429/5xx/сеть/таймаут c экспонентой+jitter; 4xx не ретраим. Замер duration, токены из `usage` (fallback-оценка 4 симв/токен, если usage нулевой). role→модель: main/cheap.
- `app/agents/prompts.py`: промпты — функции (extractor/generator/critic), JSON-схема + пример формы текстом (LM Studio strict mode не поддерживает).
- `app/schemas.py`: SupplierFacts, CardDraft (валидаторы: пустые значения, поле в двух списках, confidence 0..1), CritiqueReport, SourceRef, SeoBlock, JudgeVerdict (на шаг 8).
- `app/services/pipeline.py`: extract→(generate→critique→feedback)*≤3; валидация с лечением (невалидный ответ → повторный запрос с текстом ошибки, ≤3 попыток); точечная переделка `repair_field`; всё с инъекцией `complete` для тестов.
- `app/services/parse.py`: разбор 4 видов ответа (чистый JSON / в заборах / с текстом вокруг / мусор) через первый сбалансированный объект.
- `app/repositories/jobs.py`: create (идемпотентность по ключу — возвращает существующую), get, update_status, set_result; SQL только в репозитории.
- Temporal: `app/temporal/{workflows,activities,kickoff}.py` + `app/temporal_worker.py` (пул потоков ThreadPoolExecutor(8) для sync-activity `set_job_status_activity`; статус в базу — ОТДЕЛЬНЫЙ шаг). Сигналы approve/reject, query phase, долгая пауза `wait_condition`. Порог уверенности передаётся АРГУМЕНТОМ workflow (не читается из Settings внутри песочницы).
- Роутеры: `/cards` (sync), `/jobs` (202 + Idempotency-Key + GET), `/workflows` (старт/статус/сигналы/результат).
- E2E ПРОВЕРЕНО на живой LM Studio: job → done за ~20с (extract 2.6s, generate 9.5s, critique 3.8s). Оба теста прогона скрыты в тестах (7 passed, mock-клиент, без модели).
- УСТОЙЧИВОСТЬ ПРОВЕРЕНА: убил воркер на фазе generate r1, поднял нового — процесс дошёл до конца с того же места (phase generate r2), выполненные шаги не повторялись. Статус был pending до завершения. НАЙДЕННЫЙ ГРАБЛЬ: после смерти воркера запущенный activity ждал до start_to_close (900с) — добавлен heartbeat_timeout=3мин + activity.heartbeat() в LLM-activity, теперь переоткрытие через 3 минуты.
- консоль Windows показывает кириллицу как mojibake (кодировка cp-консоли) — это только отображение; в БД/логах всё в порядке (utf-8).
- Тесты: 7 passed (пайплайн успех/переделка/петля исчерпана, 4 вида разбора, контракт-валидация, репозиторий jobs на живой карточке test). ruff чисто.
### 2026-10-15. Шаг 4 (строгий контракт) — ВЫПОЛНЕН (в составе шага 3: schemas/parse/repair были сделаны сразу)
- Контракт: CardDraft c валидаторами (title непустой, характеристики без пустых значений, поле не в двух списках, confidence 0..1), SeoBlock вложенный, SourceRef; длина заголовка проверяется в pipeline._strict_card (из настроек).
- Передача схемы: JSON-схема + ПРИМЕР заполненной формы текстом (strict mode LM Studio не поддерживает; пример на маленьких моделях работает лучше схемы).
- Разбор 4 видов ответа — tests/test_pipeline.py::test_parse_four_kinds.
- Лечение валидации: ловит и ValidationError, и ValueError (длина заголовка); test_validation_repair_loop; повторный запрос содержит текст ошибки, попытки ограничены (max_repair_attempts).
- Точечная переделка: repair_field (текущий черновик + имя поля) —用于 на шаге 7 при исправлении ссылок.
- Порог уверенности: в GenerationWorkflow — confidence < CONFIDENCE_HUMAN_THRESHOLD (передаётся аргументом воркфлоу из настроек) → статус needs_human; тест scarce-data.
- Промпт критика ослаблен до правил проекта: нарушение = НОВОЕ число/значение (выдумка) или пустое поле; продающие выводы на фактах, пустые seo, низкий confidence НЕ нарушения. Проверено живьём: скудный текст → conf 0.75, missing_fields непустой, verdict approve с 1-го круга.
- Тесты: 8 passed. ruff чисто.
### 2026-10-15. Шаг 5 (документы: разбор, нормализация, чанкинг, процесс) — ВЫПОЛНЕН
- Парсеры: `parsing/pdf.py` (pypdf, постранично; эвристика секций; склейка пар «имя: значение» из текста pdf-таблиц; отсканированный → отказ с причиной), `parsing/docx.py` (python-docx: параграфы по Heading-секциям, таблицы с шапкой), `parsing/xlsx.py` (openpyxl: первая строка — обязательная шапка; без неё отказ; строка → «ART: параметр значение»). 
- `parsing/normalize.py`: лигатуры/soft-hyphen/nbsp, дефисные переносы («мощ-\nность»→«мощность»), пробелы; колонтитулы — только если строка на ALL страницах (или ≥60% при ≥3 стр.) И похожа на колонтитул; номер страницы нормализуется перед сравнением (иначе 2-страничный документ «стр.1/стр.2» не слова бы колонтитулом).
- `parsing/chunking.py`: текст — фиксированный размер c перекрытием (рез по границе предложения); таблицы — строками без разрыва, шапка («Колонка | Колонка») в каждой части; метаданные ord/page/section/kind/article(регэксп BLD-800)/brand(из имени файла).
- репозиторий `app/repositories/documents.py` (documents+chunks, status new|parsing|indexed|failed, идемпотентность по sha256 — повторная загрузка не создаёт дублей), миграция `migrations/0002_documents.sql`.
- Temporal: DocumentParseWorkflow (parsing→storing→completed) + activities (parse_document_activity — блокирующий, через asyncio.to_thread; store_fragments_activity — sync в threadpool воркера). Ошибка разбора → документ failed с внятной причиной, а НЕ тихий пустой результат.
- E2E на выдающемся наборе: blender_passport.pdf → indexed 10 фрагментов; blender_kp.docx → 9; kettle_spec.xlsx → 1; повторная загрузка kettle_spec → created=False (дубля нет); boiler_scan.pdf → failed «нет текстового слоя».
- Тесты: tests/test_parsing.py (5 шт) — golden-строки достаются, строки прайса не разрывы (артикул+цена в одном фрагменте), шапка в каждой части таблицы, скан отклонён. Всего 13 passed, ruff чисто.
- СЛЕДУЮЩИЙ ШАГ: шаг 7 (сквозной сценарий документы → карточка со ссылками).

### 2026-10-15. Шаг 6 (эмбеддинги и поиск) — ВЫПОЛНЕН
- `app/embeddings/client.py`: async клиент LM Studio `/v1/embeddings`, документный/запросный префиксы, клиент на процесс, батч; ретраи 429/5xx/сеть.
- `migrations/0003_vectors.sql`: `chunks.embedding vector(768)`, `chunks.tsv` (generated, GIN), HNSW на `chunks.embedding`; таблица `chunk_embs` сохранена для совместимости.
- `app/repositories/documents.py`: `store_embeddings`, `pending_embed_count`, `chunk_texts_for_embedding` (по `chunks.embedding IS NULL`), `search_vector`, `search_words` (ts_rank + LIKE по артикулам, исправлена расстановка скобок), `search_hybrid` (RRF), фильтры по документам и порог.
- `app/services/backfill.py`: досчёт пачками, повторный запуск — 0; `make backfill`.
- Индексация после разбора: `DocumentParseWorkflow` → `backfill_vectors_activity` (fail-soft: документ уже в базе, досчёт вручную).
- `app/services/search.py`, `app/routers/search.py`: `/search` с mode vector/words/hybrid и `/chunks/{id}`.
- Калибровка: threshold=0.45 — на своих запросах релевантные фрагменты показывают cosine 0.48–0.54, мусор — 0.26–0.36; порог отсекает мусор и сохраняет попадания. Префиксы взяты из карточки embeddinggemma.
- Тесты: `tests/test_search.py` (18 всего: функции RRF/cosine, репозиторий на синтетических векторах, гибрид как объединение, отсечение порогом). LLM не грузится.
- Проверено живьём: backfill 20 фрагментов за один проход, повторный — 0; запрос «мощность блендера» находит нужную страницу через vector/hybrid, «KTL-1700» — только words/hybrid.
- ruff: All checks passed; pytest: 18 passed.

### Грабли/заметки
- Temporal image auto-setup 1.29.7: HTTP `/health` на 7233 не существует — живость через UI :8233 (200) и логи.
- psycopg3 `conn.execute(multi-statement)` работает — раннер гоняет файл в одной транзакции.
- КРИТИК-МОДЕЛЬ qwen3-4b слишком строг: считает benefits (выведенные из фактов) выдуманными; треяет seo/confidence в фактах. На шаге 4 перерабатываем промпт критика: выдуманным считается только то, чего нет в фактах КАРДИНАЛЬНО (числа/характеристики), а выводы («сталь — практичный материал») разрешены.
- после смерти воркера activity без heartbeat ждёт start_to_close — ОБязательно ставить heartbeat_timeout на LLM-activities.
- `.venv` в Windows: binaries в `.venv/Scripts/` (ruff.exe и т.п.).
- standalone-скрипты: `db.init_pool()` нужен вручную (в FastAPI это делает lifespan).
- фоновые процессы с кодбуффа: BACKGROUND process_type + лог-файлы в temp; taskkill //F //PID для остановки.

### Блокеры
### 2026-10-15. Шаг 7 (сквозной сценарий) — ВЫПОЛНЕН
- `app/services/context.py`: сборка контекста `[S#] (fragment_id=...)`, дедуп по тексту, лимит `context_char_limit`, маскирование PII по возможности.
- `app/agents/prompts.py`: `generator_with_context_prompt` (контекст, обязательные ссылки на fragment_id).
- `app/services/generation.py`: поиск hybrid, сборка контекста, генерация, проверка ссылок, повтор при невалидных, статусы; функции-алиасы для разных ожидаемых имён.
- `app/repositories/jobs.py`: расширен набор статусов (parsing/indexing/searching/generating/verifying/awaiting_human/approved/rejected).
- `app/routers/generators.py`, main: `/generators` (POST 202, GET результат).
- Проверено вручную на mock-клиенте: валидная ссылка → `awaiting_human`, невалидная → `needs_human`.
- ruff: clean, pytest: 18 passed.

- СЛЕДУЮЩИЙ ШАГ: шаг 8 (стоимость, трассировка, метрики) — по плану.

### 2026-10-15. Шаги 8-9 (стоимость/метрики/защита) — ВЫПОЛЕНЫ ЧАСТИЧНО
- Шаг 8: миграция 0004_calls.sql, `app/repositories/calls.py` (учёт токенов/стоимости), `app/services/metrics.py` (нормализация и сравнение характеристик), `judge_prompt` в prompts.
- Шаг 9: `app/guards` (pii/injection/output), context использует маскирование.
- Шаг 10: README остаётся исходным; сервис проверен прогонами на mock.

- СЛЕДУЮЩИЙ ШАГ: финальная сдача/проверка (по плану).

- (нет)
