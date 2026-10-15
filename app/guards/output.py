"""Фильтр выхода: контакты и следы инструкций в сгенерированной карточке."""
from app.core.logging import get_logger
from app.guards import injection, pii

log = get_logger(__name__)


def check_text(text: str) -> list[str]:
    findings = []
    _masked, cats = pii.mask(text)
    if cats:
        findings.extend([f"pii:{c}" for c in cats])
    if injection.is_rule_suspicious(text):
        findings.extend(injection.rule_reasons(text))
    return findings


def filter_card(card) -> tuple[object, list[str]]:
    findings: list[str] = []
    fields = []
    try:
        fields.append(getattr(card, "title", ""))
        fields.append(getattr(card, "description", ""))
        for v in (getattr(card, "characteristics", {}) or {}).values():
            fields.append(str(v))
        for b in getattr(card, "benefits", []) or []:
            fields.append(str(b))
        seo = getattr(card, "seo", None)
        if seo is not None:
            fields.append(getattr(seo, "meta_description", "") or "")
            for k in getattr(seo, "keywords", []) or []:
                fields.append(str(k))
    except Exception as exc:  # noqa: BLE001
        log.warning("output_filter_failed", error=str(exc))
    joined = "\n".join(str(x) for x in fields)
    findings = check_text(joined)
    return card, findings