"""Метрики качества на эталонном наборе (шаг 8)."""
import re
from difflib import SequenceMatcher

from app.core.logging import get_logger

log = get_logger(__name__)


def normalize_value(value: str) -> str:
    """Нормализация значения для сравнения characteristics."""
    if value is None:
        return ""
    s = str(value).strip().lower()
    s = s.replace("ё", "е")
    s = re.sub(r"[^\w\d,.]+", " ", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def value_matches(actual: str, expected: str) -> bool:
    na, ne = normalize_value(actual), normalize_value(expected)
    if not na or not ne:
        return False
    if na == ne:
        return True
    # числовое + единица измерения: сравниваем извлечённое число
    a_num = re.search(r"[-+]?\d+(?:[.,]\d+)?", na)
    e_num = re.search(r"[-+]?\d+(?:[.,]\d+)?", ne)
    if a_num and e_num and a_num.group(0) == e_num.group(0):
        return True
    return SequenceMatcher(None, na, ne).ratio() >= 0.9


def characteristics_match(actual: dict, expected: dict) -> float:
    if not expected:
        return 1.0
    hits = 0
    for key, exp_val in expected.items():
        act_val = (actual or {}).get(key)
        if act_val is None:
            # поиск по похожему ключу
            for ak, av in (actual or {}).items():
                if ak.strip().lower() == key.strip().lower():
                    act_val = av
                    break
        if value_matches(act_val, exp_val):
            hits += 1
    return hits / len(expected)


# алиасы
normalize = normalize_value
normalize_characteristic = normalize_value
characteristics_match_score = characteristics_match
match_characteristics = characteristics_match