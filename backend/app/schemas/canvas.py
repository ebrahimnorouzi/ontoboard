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
    sticky_notes: list[CanvasStickyNote] = []
