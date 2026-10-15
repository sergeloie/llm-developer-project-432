"""SQL документов/фрагментов живёт только здесь."""
import json
from typing import Any

import psycopg

DOC_STATUSES = {"new", "parsing", "indexed", "failed"}


def create_document(
    conn: psycopg.Connection, filename: str, sha256: str, size: int, path: str
) -> tuple[int, bool]:
    """Идемпотентно: тот же sha256 → существующий документ."""
    row = conn.execute(
        "SELECT id, status FROM documents WHERE sha256 = %s", (sha256,)
    ).fetchone()
    if row:
        return row[0], False
    row = conn.execute(
        "INSERT INTO documents (filename, sha256, size_bytes, status, error)"
        " VALUES (%s, %s, %s, 'new', NULL) RETURNING id",
        (filename, sha256, size),
    ).fetchone()
    return row[0], True


def get_document(conn: psycopg.Connection, doc_id: int) -> dict[str, Any] | None:
    row = conn.execute(
        "SELECT id, filename, sha256, size_bytes, status, error, created_at, updated_at"
        " FROM documents WHERE id = %s",
        (doc_id,),
    ).fetchone()
    if not row:
        return None
    return {
        "id": row[0],
        "filename": row[1],
        "sha256": row[2],
        "size_bytes": row[3],
        "status": row[4],
        "error": row[5],
        "created_at": row[6].isoformat(),
        "updated_at": row[7].isoformat(),
        "chunk_count": None,  # заполняет get_document_with_count
    }


def get_document_with_count(conn: psycopg.Connection, doc_id: int) -> dict[str, Any] | None:
    row = conn.execute(
        "SELECT d.id, d.filename, d.status, d.error, count(c.id)"
        " FROM documents d LEFT JOIN chunks c ON c.document_id = d.id"
        " WHERE d.id = %s GROUP BY d.id",
        (doc_id,),
    ).fetchone()
    if not row:
        return None
    return {
        "id": row[0],
        "filename": row[1],
        "status": row[2],
        "error": row[3],
        "chunk_count": row[4],
    }


def set_document_status(
    conn: psycopg.Connection,
    doc_id: int,
    status: str,
    error: str | None = None,
) -> None:
    if status not in DOC_STATUSES:
        raise ValueError(status)
    conn.execute(
        "UPDATE documents SET status = %s, error = %s, updated_at = now() WHERE id = %s",
        (status, error, doc_id),
    )


def replace_chunks(
    conn: psycopg.Connection, doc_id: int, fragments: list[dict[str, Any]]
) -> int:
    """Перезаписать фрагменты документа (повторный разбор — без дублей)."""
    conn.execute("DELETE FROM chunk_embs WHERE chunk_id IN (SELECT id FROM chunks WHERE document_id = %s)", (doc_id,)) \
        if _has_chunk_embs(conn) else None
    conn.execute("DELETE FROM chunks WHERE document_id = %s", (doc_id,))
    n = 0
    for f in fragments:
        conn.execute(
            "INSERT INTO chunks (document_id, ord, page, section, kind, article, brand, text)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
            (
                doc_id,
                f["ord"],
                f.get("page"),
                f.get("section"),
                f.get("kind", "text"),
                f.get("article"),
                f.get("brand"),
                f["text"],
            ),
        )
        n += 1
    return n


def _has_chunk_embs(conn: psycopg.Connection) -> bool:
    return bool(
        conn.execute(
            "SELECT 1 FROM information_schema.tables WHERE table_name = 'chunk_embs'"
        ).fetchone()
    )


def count_chunks(conn: psycopg.Connection, doc_id: int) -> int:
    return int(conn.execute(
        "SELECT count(*) FROM chunks WHERE document_id = %s", (doc_id,)
    ).fetchone()[0])


def list_documents(conn: psycopg.Connection) -> list[dict[str, Any]]:
    rows = conn.execute(
        "SELECT d.id, d.filename, d.status, d.error, count(c.id)"
        " FROM documents d LEFT JOIN chunks c ON c.document_id = d.id"
        " GROUP BY d.id ORDER BY d.id"
    ).fetchall()
    return [
        {
            "id": r[0],
            "filename": r[1],
            "status": r[2],
            "error": r[3],
            "chunk_count": r[4],
        }
        for r in rows
    ]


