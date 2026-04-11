"""DL Query service — parse Description Logic queries and execute via SPARQL."""

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL

from app.services.ontology import load_graph


def execute_dl_query(g: Graph, query_text: str, query_type: str = "subclasses") -> list[dict]:
    """Execute a DL Query by translating to SPARQL.

    query_type: subclasses | superclasses | instances | equivalents
    query_text: A class name or expression (e.g., "Pizza", "hasTopping some Topping")
    """
    # Resolve the query text to a class IRI
    class_iri = _resolve_class(g, query_text.strip())

    if not class_iri:
        return [{"iri": "", "label": f"Cannot resolve: {query_text}", "type": "error"}]

    results = []

    if query_type == "subclasses":
        for s in g.subjects(RDFS.subClassOf, URIRef(class_iri)):
            if isinstance(s, BNode):
                continue
            label = _get_label(g, s) or _local(str(s))
            results.append({"iri": str(s), "label": label, "type": "class"})

    elif query_type == "superclasses":
        for o in g.objects(URIRef(class_iri), RDFS.subClassOf):
            if isinstance(o, BNode):
                continue
            label = _get_label(g, o) or _local(str(o))
            results.append({"iri": str(o), "label": label, "type": "class"})

    elif query_type == "instances":
        for s in g.subjects(RDF.type, URIRef(class_iri)):
            if isinstance(s, BNode):
                continue
            if (s, RDF.type, OWL.NamedIndividual) in g:
                label = _get_label(g, s) or _local(str(s))
                results.append({"iri": str(s), "label": label, "type": "individual"})

    elif query_type == "equivalents":
        for o in g.objects(URIRef(class_iri), OWL.equivalentClass):
            if isinstance(o, BNode):
                continue
            label = _get_label(g, o) or _local(str(o))
            results.append({"iri": str(o), "label": label, "type": "class"})

    return results


def _resolve_class(g: Graph, name: str) -> str | None:
    """Resolve a class name or IRI."""
    if name.startswith("http://") or name.startswith("https://"):
        return name
    # Search by label
    for s, _, o in g.triples((None, RDFS.label, None)):
        if isinstance(o, Literal) and str(o).strip() == name and not isinstance(s, BNode):
            if (s, RDF.type, OWL.Class) in g:
                return str(s)
    # Search by local name
    for s in g.subjects(RDF.type, OWL.Class):
        if isinstance(s, URIRef) and _local(str(s)) == name:
            return str(s)
    return None


def _get_label(g, s):
    for o in g.objects(s, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local(iri):
    return iri.split("#")[-1] if "#" in iri else iri.rsplit("/", 1)[-1]
