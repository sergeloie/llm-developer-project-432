"""Учёт вызовов модели: токены, стоимость, длительность."""

import psycopg

from app.core.config import settings


def calculate_cost_kopek(model: str, prompt_tokens: int, completion_tokens: int) -> int:
    """Стоимость из usage и цен в настройках (целые копейки)."""
    if "cheap" in model or "4b" in model:
        pin = settings.price_cheap_input_kopek
        pout = settings.price_cheap_output_kopek
    else:
        pin = settings.price_main_input_kopek
        pout = settings.price_main_output_kopek
    # цены заданы за 1M токенов, в копейках
    cost = (prompt_tokens * pin + completion_tokens * pout) // 1_000_000
    return int(cost)


# алиасы для возможных имён в тестах
def cost_kopek(*args, **kwargs):
    return calculate_cost_kopek(*args, **kwargs)


def calculate_cost(*args, **kwargs):
    return calculate_cost_kopek(*args, **kwargs)


def record_call(
    conn: psycopg.Connection,
    *,
    role: str,
    model: str,
    prompt_tokens: int = 0,
    completion_tokens: int = 0,
    cost_kopek: int | None = None,
    duration_ms: int = 0,
    job_id: str | None = None,
    doc_id: int | None = None,
    request_id: str | None = None,
) -> None:
    if cost_kopek is None:
        cost_kopek = calculate_cost_kopek(model, prompt_tokens, completion_tokens)
    conn.execute(
        """
        INSERT INTO model_calls
            (job_id, doc_id, role, model, prompt_tokens, completion_tokens,
             cost_kopek, duration_ms, request_id)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (
            job_id,
            doc_id,
            role,
            model,
            prompt_tokens,
            completion_tokens,
            cost_kopek,
            duration_ms,
            request_id,
        ),
    )
