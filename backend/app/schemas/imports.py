"""Import management schemas."""

from pydantic import BaseModel


class ImportAdd(BaseModel):
    prefix: str = ""
    iri: str
    mirror: bool = False


class ImportInfo(BaseModel):
    iri: str
    prefix: str = ""
    mirrored: bool = False


class CatalogEntry(BaseModel):
    name: str
    uri: str
