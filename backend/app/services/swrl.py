"""SWRL rule service — CRUD for SWRL rules stored as annotations.

SWRL in rdflib is complex, so rules are stored as rdfs:comment with a
``[SWRL]`` prefix on the owl:Ontology node.  The human-readable format
used is::

    Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18) -> Adult(?x)

Each stored rule is parsed into structured ``antecedent_atoms`` /
``consequent_atoms`` lists for the frontend.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any

from rdflib import Graph, Literal, RDF, RDFS, OWL, Namespace

from app.services.ontology import load_graph

SWRL = Namespace("http://www.w3.org/2003/11/swrl#")

# ── helpers ───────────────────────────────────────────────────────

_ATOM_RE = re.compile(
    r"(?P<name>[A-Za-z_][\w]*)"      # predicate / class name
    r"\s*\(\s*"
    r"(?P<args>[^)]*)"                # comma-separated args
    r"\s*\)"
)


def _stable_id(rule_text: str) -> str:
    """Deterministic short hex id for a rule string."""
    return hashlib.sha256(rule_text.encode()).hexdigest()[:12]


def _parse_atom(raw: str) -> dict[str, Any]:
    """Parse a single atom string like ``Person(?x)`` into a dict."""
    raw = raw.strip()
    m = _ATOM_RE.match(raw)
    if not m:
        return {"raw": raw}
    name = m.group("name")
    args = [a.strip() for a in m.group("args").split(",") if a.strip()]
    # Heuristic classification
    if len(args) == 1:
        atom_type = "ClassAtom"
    elif name in (
        "greaterThan", "lessThan", "equal", "greaterThanOrEqual",
        "lessThanOrEqual", "notEqual", "add", "subtract", "multiply",
        "divide", "mod", "stringConcat", "stringLength", "contains",
        "matches",
    ):
        atom_type = "BuiltinAtom"
    elif name == "SameAs" or name == "sameIndividual":
        atom_type = "SameIndividualAtom"
    elif name == "DifferentFrom" or name == "differentIndividuals":
        atom_type = "DifferentIndividualsAtom"
    else:
        atom_type = "PropertyAtom"
    return {"type": atom_type, "predicate": name, "arguments": args}


def _parse_atoms(text: str) -> list[dict[str, Any]]:
    """Split ``A(?x) ^ B(?x, ?y)`` into a list of atom dicts."""
    # Split on '^' or ',' that is outside parentheses
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    for ch in text:
        if ch == "(":
            depth += 1
            current.append(ch)
        elif ch == ")":
            depth -= 1
            current.append(ch)
        elif ch == "^" and depth == 0:
            parts.append("".join(current))
            current = []
        elif ch == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))

    return [_parse_atom(p) for p in parts if p.strip()]


def _normalise(rule_text: str) -> str:
    """Strip and normalise whitespace."""
    return " ".join(rule_text.split())


def _split_rule(rule_text: str) -> tuple[str, str]:
    """Split ``antecedent -> consequent`` into two halves."""
    if "->" in rule_text:
        ant, cons = rule_text.split("->", 1)
    elif "\u2192" in rule_text:                     # unicode arrow
        ant, cons = rule_text.split("\u2192", 1)
    else:
        ant, cons = rule_text, ""
    return ant.strip(), cons.strip()


def _rule_to_dict(rule_text: str) -> dict[str, Any]:
    """Convert a raw rule string into the full response dict."""
    ant_str, cons_str = _split_rule(rule_text)
    return {
        "id": _stable_id(rule_text),
        "label": rule_text,
        "antecedent": ant_str,
        "consequent": cons_str,
        "antecedent_atoms": _parse_atoms(ant_str),
        "consequent_atoms": _parse_atoms(cons_str),
    }


# ── public API ────────────────────────────────────────────────────

def get_swrl_rules(board_dir: Path) -> list[dict]:
    """List all SWRL rules stored as ``[SWRL]``-prefixed comments."""
    g = load_graph(board_dir)
    rules: list[dict] = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, RDFS.comment):
            text = str(o)
            if text.startswith("[SWRL]"):
                rule_text = _normalise(text[6:])
                rules.append(_rule_to_dict(rule_text))
    return rules


def add_swrl_rule(board_dir: Path, antecedent: str, consequent: str) -> dict:
    """Add a SWRL rule.

    The caller provides *antecedent* and *consequent* as human-readable
    atom strings (e.g. ``Person(?x) ^ hasAge(?x, ?a)``).
    """
    antecedent = _normalise(antecedent)
    consequent = _normalise(consequent)
    rule_text = f"{antecedent} -> {consequent}"

    g = load_graph(board_dir)
    ont_node = None
    for s in g.subjects(RDF.type, OWL.Ontology):
        ont_node = s
        break
    if ont_node is None:
        raise RuntimeError("No owl:Ontology node found in the graph")

    # Guard against duplicates
    for o in g.objects(ont_node, RDFS.comment):
        existing = str(o)
        if existing.startswith("[SWRL]") and _normalise(existing[6:]) == rule_text:
            return _rule_to_dict(rule_text)

    g.add((ont_node, RDFS.comment, Literal(f"[SWRL] {rule_text}")))
    _save(g, board_dir)
    return _rule_to_dict(rule_text)


def delete_swrl_rule(board_dir: Path, rule_id: str) -> bool:
    """Delete a SWRL rule by its id (hex hash)."""
    g = load_graph(board_dir)
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in list(g.objects(s, RDFS.comment)):
            text = str(o)
            if text.startswith("[SWRL]"):
                rule_text = _normalise(text[6:])
                if _stable_id(rule_text) == rule_id:
                    g.remove((s, RDFS.comment, o))
                    _save(g, board_dir)
                    return True
    return False


# ── legacy aliases (kept for backward compat) ────────────────────

list_rules = get_swrl_rules


def add_rule(board_dir: Path, rule_text: str) -> dict:
    ant, cons = _split_rule(rule_text)
    return add_swrl_rule(board_dir, ant, cons)


def delete_rule(board_dir: Path, rule_text: str) -> bool:
    rid = _stable_id(_normalise(rule_text))
    return delete_swrl_rule(board_dir, rid)


# ── persistence ───────────────────────────────────────────────────

def _save(g: Graph, board_dir: Path) -> None:
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
