"""Таблица цен: PRICE_<MODEL>_INPUT / PRICE_<MODEL>_OUTPUT → settings.llm_prices."""
from decimal import Decimal

from app.core.config import Settings, price_key


def test_price_key_normalizes_model_id():
    assert price_key("qwen/qwen3.5-9b") == "qwen_qwen3_5_9b"
    assert price_key("default") == "default"


def test_prices_collected_from_env(monkeypatch):
    monkeypatch.setenv("PRICE_QWEN_QWEN3_5_9B_INPUT", "0.6")
    monkeypatch.setenv("PRICE_QWEN_QWEN3_5_9B_OUTPUT", "2.4")

    loaded = Settings()

    assert loaded.llm_prices["qwen_qwen3_5_9b"] == {"input": 0.6, "output": 2.4}
    assert loaded.llm_prices["default"] == {"input": 0.0, "output": 0.0}


def test_missing_side_falls_back_to_default(monkeypatch):
    monkeypatch.setenv("PRICE_MAIN_INPUT", "1.5")
    monkeypatch.delenv("PRICE_MAIN_OUTPUT", raising=False)

    loaded = Settings()

    assert loaded.llm_prices["main"] == {"input": 1.5, "output": 0.0}


def test_cost_uses_price_from_env(monkeypatch):
    from app.llm import cost

    monkeypatch.setenv("PRICE_MAIN_INPUT", "1.5")
    monkeypatch.setenv("PRICE_MAIN_OUTPUT", "3")
    monkeypatch.setattr(cost, "settings", Settings())

    assert cost.calculate_cost("main", 1_000_000, 1_000_000) == Decimal("4.5")