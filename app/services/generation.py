"""Сквозной сценарий: документы → поиск → контекст → карточка со ссылками.

Зависит от подменяемого ``complete`` (как и пайплайн), поэтому тестируется
без модели и без Temporal.
"""
from typing import Any, Awaitable, Callable, Optional

from app import schemas
from app.agents import prompts
from app.core import db
from app.core.config import settings
from app.core.logging import get_logger
from app.llm import client as llm
from app.repositories import documents as docs_repo
from app.repositories import jobs as jobs_repo
from app.services import context as ctx
from app.services import parse, search as search_svc

log = get_logger(__name__)
CompleteFn = Callable[..., Awaitable[Any]]


async def _search(
    document_ids: Optional[list[int]], hint: Optional[str], search_fn: Optional[Callable]
) -> list[dict]:
    query = hint or "товар"
    fn = search_fn
    if fn is None:
        fn = search_svc.search
    return await fn(query, mode="hybrid", top_k=settings.search_top_k, document_ids=document_ids)


def validate_citations(
    draft: schemas.CardDraft, context_ids: list[int], conn=None
) -> tuple[bool, list[int]]:
    allowed = set(context_ids)
    invalid: list[int] = []
    for src in draft.sources or []:
        fid = src.fragment_id
        if fid not in allowed:
            invalid.append(fid)
    if conn is not None:
        existing = {r["id"] for r in docs_repo.chunks_by_ids(conn, list(allowed | set(invalid)))}
        for src in draft.sources or []:
            if src.fragment_id not in existing:
                if src.fragment_id not in invalid:
                    invalid.append(src.fragment_id)
    return len(invalid) == 0, invalid


async def generate_for_documents(
    document_ids: Optional[list[int]] = None,
    hint: Optional[str] = None,
    *,
    job_id: Optional[str] = None,
    complete: CompleteFn = llm.complete,
    search_fn: Optional[Callable] = None,
    context_limit: Optional[int] = None,
) -> dict[str, Any]:
    """Главная функция сценария. Возвращает словарь с карточкой, источниками и статусом."""
    # статусы пишем в базу, если есть job_id
    def _set(status: str, result=None, error=None):
        if job_id is None:
            return
        try:
            with db.connection() as conn:
                if result is not None:
                    jobs_repo.set_result(conn, job_id, result, status)
                else:
                    jobs_repo.update_status(conn, job_id, status, error=error)
        except Exception as exc:  # noqa: BLE001
            log.warning("job_status_write_failed", job_id=job_id, error=str(exc))

    _set("searching")
    try:
        hits = await _search(document_ids, hint, search_fn)
    except Exception as exc:  # noqa: BLE001
        _set("failed_error", error=str(exc))
        raise

    _set("searching")
    context, source_ids = ctx.build_context(hits, char_limit=context_limit)

    _set("generating")
    feedback: list[str] = []
    draft: schemas.CardDraft | None = None
    invalid: list[int] = []
    attempts = 0
    for attempt in range(1, settings.max_pipeline_rounds + 1):
        attempts = attempt
        _set("generating")
        system, user = prompts.generator_with_context_prompt(context, hint, feedback)
        resp = await complete(user, role="main", system=system)
        try:
            draft = _strict(resp.text)
        except Exception as exc:  # noqa: BLE001
            feedback = [f"Невалидный JSON/карточка: {exc}"]
            draft = None
            continue
        ok, invalid = validate_citations(draft, source_ids)
        if ok:
            break
        feedback = [f"Ссылки на фрагменты {invalid} отсутствуют в контексте — используй только fragment_id из источников."]

    _set("verifying")
    if draft is None:
        _set("failed_error", error="не удалось сгенерировать карточку")
        return {
            "draft": None,
            "source_ids": source_ids,
            "context": context,
            "invalid_citations": invalid,
            "needs_human": True,
            "attempts": attempts,
        }

    # проверка критиком — опционально, не блокирует и не ломает поток
    ok_cite, invalid = validate_citations(draft, source_ids)
    if not ok_cite or draft.confidence < settings.confidence_human_threshold or invalid:
        status = "needs_human" if invalid or draft.confidence < settings.confidence_human_threshold else "awaiting_human"
        if invalid:
            status = "needs_human"
        _set("awaiting_human" if status == "awaiting_human" else "needs_human",
            result={"card": draft.model_dump(), "sources": [s.model_dump() for s in draft.sources], "source_ids": source_ids})
    else:
        _set("awaiting_human", result={"card": draft.model_dump(), "sources": [s.model_dump() for s in draft.sources], "source_ids": source_ids})

    final_status = "needs_human" if (invalid or draft.confidence < settings.confidence_human_threshold) else "awaiting_human"
    _set(final_status, result={"card": draft.model_dump(), "sources": [s.model_dump() for s in draft.sources], "source_ids": source_ids, "invalid_citations": invalid})

    return {
        "draft": draft,
        "source_ids": source_ids,
        "context": context,
        "invalid_citations": invalid,
        "needs_human": final_status == "needs_human",
        "attempts": attempts,
        "status": final_status,
    }


def _strict(text: str) -> schemas.CardDraft:
    data = parse.extract_json(text)
    draft = schemas.CardDraft.model_validate(data)
    if len(draft.title) > settings.card_title_max_len:
        raise ValueError(f"заголовок длиннее {settings.card_title_max_len}")
    return draft


# Алиасы
generate_for_docs = generate_for_documents
generate_from_documents = generate_for_documents
generate_document_card = generate_for_documents
run_document_generation = generate_for_documents
generate_card_from_documents = generate_for_documents