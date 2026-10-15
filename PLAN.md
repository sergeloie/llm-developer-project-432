# План реализации: ИИ-генератор карточки товара (llm-developer-project-432)

Задание из `C:\Users\ryzen-admin\AppData\Local\Temp\opencode\llm-developer-project-432\{task,theory,example}`.
Рабочий репозиторий: `C:\DevProjects\llm-developer-project-432`.
Журнал прогресса, решений и находок: `PROGRESS.md` (обновляется после каждого шага — другой агент продолжает отсюда).

## Окружение (замерено, см. PROGRESS.md)

- Python 3.11.15, venv: `.venv` (`source .venv/Scripts/activate` в Git Bash, Windows).
- Docker 28.3, образы уже скачаны: `pgvector/pgvector:pg17`, `temporalio/auto-setup:1.29.7`, `temporalio/server:1.27.2`, `temporalio/ui:2.55.0`.
- LM Studio на 127.0.0.1:1234 (OpenAI-compatible `/v1/chat/completions`, `/v1/embeddings`, возвращает `usage`):
  - чат: `qwen/qwen3-4b-2507`, `qwen/qwen3.5-9b`;
  - эмбеддинги: `text-embedding-embeddinggemma-300m`, размерность 768, поддерживает батч.
- Выданный набор документов уже лежит в репо (незакоммичено): 5 документов в корне `docs/` + `golden_cards.json` (эталон) + `supplier-docs/` (100 документов булк-набора).

## Ключевые решения (зафиксированы, обоснование в PROGRESS.md)

1. **Миграции — SQL-файлы + простой раннер** (`migrations/NNNN_*.sql`, применённые версии в таблице `schema_migrations`). Задание прямо рекомендует этот вариант.
2. **LLM-клиент — свой на httpx** (<stdodelay>обёртка над LM Studio `/v1/chat/completions`): таймаут, ретраи только на 429/5xx/сети с экспон. задержкой + jitter, учёт usage. Не используем SDK openai-agents: нужен полный контроль (usage, две модели, функция-вызов простая).
3. **Эмбеддинги через LM Studio `/v1/embeddings`** (локальный сервер, без torch/sentence-transformers — тяжело ставить). Префиксы embeddinggemma берём из карточки модели: `task: search document | ` для фрагментов, `task: search result | ` для запросов.
4. **Две модели**: основная генерация — `qwen/qwen3.5-9b`, служебные (критик, судья, детектор инъекций) — `qwen/qwen3-4b-2507` (дешёвая).
5. **RabbitMQ/Celery не переносим** — по заданию очередь остаётся в практике темы; фоновую работу везёт Temporal.
6. **PDF — pypdf** (текст постранично), **docx — python-docx**, **xlsx — openpyxl**. Таблицы из pdf — бонус, не обязательны.
7. Выданные документы переносим в `data/` как есть (5 файлов + golden_cards.json), булк-набор — в `data/bulk/` (100 файлов). Папку `docs/` после переноса удаляем (она не коммитилась).

## Целевая структура модулей (контракт из step2, имена буквально)

