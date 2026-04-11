"""Reasoning schemas."""

from pydantic import BaseModel


class ReasoningRequest(BaseModel):
    reasoner: str = "ELK"  # "ELK" | "HermiT" | "JFact" | "Whelk"


class Inference(BaseModel):
    inference_type: str  # "SubClassOf" | "ClassAssertion" | "EquivalentClass" | ...
    subject: str
    subject_label: str = ""
    predicate: str
    object: str
    object_label: str = ""


class ReasoningError(BaseModel):
    entity_iri: str
    entity_label: str = ""
    axiom: str = ""
    message: str
    severity: str = "error"


class FixSuggestion(BaseModel):
    error_index: int
    description: str
    action: str  # "remove_axiom" | "weaken_axiom" | "add_disjoint"
    target_axiom: str = ""
    target_entity: str = ""


class ReasoningResult(BaseModel):
    success: bool
    consistent: bool = True
    reasoner: str = ""
    inferences: list[Inference] = []
    errors: list[ReasoningError] = []
    fixes: list[FixSuggestion] = []
    logs: str = ""
    duration_seconds: float = 0
