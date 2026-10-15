"""Расчёт стоимости LLM-вызовов: цены за 1M токенов в конфиге."""
from decimal import Decimal

from app.core.config import settings


def calculate_cost(model: str, prompt_tokens: int, completion_tokens: int) -> Decimal:
    """Цена вызова. Неизвестная модель — цены 'default' (для локальных — 0)."""
    prices = settings.llm_prices.get(model, settings.llm_prices["default"])
    cost = (
        Decimal(str(prompt_tokens)) * Decimal(str(prices["input"]))
        + Decimal(str(completion_tokens)) * Decimal(str(prices["output"]))
    ) / Decimal(1_000_000)
    return cost.quantize(Decimal("0.000001"))
