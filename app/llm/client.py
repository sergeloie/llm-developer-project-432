"""LLM-клиент как слой: обёртка над OpenAI Agents SDK с таймаутом и ретраями.

Единственное место в приложении, которое знает про LLM API:
retry-политика, таймаут, различие ошибок 429/5xx/4xx — всё здесь.
Сервисы вызывают run_agent() и не знают про провайдера.
"""
import asyncio
import random
import time

from agents import (
    Agent,
    Runner,
    set_default_openai_api,
    set_default_openai_client,
    set_tracing_disabled,
)
from openai import APIConnectionError, APIStatusError, APITimeoutError, AsyncOpenAI

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

_client_configured = False


def setup_llm() -> None:
    """Инициализация OpenAI-compatible клиента (LM Studio / OpenRouter / Ollama).

    Вызывается один раз на процесс. Идемпотентна.
    """
    global _client_configured
    if _client_configured:
        return
    client = AsyncOpenAI(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        timeout=settings.llm_timeout_s,
    )
    set_default_openai_client(client)
    # LM Studio / Ollama / OpenRouter говорят на Chat Completions, а не Responses API.
    set_default_openai_api("chat_completions")
    # Иначе SDK шлёт спаны на api.openai.com и падает с 401 на не-OpenAI ключе.
    set_tracing_disabled(True)
    _client_configured = True


def _retryable(exc: Exception) -> bool:
    """Ретраим только транспортные и серверные ошибки: 429, 5xx, таймаут, сеть.

    4xx (кроме 429) — ошибка запроса, повтор бессмыслен.
    """
    if isinstance(exc, (APIConnectionError, APITimeoutError)):
        return True
    if isinstance(exc, APIStatusError):
        return exc.status_code == 429 or exc.status_code >= 500
    return False


async def run_agent(agent: Agent, prompt: str):
    """Запустить агента с ретраями: экспоненциальная задержка + jitter.

    Ретраится только вызов LLM — не вся бизнес-задача (это работа очереди/workflow).
    Каждый успешный вызов пишется в llm_calls (токены, цена, латентность).
    """
    setup_llm()
    max_retries = settings.llm_max_retries
    for attempt in range(1, max_retries + 1):
        started = time.monotonic()
        try:
            result = await asyncio.wait_for(
                Runner.run(agent, prompt), timeout=settings.llm_timeout_s
            )
            _log_call(agent, result, started)
            return result.final_output
        except Exception as exc:
            if attempt == max_retries or not _retryable(exc):
                log.error(
                    "llm_call_failed",
                    agent=agent.name,
                    attempt=attempt,
                    error=repr(exc),
                )
                raise
            delay = min(2**attempt * 0.5, 8.0) * (1 + random.random() * 0.25)
            log.warning(
                "llm_call_retry",
                agent=agent.name,
                attempt=attempt,
                delay_s=round(delay, 2),
                error=repr(exc),
            )
            await asyncio.sleep(delay)


def _log_call(agent: Agent, result, started: float) -> None:
    """Учёт вызова в llm_calls. Ошибки учёта не должны ронять пайплайн."""
    try:
        import structlog

        from app.core import db
        from app.llm.cost import calculate_cost
        from app.repositories import llm_calls as repo

        usage = getattr(result, "raw_responses", None)
        usage = getattr(usage[-1], "usage", None) if usage else None
        prompt_tokens = getattr(usage, "input_tokens", None) if usage else None
        completion_tokens = getattr(usage, "output_tokens", None) if usage else None
        model = getattr(agent, "model", "") or ""
        cost = float(
            calculate_cost(model, prompt_tokens or 0, completion_tokens or 0)
        )
        latency_ms = int((time.monotonic() - started) * 1000)
        # job_id пробрасывается через structlog-контекст воркером
        ctx = structlog.contextvars.get_contextvars() or {}
        job_id = ctx.get("job_id")
        with db.connection() as conn:
            repo.insert_call(
                conn,
                job_id=job_id,
                agent=getattr(agent, "name", ""),
                model=model,
                prompt_tokens=prompt_tokens,
                completion_tokens=completion_tokens,
                cost=cost,
                latency_ms=latency_ms,
            )
        log.info(
            "llm_call",
            agent=getattr(agent, "name", ""),
            model=model,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            cost=cost,
            latency_ms=latency_ms,
        )
    except Exception as exc:
        log.warning("llm_call_accounting_failed", error=repr(exc))
