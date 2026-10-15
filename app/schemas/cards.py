from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from app.core.config import settings


class SupplierFacts(BaseModel):
    """Результат extractor-агента: структурированные факты из текста поставщика."""

    product_name: str = Field(description="Название товара из текста поставщика")
    characteristics: dict[str, str] = Field(
        default_factory=dict, description="Характеристики: ключ-значение"
    )
    missing_fields: list[str] = Field(
        default_factory=list,
        description="Что не найдено в тексте (например: гарантия, цвет)",
    )


class SourceRef(BaseModel):
    """Ссылка на источник утверждения: конкретный чанк документа."""

    chunk_id: str = Field(description="Идентификатор чанка в БД")
    doc_id: str = Field(default="", description="Идентификатор документа")
    page: int | None = Field(default=None, description="Страница источника")
    score: float | None = Field(default=None, description="Релевантность чанка")


class Seo(BaseModel):
    title: str = Field(default="", description="SEO-заголовок до 100 символов")
    description: str = Field(default="", description="SEO-описание до 200 символов")
    keywords: list[str] = Field(default_factory=list)


class CardDraft(BaseModel):
    """Результат generator-агента: черновик карточки товара.

    Полный контракт карточки: контент-поля, SEO-блок, цитирование источников
    (заполняется на шаге retrieval-генерации), честность о неполноте данных
    и самооценка уверенности модели.
    """

    title: str = Field(description="Заголовок карточки, до 60 символов")
    description: str = Field(description="Описание на 3-4 предложения")
    characteristics: dict[str, str] = Field(default_factory=dict)
    benefits: list[str] = Field(default_factory=list)
    seo: Seo = Field(default_factory=Seo)
    sources: list[SourceRef] = Field(
        default_factory=list,
        description="Источники утверждений: chunk_id + doc_id + page",
    )
    missing_fields: list[str] = Field(
        default_factory=list,
        description="Что важно для карточки, но данных нет; поле НЕ выдумывать",
    )
    confidence: float = Field(
        default=0.5,
        description="Самооценка модели: 0.0 — данных нет, 1.0 — всё подтверждено",
    )

    @field_validator("title")
    @classmethod
    def title_max_length(cls, v: str) -> str:
        if len(v) > settings.card_title_max_len:
            raise ValueError(
                f"заголовок длиннее {settings.card_title_max_len} символов ({len(v)}): сократи"
            )
        return v

    @field_validator("confidence")
    @classmethod
    def confidence_range(cls, v: float) -> float:
        if not 0.0 <= v <= 1.0:
            raise ValueError("confidence должен быть в [0.0, 1.0]")
        return v

    @field_validator("characteristics")
    @classmethod
    def no_empty_characteristics(cls, v: dict[str, str]) -> dict[str, str]:
        return {k: val for k, val in v.items() if str(val).strip()}

    @model_validator(mode="after")
    def missing_fields_not_in_characteristics(self) -> "CardDraft":
        dupes = set(self.missing_fields) & set(self.characteristics.keys())
        if dupes:
            raise ValueError(
                f"поля одновременно в characteristics и missing_fields: {sorted(dupes)}"
            )
        return self


class CritiqueReport(BaseModel):
    """Результат critic-агента: вердикт и список проблем."""

    verdict: Literal["approve", "regenerate"] = Field(
        description="approve — карточка годится; regenerate — переделать"
    )
    issues: list[str] = Field(
        default_factory=list,
        description="Список проблем: заголовок длиннее 60 символов, "
        "выдуманные характеристики, пустые поля и т.п.",
    )
