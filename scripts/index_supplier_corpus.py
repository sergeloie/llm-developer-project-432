"""Индексация корпуса поставщиков в dev-Postgres для замера порога retrieval.

Использует штатный ingestion (parse -> chunk -> embed) проекта, поэтому
эмбеддинги и tsvector совпадают с тем, что видит продакшен-пайплайн.

Эмбеддинг-бэкенд берётся из окружения (EMBED_BACKEND/EMBED_MODEL); по
умолчанию — LM Studio с text-embedding-embeddinggemma-300m, поднятым локально.

Запуск:
    $env:EMBED_BACKEND="lmstudio"
    $env:EMBED_MODEL="text-embedding-embeddinggemma-300m"
    .venv\\Scripts\\python.exe scripts\\index_supplier_corpus.py
"""
import os
from pathlib import Path

# Важно: выставить бэкенд эмбеддингов ДО импорта settings.
os.environ.setdefault("EMBED_BACKEND", "lmstudio")
os.environ.setdefault("EMBED_MODEL", "text-embedding-embeddinggemma-300m")

from app.core import db
from app.core.logging import get_logger, setup_logging
from app.repositories import chunks as chunks_repo
from app.repositories import documents as docs_repo
from app.services.documents import ingest

log = get_logger(__name__)

CORPUS = Path(r"C:\DevProjects\data-supplier-docs\supplier-docs")

_KINDS = {".pdf": "pdf", ".docx": "docx", ".xlsx": "xlsx"}


def kind_of(path: Path) -> str | None:
    return _KINDS.get(path.suffix.lower())


def main() -> None:
    setup_logging()
    paths = sorted(p for p in CORPUS.iterdir() if p.is_file() and kind_of(p))
    log.info("corpus_found", count=len(paths))

    total_chunks = 0
    indexed = 0
    skipped = 0
    with db.connection() as conn:
        for p in paths:
            kind = kind_of(p)
            content = p.read_bytes()
            doc_id, created = docs_repo.create_document(conn, p.name, content, kind)
            # Идемпотентность: если уже проиндексирован и эмбеддинги на месте — пропускаем.
            if not created:
                pending = chunks_repo.chunks_without_embedding(conn, doc_id=doc_id)
                doc = docs_repo.get_document(conn, doc_id)
                if doc and doc["status"] == "indexed" and not pending:
                    log.info("skip_already_indexed", name=p.name)
                    skipped += 1
                    continue
            n = ingest(conn, doc_id, content, kind, embed=True)
            total_chunks += n
            indexed += 1
            log.info("indexed", name=p.name, chunks=n)
    log.info("corpus_done", indexed=indexed, skipped=skipped, total_chunks=total_chunks)


if __name__ == "__main__":
    main()
