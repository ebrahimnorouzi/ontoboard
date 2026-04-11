"""OWL restriction and complex class expression schemas."""

from pydantic import BaseModel


class RestrictionCreate(BaseModel):
    """Create an OWL restriction as a SubClassOf axiom on an entity."""
    restriction_type: str  # someValuesFrom | allValuesFrom | hasValue | minCardinality | maxCardinality | exactCardinality
    on_property: str       # IRI of the property
    filler: str = ""       # IRI of filler class (for someValuesFrom/allValuesFrom) or literal value (for hasValue)
    cardinality: int | None = None  # For min/max/exact cardinality
    qualified_class: str | None = None  # For qualified cardinality restrictions


class ComplexExpressionCreate(BaseModel):
    """Create a complex class expression (union, intersection, complement)."""
    expression_type: str  # unionOf | intersectionOf | complementOf
    operands: list[str]   # IRIs of the classes in the expression


class RestrictionInfo(BaseModel):
    """Description of a restriction on an entity."""
    restriction_type: str
    on_property: str
    on_property_label: str = ""
    filler: str = ""
    filler_label: str = ""
    cardinality: int | None = None
    qualified_class: str | None = None
    manchester: str = ""


class RestrictionDeleteRequest(BaseModel):
    """Identify a restriction to remove."""
    restriction_type: str
    on_property: str
    filler: str = ""
    cardinality: int | None = None
