"""SWRL rule service — CRUD for SWRL rules stored as annotations."""

from pathlib import Path

from rdflib import Graph, URIRef, Literal, RDF, RDFS, OWL, Namespace

from app.services.ontology import load_graph

SWRL = Namespace("http://www.w3.org/2003/11/swrl#")
SWRL_RULE_PROP = URIRef("http://www.w3.org/2003/11/swrl#body")


def list_rules(board_dir: Path) -> list[dict]:
    """List all SWRL rules (stored as rdfs:comment with [SWRL] prefix on ontology node)."""
    g = load_graph(board_dir)
    rules = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, RDFS.comment):
            text = str(o)
            if text.startswith("[SWRL]"):
                rule_text = text[6:].strip()
                rules.append({"id": hash(rule_text) & 0xFFFFFF, "rule": rule_text})
    return rules


def add_rule(board_dir: Path, rule_text: str) -> dict:
    """Add a SWRL rule (stored as annotated comment)."""
    g = load_graph(board_dir)
    for s in g.subjects(RDF.type, OWL.Ontology):
        g.add((s, RDFS.comment, Literal(f"[SWRL] {rule_text}")))
        break
    _save(g, board_dir)
    return {"id": hash(rule_text) & 0xFFFFFF, "rule": rule_text}


def delete_rule(board_dir: Path, rule_text: str) -> bool:
    """Delete a SWRL rule by matching text."""
    g = load_graph(board_dir)
    for s in g.subjects(RDF.type, OWL.Ontology):
        target = Literal(f"[SWRL] {rule_text}")
        if (s, RDFS.comment, target) in g:
            g.remove((s, RDFS.comment, target))
            _save(g, board_dir)
            return True
    return False


def _save(g, board_dir):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
