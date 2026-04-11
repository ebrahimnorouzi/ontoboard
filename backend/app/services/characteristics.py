"""Property characteristics, property chains, and disjointness service."""

from pathlib import Path

from rdflib import Graph, URIRef, BNode, RDF, RDFS, OWL
from rdflib.collection import Collection

from app.services.ontology import load_graph

# Map characteristic names to OWL types
_CHAR_MAP = {
    "functional": OWL.FunctionalProperty,
    "inverse_functional": OWL.InverseFunctionalProperty,
    "transitive": OWL.TransitiveProperty,
    "symmetric": OWL.SymmetricProperty,
    "asymmetric": OWL.AsymmetricProperty,
    "reflexive": OWL.ReflexiveProperty,
    "irreflexive": OWL.IrreflexiveProperty,
}


def get_characteristics(g: Graph, property_iri: str) -> dict:
    """Read all characteristics of a property."""
    prop = URIRef(property_iri)
    types = set(str(t) for t in g.objects(prop, RDF.type))
    return {name: str(owl_type) in types for name, owl_type in _CHAR_MAP.items()}


def set_characteristics(board_dir: Path, property_iri: str, updates: dict) -> dict:
    """Set/unset property characteristics."""
    g = load_graph(board_dir)
    prop = URIRef(property_iri)

    for name, value in updates.items():
        if name not in _CHAR_MAP or value is None:
            continue
        owl_type = _CHAR_MAP[name]
        if value:
            g.add((prop, RDF.type, owl_type))
        else:
            g.remove((prop, RDF.type, owl_type))

    _save(g, board_dir)
    return get_characteristics(g, property_iri)


def get_property_chains(g: Graph, property_iri: str) -> list[list[str]]:
    """Get all property chain axioms where property_iri is the super-property."""
    prop = URIRef(property_iri)
    chains = []
    for _, _, chain_list in g.triples((prop, OWL.propertyChainAxiom, None)):
        if isinstance(chain_list, BNode):
            members = list(Collection(g, chain_list))
            chains.append([str(m) for m in members])
    return chains


def create_property_chain(board_dir: Path, super_property: str, chain_properties: list[str]) -> bool:
    """Create a property chain axiom: chain ⊆ super_property."""
    if len(chain_properties) < 2:
        return False
    g = load_graph(board_dir)
    prop = URIRef(super_property)
    members = [URIRef(iri) for iri in chain_properties]
    chain_node = BNode()
    Collection(g, chain_node, members)
    g.add((prop, OWL.propertyChainAxiom, chain_node))
    _save(g, board_dir)
    return True


def create_all_disjoint_classes(board_dir: Path, class_iris: list[str]) -> bool:
    """Create an owl:AllDisjointClasses axiom."""
    if len(class_iris) < 2:
        return False
    g = load_graph(board_dir)
    disjoint_node = BNode()
    g.add((disjoint_node, RDF.type, OWL.AllDisjointClasses))
    members = [URIRef(iri) for iri in class_iris]
    member_list = BNode()
    Collection(g, member_list, members)
    g.add((disjoint_node, OWL.members, member_list))
    _save(g, board_dir)
    return True


def create_disjoint_properties(board_dir: Path, property_iris: list[str]) -> bool:
    """Create pairwise owl:propertyDisjointWith axioms."""
    if len(property_iris) < 2:
        return False
    g = load_graph(board_dir)
    for i, p1 in enumerate(property_iris):
        for p2 in property_iris[i + 1:]:
            g.add((URIRef(p1), OWL.propertyDisjointWith, URIRef(p2)))
    _save(g, board_dir)
    return True


def _save(g: Graph, board_dir: Path):
    owl_files = list((board_dir / "src" / "ontology").glob("*.owl"))
    if owl_files:
        g.serialize(str(owl_files[0]), format="xml")
