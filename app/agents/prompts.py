"""Промпты как код: каждый промпт — функция, а не строка внутри логики.

LM Studio не поддерживает strict structured output — схема результата
дублируется текстом в инструкции (это осознанное решение, шаг 4: п.2).
"""
import json

from app import schemas
from app.core.config import settings


def _schema_text(model_cls) -> str:
    """JSON-схема модели текстом для промпта (дешёвая замена strict mode).

    JSON-схема формальна, но модели чаще понимают «форму ответа» — пример
    объекта. Отдаём обе сущности: компактную схему-подсказку рядом.
    """
    schema = model_cls.model_json_schema()
    return "\n```" + json.dumps(schema, ensure_ascii=False, indent=1) + "```"


def _example_text(model_cls) -> str:
    """Пример заполненной формы: работает на маленьких моделях лучше схемы."""
    schema = model_cls.model_json_schema()
    props = schema.get("properties", {})
    example = {}
    for name, prop in props.items():
        typ = prop.get("type")
        if "anyOf" in prop and not typ:
            typ = next(p2.get("type") for p2 in prop["anyOf"] if p2.get("type"))
        if typ == "string":
            example[name] = f"<{name}>"
        elif typ == "object":
            example[name] = {}
        elif typ == "array":
            example[name] = []
        elif typ == "number":
            example[name] = 0.5
        else:
            example[name] = None
    if "title" in example:
        example["title"] = "МиксерПро 800"
    return json.dumps(example, ensure_ascii=False, indent=1)


JSON_ONLY = (
    "Ответ — ТОЛЬКО валидный JSON без markdown-заборов, без пояснений,"
    " без текста до и после."
)


def extractor_prompt(supplier_text: str) -> tuple[str, str]:
    """Extract-роль: (system, user)."""
    system = (
        "Ты извлекаешь факты о товаре из текста поставщика.\n"
        "Выдели только то, что есть в тексте. Ничего не выдумывай: если важного "
        "для карточки (гарантия, цвет, страна-производитель и т.п.) нет — "
        "внеси это поле в missing_fields.\n"
        f"Схема ответа:\n{_schema_text(schemas.SupplierFacts)}\n"
        + f"Пример заполненной формы:\n{_example_text(schemas.SupplierFacts)}\n"
        + JSON_ONLY
    )
    return system, f"Текст поставщика:\n\n{supplier_text}"


def generator_prompt(facts: schemas.SupplierFacts, feedback: list[str] | None) -> tuple[str, str]:
    """Generate-роль: черновик карточки из фактов."""
    system = (
        "Ты копирайтер маркетплейса. Из фактов о товаре составь черновик карточки.\n"
        f"Правила:\n- заголовок не длиннее {settings.card_title_max_len} символов\n"
        "- используй ТОЛЬКО факты; чего нет в фактах — не выдумывай, а впиши имя поля в missing_fields\n"
        "- значения характеристик не пустые\n"
        "- confidence (0..1) отражает полноту фактов: всё ключевое есть — выше "
        f"{settings.confidence_human_threshold}; многого не хватает — ниже\n"
        + JSON_ONLY
    )
    feedback_text = ""
    if feedback:
        feedback_text = "\n\nЗамечания предыдущей проверки, учти:\n" + "\n".join(
            f"- {i}" for i in feedback
        )
    schema = {
        "title": "str, <=60",
        "description": "str, 3-5 предложений",
        "characteristics": {"ключ": "значение"},
        "benefits": ["str"],
        "seo": {"keywords": ["str"], "meta_description": "str"},
        "missing_fields": ["имя отсутствующего поля"],
        "confidence": "0..1",
    }
    user = (
        "Факты о товаре:\n"
        + facts.model_dump_json(indent=1)
        + "\n\nФорма ответа:\n"
        + json.dumps(schema, ensure_ascii=False, indent=1)
        + feedback_text
    )
    return system, user


def critic_prompt(facts: schemas.SupplierFacts, draft: schemas.CardDraft) -> tuple[str, str]:
    """Critic-роль: проверка черновика по правилам проекта."""
    system = (
        "Ты строгий редактор карточек маркетплейса. Проверь черновик против фактов:\n"
        f"1. Заголовок не длиннее {settings.card_title_max_len} символов\n"
        "2. Нет характеристик/утверждений, которых нет в фактах (выдуманные)\n"
        "3. Нет пустых значений характеристик\n"
        "4. Поле не в двух списках сразу (характеристики и missing_fields)\n"
        "5. missing_fields отражает реальные пробелы фактов\n"
        'Верди "approve", только если нарушений нет. Иначе "regenerate" и issues.\n'
        f"Схема ответа:\n{_schema_text(schemas.CritiqueReport)}\n"
        + f"Пример:\n{_example_text(schemas.CritiqueReport)}\n"
        + JSON_ONLY
    )
    user = (
        "Факты:\n"
        + facts.model_dump_json(indent=1)
        + "\n\nЧерновик карточки:\n"
        + draft.model_dump_json(indent=1)
    )
    return system, user
