"""Axiom request/response schemas."""

from pydantic import BaseModel


class AxiomInfo(BaseModel):
    axiom_type: str
    subject: str
    predicate: str
    object: str
    manchester: str


class AxiomEditRequest(BaseModel):
    manchester_text: str


class AxiomEditResult(BaseModel):
    success: bool
    applied: int = 0
    errors: list[dict] = []
    warnings: list[str] = []


class ValidationError(BaseModel):
    line: int
    column: int
    message: str


class EntityName(BaseModel):
    iri: str
    label: str
    type: str
