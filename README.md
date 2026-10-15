# product-card-ai

Сервис принимает документы поставщиков (pdf, docx, xlsx), строит по ним поисковый индекс
и генерирует черновик карточки товара — с указанием источников, списком недостающих
данных и уровнем уверенности.

## Требования

- Python 3.12
- Docker и Docker Compose
- OpenAI-совместимый сервер моделей: LM Studio, Ollama или OpenRouter

Модель эмбеддингов `google/embeddinggemma-300m` скачивается с Hugging Face при первом
запуске, примерно 600 МБ.

## Запуск

```bash
cp .env.example .env # и вписать адрес своего сервера моделей
make infra           # postgres с pgvector и движок процессов
make setup           # зависимости и миграции
make api             # http://localhost:8000
make worker          # разбор документов и генерация карточек
```

Проверка, что всё поднялось:

```bash
curl localhost:8000/health # жив ли процесс
curl localhost:8000/ready  # доступна ли база и включено ли расширение vector
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
make test     # pytest
make lint     # ruff
make lint-fix # ruff с правкой
make eval     # метрики генерации на золотом датасете (FULL=1 — весь датасет)
make reindex  # досчитать эмбеддинги для чанков без векторов
make bulk     # загрузить все файлы из data/ через POST /documents
```

## Структура

```text
app/
├── main.py            приложение и сквозной request_id
├── core/              конфигурация, база, логи, раннер миграций
├── routers/           HTTP: cards, jobs, documents, generate, workflows, health, metrics
├── services/          сценарии: пайплайн генерации, разбор документов, контракт результата
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
