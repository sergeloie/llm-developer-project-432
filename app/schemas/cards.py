"""Контракты результата и входных данных.

CardDraft — строгий контракт карточки (шаг 4):
валидаторы охраняют форму; валидация/лечение живут в services/pipeline.
"""
from typing import Any, Literal
from uuid import uuid4

from pydantic import BaseModel, Field, field_validator, model_validator


class SourceRef(BaseModel):
    """Ссылка карточки на источник: фрагмент, из которого взято значение."""

    fragment_id: int = Field(description="Идентификатор фрагмента в базе")
    label: str | None = Field(default=None, description="К какой характеристике относится")
    quote: str | None = Field(default=None, description="Ассоциированный текст источника")


class SeoBlock(BaseModel):
    """SEO-блок карточки."""

    keywords: list[str] = Field(default_factory=list, max_length=15)
    meta_description: str = Field(default="", max_length=300)


class SupplierFacts(BaseModel):
    """Результат extractor-агента: структурированные факты из текста."""

    product_name: str
    brand: str | None = None
    article: str | None = None
    characteristics: dict[str, str] = Field(default_factory=dict)
    missing_fields: list[str] = Field(default_factory=list)


class CardDraft(BaseModel):
    """Черновик карточки товара. Поля и ограничения — контракт шага 4."""

    title: str = Field(description="Заголовок карточки, <=card_title_max_len")
    description: str = Field(description="Описание на 3-5 предложений")
    characteristics: dict[str, str] = Field(default_factory=dict)
    benefits: list[str] = Field(default_factory=list)
    seo: SeoBlock = Field(default_factory=SeoBlock)
    missing_fields: list[str] = Field(
        default_factory=list,
        description="Чего нет в источниках: имя поля вместо выдуманного значения",
    )
    confidence: float = Field(ge=0, le=1, description="Уверенность 0..1")
    sources: list[SourceRef] = Field(default_factory=list)

    @field_validator("title")
    @classmethod
    def title_not_empty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("title пустой")
        return v.strip()

    @field_validator("characteristics")
    @classmethod
    def no_empty_values(cls, v: dict[str, str]) -> dict[str, str]:
        cleaned = {k: val.strip() for k, val in v.items()}
        bad = [k for k, val in cleaned.items() if not val]
        if bad:
            raise ValueError(f"пустые значения характеристик: {bad[:5]}")
        return cleaned

    @field_validator("benefits")
    @classmethod
    def benefits_not_empty_str(cls, v: list[str]) -> list[str]:
        for b in v:
            if not b.strip():
                raise ValueError("пустой элемент benefits")
        return [b.strip() for b in v]

    @model_validator(mode="after")
    def field_not_in_two_lists(self) -> "CardDraft":
        missing = {m.lower() for m in self.missing_fields}
        clash = [k for k in self.characteristics if k.lower() in missing]
        if clash:
            raise ValueError(
                f"поле одновременно в характеристиках и в недостающих: {clash[:5]}"
            )
        return self

    def check_title_limit(self, max_len: int) -> bool:
        return len(self.title) <= max_len


class CritiqueReport(BaseModel):
    """Результат critic-агента: вердикт и список нарушений."""

    verdict: Literal["approve", "regenerate"]
    issues: list[str] = Field(default_factory=list)


class JudgeVerdict(BaseModel):
    """Модель-судья (шаг 8): подтверждаются ли утверждения карточки источниками."""

    verdict: Literal["grounded", "ungrounded"]
    unsupported_statements: list[str] = Field(default_factory=list)
    justification: str = ""


class JobIn(BaseModel):
    supplier_text: str = Field(min_length=1, max_length=50000)


class CardIn(BaseModel):
    """Синхронная генерация по тексту (шаг 3/4)."""

    supplier_text: str
    hint: str | None = Field(default=None, description="Подсказка: что за товар")


class GenerateIn(BaseModel):
    """Генерация по документам (шаг 7)."""

    document_ids: list[int] | None = Field(
        default=None, description="Все документы, если не указано"
    )
    hint: str | None = None


class JobOut(BaseModel):
    id: str
    status: str
    attempts: int = 0
    result: dict[str, Any] | None = None
    error: str | None = None


class IdempotentResponse(BaseModel):
    id: str
    status: str


def new_id(prefix: str = "job") -> str:
    return f"{prefix}-{uuid4().hex[:12]}"
