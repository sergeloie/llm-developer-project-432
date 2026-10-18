"""Конфигурация приложения: единственный источник всех переключателей.

Каждый параметр переопределяется переменной окружения с тем же именем в
верхнем регистре (или записью в .env). Таблица цен собирается из
PRICE_<KEY>_INPUT / PRICE_<KEY>_OUTPUT — цена за 1M токенов в USD;
<KEY> — идентификатор модели, приведённый к виду price_key().
"""
import os
import re

from dotenv import dotenv_values
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_FILE = ".env"
PRICE_ENV_RE = re.compile(r"^PRICE_(?P<key>[A-Z0-9_]+)_(?P<side>INPUT|OUTPUT)$")
PRICE_SIDES = ("input", "output")
DEFAULT_PRICES = {"default": {"input": 0.0, "output": 0.0}}


def price_key(model: str) -> str:
    """Идентификатор модели → ключ таблицы цен (тот же, что у PRICE_<KEY>_*)."""
    return re.sub(r"[^a-z0-9]+", "_", model.lower()).strip("_")


def _prices_from_sources() -> dict[str, dict[str, float]]:
    """PRICE_<KEY>_INPUT / PRICE_<KEY>_OUTPUT из .env и окружения → таблица цен."""
    collected: dict[str, dict[str, float]] = {}
    for source in (dict(os.environ), dotenv_values(ENV_FILE)):
        for name, raw in source.items():
            match = PRICE_ENV_RE.match(name)
            if match is None or raw is None or str(raw).strip() == "":
                continue
            collected.setdefault(match.group("key").lower(), {})[match.group("side").lower()] = float(raw)
    default_side = DEFAULT_PRICES["default"]
    for entry in collected.values():
        for side in PRICE_SIDES:
            entry.setdefault(side, default_side[side])
    return collected


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ENV_FILE, extra="ignore")

    llm_base_url: str = "http://127.0.0.1:1234/v1"
    llm_api_key: str = "lm-studio"
    llm_model: str = "qwen/qwen3.5-9b"
    llm_model_cheap: str = "qwen/qwen3-4b-2507"
    llm_timeout_s: float = 300.0
    llm_max_retries: int = 3
    llm_max_tokens: int = 4096
    llm_temperature: float = 0.4

    database_url: str = "postgresql://card:card@localhost:5433/card"
    # Пул уровня приложения: min держится открытым, max ограничивает нагрузку на базу.
    db_pool_min: int = 1
    db_pool_max: int = 10
    temporal_host: str = "localhost:7233"
    temporal_task_queue: str = "card-queue"
    # Блокирующие шаги (база, парсеры) исполняются в пуле потоков воркера.
    temporal_activity_threads: int = 4

    # lmstudio — OpenAI-совместимый API эмбеддингов (модель уже загружена
    # локально, ничего не скачивается); hf|sentence-transformers — локальная
    # модель из sentence_transformers, качает веса при первом запуске.
    embed_backend: str = "lmstudio"
    embed_base_url: str = ""
    embed_api_key: str = ""
    embed_model: str = "text-embedding-embeddinggemma-300m"
    embed_dim: int = 768
    embed_timeout_s: float = 300.0
    # cpu по умолчанию: на Apple Metal (mps) PyTorch падает в дочерних процессах
    # ("crashed on child side of fork pre-exec"). mps — только для запуска скриптом.
    embed_device: str = "cpu"

    retrieval_top_k: int = 5
    # Порог косинуса для векторной ноги гибридного поиска (embeddinggemma-300m).
    # Замер на реальном корпусе поставщиков (docs/retrieval-threshold-measurement.md):
    # релевантные и нерелевантные чанки перекрываются по cosine (~0.37-0.54), чистого
    # разделителя нет, поэтому чистота топ-K стабильна (~0.91 гибрид / ~0.99 вектор)
    # во всём диапазоне 0.25-0.4 и падает только при 0.5 (режет релевантное).
    # 0.35 — середина стабильного плато; остаточная примесь в топ-K идёт от FTS-ноги
    # (OR-семантика), а не от порога. Путь генерации по явно заданным document_ids
    # порог не использует вообще — берёт все чанки документа (иначе similarity-top-k
    # отбрасывает до 75% текста, баг с heater_passport).
    retrieval_threshold: float = 0.35

    suspicious_chunk_limit: int = 2

    # Отладочный эндпоинт /debug и его UI отдают трассу пайплайна (чанки, промпты,
    # ответы LLM) без авторизации. В разработке удобно, в проде — выключить, иначе
    # внутренние данные пайплайна доступны по HTTP любому. По умолчанию вкл.
    debug_endpoints_enabled: bool = True

    upload_max_mb: int = 10
    chunk_size: int = 700
    chunk_overlap: int = 120
    context_char_limit: int = 6000
    card_title_max_len: int = 60
    confidence_human_threshold: float = 0.5
    # Доля характеристик, для которых нашёлся источник, ниже которой карточка
    # уходит человеку. 0.8 прощает одну неподтверждённую характеристику из
    # пяти и не прощает карточку, где половина атрибутов ничем не подкреплена.
    source_coverage_threshold: float = 0.8
    max_fix_attempts: int = 2
    max_pipeline_rounds: int = 3

    # Цены за 1M токенов, USD. Ключи — имена моделей (см. price_key).
    llm_prices: dict[str, dict[str, float]] = DEFAULT_PRICES

    @model_validator(mode="before")
    @classmethod
    def _load_prices(cls, data):
        if not isinstance(data, dict):
            return data
        collected = _prices_from_sources()
        if collected:
            data["llm_prices"] = {**DEFAULT_PRICES, **collected}
        return data


settings = Settings()
