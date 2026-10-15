"""Repository: векторный upsert и три режима поиска (vector / fts / hybrid RRF)."""
import psycopg
from psycopg.types.json import Json

from app.core.config import settings
from app.rag.embedder import embed_query

# rrf_k из классической формулы Reciprocal Rank Fusion
RRF_K = 60


def upsert_embeddings(conn: psycopg.Connection, items: list[tuple[str, list[float]]]) -> int:
    """Батч-запись эмбеддингов: [(chunk_id, vector)]. Возвращает число записанных."""
    if not items:
        return 0
    # vector передаём строкой '[1,2,...]' — psycopg не знает тип vector
    with conn.cursor() as cur:
        cur.executemany(
            "UPDATE chunks SET embedding = %s::vector WHERE id = %s",
            [(_vec_str(v), cid) for cid, v in items],
        )
    return len(items)


def _vec_str(v: list[float]) -> str:
    return "[" + ",".join(f"{x:.6f}" for x in v) + "]"


def chunks_without_embedding(
    conn: psycopg.Connection, limit: int = 256, doc_id: str | None = None
) -> list[tuple[str, str]]:
    sql = "SELECT id, text FROM chunks WHERE embedding IS NULL"
    args: list = []
    if doc_id:
        sql += " AND doc_id = %s"
        args.append(doc_id)
    sql += " LIMIT %s"
    args.append(limit)
    with conn.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall()


def search_vector(
    conn: psycopg.Connection, query: str, *, top_k: int | None = None,
    doc_ids: list[str] | None = None, threshold: float | None = None,
) -> list[dict]:
    """top-K по косинусной дистанции (<=>)."""
    top_k = top_k or settings.retrieval_top_k
    threshold = settings.retrieval_threshold if threshold is None else threshold
    vec = _vec_str(embed_query(query))
    sql = """
        SELECT c.id, c.doc_id, c.text, c.metadata,
               d.filename,
               1 - (c.embedding <=> %s::vector) AS score
        FROM chunks c JOIN documents d ON d.id = c.doc_id
        WHERE c.embedding IS NOT NULL AND d.status = 'indexed'
    """
    args: list = [vec]
    if doc_ids:
        sql += " AND c.doc_id = ANY(%s)"
        args.append(doc_ids)
    sql += " ORDER BY c.embedding <=> %s::vector LIMIT %s"
    args += [vec, top_k * 3]  # берём с запасом до фильтра по threshold
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    return [
        _row_to_dict(r) for r in rows
        if r[5] is not None and r[5] >= threshold
    ][:top_k]


def search_fts(
    conn: psycopg.Connection, query: str, *, top_k: int | None = None,
    doc_ids: list[str] | None = None,
) -> list[dict]:
    """Полнотекстовый поиск по tsvector ('russian')."""
    top_k = top_k or settings.retrieval_top_k
    sql = """
        SELECT c.id, c.doc_id, c.text, c.metadata,
               d.filename,
               ts_rank(c.tsv, q.query) AS score
        FROM chunks c JOIN documents d ON d.id = c.doc_id,
             plainto_tsquery('russian', %s) AS q(query)
        WHERE c.tsv @@ q.query AND d.status = 'indexed'
    """
    args: list = [query]
    if doc_ids:
        sql += " AND c.doc_id = ANY(%s)"
        args.append(doc_ids)
    sql += " ORDER BY score DESC LIMIT %s"
    args.append(top_k)
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    return [_row_to_dict(r) for r in rows]


def search_hybrid(
    conn: psycopg.Connection, query: str, *, top_k: int | None = None,
    doc_ids: list[str] | None = None, threshold: float | None = None,
) -> list[dict]:
    """Гибрид: векторный + полнотекстовый, слияние рангов через RRF.

    score = sum(1 / (rrf_k + rank_i)) по спискам результатов.
    CTE: каждый поиск — свой SELECT с ROW_NUMBER(), JOIN по chunk_id.
    """
    top_k = top_k or settings.retrieval_top_k
    threshold = settings.retrieval_threshold if threshold is None else threshold
    vec = _vec_str(embed_query(query))
    sql = """
        WITH vec AS (
            SELECT c.id, ROW_NUMBER() OVER (ORDER BY c.embedding <=> %s::vector) AS rank
            FROM chunks c JOIN documents d ON d.id = c.doc_id
            WHERE c.embedding IS NOT NULL AND d.status = 'indexed'
    """
    args: list = [vec]
    if doc_ids:
        sql += " AND c.doc_id = ANY(%s)"
        args.append(doc_ids)
    sql += """
            LIMIT %s
        ), fts AS (
            SELECT c.id, ROW_NUMBER() OVER (ORDER BY ts_rank(c.tsv, q.query) DESC) AS rank
            FROM chunks c JOIN documents d ON d.id = c.doc_id,
                 plainto_tsquery('russian', %s) AS q(query)
            WHERE c.tsv @@ q.query AND d.status = 'indexed'
    """
    args += [top_k * 2, query]
    if doc_ids:
        sql += " AND c.doc_id = ANY(%s)"
        args.append(doc_ids)
    sql += """
            LIMIT %s
        )
        SELECT c.id, c.doc_id, c.text, c.metadata, d.filename,
               COALESCE(1.0/( %s + v.rank), 0) + COALESCE(1.0/( %s + f.rank), 0) AS rrf_score,
               1 - (c.embedding <=> %s::vector) AS vec_score
        FROM chunks c
        JOIN documents d ON d.id = c.doc_id
        LEFT JOIN vec v ON v.id = c.id
        LEFT JOIN fts f ON f.id = c.id
        WHERE v.id IS NOT NULL OR f.id IS NOT NULL
    """
    args += [top_k * 2, RRF_K, RRF_K, vec]
    sql += " ORDER BY rrf_score DESC LIMIT %s"
    args.append(top_k)
    with conn.cursor() as cur:
        cur.execute(sql, args)
        rows = cur.fetchall()
    return [
        {
            **_row_to_dict((r[0], r[1], r[2], r[3], r[4], r[6])),
            "rrf_score": float(r[5]),
        }
        for r in rows
    ]


def get_chunks_by_ids(conn: psycopg.Connection, ids: list[str]) -> dict[str, dict]:
    """chunk_id → {doc_id, text, metadata}. Для валидации источников."""
    if not ids:
        return {}
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, doc_id, text, metadata FROM chunks WHERE id = ANY(%s)", (ids,)
        )
        rows = cur.fetchall()
    return {
        r[0]: {"doc_id": r[1], "text": r[2], "metadata": _as_dict(r[3])}
        for r in rows
    }


def _as_dict(v) -> dict:
    return v if isinstance(v, dict) else Json({})


def _row_to_dict(r) -> dict:
    return {
        "chunk_id": r[0],
        "doc_id": r[1],
        "text": r[2],
        "metadata": r[3] if isinstance(r[3], dict) else {},
        "filename": r[4],
        "score": float(r[5]) if r[5] is not None else None,
    }
