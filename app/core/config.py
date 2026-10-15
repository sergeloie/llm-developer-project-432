"""Типизированные настройки. Всё окружение — в одном Settings, из .env."""
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # LLM (LM Studio, OpenAI-compatible)
    llm_base_url: str = "http://127.0.0.1:1234/v1"
    llm_api_key: str = "lm-studio"
    llm_model_main: str = "qwen/qwen3.5-9b"
    llm_model_cheap: str = "qwen/qwen3-4b-2507"
    llm_timeout_s: float = 300.0
    llm_max_retries: int = 3
    llm_max_tokens: int = 2048
    llm_temperature: float = 0.3

    # Цены за 1M токенов, копейки (integer — без float-денег)
    price_main_input_kopek: int = 0
    price_main_output_kopek: int = 0
    price_cheap_input_kopek: int = 0
    price_cheap_output_kopek: int = 0

    # База
    database_url: str = "postgresql://card:card@localhost:5432/card"
    db_pool_min: int = 1
    db_pool_max: int = 8

    # Temporal
    temporal_host: str = "localhost:7233"
    temporal_task_queue: str = "card-queue"

    # Эмбеддинги
    emb_model: str = "text-embedding-embeddinggemma-300m"
    emb_dim: int = 768
    emb_timeout_s: float = 120.0
    emb_doc_prefix: str = "task: search document | "
    emb_query_prefix: str = "task: search result | "

    # Поиск и контекст
    search_relevance_threshold: float = 0.45
    context_char_limit: int = 12000
    search_top_k: int = 12

    # Чанкинг
    chunk_size: int = 1200
    chunk_overlap: int = 200

    # Контракт карточки
    card_title_max_len: int = 60
    confidence_human_threshold: float = 0.7
    max_repair_attempts: int = 3
    max_pipeline_rounds: int = 3

    # Защита
    max_suspicious_per_doc: int = 3

    # Файлы приёмки
    upload_max_mb: int = 20
    upload_allowed_ext: tuple[str, ...] = (".pdf", ".docx", ".xlsx")


settings = Settings()
