"""Full-text search across ontology entities."""

from rdflib import Graph, URIRef, Literal, BNode, RDF, RDFS, OWL, SKOS


_TYPE_MAP = {
    str(OWL.Class): "class",
    str(OWL.ObjectProperty): "object_property",
    str(OWL.DatatypeProperty): "data_property",
    str(OWL.AnnotationProperty): "annotation_property",
    str(OWL.NamedIndividual): "individual",
}


def search_entities(g: Graph, query: str, entity_types: list[str] | None = None, limit: int = 50) -> list[dict]:
    """Search entities by label, IRI local name, or comment."""
    query_lower = query.lower()
    results = []
    seen = set()

    for rdf_type_uri, etype in _TYPE_MAP.items():
        if entity_types and etype not in entity_types:
            continue
        for s in g.subjects(RDF.type, URIRef(rdf_type_uri)):
            if isinstance(s, BNode) or str(s) in seen:
                continue
            seen.add(str(s))
            iri = str(s)
            local = _local(iri)

            # Check label match
            for pred in (RDFS.label, SKOS.prefLabel, SKOS.altLabel):
                for o in g.objects(s, pred):
                    if isinstance(o, Literal) and query_lower in str(o).lower():
                        results.append({
                            "iri": iri, "label": str(o), "entity_type": etype,
                            "match_field": "label", "snippet": str(o),
                        })
                        break

            # Check IRI local name match
            if query_lower in local.lower() and iri not in [r["iri"] for r in results]:
                label = _get_label(g, s) or local
                results.append({
                    "iri": iri, "label": label, "entity_type": etype,
                    "match_field": "iri", "snippet": local,
                })

            # Check comment match
            for o in g.objects(s, RDFS.comment):
                if isinstance(o, Literal) and query_lower in str(o).lower():
                    label = _get_label(g, s) or local
                    if iri not in [r["iri"] for r in results]:
                        snippet = str(o)[:80]
                        results.append({
                            "iri": iri, "label": label, "entity_type": etype,
                            "match_field": "comment", "snippet": snippet,
                        })
                    break

            if len(results) >= limit:
                return results[:limit]

    return results[:limit]


def _get_label(g, s):
    for o in g.objects(s, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local(iri):
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]
