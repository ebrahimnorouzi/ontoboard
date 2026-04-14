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


# --- Import resolution schemas ---

class ResolvedImport(BaseModel):
    iri: str
    status: str  # "local" | "remote" | "missing"
    local_path: str | None = None
    classes_count: int = 0
    properties_count: int = 0


class ImportDownloadRequest(BaseModel):
    iri: str


class ImportDownloadResult(BaseModel):
    success: bool
    local_path: str = ""
    classes_count: int = 0
    properties_count: int = 0
    error: str | None = None
