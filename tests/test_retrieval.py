"""Тесты retrieval на фиксированных векторах (мок embedder — модель не грузим).

Требует живую БД с применёнными миграциями (docker compose up + make migrate).
"""
import os

import pytest

from app.core import db

pytestmark = pytest.mark.skipif(
    os.environ.get("PCA_SKIP_DB_TESTS") == "1",
    reason="PCA_SKIP_DB_TESTS=1",
)

# Опорные векторы: значащие первые координаты + нули до 768 (размерность колонки)
import math


def _unit(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v))
    v = [x / n for x in v]
    return v + [0.0] * (768 - len(v))


E_BLENDER = _unit([1, 0])
E_KETTLE = _unit([0, 1])


def _vec(v: list[float]) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in v) + "]"


@pytest.fixture
def db_with_chunks(monkeypatch):
    """Тестовые чанки с векторами + мок embed_query по ключевым словам."""
    with db.connection() as conn:
        doc_id = "tstdoc1"
        with conn.cursor() as cur:
            cur.execute("DELETE FROM chunks WHERE doc_id = %s", (doc_id,))
            cur.execute(
                "DELETE FROM documents WHERE id = %s", (doc_id,)
            )
            cur.execute(
                "INSERT INTO documents (id, filename, content_hash, kind, status) "
                "VALUES (%s, 'test.pdf', 'hash-тест-retrieval', 'pdf', 'indexed')",
                (doc_id,),
            )
            rows = [
                ("tstc1", 0, "Мощность блендера 800 Вт", E_BLENDER),
                ("tstc2", 1, "Чайник 2200 Вт, объём 1.7 л", E_KETTLE),
                ("tstc3", 2, "Гарантия 24 месяца", _unit([1, 1])),
            ]
            for cid, ordinal, text, vec in rows:
                cur.execute(
                    "INSERT INTO chunks (id, doc_id, ordinal, text, metadata, embedding) "
                    "VALUES (%s, %s, %s, %s, %s, %s::vector)",
                    (cid, doc_id, ordinal, text,
                     f'{{"doc_id": "{doc_id}", "page": 1}}', _vec(vec)),
                )

        # Мок: «блендер» → E_BLENDER, всё прочее → E_KETTLE
        from app.repositories import chunks as chunks_repo

        def fake_embed_query(text: str) -> list[float]:
            return E_BLENDER if "блендер" in text.lower() else E_KETTLE

        monkeypatch.setattr(chunks_repo, "embed_query", fake_embed_query)
        yield conn
        with conn.cursor() as cur:
            cur.execute("DELETE FROM chunks WHERE doc_id = %s", (doc_id,))
            cur.execute("DELETE FROM documents WHERE id = %s", (doc_id,))


def test_vector_search_finds_semantic_match(db_with_chunks):
    from app.repositories import chunks as chunks_repo

    results = chunks_repo.search_vector(db_with_chunks, "мощность блендера", top_k=3)
    assert results, "пусто — проверь threshold/векторы"
    assert results[0]["chunk_id"] == "tstc1"
    assert results[0]["score"] >= 0.9


def test_vector_search_threshold_filters(db_with_chunks):
    from app.repositories import chunks as chunks_repo

    # Порог 0.99: только почти точное совпадение проходит
    results = chunks_repo.search_vector(
        db_with_chunks, "мощность блендера", top_k=5, threshold=0.99
    )
    ids = [r["chunk_id"] for r in results]
    assert "tstc1" in ids
    assert "tstc2" not in ids


def test_hybrid_combines_vector_and_fts(db_with_chunks):
    from app.repositories import chunks as chunks_repo

    # «блендер» → векторный близок к tstc1; FTS найдёт «гарантия» в tstc3
    # (мок возвращает E_KETTLE для «гарантия» — вектор промахивается)
    results = chunks_repo.search_hybrid(db_with_chunks, "мощность блендера", top_k=3)
    ids = [r["chunk_id"] for r in results]
    assert "tstc1" in ids  # векторное попадание
    assert results[0]["chunk_id"] == "tstc1"  # гибрид ранжирует точное выше


