"""Refactoring schemas."""

from pydantic import BaseModel


class RenameRequest(BaseModel):
    old_iri: str
    new_iri: str


class MoveRequest(BaseModel):
    entity_iri: str
    new_parent_iri: str
    old_parent_iri: str | None = None