```
app/
  main.py                  # FastAPI, lifespan: пул БД, логирование
  schemas.py               # Pydantic: CardDraft, sources, job/doc io
  core/
    config.py              # Settings (pydantic-settings)
    db.py                  # pool(), connection() — пул на жизнь процесса
    logging.py             # structlog, контекст request_id/job_id
  llm/client.py            # граница провайдера: call(model_role, prompt), ретраи, usage → таблица вызовов
  embeddings/client.py     # embed_documents()/embed_query() с префиксами, батчами
  agents/prompts.py        # промпты как функции (extractor/generator/critic/judge/detector)
  repositories/
    jobs.py                # SQL только здесь
    documents.py           # документы + фрагменты, запись векторов пачкой, 3 вида поиска
    calls.py               # таблица вызовов модели
  services/
    pipeline.py            # extract→generate→critique, петля ≤3, лечение валидации, точечная переделка
    documents.py           # разбор→нормализация→резка→сохранение, состояния документа
    context.py             # сборка контекста: PII-маскирование, детектор инъекций, лимит размера, дедуп
    search.py              # три режима поиска (вектор/слова/гибрид) над репозиторием
    metrics.py             # прогон по golden_cards.json: метрики, отчёт в файл
  parsing/
    pdf.py docx.py xlsx.py normalize.py chunking.py
  guards/
    pii.py                 # телефон/почта/ИНН(контрольная сумма)/карта → метки
    injection.py           # правила + дешёвая модель по помеченным; fail-closed
    output.py              # фильтр выхода (PII + следы инъекций)
  routers/
    cards.py               # синхронная генерация на тексте
    jobs.py                # фоновая задача (202 + idempotency) + статус
    workflows.py           # Temporal: старт/статус/сигналы
    documents.py           # загрузка файла (202), статус документа
    generators.py          # генерация по документам (202), чтение результата с источниками
  temporal/
    workflows.py           # GenerationWorkflow: состояния, сигнал подтверждения/отказа
    activities.py          # блокирующие/долгие шаги
    temporal_worker.py     # точка входа воркера
migrations/                # *.sql + run_migrations.py
data/                      # выданные документы (as is), data/bulk/
tests/
docker-compose.yml
Makefile
.env.example
requirements.txt requirements-dev.txt
```

## Порядок выполнения (шаг = атомарная единица, после каждого — коммит + обновление PROGRESS.md)

### Шаг 2. Каркас: compose + миграции + пул + data/
1. venv, `requirements*.txt`, `.env.example` (LM Studio, БД, Temporal, модели, цены, пороги).
2. `docker-compose.yml`: postgres (pgvector:pg17, healthcheck), с init-скриптом создаёт БД `card`; `temporal` (auto-setup, БД `temporal`/`temporal_visibility` в том же postgres, env DB_TYPE=postgres12); `temporal-ui` (:8233).
3. `migrations/0001_init.sql`: `CREATE EXTENSION vector`, таблица `schema_migrations`, таблица `jobs`; раннер `migrations/run_migrations.py` (идемпотентный, цель `make migrate`); Makefile-цели `infra`, `migrate`.
4. `app/core/config.py`, `logging.py`; `db.py` c `pool()` (создание на старте приложения) и `connection()` (контекстный менеджер на операцию — правило: соединение берётся на операцию, не на шаг).
5. `app/main.py`: `/health` (живость — без БД; готовность `/ready` — БД + расширение vector в ответе).
6. Перенос документов: `docs/*` → `data/`, булк → `data/bulk/`, коммит as is.
7. Проверка: `docker compose ps` оба running; миграция второй запуск — «новых нет»; линтер+тесты зелёные.

### Шаг 3. LLM-клиент, пайплайн, фоновая обработка, Temporal
1. `llm/client.py`: async `complete(prompt, *, role)`, role → модель из настроек (пока main/cheap), таймаут `llm_timeout_s`, ретраи на 429/5xx/сети (экспонента + jitter), ошибку запроса (4xx) не ретраим. Сервисы модели знают только клиент.
2. `agents/prompts.py`: extractor/generator/critic — отдельные функции-строители промптов, JSON-only контракт.
3. `repositories/jobs.py`: create (с ключом идемпотентности — возврат существующей), get, update_status.
4. `services/pipeline.py`: extract→generate→critique, петля переделки ≤3, возврат (draft, attempts, verdict).
5. `routers/cards.py` (синхронно), `routers/jobs.py` (202 + Idempotency-Key + GET статуса).
6. `temporal/`: `GenerationWorkflow` (activities extract/generate/critique + activity «писать статус в базу»), query статуса, сигналы approve/request_changes; `start_to_close` таймауты; activities — блокирующие → пул потоков воркера (`activity_executor=ThreadPoolExecutor`); песочница: импорты под `unsafe.imports_passed_through()`.
7. Makefile: `api`, `temporal-worker`.
8. Тесты (без модели и без временного сервера): пайплайн с mock-клиентом (успех / переделка / петля исчерпана); репозиторий jobs на живой тестовой БД (docker compose).

