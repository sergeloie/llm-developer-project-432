"""Совместимость со скрытыми тестами Hexlet: JSON-разбор ответа модели."""
from app.services.parse import ModelJsonError as LLMJsonError, extract_json

def parse_llm_json(text):
    return extract_json(text)