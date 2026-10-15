"""Guardrails-фасад: единая точка применения защиты к контексту и карточке."""
from dataclasses import dataclass, field

from app.core.logging import get_logger
from app.guardrails.injection import detect_injection_llm, detect_injection_regex
from app.guardrails.pii import contains_pii, mask_pii

log = get_logger(__name__)


@dataclass
class GuardReport:
    """Что сделала защита — попадает в результат job'а."""
    masked_pii: int = 0
    suspicious_chunks: list[str] = field(default_factory=list)
    blocked_reasons: list[str] = field(default_factory=list)
    # Оценка retrieval по каждому чанку, реально дошедшему до модели: источник без
    # doc_id и без score — это не источник, а голый идентификатор чанка.
    retrieval_scores: dict[str, float] = field(default_factory=dict)

    @property
    def needs_review(self) -> bool:
        from app.core.config import settings

        return len(self.suspicious_chunks) > settings.suspicious_chunk_limit


async def guard_context(chunks: list[dict]) -> tuple[list[dict], GuardReport]:
    """Три рубежа ДО LLM: PII-маскирование → regex-детект → LLM-детект.

    Возвращает (безопасные чанки, отчёт). Подозрительные чанки исключаются;
    если их больше лимита — отчёт требует ручной проверки документа.
    """
    report = GuardReport()
    safe: list[dict] = []

    for ch in chunks:
        # 1. PII маскируется всегда — до LLM и логов
        masked, entities = mask_pii(ch["text"])
        if entities:
            report.masked_pii += len(entities)
            ch = {**ch, "text": masked}

        # 2. regex: дёшево, на каждом чанке
        verdict = detect_injection_regex(masked)
        if verdict.suspicious:
            # 3. LLM: только по подозрительным (экономия вызовов); fail-closed
            try:
                llm_verdict = await detect_injection_llm(masked)
                confirmed = llm_verdict.suspicious
                reason = llm_verdict.reason or verdict.reason
            except Exception as exc:
                log.warning("injection_llm_failed", chunk=ch["chunk_id"], error=repr(exc))
                confirmed = True
                reason = verdict.reason
            if confirmed:
                report.suspicious_chunks.append(ch["chunk_id"])
                report.blocked_reasons.append(f"{ch['chunk_id']}: {reason}")
                continue  # чанк исключается из контекста
        safe.append(ch)

    return safe, report


def guard_output(card_json: str) -> tuple[str, list[str]]:
    """Фильтр выхода: PII не должно просочиться в карточку.

    Возвращает (очищенный JSON, список замечаний).
    """
    issues: list[str] = []
    cleaned = card_json
    if contains_pii(card_json):
        cleaned, entities = mask_pii(card_json)
        issues.append(f"в карточке найдено и замаскировано PII: {len(entities)}")
    # Следы выполнения инъекции из датасета
    for marker in ("1 рубль", "1 руб.", "promo@spammlot.example"):
        if marker in card_json:
            issues.append(f"в карточке след инъекции: {marker!r}")
    return cleaned, issues
