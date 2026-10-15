"""Embeddings lmstudio-бэкенда: батчинг, порядок ответов, отказ вместо нулей.

Сеть не используется: клиент embeddings.create — стаб, фиксирующий вызовы.
"""
from math import hypot
from types import SimpleNamespace

import pytest

from app.core.config import settings
from app.rag import embedder

TEXTS = [f"чанк номер {i}" for i in range(7)]


def _expected_vector(i: int) -> list[float]:
    norm = hypot(1.0, float(i + 1))
    return [1.0 / norm, float(i + 1) / norm]


class _StubEmbeddings:
    def __init__(self, calls: list[dict]):
        self.calls = calls

    def create(self, *, model, input):
        payload = [input] if isinstance(input, str) else list(input)
        self.calls.append({"model": model, "input": payload})
        # Ответ приходит вперемешку: порядок обязан восстанавливать сам embedder.
        return SimpleNamespace(
            data=[
                SimpleNamespace(index=i, embedding=[1.0, float(i + 1)])
                for i in reversed(range(len(payload)))
            ]
        )


@pytest.fixture
def stub_client(monkeypatch):
    monkeypatch.setattr(settings, "embed_backend", "lmstudio")
    monkeypatch.setenv("EMBED_BACKEND", "hf")
    calls: list[dict] = []
    client = SimpleNamespace(embeddings=_StubEmbeddings(calls))
    monkeypatch.setattr(embedder, "_lmstudio_client", lambda: client)
    monkeypatch.setattr(embedder, "_client", client)
    return calls


def test_backend_comes_from_settings_not_environ(stub_client):
    assert embedder._use_lmstudio_embeddings() is True


def test_embed_documents_sends_one_call_for_the_whole_batch(stub_client):
    vectors = embedder.embed_documents(TEXTS)

    assert len(stub_client) == 1
    assert len(stub_client[0]["input"]) == len(TEXTS)
    assert stub_client[0]["model"] == settings.embed_model
    assert len(vectors) == len(TEXTS)


def test_embed_documents_applies_document_prefix(stub_client):
    embedder.embed_documents(TEXTS)

    assert stub_client[0]["input"] == [embedder.DOC_PREFIX + t for t in TEXTS]


def test_embed_documents_restores_input_order(stub_client):
    vectors = embedder.embed_documents(TEXTS)

    assert vectors == [_expected_vector(i) for i in range(len(TEXTS))]


def test_embed_documents_raises_instead_of_zero_vector(monkeypatch):
    monkeypatch.setattr(settings, "embed_backend", "lmstudio")

    def boom(*, model, input):
        raise RuntimeError("network down")

    client = SimpleNamespace(embeddings=SimpleNamespace(create=boom))
    monkeypatch.setattr(embedder, "_lmstudio_client", lambda: client)

    with pytest.raises(RuntimeError, match="network down"):
        embedder.embed_documents(TEXTS)


def test_embed_documents_short_response_raises(monkeypatch):
    monkeypatch.setattr(settings, "embed_backend", "lmstudio")
    stub = SimpleNamespace(
        embeddings=SimpleNamespace(
            create=lambda **kw: SimpleNamespace(
                data=[SimpleNamespace(index=0, embedding=[1.0, 0.0])]
            )
        )
    )
    monkeypatch.setattr(embedder, "_lmstudio_client", lambda: stub)

    with pytest.raises(RuntimeError, match="1 векторов на 7 входов"):
        embedder.embed_documents(TEXTS)


def test_empty_input_makes_no_call(stub_client):
    assert embedder.embed_documents([]) == []
    assert stub_client == []