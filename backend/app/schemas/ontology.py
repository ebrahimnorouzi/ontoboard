"""Ontology metadata and statistics schemas."""

from pydantic import BaseModel


class PrefixEntry(BaseModel):
    prefix: str
    namespace: str


class OntologyMetadata(BaseModel):
    ontology_iri: str
    version_iri: str | None = None
    imports: list[str] = []
    prefixes: list[PrefixEntry] = []
    languages: list[str] = []


class OntologyStats(BaseModel):
    classes: int = 0
    object_properties: int = 0
    data_properties: int = 0
    annotation_properties: int = 0
    individuals: int = 0
    total_axioms: int = 0
    subclass_axioms: int = 0
    equivalent_axioms: int = 0
    disjoint_axioms: int = 0
    domain_axioms: int = 0
    range_axioms: int = 0
    total_triples: int = 0


class ReportViolation(BaseModel):
    subject: str = ""
    property: str = ""
    severity: str = "INFO"
    message: str = ""
    rule: str = ""


class RobotReportResult(BaseModel):
    exit_code: int = 0
    violations: list[ReportViolation] = []
    summary: str = ""


class DashboardData(BaseModel):
    metadata: OntologyMetadata
    statistics: OntologyStats
    report: RobotReportResult | None = None
