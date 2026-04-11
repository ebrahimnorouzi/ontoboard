"""Tree browser schemas — hierarchical entity views."""

from __future__ import annotations
from pydantic import BaseModel


class TreeNode(BaseModel):
    iri: str
    label: str
    entity_type: str  # "class" | "object_property" | "data_property" | "annotation_property" | "individual"
    children: list[TreeNode] = []
    annotation_count: int = 0


class AnnotationValue(BaseModel):
    property_iri: str
    property_label: str
    value: str
    language: str | None = None
    datatype: str | None = None


class UsageInfo(BaseModel):
    subject_iri: str
    subject_label: str
    predicate: str
    role: str  # "subject" | "object"


class EntityDetail(BaseModel):
    iri: str
    label: str
    entity_type: str
    annotations: list[AnnotationValue] = []
    axiom_count: int = 0
    usages: list[UsageInfo] = []


class AnnotationUpdate(BaseModel):
    property_iri: str
    value: str
    language: str | None = None
    action: str = "add"  # "add" | "edit" | "delete"


class EntityCreateRequest(BaseModel):
    entity_type: str  # "class" | "object_property" | "data_property" | "individual"
    iri: str
    label: str
    parent_iri: str | None = None
