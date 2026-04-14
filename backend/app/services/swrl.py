"""SWRL rule service — CRUD for native OWL/XML SWRL rules.

Rules are stored as native ``swrl:Imp`` individuals in the RDF graph,
using the W3C SWRL vocabulary so that standard OWL reasoners (Pellet,
HermiT, etc.) can execute them directly.

For backward compatibility, ``[SWRL]``-prefixed ``rdfs:comment``
annotations on the ``owl:Ontology`` node are still *read* (but new
rules are always written in native format).

Human-readable format::

    Person(?x) ^ hasAge(?x, ?a) ^ greaterThan(?a, 18) -> Adult(?x)
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any

from rdflib import BNode, Graph, Literal, Namespace, RDF, RDFS, OWL, URIRef
from rdflib.collection import Collection

from app.services.ontology import load_graph

# ── namespaces ───────────────────────────────────────────────────
SWRL = Namespace("http://www.w3.org/2003/11/swrl#")
SWRLB = Namespace("http://www.w3.org/2003/11/swrlb#")

# Known SWRL built-in names → full URIs
_BUILTINS = {
    "greaterThan": SWRLB.greaterThan,
    "lessThan": SWRLB.lessThan,
    "equal": SWRLB.equal,
    "greaterThanOrEqual": SWRLB.greaterThanOrEqual,
    "lessThanOrEqual": SWRLB.lessThanOrEqual,
    "notEqual": SWRLB.notEqual,
    "add": SWRLB.add,
    "subtract": SWRLB.subtract,
    "multiply": SWRLB.multiply,
    "divide": SWRLB.divide,
    "mod": SWRLB.mod,
    "stringConcat": SWRLB.stringConcat,
    "stringLength": SWRLB.stringLength,
    "contains": SWRLB.contains,
    "matches": SWRLB.matches,
    "abs": SWRLB.abs,
    "ceiling": SWRLB.ceiling,
    "floor": SWRLB.floor,
    "round": SWRLB.round,
    "pow": SWRLB.pow,
    "integerDivide": SWRLB.integerDivide,
    "unaryMinus": SWRLB.unaryMinus,
    "unaryPlus": SWRLB.unaryPlus,
    "sin": SWRLB.sin,
    "cos": SWRLB.cos,
    "tan": SWRLB.tan,
    "booleanNot": SWRLB.booleanNot,
    "substringBefore": SWRLB.substringBefore,
    "substringAfter": SWRLB.substringAfter,
    "substring": SWRLB.substring,
    "upperCase": SWRLB.upperCase,
    "lowerCase": SWRLB.lowerCase,
    "normalizeSpace": SWRLB.normalizeSpace,
    "startsWith": SWRLB.startsWith,
    "endsWith": SWRLB.endsWith,
    "tokenize": SWRLB.tokenize,
    "translate": SWRLB.translate,
    "replace": SWRLB.replace,
    "yearMonthDuration": SWRLB.yearMonthDuration,
    "dayTimeDuration": SWRLB.dayTimeDuration,
    "date": SWRLB.date,
    "time": SWRLB.time,
    "dateTime": SWRLB.dateTime,
    "resolveURI": SWRLB.resolveURI,
    "anyURI": SWRLB.anyURI,
    "listConcat": SWRLB.listConcat,
    "member": SWRLB.member,
    "length": SWRLB.length,
    "first": SWRLB.first,
    "rest": SWRLB.rest,
    "sublist": SWRLB.sublist,
    "empty": SWRLB.empty,
}

# Reverse lookup: URI → short name
_BUILTIN_NAMES: dict[URIRef, str] = {v: k for k, v in _BUILTINS.items()}

# ── regex / helpers ──────────────────────────────────────────────

_ATOM_RE = re.compile(
    r"(?P<name>[A-Za-z_][\w:#/.]*)"   # predicate / class name (may be URI)
    r"\s*\(\s*"
    r"(?P<args>[^)]*)"                 # comma-separated args
    r"\s*\)"
)


def _stable_id(rule_text: str) -> str:
    """Deterministic short hex id for a rule string."""
    return hashlib.sha256(rule_text.encode()).hexdigest()[:12]


def _normalise(rule_text: str) -> str:
    """Strip and normalise whitespace."""
    return " ".join(rule_text.split())


def _split_rule(rule_text: str) -> tuple[str, str]:
    """Split ``antecedent -> consequent`` into two halves."""
    if "->" in rule_text:
        ant, cons = rule_text.split("->", 1)
    elif "\u2192" in rule_text:
        ant, cons = rule_text.split("\u2192", 1)
    else:
        ant, cons = rule_text, ""
    return ant.strip(), cons.strip()


def _parse_atom(raw: str) -> dict[str, Any]:
    """Parse a single atom string like ``Person(?x)`` into a dict."""
    raw = raw.strip()
    m = _ATOM_RE.match(raw)
    if not m:
        return {"raw": raw}
    name = m.group("name")
    args = [a.strip() for a in m.group("args").split(",") if a.strip()]
    if len(args) == 1:
        atom_type = "ClassAtom"
    elif name in _BUILTINS:
        atom_type = "BuiltinAtom"
    elif name in ("SameAs", "sameIndividual"):
        atom_type = "SameIndividualAtom"
    elif name in ("DifferentFrom", "differentIndividuals"):
        atom_type = "DifferentIndividualsAtom"
    else:
        atom_type = "PropertyAtom"
    return {"type": atom_type, "predicate": name, "arguments": args}


def _parse_atoms(text: str) -> list[dict[str, Any]]:
    """Split ``A(?x) ^ B(?x, ?y)`` into a list of atom dicts."""
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
        else:
            current.append(ch)
    if current:
        parts.append("".join(current))
    return [_parse_atom(p) for p in parts if p.strip()]


def _rule_to_dict(rule_text: str, *, source: str = "native") -> dict[str, Any]:
    """Convert a raw rule string into the full response dict."""
    ant_str, cons_str = _split_rule(rule_text)
    return {
        "id": _stable_id(rule_text),
        "label": rule_text,
        "antecedent": ant_str,
        "consequent": cons_str,
        "antecedent_atoms": _parse_atoms(ant_str),
        "consequent_atoms": _parse_atoms(cons_str),
        "source": source,
    }


# ── RDF graph helpers ────────────────────────────────────────────

def _ontology_ns(g: Graph) -> str:
    """Return the ontology namespace (base URI) from the graph."""
    for s in g.subjects(RDF.type, OWL.Ontology):
        uri = str(s)
        if not uri.endswith("/") and not uri.endswith("#"):
            return uri + "#"
        return uri
    return "http://example.org/ontology#"


def _resolve_name(name: str, g: Graph) -> URIRef:
    """Resolve a short name to a full URI using graph namespaces.

    Tries each bound prefix. Falls back to the ontology namespace.
    """
    if name.startswith("http://") or name.startswith("https://"):
        return URIRef(name)
    # Check if name matches a known entity in the graph by local name
    for s in g.subjects(RDF.type, None):
        if isinstance(s, URIRef):
            local = str(s).rsplit("#", 1)[-1].rsplit("/", 1)[-1]
            if local == name:
                return s
    # Fallback: use ontology namespace
    return URIRef(_ontology_ns(g) + name)


def _local_name(uri: URIRef | BNode) -> str:
    """Extract local name from a URI."""
    if isinstance(uri, BNode):
        return str(uri)
    s = str(uri)
    for sep in ("#", "/"):
        if sep in s:
            return s.rsplit(sep, 1)[-1]
    return s


def _ensure_variable(g: Graph, var_name: str, var_cache: dict[str, URIRef]) -> URIRef:
    """Get or create a swrl:Variable for ``?x`` style variables."""
    if var_name in var_cache:
        return var_cache[var_name]
    # Use a deterministic URI based on the variable name
    ns = _ontology_ns(g)
    var_uri = URIRef(ns + var_name.lstrip("?"))
    # Only add type if not already present
    if (var_uri, RDF.type, SWRL.Variable) not in g:
        g.add((var_uri, RDF.type, SWRL.Variable))
    var_cache[var_name] = var_uri
    return var_uri


def _resolve_arg(arg: str, g: Graph, var_cache: dict[str, URIRef]) -> URIRef | Literal:
    """Resolve an atom argument to a variable URI, individual URI, or literal."""
    arg = arg.strip()
    if arg.startswith("?"):
        return _ensure_variable(g, arg, var_cache)
    # Try as integer
    try:
        return Literal(int(arg))
    except ValueError:
        pass
    # Try as float
    try:
        return Literal(float(arg))
    except ValueError:
        pass
    # Quoted string literal
    if (arg.startswith('"') and arg.endswith('"')) or \
       (arg.startswith("'") and arg.endswith("'")):
        return Literal(arg[1:-1])
    # Otherwise treat as individual
    return _resolve_name(arg, g)


def _classify_atom(name: str, g: Graph) -> str:
    """Classify an atom name into its SWRL type using the ontology graph."""
    if name in _BUILTINS:
        return "BuiltinAtom"
    uri = _resolve_name(name, g)
    # Check if it's a class
    if (uri, RDF.type, OWL.Class) in g or (uri, RDF.type, RDFS.Class) in g:
        return "ClassAtom"
    # Check if it's a datatype property
    if (uri, RDF.type, OWL.DatatypeProperty) in g:
        return "DatavaluedPropertyAtom"
    # Check if it's an object property
    if (uri, RDF.type, OWL.ObjectProperty) in g:
        return "IndividualPropertyAtom"
    # Fallback heuristics
    return "ClassAtom" if True else "IndividualPropertyAtom"


def _create_atom_node(
    g: Graph,
    atom: dict[str, Any],
    var_cache: dict[str, URIRef],
) -> BNode:
    """Create RDF triples for one SWRL atom, return the atom BNode."""
    name = atom["predicate"]
    args = atom["arguments"]
    atom_node = BNode()

    atom_type = atom.get("type", "")
    # Re-classify using the graph if we have a generic PropertyAtom
    if atom_type == "PropertyAtom":
        atom_type = _classify_atom(name, g)

    if atom_type == "BuiltinAtom":
        g.add((atom_node, RDF.type, SWRL.BuiltinAtom))
        builtin_uri = _BUILTINS.get(name, SWRLB[name])
        g.add((atom_node, SWRL.builtin, builtin_uri))
        # Arguments go in an RDF list via swrl:arguments
        arg_list_head = BNode()
        arg_nodes = [_resolve_arg(a, g, var_cache) for a in args]
        Collection(g, arg_list_head, arg_nodes)
        g.add((atom_node, SWRL.arguments, arg_list_head))

    elif atom_type == "ClassAtom":
        g.add((atom_node, RDF.type, SWRL.ClassAtom))
        g.add((atom_node, SWRL.classPredicate, _resolve_name(name, g)))
        if args:
            g.add((atom_node, SWRL.argument1, _resolve_arg(args[0], g, var_cache)))

    elif atom_type == "DatavaluedPropertyAtom":
        g.add((atom_node, RDF.type, SWRL.DatavaluedPropertyAtom))
        g.add((atom_node, SWRL.propertyPredicate, _resolve_name(name, g)))
        if len(args) >= 1:
            g.add((atom_node, SWRL.argument1, _resolve_arg(args[0], g, var_cache)))
        if len(args) >= 2:
            g.add((atom_node, SWRL.argument2, _resolve_arg(args[1], g, var_cache)))

    else:  # IndividualPropertyAtom (default for 2-arg property atoms)
        g.add((atom_node, RDF.type, SWRL.IndividualPropertyAtom))
        g.add((atom_node, SWRL.propertyPredicate, _resolve_name(name, g)))
        if len(args) >= 1:
            g.add((atom_node, SWRL.argument1, _resolve_arg(args[0], g, var_cache)))
        if len(args) >= 2:
            g.add((atom_node, SWRL.argument2, _resolve_arg(args[1], g, var_cache)))

    return atom_node


def _build_atom_list(
    g: Graph,
    atoms: list[dict[str, Any]],
    var_cache: dict[str, URIRef],
) -> BNode:
    """Build an RDF list of SWRL atoms and return the list head BNode."""
    atom_nodes = [_create_atom_node(g, a, var_cache) for a in atoms]
    list_head = BNode()
    Collection(g, list_head, atom_nodes)
    return list_head


# ── reading native SWRL from the graph ───────────────────────────

def _rdf_list_items(g: Graph, head) -> list:
    """Walk an RDF list (rdf:first/rdf:rest) and return items in order."""
    items: list = []
    node = head
    while node and node != RDF.nil:
        first = g.value(node, RDF.first)
        if first is not None:
            items.append(first)
        node = g.value(node, RDF.rest)
    return items


def _read_atom(g: Graph, atom_node) -> dict[str, Any]:
    """Read one SWRL atom from the graph into a dict."""
    rdf_types = set(g.objects(atom_node, RDF.type))

    if SWRL.ClassAtom in rdf_types:
        pred = g.value(atom_node, SWRL.classPredicate)
        arg1 = g.value(atom_node, SWRL.argument1)
        name = _local_name(pred) if pred else "?"
        a1 = _format_arg(g, arg1)
        return {"type": "ClassAtom", "predicate": name, "arguments": [a1]}

    if SWRL.IndividualPropertyAtom in rdf_types:
        pred = g.value(atom_node, SWRL.propertyPredicate)
        arg1 = g.value(atom_node, SWRL.argument1)
        arg2 = g.value(atom_node, SWRL.argument2)
        name = _local_name(pred) if pred else "?"
        a1 = _format_arg(g, arg1)
        a2 = _format_arg(g, arg2)
        return {"type": "IndividualPropertyAtom", "predicate": name, "arguments": [a1, a2]}

    if SWRL.DatavaluedPropertyAtom in rdf_types:
        pred = g.value(atom_node, SWRL.propertyPredicate)
        arg1 = g.value(atom_node, SWRL.argument1)
        arg2 = g.value(atom_node, SWRL.argument2)
        name = _local_name(pred) if pred else "?"
        a1 = _format_arg(g, arg1)
        a2 = _format_arg(g, arg2)
        return {"type": "DatavaluedPropertyAtom", "predicate": name, "arguments": [a1, a2]}

    if SWRL.BuiltinAtom in rdf_types:
        builtin_uri = g.value(atom_node, SWRL.builtin)
        args_head = g.value(atom_node, SWRL.arguments)
        name = _BUILTIN_NAMES.get(builtin_uri, _local_name(builtin_uri)) if builtin_uri else "?"
        arg_nodes = _rdf_list_items(g, args_head) if args_head else []
        formatted = [_format_arg(g, a) for a in arg_nodes]
        return {"type": "BuiltinAtom", "predicate": name, "arguments": formatted}

    # Fallback: try to render something
    return {"type": "UnknownAtom", "predicate": "?", "arguments": []}


def _format_arg(g: Graph, node) -> str:
    """Format an atom argument as human-readable string."""
    if node is None:
        return "?"
    if isinstance(node, Literal):
        return str(node)
    if isinstance(node, URIRef):
        # Check if it's a swrl:Variable
        if (node, RDF.type, SWRL.Variable) in g:
            return "?" + _local_name(node)
        return _local_name(node)
    # BNode — shouldn't normally happen for arguments
    return str(node)


def _atom_to_str(atom: dict[str, Any]) -> str:
    """Render an atom dict as human-readable ``Name(arg1, arg2)``."""
    args_str = ", ".join(atom.get("arguments", []))
    return f"{atom['predicate']}({args_str})"


def _read_native_rules(g: Graph) -> list[dict[str, Any]]:
    """Read all native swrl:Imp rules from the graph."""
    rules: list[dict[str, Any]] = []
    for imp in g.subjects(RDF.type, SWRL.Imp):
        body_head = g.value(imp, SWRL.body)
        head_head = g.value(imp, SWRL.head)

        body_atoms = []
        if body_head:
            for atom_node in _rdf_list_items(g, body_head):
                body_atoms.append(_read_atom(g, atom_node))

        head_atoms = []
        if head_head:
            for atom_node in _rdf_list_items(g, head_head):
                head_atoms.append(_read_atom(g, atom_node))

        ant_str = " ^ ".join(_atom_to_str(a) for a in body_atoms)
        cons_str = " ^ ".join(_atom_to_str(a) for a in head_atoms)
        rule_text = f"{ant_str} -> {cons_str}" if cons_str else ant_str

        rules.append({
            "id": _stable_id(rule_text),
            "label": rule_text,
            "antecedent": ant_str,
            "consequent": cons_str,
            "antecedent_atoms": body_atoms,
            "consequent_atoms": head_atoms,
            "source": "native",
            "_imp_node": imp,  # internal, for deletion
        })
    return rules


def _read_annotation_rules(g: Graph) -> list[dict[str, Any]]:
    """Read legacy [SWRL]-prefixed annotation rules."""
    rules: list[dict[str, Any]] = []
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, RDFS.comment):
            text = str(o)
            if text.startswith("[SWRL]"):
                rule_text = _normalise(text[6:])
                rules.append(_rule_to_dict(rule_text, source="annotation"))
    return rules


# ── public API ───────────────────────────────────────────────────

def get_swrl_rules(board_dir: Path) -> list[dict]:
    """List all SWRL rules (native + legacy annotation-based).

    Native ``swrl:Imp`` rules are listed first, followed by any
    ``[SWRL]``-prefixed ``rdfs:comment`` rules that don't duplicate
    a native rule.
    """
    g = load_graph(board_dir)
    g.bind("swrl", SWRL)
    g.bind("swrlb", SWRLB)

    native = _read_native_rules(g)
    annotation = _read_annotation_rules(g)

    # De-duplicate: skip annotation rules whose id matches a native one
    native_ids = {r["id"] for r in native}
    merged = list(native)
    for r in annotation:
        if r["id"] not in native_ids:
            merged.append(r)

    # Strip internal fields before returning
    for r in merged:
        r.pop("_imp_node", None)

    return merged


def add_swrl_rule(board_dir: Path, antecedent: str, consequent: str) -> dict:
    """Add a SWRL rule in native OWL/XML format.

    The caller provides *antecedent* and *consequent* as human-readable
    atom strings (e.g. ``Person(?x) ^ hasAge(?x, ?a)``).
    """
    antecedent = _normalise(antecedent)
    consequent = _normalise(consequent)
    rule_text = f"{antecedent} -> {consequent}"
    rule_id = _stable_id(rule_text)

    g = load_graph(board_dir)
    g.bind("swrl", SWRL)
    g.bind("swrlb", SWRLB)

    # Guard against duplicates (check native rules)
    for existing_imp in g.subjects(RDF.type, SWRL.Imp):
        existing_rules = _read_native_rules(g)
        for er in existing_rules:
            if er["id"] == rule_id:
                er.pop("_imp_node", None)
                return er
        break  # only need one check

    # Also guard against annotation duplicates
    for s in g.subjects(RDF.type, OWL.Ontology):
        for o in g.objects(s, RDFS.comment):
            text = str(o)
            if text.startswith("[SWRL]") and _stable_id(_normalise(text[6:])) == rule_id:
                return _rule_to_dict(rule_text)

    # Parse atoms
    ant_atoms = _parse_atoms(antecedent)
    cons_atoms = _parse_atoms(consequent)

    # Re-classify atoms using graph knowledge
    for atom in ant_atoms + cons_atoms:
        if atom.get("type") == "PropertyAtom":
            atom["type"] = _classify_atom(atom["predicate"], g)
        elif atom.get("type") == "ClassAtom" and len(atom.get("arguments", [])) > 1:
            atom["type"] = _classify_atom(atom["predicate"], g)

    # Build SWRL triples
    var_cache: dict[str, URIRef] = {}
    imp_node = BNode()
    g.add((imp_node, RDF.type, SWRL.Imp))

    body_list = _build_atom_list(g, ant_atoms, var_cache)
    g.add((imp_node, SWRL.body, body_list))

    head_list = _build_atom_list(g, cons_atoms, var_cache)
    g.add((imp_node, SWRL.head, head_list))

    _save(g, board_dir)

    result = _rule_to_dict(rule_text, source="native")
    result["antecedent_atoms"] = ant_atoms
    result["consequent_atoms"] = cons_atoms
    return result


def delete_swrl_rule(board_dir: Path, rule_id: str) -> bool:
    """Delete a SWRL rule by its id (hex hash).

    Handles both native ``swrl:Imp`` rules and legacy annotation rules.
    """
    g = load_graph(board_dir)
    g.bind("swrl", SWRL)
    g.bind("swrlb", SWRLB)
    deleted = False

    # Try native rules first
    native = _read_native_rules(g)
    for rule in native:
        if rule["id"] == rule_id:
            imp_node = rule["_imp_node"]
            _remove_imp(g, imp_node)
            deleted = True
            break

    # Try annotation rules
    if not deleted:
        for s in g.subjects(RDF.type, OWL.Ontology):
            for o in list(g.objects(s, RDFS.comment)):
                text = str(o)
                if text.startswith("[SWRL]"):
                    rt = _normalise(text[6:])
                    if _stable_id(rt) == rule_id:
                        g.remove((s, RDFS.comment, o))
                        deleted = True
                        break
            if deleted:
                break

    if deleted:
        _save(g, board_dir)
    return deleted


def _remove_imp(g: Graph, imp_node) -> None:
    """Remove a swrl:Imp and all its associated triples (atoms, lists, variables)."""
    # Collect all nodes to remove
    nodes_to_remove: set = {imp_node}

    for pred in (SWRL.body, SWRL.head):
        list_head = g.value(imp_node, pred)
        if list_head:
            _collect_list_nodes(g, list_head, nodes_to_remove)

    # Remove all triples involving collected nodes
    for node in nodes_to_remove:
        # Remove triples where node is subject
        for p, o in list(g.predicate_objects(node)):
            g.remove((node, p, o))
        # Remove triples where node is object
        for s, p in list(g.subject_predicates(node)):
            g.remove((s, p, node))

    # Clean up orphaned swrl:Variable nodes (those no longer referenced)
    for var in list(g.subjects(RDF.type, SWRL.Variable)):
        # Check if any triple still references this variable (besides its type)
        still_used = False
        for s, p in g.subject_predicates(var):
            if p != RDF.type:
                still_used = True
                break
        if not still_used:
            for s, p, o in list(g.triples((var, None, None))):
                g.remove((s, p, o))


def _collect_list_nodes(g: Graph, head, nodes: set) -> None:
    """Recursively collect all BNodes in an RDF list and the atom nodes."""
    node = head
    while node and node != RDF.nil:
        if isinstance(node, BNode):
            nodes.add(node)
        first = g.value(node, RDF.first)
        if first is not None and isinstance(first, BNode):
            nodes.add(first)
            # Also collect sub-lists in BuiltinAtom arguments
            args_head = g.value(first, SWRL.arguments)
            if args_head:
                _collect_list_nodes(g, args_head, nodes)
        node = g.value(node, RDF.rest)
        if node and isinstance(node, BNode):
            nodes.add(node)


# ── legacy aliases (kept for backward compat) ────────────────────

list_rules = get_swrl_rules


def add_rule(board_dir: Path, rule_text: str) -> dict:
    """Legacy wrapper: parse ``antecedent -> consequent`` and add."""
    ant, cons = _split_rule(rule_text)
    return add_swrl_rule(board_dir, ant, cons)


def delete_rule(board_dir: Path, rule_text: str) -> bool:
    """Legacy wrapper: delete by rule text."""
    rid = _stable_id(_normalise(rule_text))
    return delete_swrl_rule(board_dir, rid)


# ── persistence ──────────────────────────────────────────────────

def _save(g: Graph, board_dir: Path) -> None:
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
