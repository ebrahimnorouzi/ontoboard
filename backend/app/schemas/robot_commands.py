"""ROBOT command request schemas."""

from pydantic import BaseModel


class AnnotateRequest(BaseModel):
    annotations: dict = {}  # {property_iri: value, ...}


class RenameCommandRequest(BaseModel):
    mappings: dict = {}  # {old_iri: new_iri, ...}
    prefix_based: bool = False


class ExtractRequest(BaseModel):
    method: str = "STAR"  # STAR | TOP | BOT | MIREOT
    term_iris: list[str] = []
    output_format: str = "owl"


class FilterRequest(BaseModel):
    select: str = ""  # SPARQL-like filter expression
    include_imports: bool = False


class MergeCommandRequest(BaseModel):
    sources: list[str] = []  # Relative paths to OWL files


class ExplainRequest(BaseModel):
    axiom: str = ""  # The axiom to explain in Manchester Syntax


class ConvertRequest(BaseModel):
    output_format: str = "ttl"  # owl | ttl | obo | ofn | nt | jsonld


class RobotCommandResult(BaseModel):
    command: str
    exit_code: int
    stdout: str = ""
    stderr: str = ""
    output_file: str | None = None
