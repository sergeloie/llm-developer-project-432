"""Repository: documents + chunks. Единственный владелец SQL."""
import hashlib
import json
import uuid
from datetime import UTC, datetime

import psycopg


def _now() -> str:
    return datetime.now(UTC).isoformat()


def create_document(
    conn: psycopg.Connection, filename: str, content: bytes, kind: str
) -> tuple[str, bool]:
    """Создать документ. Возвращает (id, created?). False = дубликат по хэшу."""
    content_hash = hashlib.sha256(content).hexdigest()
    with conn.cursor() as cur:
        cur.execute("SELECT id FROM documents WHERE content_hash = %s", (content_hash,))
        row = cur.fetchone()
        if row:
            return row[0], False
        doc_id = uuid.uuid4().hex[:12]
        cur.execute(
            "INSERT INTO documents (id, filename, content_hash, kind) VALUES (%s, %s, %s, %s)",
            (doc_id, filename, content_hash, kind),
        )
        return doc_id, True


def set_status(
    conn: psycopg.Connection, doc_id: str, status: str, *, error: str | None = None
) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE documents SET status = %s, error = %s, updated_at = %s WHERE id = %s",
            (status, error, _now(), doc_id),
        )


def set_chunks_count(conn: psycopg.Connection, doc_id: str, count: int) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE documents SET chunks_count = %s, updated_at = %s WHERE id = %s",
            (count, _now(), doc_id),
        )


def insert_chunks(conn: psycopg.Connection, chunks: list[dict]) -> None:
    """Батч-вставка чанков. Транзакция: либо все, либо ничего."""
    if not chunks:
        return
    rows = [
        (
            c["id"],
            c["metadata"]["doc_id"],
            c["ordinal"],
            c["text"],
            json.dumps(c["metadata"], ensure_ascii=False),
        )
        for c in chunks
    ]
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO chunks (id, doc_id, ordinal, text, metadata) VALUES (%s, %s, %s, %s, %s)",
            rows,
        )


def mark_chunks_suspicious(conn: psycopg.Connection, chunk_ids: list[str]) -> int:
    """Пометить чанки, которые guardrails заблокировали. Возвращает число обновленных.

    Флаг переживает перезапуск воркера: по нему видно, что конкретный фрагмент
    документа не дошёл до модели, даже когда отчёт давно уже потерян.
    """
    if not chunk_ids:
        return 0
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE chunks SET suspicious = TRUE WHERE id = ANY(%s)", (list(chunk_ids),)
        )
        return cur.rowcount


def get_document(conn: psycopg.Connection, doc_id: str) -> dict | None:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, filename, kind, status, chunks_count, error, created_at FROM documents WHERE id = %s",
            (doc_id,),
        )
        row = cur.fetchone()
    if not row:
        return None
    return {
        "id": row[0],
        "filename": row[1],
        "kind": row[2],
        "status": row[3],
        "chunks_count": row[4],
        "error": row[5],
        "created_at": str(row[6]),
    }


def list_documents(conn: psycopg.Connection) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id, filename, kind, status, chunks_count FROM documents ORDER BY created_at"
        )
        rows = cur.fetchall()
    return [
        {"id": r[0], "filename": r[1], "kind": r[2], "status": r[3], "chunks_count": r[4]}
        for r in rows
    ]


def delete_chunks(conn: psycopg.Connection, doc_id: str) -> None:
    with conn.cursor() as cur:
        cur.execute("DELETE FROM chunks WHERE doc_id = %s", (doc_id,))
