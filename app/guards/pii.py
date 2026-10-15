"""PII-маскирование: телефон, почта, ИНН (с контрольной суммой), карта (Luhn)."""
import re

from app.core.logging import get_logger

log = get_logger(__name__)


def _inn_checksum_ok(digits: str) -> bool:
    if len(digits) == 10:
        weights = [2, 4, 10, 3, 5, 9, 4, 6, 8]
        s = sum(int(d) * w for d, w in zip(digits[:9], weights)) % 11 % 10
        return s == int(digits[9])
    if len(digits) == 12:
        w1 = [7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
        w2 = [3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8]
        s1 = sum(int(d) * w for d, w in zip(digits[:10], w1)) % 11 % 10
        s2 = sum(int(d) * w for d, w in zip(digits[:11], w2)) % 11 % 10
        return s1 == int(digits[10]) and s2 == int(digits[11])
    return False


def _luhn_ok(digits: str) -> bool:
    if len(digits) < 13:
        return False
    total = 0
    parity = len(digits) % 2
    for i, ch in enumerate(digits):
        d = int(ch)
        if i % 2 == parity:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def _mask_with_counter(text: str, pattern: str, category: str, counter: list[int]) -> tuple[str, bool]:
    found = False

    def repl(m):
        nonlocal found
        found = True
        counter[0] += 1
        return f"{{{{PII:{category} {counter[0]}}}}}"

    new = re.sub(pattern, repl, text)
    return new, found


def mask(text: str) -> tuple[str, list[str]]:
    found_categories: list[str] = []
    counters = {"телефон": [0], "почта": [0], "ИНН": [0], "карта": [0]}
    # email
    text, f = _mask_with_counter(text, r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "почта", counters["почта"])
    if f:
        found_categories.append("почта")
    # phone
    text, f = _mask_with_counter(text, r"(?<!\d)(?:\+7|8)[\s\-()]*\d[\d\s\-()]{8,}\d", "телефон", counters["телефон"])
    if f:
        found_categories.append("телефон")
    # inn: only valid checksums
    def inn_repl(m):
        digs = m.group(0)
        if not _inn_checksum_ok(digs):
            return digs
        nonlocal found_inn
        found_inn = True
        counters["ИНН"][0] += 1
        return f"{{{{PII:ИНН {counters['ИНН'][0]}}}}}"
    found_inn = False
    text = re.sub(r"(?<!\d)\d{10}(?!\d)|(?<!\d)\d{12}(?!\d)", inn_repl, text)
    if found_inn:
        found_categories.append("ИНН")
    # cards: luhn-valid 13-19 digits (possibly spaces/dashes)
    def card_repl(m):
        digits = re.sub(r"\D", "", m.group(0))
        if not _luhn_ok(digits):
            return m.group(0)
        nonlocal found_card
        found_card = True
        counters["карта"][0] += 1
        return f"{{{{PII:карта {counters['карта'][0]}}}}}"
    found_card = False
    text = re.sub(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)", card_repl, text)
    if found_card:
        found_categories.append("карта")
    return text, found_categories


def mask_pii(text: str) -> tuple[str, list[str]]:
    return mask(text)


def find_pii(text: str) -> list[str]:
    _t, cats = mask(text)
    return cats


def detect_pii(text: str):
    return mask(text)
_inn_checksum = _inn_checksum_ok
