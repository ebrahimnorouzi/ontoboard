"""CSV import and KG generation schemas."""

from pydantic import BaseModel


class ColumnInfo(BaseModel):
    name: str
    inferred_type: str = "string"  # string | integer | float | date | uri
    unique_count: int = 0
    sample_values: list[str] = []


class CsvAnalysis(BaseModel):
    filename: str
    separator: str
    row_count: int
    columns: list[ColumnInfo]
    sample_rows: list[dict] = []


class ColumnMapping(BaseModel):
    column: str
    target_iri: str
    mapping_type: str = "data_property"  # class_assertion | data_property | object_property | annotation
    iri_role: str = "value"  # value | type | skip


class KgPreviewRequest(BaseModel):
    csv_file: str
    mappings: list[ColumnMapping]
    iri_strategy: str = "sequential"  # uuid | hash | sequential | pattern
    iri_pattern: str | None = None
    base_iri: str = "http://example.org/instance"
    limit: int = 5


class KgBuildRequest(BaseModel):
    csv_file: str
    mappings: list[ColumnMapping]
    iri_strategy: str = "sequential"
    iri_pattern: str | None = None
    base_iri: str = "http://example.org/instance"


class KgBuildResult(BaseModel):
    triples_count: int
    individuals_count: int
    output_path: str
    preview: list[dict] = []
