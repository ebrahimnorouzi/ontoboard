"""Canvas state schemas — richer than the old CanvasGraph."""

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


class CanvasProperty(BaseModel):
    id: str
    iri: str
    label: str
    source_id: str
    target_id: str
    property_type: str = "object"  # "object" | "data" | "annotation"


class CanvasIndividual(BaseModel):
    id: str
    iri: str
    label: str
    class_iri: str = ""
    x: float = 0
    y: float = 0


class CanvasState(BaseModel):
    classes: list[CanvasClass] = []
    properties: list[CanvasProperty] = []
    individuals: list[CanvasIndividual] = []
