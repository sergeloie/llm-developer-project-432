"""LLM-клиент как слой: обёртка над OpenAI Agents SDK с таймаутом и ретраями.

Единственное место в приложении, которое знает про LLM API:
retry-политика, таймаут, различие ошибок 429/5xx/4xx — всё здесь.
Сервисы вызывают run_agent() и не знают про провайдера.
"""
import asyncio
import json
import random
import time

from agents import (
    Agent,
    OpenAIChatCompletionsModel,
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
_client: AsyncOpenAI | None = None
_models: dict[str, OpenAIChatCompletionsModel] = {}


def setup_llm() -> AsyncOpenAI:
    """Инициализация OpenAI-compatible клиента (LM Studio / OpenRouter / Ollama).

    Вызывается один раз на процесс. Идемпотентна. Возвращает тот самый клиент,
    на котором дальше строятся модели (model_for).
    """
    global _client_configured, _client
    if _client_configured and _client is not None:
        return _client
    _client = AsyncOpenAI(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        timeout=settings.llm_timeout_s,
    )
    set_default_openai_client(_client)
    # LM Studio / Ollama / OpenRouter говорят на Chat Completions, а не Responses API.
    set_default_openai_api("chat_completions")
    # Иначе SDK шлёт спаны на api.openai.com и падает с 401 на не-OpenAI ключе.
    set_tracing_disabled(True)
    _client_configured = True
    return _client


def model_for(name: str) -> OpenAIChatCompletionsModel:
    """Модель по имени из конфига как объект SDK, а не как строка.

    Строка в Agent(model=...) уходит в MultiProvider, который читает
    `префикс/имя` как provider-qualified идентификатор и падает с
    UserError("Unknown prefix: qwen") до какого-либо HTTP-запроса.
    Объект модели с явным клиентом этого разбора не проходит, а имя
    (`qwen/qwen3.5-9b`) уходит на сервер ровно тем же текстом, каким его
    объявили в GET /v1/models. По одному имени на процесс кешируется
    один объект.
    """
    existing = _models.get(name)
    if existing is not None:
        return existing
    built = OpenAIChatCompletionsModel(model=name, openai_client=setup_llm())
    _models[name] = built
    return built


def model_name(model: object) -> str:
    """Имя модели для учёта в llm_calls: у объекта берём .model, строка — сама."""
    if isinstance(model, str):
        return model
    inner = getattr(model, "model", None)
    return inner if isinstance(inner, str) else ""


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
    Каждый успешный вызов пишется в llm_calls (токены, цена, латентность),
    каждая исчерпанная попытка — тоже, с outcome='error': без этого доля
    неудачных ответов модели считалась бы по логам, а не по таблице.
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
            _trace_step(agent, prompt, result)
            return result.final_output
        except Exception as exc:
            if attempt == max_retries or not _retryable(exc):
                _log_failure(agent, exc, started, attempt)
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


def _log_failure(agent: Agent, exc: Exception, started: float, attempt: int) -> None:
    """Учёт неуспешной попытки в llm_calls. Ошибки учёта не роняют пайплайн."""
    try:
        import structlog

        from app.core import db
        from app.repositories import llm_calls as repo

        model = model_name(getattr(agent, "model", None))
        latency_ms = int((time.monotonic() - started) * 1000)
        ctx = structlog.contextvars.get_contextvars() or {}
        with db.connection() as conn:
            repo.insert_call(
                conn,
                job_id=ctx.get("job_id"),
                agent=getattr(agent, "name", ""),
                model=model,
                prompt_tokens=None,
                completion_tokens=None,
                cost=0.0,
                latency_ms=latency_ms,
                outcome="error",
                error=repr(exc)[:500],
            )
        log.info(
            "llm_call_error",
            agent=getattr(agent, "name", ""),
            model=model,
            attempt=attempt,
            latency_ms=latency_ms,
            error=repr(exc)[:500],
        )
    except Exception as accounting_exc:
        log.warning("llm_call_accounting_failed", error=repr(accounting_exc))


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
        model = model_name(getattr(agent, "model", None))
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


def _extract_raw_text(result) -> str | None:
    """Сырой текст ответа модели из RunResult: лучшие усилия, не роняет пайплайн."""
    try:
        responses = getattr(result, "raw_responses", None)
        if not responses:
            return None
        parts: list[str] = []
        for resp in responses:
            output = getattr(resp, "output", None)
            if not output:
                continue
            for item in output:
                content = getattr(item, "content", None)
                if not content:
                    continue
                for block in content:
                    text = getattr(block, "text", None)
                    if text:
                        parts.append(text)
        return "\n".join(parts) if parts else None
    except Exception:
        return None


def _normalize_parsed(out):
    """final_output → JSON-совместимый объект для колонки jsonb."""
    try:
        if hasattr(out, "model_dump"):
            return out.model_dump()
        if isinstance(out, (dict, list)):
            return out
        if isinstance(out, str):
            try:
                return json.loads(out)
            except Exception:
                return {"text": out}
    except Exception:
        return None
    return None


def _trace_step(agent: Agent, prompt: str, result) -> None:
    """Шаг трассы пайплайна в pipeline_traces: промпт, сырой ответ, распарсенный вывод.

    Пишется только для вызовов внутри job (job_id есть в structlog-контексте) —
    иначе смотреть не на что. Ошибки учёта не роняют пайплайн.
    """
    try:
        import structlog

        from app.core import db
        from app.repositories import traces as repo

        ctx = structlog.contextvars.get_contextvars() or {}
        job_id = ctx.get("job_id")
        if not job_id:
            return
        instructions = getattr(agent, "instructions", None)
        prompt_text = (
            f"# System\n{instructions}\n\n# User\n{prompt}" if instructions else prompt
        )
        usage = None
        responses = getattr(result, "raw_responses", None)
        if responses:
            usage_obj = getattr(responses[-1], "usage", None)
            if usage_obj:
                usage = {
                    "input_tokens": getattr(usage_obj, "input_tokens", None),
                    "output_tokens": getattr(usage_obj, "output_tokens", None),
                }
        with db.connection() as conn:
            repo.insert_step(
                conn,
                job_id=job_id,
                step=getattr(agent, "name", "agent"),
                agent=getattr(agent, "name", None),
                model=model_name(getattr(agent, "model", None)),
                prompt_text=prompt_text,
                raw_completion=_extract_raw_text(result),
                parsed_output=_normalize_parsed(getattr(result, "final_output", None)),
                usage_json=usage,
            )
    except Exception as exc:
        log.warning("trace_accounting_failed", error=repr(exc))
