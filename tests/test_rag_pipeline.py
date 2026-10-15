"""E2E-тест RAG-пайплайна: чанки в БД → retrieval → генерация (мок LLM) → sources."""
import asyncio
import os

import pytest

from app.core import db
from app.rag.context import build_context, context_chunk_ids

pytestmark = pytest.mark.skipif(
    os.environ.get("PCA_SKIP_DB_TESTS") == "1", reason="PCA_SKIP_DB_TESTS=1"
)

DOC_ID = "tstrag1"
CHUNK_TEXTS = {
    "tstragc1": "Блендер МиксерПро 800. Мощность: 800 Вт. Скоростей: 6. Артикул: BLD-800.",
    "tstragc2": "Гарантия 24 месяца. Цвет графитовый. Комплект: венчик, стакан 1,5 л.",
}


@pytest.fixture(scope="module")
def rag_setup():
    with db.connection() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM chunks WHERE doc_id = %s", (DOC_ID,))
            cur.execute("DELETE FROM documents WHERE id = %s", (DOC_ID,))
            cur.execute(
                "INSERT INTO documents (id, filename, content_hash, kind, status) "
                "VALUES (%s, 'blender_test.pdf', 'hash-rag-test', 'pdf', 'indexed')",
                (DOC_ID,),
            )
            for i, (cid, text) in enumerate(CHUNK_TEXTS.items()):
                cur.execute(
                    "INSERT INTO chunks (id, doc_id, ordinal, text, metadata) VALUES (%s,%s,%s,%s,%s)",
                    (cid, DOC_ID, i, text, f'{{"doc_id": "{DOC_ID}", "page": 1}}'),
                )
        yield conn
        with conn.cursor() as cur:
            cur.execute("DELETE FROM chunks WHERE doc_id = %s", (DOC_ID,))
            cur.execute("DELETE FROM documents WHERE id = %s", (DOC_ID,))


def test_build_context_headers(rag_setup):
    chunks = [
        {"chunk_id": cid, "text": txt, "metadata": {"doc_id": DOC_ID, "page": 2, "section": "Характеристики"}, "filename": "f.pdf"}
        for cid, txt in CHUNK_TEXTS.items()
    ]
    ctx = build_context(chunks)
    # Подписи в шапке («стр.», «page») на любом языке, важны сами данные источника
    header = next(line for line in ctx.splitlines() if line.startswith("[tstragc1"))
    for part in ("f.pdf", "2", "Характеристики"):
        assert part in header
    assert "Мощность: 800 Вт" in ctx
    assert context_chunk_ids(ctx) == set(CHUNK_TEXTS)


def test_build_context_limit(rag_setup):
    chunks = [
        {"chunk_id": f"c{i}", "text": "x" * 4000, "metadata": {}, "filename": "f.pdf"}
        for i in range(5)
    ]
    ctx = build_context(chunks, max_chars=6000)
    assert len(ctx) < 6000 + 100  # жёсткий потолок соблюдён (хвост не рвём)


def _fake_retrieve(context: str, scores: dict[str, float] | None = None):
    from app.guardrails import GuardReport

    async def _retrieve(q, doc_ids=None):
        return context, GuardReport(retrieval_scores=scores or {})
    return _retrieve


def _patch_llm(monkeypatch, card_json: str, verdict="approve"):
    from app.services import rag_pipeline as rp

    calls = {"n": 0}

    async def fake_run(agent, prompt):
        calls["n"] += 1
        if agent.name == "Critic":
            from app.schemas.cards import CritiqueReport

            return CritiqueReport(verdict=verdict).model_dump_json()
        return card_json

    monkeypatch.setattr(rp, "run_agent", fake_run)
    return calls


def _card_json(chunk_id="tstragc1", confidence=0.9):
    import json

    return json.dumps(
        {
            "title": "МиксерПро 800 — блендер 800 Вт",
            "description": "Погружной блендер.",
            "characteristics": {"Мощность": "800 Вт"},
            "benefits": ["Металлическая ножка"],
            "seo": {"title": "Блендер", "description": "Мощный", "keywords": ["блендер"]},
            "sources": [{"chunk_id": chunk_id, "page": 1}],
            "missing_fields": [],
            "confidence": confidence,
        },
        ensure_ascii=False,
    )


def test_rag_pipeline_happy_path(rag_setup, monkeypatch):
    from app.services import rag_pipeline as rp

    _patch_llm(monkeypatch, _card_json())
    # retrieval мокаем на уровне retrieve_context — гибрид с gemma тут не нужен
    monkeypatch.setattr(
        rp, "retrieve_context_async",
        _fake_retrieve("[tstragc1 | blender_test.pdf | стр. 1]\n" + CHUNK_TEXTS["tstragc1"]),
    )
    draft, attempts, verdict, status, _guard, context = asyncio.run(
        rp.run_rag_pipeline("блендер", [DOC_ID])
    )
    assert verdict == "approved" and attempts == 1
    assert status == "done"
    # контекст в ответе — тот самый, что ушёл в модель
    assert context == "[tstragc1 | blender_test.pdf | стр. 1]\n" + CHUNK_TEXTS["tstragc1"]
    assert draft.sources[0].chunk_id == "tstragc1"
    # источник реально существует в БД
    from app.repositories import chunks as chunks_repo

    assert "tstragc1" in chunks_repo.get_chunks_by_ids(rag_setup, ["tstragc1"])


def test_rag_pipeline_hallucinated_source_rejected(rag_setup, monkeypatch):
    """Модель сослалась на выдуманный chunk_id → ретрай → финальная ошибка."""
    from app.services import rag_pipeline as rp

    _patch_llm(monkeypatch, _card_json(chunk_id="deadbeef0000"))
    monkeypatch.setattr(
        rp, "retrieve_context_async",
        _fake_retrieve("[tstragc1 | blender_test.pdf]\n" + CHUNK_TEXTS["tstragc1"]),
    )
    with pytest.raises(rp.SourceValidationError):
        asyncio.run(rp.run_rag_pipeline("блендер", [DOC_ID]))


