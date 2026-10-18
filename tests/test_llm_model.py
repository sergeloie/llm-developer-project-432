"""Регрессия: модель из конфига резолвится в объект SDK, а не в строку.

Строка `qwen/qwen3.5-9b` в Agent(model=...) уходит в MultiProvider, который
читает её как provider-qualified имя и падает с UserError("Unknown prefix: qwen")
до какого-либо HTTP-запроса. Идентификатор при этом остаётся ровно тем, что
объявил сервер в GET /v1/models, — чинить это обрезанием префикса нельзя.
"""
import asyncio

import agents
import pytest
from agents.exceptions import UserError
from agents.models.multi_provider import MultiProvider

from app.agents.prompts import (
    context_generator_agent,
    critic_agent,
    extractor_agent,
    fixer_agent,
    generator_agent,
)
from app.core.config import settings
from app.llm.client import model_for, model_name, setup_llm

CARD_AGENTS = [
    extractor_agent,
    generator_agent,
    context_generator_agent,
    fixer_agent,
    critic_agent,
]


def test_prefixed_id_alone_is_rejected_by_sdk():
    """Якорь регрессии: именно это поведение ломало каждый вызов модели."""
    with pytest.raises(UserError):
        MultiProvider().get_model(settings.llm_model)


def test_model_for_returns_chat_completions_model():
    model = model_for(settings.llm_model)
    assert isinstance(model, agents.OpenAIChatCompletionsModel)
    assert model.model == settings.llm_model


def test_model_for_is_cached_per_name():
    assert model_for(settings.llm_model) is model_for(settings.llm_model)
    assert model_for(settings.llm_model_cheap) is not model_for(settings.llm_model)


def test_model_for_uses_client_from_setup_llm():
    client = model_for(settings.llm_model)._client
    assert client is setup_llm()
    assert str(client.base_url).rstrip("/") == settings.llm_base_url.rstrip("/")


def test_model_name_unwraps_model_object():
    assert model_name(model_for(settings.llm_model)) == settings.llm_model
    assert model_name(settings.llm_model) == settings.llm_model
    assert model_name(None) == ""


def test_card_agents_are_bound_to_model_objects():
    # Генерация/извлечение — на основной модели; критик — на дешёвой (он лишь
    # фильтр-проверка, как детектор инъекций и судья), это экономит токены.
    for agent in CARD_AGENTS:
        assert isinstance(agent.model, agents.OpenAIChatCompletionsModel), agent.name
        expected = settings.llm_model_cheap if agent.name == "Critic" else settings.llm_model
        assert model_name(agent.model) == expected, agent.name


def test_injection_detector_agent_is_bound_to_cheap_model(monkeypatch):
    import app.llm.client as llm_client
    from app.guardrails import injection

    built: list = []

    async def fake_run_agent(agent, prompt):
        built.append(agent)
        raise _StopNow

    monkeypatch.setattr(llm_client, "run_agent", fake_run_agent)
    with pytest.raises(_StopNow):
        asyncio.run(injection.detect_injection_llm("обычный текст поставщика"))

    assert built
    for agent in built:
        assert isinstance(agent.model, agents.OpenAIChatCompletionsModel)
        assert model_name(agent.model) == settings.llm_model_cheap


def test_eval_judge_agent_is_bound_to_cheap_model():
    from evals import judge

    assert isinstance(judge._judge_agent.model, agents.OpenAIChatCompletionsModel)
    assert model_name(judge._judge_agent.model) == settings.llm_model_cheap


class _StopNow(Exception):
    pass