"""CSV import / ROBOT Template Builder schemas."""

from pydantic import BaseModel


# ── Analysis ──────────────────────────────────────────────────

class ColumnInfo(BaseModel):
    name: str
    sample_values: list[str] = []
    suggested_type: str = "A"  # ID | TYPE | A rdfs:label | A | I | IGNORE


class CsvAnalysis(BaseModel):
    filename: str
    columns: list[ColumnInfo]
    row_count: int
    delimiter: str = ","
    sample_rows: list[dict] = []


# ── Ontology entities ────────────────────────────────────────

class OntologyEntity(BaseModel):
    iri: str
    label: str


class OntologyEntities(BaseModel):
    classes: list[OntologyEntity] = []
    object_properties: list[OntologyEntity] = []
    data_properties: list[OntologyEntity] = []
    annotation_properties: list[OntologyEntity] = []


# ── Column mapping ────────────────────────────────────────────

class ColumnMapping(BaseModel):
    column_name: str
    directive_type: str = "A"       # ID | TYPE | A rdfs:label | A rdfs:comment | A | I | IGNORE
    property_iri: str = ""          # IRI of the property (for A/I directives)
    split_char: str = ""            # e.g. "," for multi-valued columns
    ref_type: str = ""              # TYPE (class label/IRI) for referenced entities created by expansion


# ── Template generation request ───────────────────────────────

class TemplateRequest(BaseModel):
    filename: str
    column_mappings: list[ColumnMapping]
    iri_strategy: str = "auto_sequential"  # auto_sequential | auto_uuid | auto_hash | from_column | custom_pattern
    base_iri: str = "https://example.org/resource"
    template_name: str | None = None


class TemplateResult(BaseModel):
    template_path: str
    template_name: str
    preview_rows: list[list[str]] = []
    total_data_rows: int = 0


# ── Build KG request ─────────────────────────────────────────

class BuildRequest(BaseModel):
    template_path: str


class BuildResult(BaseModel):
    success: bool
    output_path: str = ""
    triples_count: int = 0
    error: str | None = None
    logs: list[dict] = []
    consistency_check: dict | None = None


# ── Merge request ─────────────────────────────────────────────

class MergeRequest(BaseModel):
    file_paths: list[str]


class MergeResult(BaseModel):
    success: bool
    output_path: str = ""
    triples_count: int = 0
    error: str | None = None


# ── File listing ──────────────────────────────────────────────

class KgFileInfo(BaseModel):
    name: str
    size: int = 0
    path: str = ""


class KgFiles(BaseModel):
    uploads: list[KgFileInfo] = []
    templates: list[KgFileInfo] = []
    output: list[KgFileInfo] = []
