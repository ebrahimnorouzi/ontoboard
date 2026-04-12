"""Canvas state schemas with provenance tracking."""

from pydantic import BaseModel


class CanvasClass(BaseModel):
    id: str
    iri: str
    label: str
    x: float = 0
    y: float = 0
    w: float = 160
    h: float = 60
    color: str = "violet"
    # Provenance
    created_by: str = ""
    created_at: str = ""
    modified_by: str = ""
    modified_at: str = ""


class CanvasProperty(BaseModel):
    id: str
    iri: str
    label: str
    source_id: str
    target_id: str
    property_type: str = "object"  # "object" | "data" | "annotation"
    # Provenance
    created_by: str = ""
    created_at: str = ""
    modified_by: str = ""
    modified_at: str = ""


class CanvasIndividual(BaseModel):
    id: str
    iri: str
    label: str
    class_iri: str = ""
    x: float = 0
    y: float = 0
    # Provenance
    created_by: str = ""
    created_at: str = ""
    modified_by: str = ""
    modified_at: str = ""


class CanvasLiteral(BaseModel):
    id: str
    value: str = ""
    datatype: str = "xsd:string"
    language: str = ""
    x: float = 0
    y: float = 0


class CanvasStickyNote(BaseModel):
    id: str
    text: str = ""
    x: float = 0
    y: float = 0
    w: float = 200
    h: float = 150
    color: str = "#fef3c7"
    fontSize: int = 14


class CanvasState(BaseModel):
    classes: list[CanvasClass] = []
    properties: list[CanvasProperty] = []
    individuals: list[CanvasIndividual] = []
    literals: list[CanvasLiteral] = []
    sticky_notes: list[CanvasStickyNote] = []
    # Board-level provenance settings
    track_provenance: bool = True
    provenance_target: str = "both"  # "board" | "ontology" | "both"