def test_hybrid_threshold_cuts_vector_leg_but_keeps_fts_only(db_with_chunks):
    """Порог режёт векторную ногу; чанк без вектора остаётся по рангу FTS."""
    from app.repositories import chunks as chunks_repo

    with db_with_chunks.cursor() as cur:
        cur.execute(
            "INSERT INTO chunks (id, doc_id, ordinal, text, metadata) "
            "VALUES ('tstc4', 'tstdoc1', 3, %s, '{}')",
            ("Мощность блендера 800 Вт, двойная стенка корпуса",),
        )
    # 0.9: tstc1 (косинус 1.0) проходит, tstc3 (≈0.71) и tstc2 (0.0) — нет.
    # Scoped к фикстурному документу: иначе OR-FTS находит «Мощность» и в
    # реальных документах БД, и тест становится недетерминированным.
    results = chunks_repo.search_hybrid(
        db_with_chunks, "мощность блендера", top_k=5, threshold=0.9, doc_ids=["tstdoc1"]
    )
    ids = [r["chunk_id"] for r in results]
    assert "tstc1" in ids
    assert "tstc3" not in ids
    assert "tstc2" not in ids
    assert "tstc4" in ids


def test_fts_search_exact_words(db_with_chunks):
    from app.repositories import chunks as chunks_repo

    # top_k с запасом: в датасете есть свои чанки со словом «гарантия»
    results = chunks_repo.search_fts(db_with_chunks, "гарантия", top_k=50)
    assert any(r["chunk_id"] == "tstc3" for r in results)


def test_doc_filter(db_with_chunks):
    from app.repositories import chunks as chunks_repo

    results = chunks_repo.search_vector(
        db_with_chunks, "мощность блендера", top_k=3, doc_ids=["другой-doc"]
    )
    assert results == []


def test_fts_search_uses_or_semantics(db_with_chunks):
    """Регрессия: FTS должен быть OR, а не AND.

    Ни один чанок не содержит оба слова сразу («блендера» только в tstc1,
    «гарантия» только в tstc3). При AND-семантике совпадений нет; при OR
    должны найтись оба чанка, а «чайник» (tstc2) — ни по одному слову.
    """
    from app.repositories import chunks as chunks_repo

    results = chunks_repo.search_fts(db_with_chunks, "блендера гарантия", top_k=50)
    ids = {r["chunk_id"] for r in results}
    assert "tstc1" in ids
    assert "tstc3" in ids
    assert "tstc2" not in ids


def test_hybrid_fts_uses_or_semantics(db_with_chunks):
    """То же, но через гибрид: OR-FTS должен подтянуть оба чанка в слияние."""
    from app.repositories import chunks as chunks_repo

    results = chunks_repo.search_hybrid(db_with_chunks, "блендера гарантия", top_k=50)
    ids = {r["chunk_id"] for r in results}
    assert "tstc1" in ids
    assert "tstc3" in ids


def test_hybrid_keeps_fts_match_with_low_vector_score(db_with_chunks):
    """OR-FTS бесполезен, если чанк, найденный FTS, режется по векторному cosine.

    Запрос без слова «блендер» → мок отдаёт E_KETTLE, поэтому tstc1 (E_BLENDER)
    имеет cosine 0 и векторная нога его не держит; но tstc1 содержит «Мощность»,
    значит FTS его находит. Гибрид обязан сохранить такой чанк.
    """
    from app.repositories import chunks as chunks_repo

    results = chunks_repo.search_hybrid(db_with_chunks, "мощность", top_k=10)
    ids = [r["chunk_id"] for r in results]
    assert "tstc1" in ids


def test_retrieve_context_returns_all_chunks_when_doc_ids_given(db_with_chunks):
    """Регрессия на баг heater_passport: при явном doc_ids берём ВЕСЬ документ,
    а не similarity-top-k. Иначе чанки спецификации (не проходящие порог cosine
    по generic-запросу) отбрасывались, и модель достраивала карточку из имени
    файла."""
    import asyncio

    from app.services.rag_pipeline import retrieve_context_async

    ctx, report = asyncio.run(
        retrieve_context_async("карточка товара характеристики", doc_ids=["tstdoc1"])
    )
    # Все три чанка документа должны попасть в контекст. tstc1 («Мощность
    # блендера») по similarity отбрасывается (query → E_KETTLE, tstc1 → E_BLENDER,
    # cosine 0 < 0.35) — именно этот чанк терялся до фикса.
    assert "Мощность блендера 800 Вт" in ctx
    assert "Чайник 2200 Вт" in ctx
    assert "Гарантия 24 месяца" in ctx
    assert report.suspicious_chunks == []
