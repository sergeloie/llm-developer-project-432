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
            "SELECT c.id, c.text FROM chunks c"
            " LEFT JOIN chunk_embs e ON e.chunk_id = c.id"
            " WHERE e.chunk_id IS NULL ORDER BY c.id"
        ).fetchall()
    else:
        rows = conn.execute(
            "SELECT c.id, c.text FROM chunks c"
            " LEFT JOIN chunk_embs e ON e.chunk_id = c.id"
            " WHERE e.chunk_id IS NULL AND c.document_id = %s ORDER BY c.id",
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
