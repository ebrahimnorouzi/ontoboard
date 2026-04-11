"""SPARQL query schemas."""

from pydantic import BaseModel


class SparqlQuery(BaseModel):
    query: str
    format: str = "json"  # json | csv | turtle (for CONSTRUCT)


class SparqlResult(BaseModel):
    columns: list[str] = []
    rows: list[dict] = []
    total: int = 0
    execution_time_ms: float = 0
    query_type: str = "SELECT"  # SELECT | CONSTRUCT | ASK | DESCRIBE


class GraphNode(BaseModel):
    id: str
    label: str
    type: str = ""  # rdf:type value
    color: str = ""


class GraphEdge(BaseModel):
    source: str
    target: str
    label: str
    iri: str = ""


class GraphVisualization(BaseModel):
    nodes: list[GraphNode] = []
    edges: list[GraphEdge] = []
