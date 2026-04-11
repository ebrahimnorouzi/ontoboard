"""SPARQL service — execute queries against the board's ontology + KG."""

import logging
import time
from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL

from app.services.ontology import load_full_graph

logger = logging.getLogger("ontoboard.sparql")

# Color palette for graph visualization node types
_TYPE_COLORS = {
    str(OWL.Class): "#6c5ce7",
    str(OWL.NamedIndividual): "#00cec9",
    str(OWL.ObjectProperty): "#fdcb6e",
    str(OWL.DatatypeProperty): "#fab1a0",
}


def execute_sparql(board_dir: Path, query: str) -> dict:
    """Execute a SPARQL query against all RDF data in the board."""
    t0 = time.time()
    g = load_full_graph(board_dir)

    query_stripped = query.strip().upper()
    if query_stripped.startswith("SELECT") or query_stripped.startswith("PREFIX"):
        query_type = _detect_query_type(query)
    elif query_stripped.startswith("CONSTRUCT"):
        query_type = "CONSTRUCT"
    elif query_stripped.startswith("ASK"):
        query_type = "ASK"
    elif query_stripped.startswith("DESCRIBE"):
        query_type = "DESCRIBE"
    else:
        query_type = "SELECT"

    try:
        result = g.query(query)
    except Exception as exc:
        return {
            "columns": [],
            "rows": [{"error": str(exc)}],
            "total": 0,
            "execution_time_ms": (time.time() - t0) * 1000,
            "query_type": query_type,
        }

    duration_ms = (time.time() - t0) * 1000

    if query_type == "SELECT":
        columns = [str(v) for v in result.vars] if result.vars else []
        rows = []
        for row in result:
            row_dict = {}
            for i, col in enumerate(columns):
                val = row[i]
                row_dict[col] = str(val) if val is not None else ""
            rows.append(row_dict)
        return {
            "columns": columns,
            "rows": rows,
            "total": len(rows),
            "execution_time_ms": duration_ms,
            "query_type": "SELECT",
        }

    elif query_type == "ASK":
        return {
            "columns": ["result"],
            "rows": [{"result": str(bool(result))}],
            "total": 1,
            "execution_time_ms": duration_ms,
            "query_type": "ASK",
        }

    elif query_type in ("CONSTRUCT", "DESCRIBE"):
        # Return triples as rows
        rows = []
        for s, p, o in result:
            rows.append({
                "subject": str(s),
                "predicate": str(p),
                "object": str(o),
            })
        return {
            "columns": ["subject", "predicate", "object"],
            "rows": rows,
            "total": len(rows),
            "execution_time_ms": duration_ms,
            "query_type": query_type,
        }

    return {
        "columns": [],
        "rows": [],
        "total": 0,
        "execution_time_ms": duration_ms,
        "query_type": query_type,
    }


def sparql_to_graph_visualization(board_dir: Path, query: str) -> dict:
    """Execute a CONSTRUCT/DESCRIBE query and return a graph visualization structure."""
    g = load_full_graph(board_dir)

    try:
        result = g.query(query)
    except Exception as exc:
        return {"nodes": [], "edges": [], "error": str(exc)}

    nodes_map: dict[str, dict] = {}
    edges = []

    # For CONSTRUCT results, iterate triples
    triples = list(result) if hasattr(result, '__iter__') else []

    for s, p, o in triples:
        if isinstance(s, BNode) or isinstance(o, BNode):
            continue

        s_str = str(s)
        p_str = str(p)
        o_str = str(o)

        # Add subject node
        if s_str not in nodes_map:
            s_label = _get_label(g, s) or _local_name(s_str)
            s_type = _get_type(g, s)
            nodes_map[s_str] = {
                "id": s_str,
                "label": s_label,
                "type": _local_name(s_type) if s_type else "",
                "color": _TYPE_COLORS.get(s_type, "#a29bfe"),
            }

        # Add object as node if it's a URI
        if isinstance(o, URIRef):
            if o_str not in nodes_map:
                o_label = _get_label(g, o) or _local_name(o_str)
                o_type = _get_type(g, o)
                nodes_map[o_str] = {
                    "id": o_str,
                    "label": o_label,
                    "type": _local_name(o_type) if o_type else "",
                    "color": _TYPE_COLORS.get(o_type, "#74b9ff"),
                }

            edges.append({
                "source": s_str,
                "target": o_str,
                "label": _local_name(p_str),
                "iri": p_str,
            })
        elif isinstance(o, Literal):
            # Literals become special leaf nodes
            lit_id = f"{s_str}_{p_str}_{o_str}"
            if lit_id not in nodes_map:
                nodes_map[lit_id] = {
                    "id": lit_id,
                    "label": str(o)[:50],
                    "type": "Literal",
                    "color": "#55efc4",
                }
            edges.append({
                "source": s_str,
                "target": lit_id,
                "label": _local_name(p_str),
                "iri": p_str,
            })

    return {
        "nodes": list(nodes_map.values()),
        "edges": edges,
    }


def get_common_prefixes(board_dir: Path) -> list[dict]:
    """Return prefix bindings from the board's graph for SPARQL autocomplete."""
    g = load_full_graph(board_dir)
    return [{"prefix": p or ":", "namespace": str(ns)} for p, ns in g.namespaces()]


def _detect_query_type(query: str) -> str:
    """Detect query type from the full query (which may start with PREFIX)."""
    upper = query.upper()
    for keyword in ("SELECT", "CONSTRUCT", "ASK", "DESCRIBE"):
        if keyword in upper:
            return keyword
    return "SELECT"


def _get_label(g: Graph, node) -> str | None:
    for o in g.objects(node, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _get_type(g: Graph, node) -> str | None:
    for o in g.objects(node, RDF.type):
        o_str = str(o)
        if o_str in _TYPE_COLORS:
            return o_str
    return None


def _local_name(iri: str) -> str:
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]
