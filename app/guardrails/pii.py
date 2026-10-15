"""PII-анонимизатор: телефоны, email, ИНН, карты — ДО подачи в LLM и в логи.

ИНН проверяется контрольной суммой, чтобы не маскировать случайные числа.
"""
import re
from dataclasses import dataclass

_PHONE_RE = re.compile(r"(?:\+7|8)[\s(-]?\d{3}[\s)-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}")
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_INN_RE = re.compile(r"\b\d{10}\b|\b\d{12}\b")
_CARD_RE = re.compile(r"\b(?:\d[ -]?){13,19}\b")


@dataclass
class MaskedEntity:
    kind: str   # phone | email | inn | card
    placeholder: str
    original: str  # только для внутреннего логирования — НЕ попадает в вывод


def _inn_checksum(digits: str) -> bool:
    """Контрольная сумма ИНН (10- и 12-значные)."""
    if len(digits) == 10:
        weights = [2, 4, 10, 3, 5, 9, 4, 6, 8]
        return int(digits[-1]) == (sum(w * int(d) for w, d in zip(weights, digits)) % 11) % 10
    if len(digits) == 12:
        w1 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
        w2 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
        c1 = (sum(w * int(d) for w, d in zip(w1, digits)) % 11) % 10
        c2 = (sum(w * int(d) for w, d in zip(w2, digits)) % 11) % 10
        return int(digits[10]) == c1 and int(digits[11]) == c2
    return False


def mask_pii(text: str) -> tuple[str, list[MaskedEntity]]:
    """Заменить PII на плейсхолдеры [PHONE_1], [EMAIL_1], [INN_1], [CARD_1].

    Возвращает (очищенный текст, список сущностей без исходников).
    """
    entities: list[MaskedEntity] = []
    counters = {"phone": 0, "email": 0, "inn": 0, "card": 0}
    result = text

    def _sub(pattern: re.Pattern, kind: str, check=None) -> None:
        nonlocal result

        def repl(m):
            if check and not check(m.group(0)):
                return m.group(0)
            counters[kind] += 1
            ph = f"[{kind.upper()}_{counters[kind]}]"
            entities.append(MaskedEntity(kind=kind, placeholder=ph, original=""))
            return ph

        result = pattern.sub(repl, result)

    _sub(_EMAIL_RE, "email")
    _sub(_PHONE_RE, "phone")
    _sub(
        _INN_RE,
        "inn",
        check=lambda s: _inn_checksum(re.sub(r"\D", "", s)),
    )
    _sub(_CARD_RE, "card", check=lambda s: len(re.sub(r"\D", "", s)) in (13, 16, 19))
    return result, entities


def contains_pii(text: str) -> bool:
    """Быстрая проверка для фильтра выхода: есть ли PII в тексте."""
    cleaned, found = mask_pii(text)
    return bool(found) and cleaned != text
