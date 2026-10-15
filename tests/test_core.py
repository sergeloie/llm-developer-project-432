"""Smoke-тесты каркаса: конфиг и логгер поднимаются, значения на месте."""
from app.core.config import settings
from app.core.logging import get_logger, setup_logging


def test_settings_load_defaults():
    assert settings.database_url.startswith("postgresql://")
    assert ":" in settings.temporal_host
    assert settings.llm_base_url.startswith("http")
    assert settings.embed_dim > 0


def test_logger_setup_idempotent():
    setup_logging()
    setup_logging()
    log = get_logger("smoke")
    log.info("smoke_ok")