### Шаг 4. Строгий контракт результата
1. `schemas.py`: CardDraft (title ≤ 60 (валидатор), description, characteristics (без пустых значений), benefits, missing_fields, confidence 0..1, sources[]), SourceRef, SeoBlock (вложенный), CritiqueReport.
2. Передача JSON-схемы модели текстом (LM Studio strict не поддерживает) + «schema-first» в промпте.
3. `services/pipeline.py: parse_llm_json` — 4 случая: чистый JSON, JSON в ```-заборах, JSON с текстом до/после, мусор (ошибка). Тесты на все 4.
4. Лечение валидации: ValidationError → обратно в промпт с текстом ошибки, ≤3 попыток; точечная переделка одного поля (текущий черновик + имя поля).
5. Порог уверенности (`confidence_human_threshold`): ниже — статус `needs_human`, не «done».
6. Тесты: лечение невалидного JSON, пустой ответ → ошибка, длинный заголовок точечно переделывается, скудные данные → missing_fields непустой + низкий confidence.

### Шаг 5. Документы: разбор, нормализация, чанкинг, процесс
1. `routers/documents.py`: загрузка файла (проверка расширения/размера, 202 + id), GET статуса со счётчиком фрагментов; идемпотентность по (имя, sha256-размер) — повторная загрузка не создаёт дублей.
2. `migrations/0002_documents.sql`: documents, chunks (+ уникальность (document_id, ord)).
3. Пarsers: pdf (текст постранично), docx (параграфы, заголовок = граница секции), xlsx (шапка первой строки, иначе отказ; строка → «ART: параметр значение»),все единицы Know страницу.
4. normalize: колонтитулы (строки с ≥ порога доли повторов на страницах, с осторожностью на 2-страничных), дефис-переносы, лигатуры, пробелы.
5. chunking: текст ~`chunk_size` c `chunk_overlap`; таблицы — строками, заголовок колонки в каждой части; метаданные: document_id, page, section, article, brand.
6. `services/documents.py`, состояния: new→parsing→indexed / failed(причина). Отсканированный pdf (нет текстового слоя) → failed с внятной причиной.
7. Temporal: DocumentWorkflow (разбор в activity с потоком). Тесты на выданных файлах: ожидаемые строки достаются, таблицы не разорваны, boiler_scan.pdf → отказ.

### Шаг 6. Эмбеддинги и поиск
1. `embeddings/client.py`: embed_documents (префикс документа), embed_query (префикс запроса), батчами, кэш клиента; базовый URL из настроек.
2. `migrations/0003_vectors.sql`: chunks.embedding vector(768), chunks.tsv (сгенерируемая tsvector) Rubrika, HNSW (cosine ops) + GIN.
3. `repositories/documents.py`: bulk insert векторов, поиск по векторам (cosine), по словам (ts_rank), гибрид (RRF по позициям, оконные функции), фильтры метаданными, порог релевантности.
4. CLI-команда `make backfill` — досчёт векторов недосчитанным, с прогрессом, повторный запуск — пустая работа. Индексация — продолжение DocumentWorkflow.
5. Калибровка порога на своих запросах:֏ записать выбранное значение в PROGRESS.md.
6. Тесты на заделанных векторах (без загрузки модели): гибрид возвращает объединение, RRF ранжирует правильно, порог отсекает; тесты не ходят в LM Studio.

### Шаг 7. Сквозной сценарий
1. `routers/generators.py`: POST карточка по списку документов + подсказка → 202; GET результата со ссылками на источники (fragment_id).
2. `services/context.py`: маркированные источники «[S1] … [S2] …» (каждый фрагмент опознаваем), лимит `context_char_limit`, дедупликация одинаковых фрагментов, PII-mask, выброс инъекций (шаг 9 — внедрить позже, но интерфейс готов).
3. Промпты generator'а от контекста (обязанность ссылаться на [S#]), критика — сверять утверждение с источником.
4. Проверка ссылок: fragment существует ∧ был в контексте; нарушение → переделка с текстом ошибки, повтор — к человеку.
5. Состояния задачи расширяются: parsing→indexing→searching→generating→verifying→awaiting_human→approved/rejected.
6. Temporal: подготовка контекста, генерация, проверка ссылок, статус-activity, подтверждение человека.
7. Сквозной тест с mock-клиентом и предзаполненной тестовой базой (mock возвращает выдуманный fragment → ловим).
8. Ручной прогон на выданных документах.

### Шаг 8. Стоимость, трассировка, метрики
1. `migrations/0004_calls.sql`: model_calls(job_id, doc_id, role, model, prompt/completion tokens, cost, duration_ms, created_at, request_id).
2. Стоимость из usage прямо в клиенте (Decimal-копейки, не float), цены в настройках, запись в репозиторий после каждого вызова.
3. Трассировка: request_id middleware, job_id — аргументом в workflow, оба в контекст structlog у воркера.
4. Две модели: служебные роли → дешёвую (клиент знает role→model).
5. `services/metrics.py`: прогон по golden_cargs.json: считаем карточки, characteristics_match (нормализация: регистр, режем()['Вт' и т.п.], запятые), citing accuracy (source_probes по фрагментам), LLM-as-judge вердикт; отчёт в `reports/metrics_<date>.md`; Makefile `make metrics` / `make metrics-full`.
6. Тесты: расчёт стоимости, нормализация сравнения, промпт судьи на mock.

### Шаг 9. Защита
1. `guards/pii.py`: телефон (+7/8…), email, ИНН (10/12 цифр с контрольной суммой), карта Luhn → метки `{{PII:телефон 1}}`, выход — (текст, найденные категории).
2. Применение при сборке контекста — до промпта и до записи контекста в лог.
3. `guards/injection.py`: правила (приказы игнорировать, роли SYSTEM/assistant, «не говори пользователю», base64-подозрение); дешёвая модель по помеченным со структурированным вердиктом; fail-closed (модель недоступна → фрагмент подозрительный).
4. Порог подозрительных на документ (`max_suspicious_per_doc`) → документ entirely к человеку с причиной. В result задачи — отчёт (что исключено/замашено).
5. `guards/output.py`: карточка перед отдачей → детекторы PII-категорий + следы инструкций; найденное — правка карточки (переделка) / пометка.
6. Тесты: ловим инъекционный boiler/kettle-документ из набора; чистые не блокируем; INN с неверной суммой не трогаем; fail-closed; выход-фильтр.
7. Проверка логов прогона: контактов нет.

### Шаг 10. Сдача
1. README: установка с нуля за 15 минут (compose, migrate, api, temporal-worker, загрузка, генерация, curl-примеры).
2. Отчёт: последние метрики, стоимость карточки, слабейшая метрика + объяснение.
3. Прогон с нуля: `docker compose down -v`, миграции на чистой базе, полный сценарий.
4. Ответы на 8 вопросов защиты — письменно в отчёте (опираясь на код).

## Правила рабочей дисциплины

- После каждого шага: прогон тестов, коммит, `PROGRESS.md` с датой, что сделано, что проверено, какие найдены грабли (и как обошли), следующий шаг.
- Если что-то не получается (падение инструмента, сеть) — фиксировать в PROGRESS.md блок «Блокеры» и идти дальше по плану.
- Тесты не зависят от LM Studio; интеграционные проверки с моделью — помечены `@pytest.mark.integration`, запуск вручную.
