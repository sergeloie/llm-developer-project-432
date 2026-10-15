"""LLM-клиент — единственное место, которое знает про провайдера.

Ничего кроме политики доступа: адрес/ключ из настроек, таймаут, повтор
только на исправимых ошибках (429/5xx/сеть/таймаут) с растущей задержкой
и случайной примесью, учёт расхода токенов из ответа.

Сервисы вызывают `complete()` и не знают про LM Studio/OpenAI/HTTP.
Внутренний run-time повтора относится к ОДНОМУ вызову модели; повтор
всего шага — работа движка устойчивого исполнения (Temporal).
"""
import asyncio
import json
import random
import time
from dataclasses import dataclass
from typing import Any

import httpx

from app.core.config import settings
from app.core.logging import get_logger

log = get_logger(__name__)

_client: httpx.AsyncClient | None = None


@dataclass
class LlmResponse:
    text: str
    model: str
    role: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    duration_s: float = 0.0
    duration_ms: float = 0.0
    raw: dict | None = None


class LlmError(Exception):
    """Все ошибки вызова модели — один тип, различаем retryable-флагом."""

    def __init__(self, message: str, retryable: bool = False, status: int | None = None):
        super().__init__(message)
        self.retryable = retryable
        self.status = status


def role_model(role: str) -> str:
    """Политика двух моделей: main-роли — дорогая, служебные — дешёвая."""
    return settings.llm_model_main if role == "main" else settings.llm_model_cheap


def setup_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            base_url=settings.llm_base_url,
            timeout=httpx.Timeout(settings.llm_timeout_s, connect=10.0),
        )
    return _client


async def aclose_client() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


def messages(prompt: str, system: str | None = None) -> list[dict[str, str]]:
    msgs = []
    if system:
        msgs.append({"role": "system", "content": system})
    msgs.append({"role": "user", "content": prompt})
    return msgs


def _classify_status(status: int) -> bool:
    return status == 429 or status >= 500


async def complete(
    prompt: str,
    *,
    role: str = "main",
    system: str | None = None,
    temperature: float | None = None,
    max_tokens: int | None = None,
) -> LlmResponse:
    """Один вызов модели с политикой ретраев. role выбирает модель из настроек."""
    client = setup_client()
    model = role_model(role)
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages(prompt, system),
        "temperature": settings.llm_temperature if temperature is None else temperature,
        "max_tokens": max_tokens or settings.llm_max_tokens,
    }
    last_exc: LlmError | None = None
    for attempt in range(1, settings.llm_max_retries + 1):
        started = time.monotonic()
        try:
            resp = await client.post("/chat/completions", json=payload)
            duration_s = time.monotonic() - started
            if resp.status_code != 200:
                raise LlmError(
                    f"llm http {resp.status_code}: {resp.text[:300]}",
                    retryable=_classify_status(resp.status_code),
                    status=resp.status_code,
                )
            data = resp.json()
            choice = data.get("choices") or [{}]
            message = choice[0].get("message") or {}
            text = message.get("content") or ""
            usage = data.get("usage") or {}
            out = LlmResponse(
                text=text,
                model=data.get("model", model),
                role=role,
                prompt_tokens=int(usage.get("prompt_tokens", 0) or 0),
                completion_tokens=int(usage.get("completion_tokens", 0) or 0),
                total_tokens=int(usage.get("total_tokens", 0) or 0),
                duration_s=duration_s,
                duration_ms=duration_s * 1000,
                raw=data,
            )
            if out.total_tokens == 0:
                # сервер не отдал usage — грубая оценка 4 символа = 1 токен
                out.prompt_tokens = len(prompt) // 4
                out.completion_tokens = len(text) // 4
                out.total_tokens = out.prompt_tokens + out.completion_tokens
            log.info(
                "llm_call",
                model=out.model,
                role=role,
                prompt_tokens=out.prompt_tokens,
                completion_tokens=out.completion_tokens,
                duration_ms=round(out.duration_ms, 1),
            )
            return out
        except LlmError as exc:
            last_exc = exc
        except httpx.TimeoutException as exc:
            duration_s = time.monotonic() - started
            last_exc = LlmError(f"timeout after {duration_s:.1f}s: {exc}", retryable=True)
        except (httpx.TransportError, json.JSONDecodeError) as exc:
            last_exc = LlmError(f"transport/json error: {exc}", retryable=True)
        if attempt == settings.llm_max_retries or not last_exc.retryable:
            log.error("llm_call_failed", role=role, model=model, attempt=attempt, error=str(last_exc))
            raise last_exc
        delay = min(2**attempt * 0.5, 8.0) * (1 + random.random() * 0.25)
        log.warning("llm_call_retry", role=role, model=model, attempt=attempt, delay_s=round(delay, 2), error=str(last_exc))
        await asyncio.sleep(delay)
    raise LlmError("unreachable retries state", retryable=False)