def test_rag_pipeline_low_confidence_needs_review(rag_setup, monkeypatch):
    from app.services import rag_pipeline as rp

    _patch_llm(monkeypatch, _card_json(confidence=0.3))
    monkeypatch.setattr(
        rp, "retrieve_context_async",
        _fake_retrieve("[tstragc1 | blender_test.pdf]\n" + CHUNK_TEXTS["tstragc1"]),
    )
    _draft, _a, _v, status, _g, _context = asyncio.run(rp.run_rag_pipeline("блендер", [DOC_ID]))
    assert status == "needs_review"


def test_sources_carry_document_page_and_retrieval_score(rag_setup, monkeypatch):
    """Источник — это пара «документ + фрагмент» плюс оценка retrieval.

    Модель doc_id и score не видит и выдумывать их не должна: подставляет их
    пайплайн из БД и из результата поиска.
    """
    from app.services import rag_pipeline as rp

    _patch_llm(monkeypatch, _card_json())
    monkeypatch.setattr(
        rp, "retrieve_context_async",
        _fake_retrieve(
            "[tstragc1 | blender_test.pdf | стр. 1]\n" + CHUNK_TEXTS["tstragc1"],
            {"tstragc1": 0.5123},
        ),
    )
    draft, *_rest = asyncio.run(rp.run_rag_pipeline("блендер", [DOC_ID]))

    source = draft.sources[0]
    assert source.doc_id == DOC_ID
    assert source.chunk_id == "tstragc1"
    assert source.page == 1
    assert source.score == 0.5123


def test_mark_chunks_suspicious_persists_guard_verdict(rag_setup):
    """Заблокированный фрагмент помечается в БД иначе он неотличим от обычного."""
    from app.repositories import documents as docs_repo

    docs_repo.mark_chunks_suspicious(rag_setup, ["tstragc2"])
    with rag_setup.cursor() as cur:
        cur.execute(
            "SELECT id, suspicious FROM chunks WHERE doc_id = %s ORDER BY id", (DOC_ID,)
        )
        flags = dict(cur.fetchall())
    assert flags["tstragc2"] is True
    assert flags["tstragc1"] is False
    docs_repo.mark_chunks_suspicious(rag_setup, ["tstragc1", "tstragc2"])


def test_critic_prompt_declares_system_filled_source_fields(rag_setup, monkeypatch):
    """Контракт критика: doc_id/page/score заполняет система — дефектом не является.

    Промпт снимается с реального вызова critique_with_context, поэтому тест
    падает, если контракт убрать из того промпта, который уходит модели.
    Проверяется владение полями, а не формулировка: системные поля названы
    и объявлены заполняемыми системой, chunk_id остаётся за моделью,
    missing_fields — ожидаемо непустой. Вердикт модели не проверяется.
    """
    import json

    from app.schemas.cards import CardDraft, CritiqueReport
    from app.services import rag_pipeline as rp

    data = json.loads(_card_json())
    data["sources"] = [{"chunk_id": "tstragc1", "page": 1, "doc_id": DOC_ID, "score": 0.51}]
    data["missing_fields"] = ["Гарантия"]
    draft = CardDraft.model_validate(data)

    captured = {}

    async def fake_run(agent, prompt):
        captured["agent"] = agent
        captured["prompt"] = prompt
        return CritiqueReport(verdict="approve")

    monkeypatch.setattr(rp, "run_agent", fake_run)
    asyncio.run(
        rp.critique_with_context(
            "[tstragc1 | blender_test.pdf | стр. 1]\n" + CHUNK_TEXTS["tstragc1"], draft
        )
    )

    assert captured["agent"] is rp.critic_agent
    prompt = captured["prompt"]
    instructions, _, body = prompt.partition("КОНТЕКСТ:")
    for system_field in ("doc_id", "page", "score"):
        assert system_field in instructions, system_field
    assert "chunk_id" in instructions
    assert "missing_fields" in instructions
    assert DOC_ID in body and "0.51" in body, "критик видит обогащённый источник"


def test_run_rag_pipeline_gives_critic_the_source_contract(rag_setup, monkeypatch):
    """Критик внутри петли получает тот же контракт: иначе петля не завершится.

    Проверяется присутствие контракта в промпте, а не вердикт: approve от
    локальной модели — её суждение, оно не должно быть предметом теста.
    """
    from app.core.config import settings
    from app.schemas.cards import CritiqueReport
    from app.services import rag_pipeline as rp

    critic_prompts = []

    async def fake_run(agent, prompt):
        if agent.name == "Critic":
            critic_prompts.append(prompt)
            return CritiqueReport(verdict="regenerate", issues=["переделай"]).model_dump_json()
        return _card_json()

    monkeypatch.setattr(rp, "run_agent", fake_run)
    monkeypatch.setattr(
        rp,
        "retrieve_context_async",
        _fake_retrieve("[tstragc1 | blender_test.pdf | стр. 1]\n" + CHUNK_TEXTS["tstragc1"]),
    )
    _draft, attempts, verdict, _status, _g, _context = asyncio.run(
        rp.run_rag_pipeline("блендер", [DOC_ID])
    )
    assert verdict == "rejected"
    assert attempts == settings.max_pipeline_rounds, "петля остаётся ограниченной"
    assert len(critic_prompts) == settings.max_pipeline_rounds
    for prompt in critic_prompts:
        assert rp.CRITIC_CONTRACT in prompt
