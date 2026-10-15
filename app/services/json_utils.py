"""Парсер «грязного» JSON из ответов LLM.

Локальные модели любят оборачивать JSON в markdown-заборы и добавлять
болтовню до/после. Здесь одна точка, где это чистится.
"""
import json
from typing import Any


class LLMJsonError(ValueError):
    """Ответ не содержит распознаваемый JSON."""


def parse_llm_json(text: Any) -> dict:
    """Достать JSON-объект из ответа модели.

    Понимает: чистый JSON, JSON в ```-заборах, JSON с текстом вокруг,
    а также python-repr (title='...' вместо "title": ...) — локальные
    reasoning-модели иногда выдают именно его.
    Бросает LLMJsonError, если объекта не нашлось.
    """
    s = str(text).strip()
    if not s:
        raise LLMJsonError("пустой ответ модели")
    # Снятие markdown-заборов
    if s.startswith("```"):
        s = s.split("\n", 1)[1] if "\n" in s else s
        s = s.rsplit("```", 1)[0].strip()
    try:
        return json.loads(s)
    except json.JSONDecodeError:
        pass
    # JSON с болтовнёй вокруг: ищем внешний объект по скобкам
    start = s.find("{")
    end = s.rfind("}")
    if start != -1 and end > start:
        try:
            obj = json.loads(s[start : end + 1])
            if isinstance(obj, dict):
                return obj
        except json.JSONDecodeError:
            pass
    # Python-repr: {"k": 'v'} / dict с одинарными кавычками
    if "='" in s or "'=" in s or "={'" in s or "= '" in s:
        import ast

        start = s.find("{")
        end = s.rfind("}")
        if start != -1 and end > start:
            candidate = s[start : end + 1]
            try:
                obj = ast.literal_eval(candidate)
                if isinstance(obj, dict):
                    return obj
            except (ValueError, SyntaxError):
                pass
        # repr без скобок объекта целиком: title=... description=...
        try:
            obj = ast.literal_eval("{" + s + "}")
            if isinstance(obj, dict):
                return obj
        except (ValueError, SyntaxError):
            pass
    raise LLMJsonError(f"JSON не найден в ответе: {s[:200]!r}")
