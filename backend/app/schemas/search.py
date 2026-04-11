"""Search schemas."""

from pydantic import BaseModel


class SearchRequest(BaseModel):
    query: str
    entity_types: list[str] | None = None  # class, object_property, data_property, individual, annotation_property
    limit: int = 50


class SearchResult(BaseModel):
    iri: str
    label: str
    entity_type: str
    match_field: str  # label | iri | comment
    snippet: str = ""