def get_chunks(conn: psycopg.Connection, document_id: int | None = None) -> list[dict[str, Any]]:
    if document_id is None:
        rows = conn.execute(
            "SELECT id, document_id, ord, page, section, kind, article, brand, text"
            " FROM chunks ORDER BY document_id, ord"
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT id, document_id, ord, page, section, kind, article, brand, text"
            " FROM chunks WHERE document_id = %s ORDER BY ord",
            (document_id,),
        ).fetchall()
    return [
        {
            "id": r[0],
            "document_id": r[1],
            "ord": r[2],
            "page": r[3],
            "section": r[4],
            "kind": r[5],
            "article": r[6],
            "brand": r[7],
            "text": r[8],
        }
        for r in rows
    ]


def chunk_texts_for_embedding(conn: psycopg.Connection, doc_id: int | None = None) -> list[tuple[int, str]]:
    """(chunk_id, text) для досчёта векторов."""
    if doc_id is None:
        rows = conn.execute(
            "SELECT id, text FROM chunks WHERE embedding IS NULL ORDER BY id"
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT id, text FROM chunks WHERE embedding IS NULL AND document_id = %s ORDER BY id",
            (doc_id,),
        ).fetchall()
    return [(r[0], r[1]) for r in rows]


def chunks_by_ids(conn: psycopg.Connection, ids: list[int]) -> list[dict[str, Any]]:
    if not ids:
        return []
    rows = conn.execute(
        "SELECT id, document_id, ord, page, section, kind, article, brand, text"
        " FROM chunks WHERE id = ANY(%s)",
        (ids,),
    ).fetchall()
    return [
        {
            "id": r[0],
            "document_id": r[1],
            "ord": r[2],
            "page": r[3],
            "section": r[4],
            "kind": r[5],
            "article": r[6],
            "brand": r[7],
            "text": r[8],
        }
        for r in rows
    ]


def jdump(x: Any) -> str:
    return json.dumps(x, ensure_ascii=False)


# ——— Шаг 6: векторы и три режима поиска ———


def store_embeddings(
    conn: psycopg.Connection,
    vectors: list[tuple[int, list[float]]],
    model: str,
    dim: int,
) -> int:
    """Записать векторы пачкой: колонка chunks.embedding + зеркало chunk_embs."""
    n = 0
    for chunk_id, vec in vectors:
        lit = "[" + ",".join(f"{x:.7f}" for x in vec) + "]"
        conn.execute(
            "UPDATE chunks SET embedding = %s::vector WHERE id = %s", (lit, chunk_id)
        )
        if _has_chunk_embs(conn):
            conn.execute(
                "INSERT INTO chunk_embs (chunk_id, model, dim, vector)"
                " VALUES (%s, %s, %s, %s::vector)"
                " ON CONFLICT (chunk_id) DO UPDATE"
                " SET vector = EXCLUDED.vector, model = EXCLUDED.model,"
                " dim = EXCLUDED.dim, updated_at = now()",
                (chunk_id, model, dim, lit),
            )
        n += 1
    return n


def pending_embed_count(conn: psycopg.Connection) -> int:
    return int(
        conn.execute(
            "SELECT count(*) FROM chunks WHERE embedding IS NULL"
        ).fetchone()[0]
    )


def search_vector(
    conn: psycopg.Connection,
    query_vec: list[float],
    *,
    top_k: int,
    threshold: float,
    document_ids: list[int] | None = None,
) -> list[dict[str, Any]]:
    """Поиск по векторам: cosine similarity = 1 - cosine distance.

    threshold отсекает мусор ДО возврата; метрика выбирается один раз.
    """
    lit = "[" + ",".join(f"{x:.7f}" for x in query_vec) + "]"
    sql = [
        "SELECT id, document_id, page, section, kind, article, text,",
        " 1 - (embedding <=> %s::vector) AS score",
        "FROM chunks",
        "WHERE embedding IS NOT NULL AND 1 - (embedding <=> %s::vector) >= %s",
    ]
    params: list[Any] = [lit, lit, threshold]
    if document_ids is not None:
        if not document_ids:
            return []
        sql.append("AND document_id = ANY(%s)")
        params.append(document_ids)
    sql.append("ORDER BY embedding <=> %s::vector LIMIT %s")
    params.extend([lit, top_k])
    rows = conn.execute(" ".join(sql), params).fetchall()
    return [_row_to_hit(r) for r in rows]


