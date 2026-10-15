from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    llm_base_url: str = "http://localhost:1234/v1"
    llm_api_key: str = "lm-studio"
    llm_model: str = "gemma-4-e4b-it"
    llm_model_cheap: str = "gemma-4-e4b-it"
    llm_timeout_s: float = 120.0
    llm_max_retries: int = 3

    database_url: str = "postgresql://card:card@localhost:5433/card"
    # Пул уровня приложения: min держится открытым, max ограничивает нагрузку на базу.
    db_pool_min: int = 1
    db_pool_max: int = 10
    temporal_host: str = "localhost:7233"
    temporal_task_queue: str = "card-queue"
    # Блокирующие шаги (база, парсеры) исполняются в пуле потоков воркера.
    temporal_activity_threads: int = 4

    embed_model: str = "google/embeddinggemma-300m"
    embed_dim: int = 768
    # cpu по умолчанию: на Apple Metal (mps) PyTorch падает в дочерних процессах
    # ("crashed on child side of fork pre-exec"). mps — только для запуска скриптом.
    embed_device: str = "cpu"

    retrieval_top_k: int = 5
    # embeddinggemma-300m даёт косинус 0.4-0.55 для релевантных пар:
    # порог выше — режет нормальные результаты, ниже — пускает мусор
    retrieval_threshold: float = 0.35

    suspicious_chunk_limit: int = 2

    # Цены за 1M токенов, USD. Ключи — имена моделей.
    llm_prices: dict[str, dict[str, float]] = {
        "default": {"input": 0.0, "output": 0.0},
    }


settings = Settings()
