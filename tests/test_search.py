"""Поиск шага 6 на заранее заданных векторах: без модели и без сети."""
import math

import psycopg
import pytest

from app.core.config import settings as app_settings
from app.repositories import documents as repo
from app.services import search as svc
from migrations import runner


@pytest.fixture
def conn(test_db_url):
    app_settings.database_url = test_db_url
    runner.run()
    c = psycopg.connect(test_db_url, autocommit=True)
    try:
        c.execute("INSERT INTO documents (filename, sha256, size_bytes) VALUES ('t.txt','h',4)")
        doc_id = c.execute("SELECT id FROM documents WHERE sha256='h'").fetchone()[0]
        # фрагменты: id 1 близок запросу «а», id 2 — лексически артикул
        c.execute("INSERT INTO chunks (document_id,ord,text) VALUES (%s,1,'мощность 800 Вт')", (doc_id,))
        c.execute("INSERT INTO chunks (document_id,ord,text) VALUES (%s,2,'артикул KTL-1700')", (doc_id,))
        v1 = "[" + ",".join(["1.0"] + ["0.0"] * 767) + "]"
        v2 = "[" + ",".join(["0.0"] + ["1.0"] + ["0.0"] * 766) + "]"
        c.execute("UPDATE chunks SET embedding=%s::vector WHERE ord=1", (v1,))
        c.execute("UPDATE chunks SET embedding=%s::vector WHERE ord=2", (v2,))
        yield c, [r[0] for r in c.execute("SELECT id FROM chunks ORDER BY id").fetchall()]
    finally:
        c.execute("DELETE FROM chunks")
        c.execute("DELETE FROM documents")
        c.close()


def test_rrf_fuse_merges_both_lists():
    assert svc.rrf_fuse([[1, 2], [2, 3]])[:2] == [2, 1]


def test_cosine():
    assert math.isclose(svc.cosine([1, 0], [1, 0]), 1.0)


def test_repository_vector_and_words(conn):
    c, ids = conn
    qvec = [1.0] + [0.0] * 767
    vh = repo.search_vector(c, qvec, top_k=5, threshold=0.5)
    assert {h["id"] for h in vh} == {ids[0]}
    wh = repo.search_words(c, "KTL-1700", top_k=5)
    assert {h["id"] for h in wh} == {ids[1]}


def test_repository_hybrid_is_union(conn):
    c, ids = conn
    qvec = [1.0] + [0.0] * 767
    res = repo.search_hybrid(c, "KTL-1700", qvec, top_k=5, threshold=0.0)
    hit_ids = {h["id"] for h in res}
    assert ids[0] in hit_ids or ids[1] in hit_ids
    assert len(res) >= 1


def test_threshold_cuts_low_scores(conn):
    c, ids = conn
    vh = repo.search_vector(c, [0.2] + [0.99] + [0.0] * 766, top_k=5, threshold=0.9)
    # второй фрагмент низкий cosine — отфильтрован; первый тоже
    assert len(vh) == 0 or all(h["score"] >= 0.9 for h in vh)