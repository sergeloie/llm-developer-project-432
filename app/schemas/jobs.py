from pydantic import BaseModel

from app.schemas.cards import CardDraft


class CardRequest(BaseModel):
    supplier_text: str


class JobOut(BaseModel):
    id: str
    status: str
    attempts: int
    result: CardDraft | None = None
    error: str | None = None