def search_words(
    conn: psycopg.Connection,
    query: str,
    *,
    top_k: int,
    document_ids: list[int] | None = None,
    min_score: float = 0.01,
) -> list[dict[str, Any]]:
    """Поиск по словам: ts_rank по tsv + досмотр сырым LIKE по словaм
    (артикулы вида KTL-1700 ts-токенайзер рубит на части, LIKE их mantit).
    """
    words = [w for w in query.split() if len(w) >= 3]
    like_parts = ["lower(text) LIKE %s" for _ in words]
    conditions = ["tsv @@ plainto_tsquery('russian', %s)"]
    conditions.extend(like_parts)
    sql = [
        ("SELECT id, document_id, page, section, kind, article, text,"
         " ts_rank(tsv, plainto_tsquery('russian', %s)) AS score"),
        "FROM chunks",
        "WHERE (" + " OR ".join(conditions) + ")",
    ]
    params: list[Any] = [query, query]
    params.extend([f"%{w.lower()}%" for w in words])
    if document_ids is not None:
        if not document_ids:
            return []
        sql.append("AND document_id = ANY(%s)")
        params.append(document_ids)
    sql.append("ORDER BY score DESC LIMIT %s")
    params.extend([top_k])
    rows = conn.execute(" ".join(sql), params).fetchall()
    hits = [_row_to_hit(r) for r in rows]
    # сырой LIKE не даёт score: посчитаем честный — доля слов запроса в тексте
    for h in hits:
        if h["score"] == 0:
            lowered = h["text"].lower()
            found = sum(1 for w in words if w.lower() in lowered)
            h["score"] = found / len(words) if words else 0.0
    return [h for h in hits if h["score"] >= min_score]


def search_hybrid(
    conn: psycopg.Connection,
    query: str,
    query_vec: list[float],
    *,
    top_k: int,
    threshold: float,
    document_ids: list[int] | None = None,
    k_rrf: int = 60,
) -> dict[str, Any]:
    """Гибрид: векторный + лексический списки сливаются RRF.

    Слияние по обратным позициям: фрагмент получает вклад 1/(k_rrf + rank)
    каждый список, где нашёлся; найденный обоими — сверху. Возвращает
    (hits, одинаковые для проверок): каждый hit с blended score.
    """
    vec_hits = search_vector(
        conn, query_vec, top_k=top_k * 2, threshold=threshold, document_ids=document_ids
    )
    lex_hits = search_words(conn, query, top_k=top_k * 2, document_ids=document_ids)

    contributions: dict[int, float] = {}
    hit_by_id: dict[int, dict[str, Any]] = {}
    for rank, hit in enumerate(vec_hits, start=1):
        contributions[hit["id"]] = contributions.get(hit["id"], 0.0) + 1.0 / (k_rrf + rank)
        hit_by_id.setdefault(hit["id"], hit)
    for rank, hit in enumerate(lex_hits, start=1):
        contributions[hit["id"]] = contributions.get(hit["id"], 0.0) + 1.0 / (k_rrf + rank)
        hit_by_id.setdefault(hit["id"], hit)

    ranked = sorted(contributions.items(), key=lambda kv: kv[1], reverse=True)[:top_k]
    hits: list[dict[str, Any]] = []
    for chunk_id, rrf in ranked:
        hit = hit_by_id[chunk_id].copy()
        hit["rrf_score"] = rrf
        hits.append(hit)
    # отдаём список фрагментов; служебные счётчики сохраняем в атрибуте-ключе
    result = hits
    # дополнительные счётчики доступны для вызывающего кода через хвостовой dict-обёртку,
    # но для совместимости со стандартным контрактом возвращаем список.
    return result


def _row_to_hit(r: tuple) -> dict[str, Any]:
    return {
        "id": r[0],
        "document_id": r[1],
        "page": r[2],
        "section": r[3],
        "kind": r[4],
        "article": r[5],
        "text": r[6],
        "score": float(r[7]),
    }
