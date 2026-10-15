from app.guards import pii, injection, output
from app.guards.pii import *
from app.guards.injection import *
from app.guards.output import *


class GuardReport:
    """Отчёт защиты; итерируется как (sanitized, self) для unpacking."""

    def __init__(self, payload=None, findings=None, text=None, categories=None):
        self.payload = payload
        self.sanitized = payload
        self.sanitized_text = payload if isinstance(payload, str) else None
        self.findings = findings or []
        self.text = text
        self.categories = categories or []
        self.suspicious = bool(self.findings)

    def __iter__(self):
        yield self.payload
        yield self

    def __len__(self):
        return 2

    def __getitem__(self, idx):
        return (self.payload, self)[idx]


def guard_context(context, **kwargs):
    text = context if isinstance(context, str) else str(context)
    sanitized, cats = pii.mask(text)
    reasons = injection.rule_reasons(text)
    findings = [f"pii:{c}" for c in cats] + reasons
    return GuardReport(payload=sanitized, findings=findings, text=text, categories=cats)


def guard_output(card_or_text, **kwargs):
    if hasattr(card_or_text, "title"):
        _card, findings = output.filter_card(card_or_text)
        payload = card_or_text
    else:
        findings = output.check_text(str(card_or_text))
        payload = card_or_text
    return GuardReport(payload=payload, findings=findings, text=str(card_or_text))