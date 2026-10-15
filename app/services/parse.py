"""Разбор грязного JSON-ответа модели (шаг 4, задача 3).

Четыре случая: чистый JSON, JSON в markdown-заборах, JSON с текстом
до/после, полноценный мусор. Устойчиво: сначала прямые стратегии,
потом поиск первого сбалансированного {...}/[...].
"""
import json
import re
from typing import Any

from app.core.logging import get_logger

log = get_logger(__name__)


class ModelJsonError(Exception):
    """Ответ модели нельзя разобрать как JSON."""


def extract_json(text: str) -> dict[str, Any]:
    """Извлечь JSON-объект из ответа модели или ModelJsonError."""
    if not text or not text.strip():
        raise ModelJsonError("пустой ответ модели")
    s = text.strip()
    # 1) чистый JSON
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        pass
    # 2) markdown-заборы ```json ... ``` / ``` ... ```
    fence = re.search(r"```(?:json)?\s*(.*?)```", s, re.DOTALL)
    if fence:
        try:
            return json.loads(fence.group(1).strip())
        except json.JSONDecodeError:
            pass
        s = fence.group(1).strip()
    # 3) JSON с текстом до/после: берём первый сбалансированный объект
    balanced = _first_balanced_object(s)
    if balanced is not None:
        try:
            return json.loads(balanced)
        except json.JSONDecodeError:
            pass
    raise ModelJsonError(f"нельзя разобрать ответ как JSON: {s[:200]!r}")


def _first_balanced_object(s: str) -> str | None:
    for open_ch, close_ch in (("{", "}"), ("[", "]")):
        start = s.find(open_ch)
        if start == -1:
            continue
        depth = 0
        in_string = False
        escape = False
        for i in range(start, len(s)):
            ch = s[i]
            if in_string:
                if escape:
                    escape = False
                elif ch == "\\":
                    escape = True
                elif ch == '"':
                    in_string = False
                continue
            if ch == '"':
                in_string = True
            elif ch == open_ch:
                depth += 1
            elif ch == close_ch:
                depth -= 1
                if depth == 0:
                    return s[start : i + 1]
    return None


def repair_prompt(model, errors: list[str]) -> str:
    """Текст ошибки валидации для повторного запроса к модели (лечение)."""
    return (
        f"Предыдущий ответ не прошёл валидацию по этому полю: {model}.\n"
        "Ошибки:\n- " + "\n- ".join(errors) + "\n"
        "Исправь ТОЛЬКО перечисленные поля. Верни исправленный полный JSON"
        " в прежней форме."
    )
