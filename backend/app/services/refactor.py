"""Refactoring service — rename IRI (cascading), move entity in hierarchy, undo/redo."""

import copy
import logging
from pathlib import Path

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL

from app.services.ontology import load_graph

logger = logging.getLogger("ontoboard.refactor")

# Per-board undo stacks (in-memory, keyed by board_id)
_undo_stacks: dict[str, list[bytes]] = {}
_redo_stacks: dict[str, list[bytes]] = {}
_MAX_UNDO = 50


def rename_iri(board_dir: Path, old_iri: str, new_iri: str) -> dict:
    """Atomically rename an IRI — updates all references (subject, predicate, object)."""
    g = load_graph(board_dir)
    _push_undo(board_dir.name, g)

    old = URIRef(old_iri)
    new = URIRef(new_iri)
    affected = 0

    # Replace as subject
    for _, p, o in list(g.triples((old, None, None))):
        g.remove((old, p, o))
        g.add((new, p, o))
        affected += 1

    # Replace as predicate
    for s, _, o in list(g.triples((None, old, None))):
        g.remove((s, old, o))
        g.add((s, new, o))
        affected += 1

    # Replace as object
    for s, p, _ in list(g.triples((None, None, old))):
        g.remove((s, p, old))
        g.add((s, p, new))
        affected += 1

    _save(g, board_dir)
    return {"old_iri": old_iri, "new_iri": new_iri, "affected_triples": affected}


def move_entity(board_dir: Path, entity_iri: str, new_parent_iri: str, old_parent_iri: str | None = None) -> bool:
    """Move an entity to a new parent (change SubClassOf/SubPropertyOf)."""
    g = load_graph(board_dir)
    _push_undo(board_dir.name, g)

    entity = URIRef(entity_iri)
    new_parent = URIRef(new_parent_iri)

    # Determine predicate (subClassOf for classes, subPropertyOf for properties)
    types = set(str(t) for t in g.objects(entity, RDF.type))
    if str(OWL.Class) in types:
        pred = RDFS.subClassOf
    elif str(OWL.ObjectProperty) in types or str(OWL.DatatypeProperty) in types:
        pred = RDFS.subPropertyOf
    else:
        pred = RDFS.subClassOf  # default

    # Remove old parent link
    if old_parent_iri:
        g.remove((entity, pred, URIRef(old_parent_iri)))
    else:
        # Remove all parent links
        g.remove((entity, pred, None))

    # Add new parent link
    g.add((entity, pred, new_parent))
    _save(g, board_dir)
    return True


def undo(board_dir: Path) -> bool:
    """Undo the last operation by restoring the previous graph snapshot."""
    board_id = board_dir.name
    if board_id not in _undo_stacks or not _undo_stacks[board_id]:
        return False

    # Save current state to redo stack
    g = load_graph(board_dir)
    _push_redo(board_id, g)

    # Restore previous state
    snapshot = _undo_stacks[board_id].pop()
    g_restored = Graph()
    g_restored.parse(data=snapshot, format="xml")
    _save(g_restored, board_dir)
    return True


def redo(board_dir: Path) -> bool:
    """Redo the last undone operation."""
    board_id = board_dir.name
    if board_id not in _redo_stacks or not _redo_stacks[board_id]:
        return False

    # Save current state to undo stack
    g = load_graph(board_dir)
    _push_undo_no_clear_redo(board_id, g)

    # Restore redo state
    snapshot = _redo_stacks[board_id].pop()
    g_restored = Graph()
    g_restored.parse(data=snapshot, format="xml")
    _save(g_restored, board_dir)
    return True


# ── Internal helpers ───────────────────────────────────────────

def _push_undo(board_id: str, g: Graph):
    if board_id not in _undo_stacks:
        _undo_stacks[board_id] = []
    snapshot = g.serialize(format="xml").encode() if isinstance(g.serialize(format="xml"), str) else g.serialize(format="xml")
    _undo_stacks[board_id].append(snapshot)
    if len(_undo_stacks[board_id]) > _MAX_UNDO:
        _undo_stacks[board_id] = _undo_stacks[board_id][-_MAX_UNDO:]
    # Clear redo on new action
    _redo_stacks[board_id] = []


def _push_undo_no_clear_redo(board_id: str, g: Graph):
    if board_id not in _undo_stacks:
        _undo_stacks[board_id] = []
    snapshot = g.serialize(format="xml").encode() if isinstance(g.serialize(format="xml"), str) else g.serialize(format="xml")
    _undo_stacks[board_id].append(snapshot)


def _push_redo(board_id: str, g: Graph):
    if board_id not in _redo_stacks:
        _redo_stacks[board_id] = []
    snapshot = g.serialize(format="xml").encode() if isinstance(g.serialize(format="xml"), str) else g.serialize(format="xml")
    _redo_stacks[board_id].append(snapshot)


def _save(g: Graph, board_dir: Path):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
