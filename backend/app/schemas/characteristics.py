"""Property characteristics and chain schemas."""

from pydantic import BaseModel


class CharacteristicsUpdate(BaseModel):
    """Set property characteristics (all are optional booleans)."""
    functional: bool | None = None
    inverse_functional: bool | None = None
    transitive: bool | None = None
    symmetric: bool | None = None
    asymmetric: bool | None = None
    reflexive: bool | None = None
    irreflexive: bool | None = None


class CharacteristicsInfo(BaseModel):
    """Current characteristics of a property."""
    functional: bool = False
    inverse_functional: bool = False
    transitive: bool = False
    symmetric: bool = False
    asymmetric: bool = False
    reflexive: bool = False
    irreflexive: bool = False


class PropertyChainCreate(BaseModel):
    """Create a property chain axiom: chain_properties ⊆ super_property."""
    super_property: str  # IRI of the super-property
    chain_properties: list[str]  # Ordered list of property IRIs in the chain


class AllDisjointRequest(BaseModel):
    """Declare a set of classes as mutually disjoint."""
    class_iris: list[str]  # At least 2 IRIs


class DisjointPropertiesRequest(BaseModel):
    """Declare properties as disjoint."""
    property_iris: list[str]
