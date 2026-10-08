#!/usr/bin/env python3
"""Extracts a fifth pattern collection from the exact BFO that NFDIcore and MWO use.

Asked for: "the ontology design patterns that could be extracted from exactly the BFO ontology
that NFDI core is using ... just be careful that the BFO ontology that you are using should be the
one that is used in NFDI core and MWO".

WHICH BFO. Four independent lines of evidence, three of them verified here at build time:

  1. NFDIcore's own src/ontology/nfdicore-odk.yaml says, verbatim:
         - id: bfo
           mirror_from: http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl
  2. Its committed module src/ontology/imports/bfo_import.owl records
         Annotation(dc11:source <http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl>)
  3. That PURL resolves to the raw tag URL below, whose sha256 is checked by this script.
  4. MWO 3.0.2's 66 distinct BFO IRIs are the SAME SET as NFDIcore 3.0.4's, and MWO declares no
     BFO product of its own - so its BFO is NFDIcore's by construction, not by coincidence.

THE TRAP THIS AVOIDS. The obvious URL, http://purl.obolibrary.org/obo/bfo.owl, returns HTTP 200
and serves BFO/v2019-08-26/bfo_classes_only.owl - which contains **zero** owl:ObjectProperty
elements. Extracting from that would produce four patterns with no relations in them at all,
which is precisely the failure that makes the 123 ODP patterns useless to a BFO project. The
pinned URL below is the immutable raw tag, not the PURL, because a PURL is a redirect somebody
else controls and the artifact's own versionIRI is that same undated PURL.

WHY NOT THE 1.87.0 EXTRACTOR UNCHANGED. Its rule - own terms, every axiom mentioning only those
terms, labels and definitions, one level of parent - is kept, with signature closure added before
the parent step. Without closure the four patterns lose domain and range axioms between them and
the dependence pattern ships both its headline relations with half a signature: a participation
pattern whose participates_in has no declared range is not a pattern, it is a class list.

    python tools/extract-bfo-patterns.py            # writes four directories under patterns/
    python tools/extract-bfo-patterns.py --check    # verifies the artifact only, writes nothing

Afterwards, regenerate patterns/index.tsv - PatternIndexTest rebuilds it and compares, so a
pattern added without reindexing fails the build rather than going missing in silence.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

import rdflib
from rdflib import BNode, Literal, URIRef
from rdflib.namespace import OWL, RDF, RDFS, SKOS

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PATTERNS = os.path.join(ROOT, "patterns")

# The immutable raw tag, not the PURL. See the module docstring.
SOURCE = ("https://raw.githubusercontent.com/BFO-ontology/BFO-2020/release-2024-01-29/"
          "src/owl/profiles/atemporal/bfo-2020-without-some-all-times.owl")
SHA256 = "57264477d936fc2ed30da6498e2e5fd37ec98c93e0ae68402e3692fea145bf7e"
VERSION_IRI = "http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl"

OBO = "http://purl.obolibrary.org/obo/"
IAO_DEFINITION = URIRef(OBO + "IAO_0000115")

# The four, with the seeds the survey settled on. Each one has real axiom content - restrictions,
# domains, ranges, inverses, transitivity - rather than being a slice of the class tree; a
# collection of hierarchy fragments would be worse than nothing, because it would look like four
# patterns.
WANTED = [
    {
        "id": "bfo-occurrent-parthood-and-order",
        "name": "Occurrent Parthood And Order",
        "category": "structural",
        "domain": "upper ontology",
        "description": (
            "How a process decomposes, and in what order. Four parthood relations in a "
            "proper/improper lattice - occurrent part, proper occurrent part, temporal part, "
            "proper temporal part - each paired with its inverse and declared transitive, with "
            "BFO's closure axioms saying which kinds of occurrent can be parts of which. Plus "
            "precedes and preceded by, and the process boundaries that begin and end a process."),
        "competency_questions": (
            "Which sub-processes make up a given process, and which of them are temporal parts "
            "rather than merely occurrent parts?; Which process precedes a given process, and "
            "which process boundary begins or ends it?"),
        "seed": ["BFO_0000003", "BFO_0000015", "BFO_0000035", "BFO_0000062", "BFO_0000063",
                 "BFO_0000117", "BFO_0000118", "BFO_0000121", "BFO_0000132", "BFO_0000136",
                 "BFO_0000138", "BFO_0000139", "BFO_0000181"],
    },
    {
        "id": "bfo-specific-dependence-and-inherence",
        "name": "Specific Dependence And Inherence",
        "category": "structural",
        "domain": "upper ontology",
        "description": (
            "What it means for one entity to depend on another. Inherence and its inverse - the "
            "quality, role, disposition or function that a bearer carries - sitting on generic "
            "and specific dependence, with the realizable entities and the processes that "
            "realize them."),
        "competency_questions": (
            "Which qualities, roles, dispositions or functions inhere in a given entity, and "
            "which entity bears a given one?; What must exist for a given dependent entity to "
            "exist?"),
        # BFO_0000101 was in the first draft of this seed and is NOT declared by this
        # artifact - the script refuses rather than quietly extracting a smaller pattern.
        "seed": ["BFO_0000016", "BFO_0000017", "BFO_0000019", "BFO_0000020", "BFO_0000023",
                 "BFO_0000031", "BFO_0000034", "BFO_0000145", "BFO_0000194", "BFO_0000195",
                 "BFO_0000196", "BFO_0000197", "BFO_0000054", "BFO_0000055"],
    },
    {
        "id": "bfo-process-site-and-history",
        "name": "Process Site And History",
        "category": "structural",
        "domain": "upper ontology",
        "description": (
            "Where a process happens and what a thing's history is. Occurs in and its inverse "
            "environs, relating a process to the material entity or site that contains it, "
            "together with history and the material entity it is the history of."),
        "competency_questions": (
            "In which site or material entity does a given process occur, and which processes "
            "does a given facility environ?; What is the history of a given material entity?"),
        "seed": ["BFO_0000015", "BFO_0000029", "BFO_0000040", "BFO_0000182", "BFO_0000066",
                 "BFO_0000183", "BFO_0000184", "BFO_0000185"],
    },
    {
        "id": "bfo-occurrent-spacetime-regions",
        "name": "Occurrent Spacetime Regions",
        "category": "structural",
        "domain": "upper ontology",
        "description": (
            "Where and when an occurrent is. The spatiotemporal region a process occupies, the "
            "temporal region that region projects onto, and the temporal regions at which a "
            "continuant exists."),
        "competency_questions": (
            "Which spatiotemporal region does a given process occupy, and onto which temporal "
            "region does that project?; At which times does a given entity exist?"),
        # BFO_0000153, "temporally projects onto", is the relation the pattern is named for
        # and was missing from the first draft of this seed.
        "seed": ["BFO_0000003", "BFO_0000008", "BFO_0000011", "BFO_0000038", "BFO_0000148",
                 "BFO_0000202", "BFO_0000203", "BFO_0000108", "BFO_0000153", "BFO_0000199",
                 "BFO_0000200", "BFO_0000221", "BFO_0000222", "BFO_0000223", "BFO_0000224"],
    },
]


def fetch(cache):
    """The artifact, verified. A wrong BFO invalidates every pattern extracted from it."""
    if os.path.isfile(cache):
        data = open(cache, "rb").read()
    else:
        data = urllib.request.urlopen(SOURCE, timeout=60).read()
        with open(cache, "wb") as handle:
            handle.write(data)
    got = hashlib.sha256(data).hexdigest()
    if got != SHA256:
        raise SystemExit(
            "the BFO artifact is not the one this script was written against.\n"
            "  expected sha256 %s\n  got          %s\n"
            "  from %s\n"
            "Re-pin deliberately rather than extracting from something else."
            % (SHA256, got, SOURCE))
    return data


def named(term):
    return isinstance(term, URIRef)


VOCABULARY = ("http://www.w3.org/1999/02/22-rdf-syntax-ns#",
              "http://www.w3.org/2000/01/rdf-schema#",
              "http://www.w3.org/2002/07/owl#",
              "http://www.w3.org/2004/02/skos/core#",
              "http://www.w3.org/2001/XMLSchema#")


def is_vocabulary(term):
    """RDF, RDFS, OWL, SKOS and XSD - the language, not the ontology."""
    return any(str(term).startswith(ns) for ns in VOCABULARY)


def names_in(graph, node, seen=None):
    """Every named IRI inside an expression, recursing through blank-node class constructs.

    An rdfs:range of `union of (material entity, site)` is a blank node whose members are the
    classes that matter; reading only direct URIRefs would miss both and the pattern would ship a
    relation whose range mentions classes it does not declare.
    """
    if seen is None:
        seen = set()
    if named(node):
        return {node}
    if not isinstance(node, BNode) or node in seen:
        return set()
    seen.add(node)
    found = set()
    for predicate, obj in graph.predicate_objects(node):
        if predicate in (RDF.first, RDF.rest, OWL.unionOf, OWL.intersectionOf,
                         OWL.complementOf, OWL.onClass, OWL.someValuesFrom, OWL.allValuesFrom,
                         OWL.onProperty):
            found |= names_in(graph, obj, seen)
    return found


def close_signature(graph, seed):
    """Adds what the seed's relations need in order to mean anything.

    For every object property in the set: the named classes in its domain and range, its inverses
    and its super-properties. Repeated until stable.

    This is the one change from the 1.87.0 rule, and it is what makes the collection real rather
    than four lists of class names: without it the four patterns lose domain and range axioms
    between them and the dependence pattern ships both its headline relations with half a
    signature.
    """
    terms = set(seed)
    while True:
        grown = set(terms)
        for term in terms:
            if (term, RDF.type, OWL.ObjectProperty) not in graph:
                continue
            for predicate in (RDFS.domain, RDFS.range):
                for obj in graph.objects(term, predicate):
                    grown |= names_in(graph, obj)
            for obj in graph.objects(term, OWL.inverseOf):
                if named(obj):
                    grown.add(obj)
            for obj in graph.objects(term, RDFS.subPropertyOf):
                if named(obj):
                    grown.add(obj)
        if grown == terms:
            return terms
        terms = grown


def with_parents(graph, terms):
    """One level of named parent above each term, so the pattern says where it sits."""
    grown = set(terms)
    for term in terms:
        for predicate in (RDFS.subClassOf, RDFS.subPropertyOf):
            for parent in graph.objects(term, predicate):
                if named(parent):
                    grown.add(parent)
    return grown


def induced(graph, terms):
    """Every axiom all of whose named IRIs lie in the set, plus labels and definitions.

    Not a ROBOT STAR module. Measured in 1.87.0 on these same sources: a PMDco pattern documented
    with eight terms came out of STAR with 119 classes and 1,508 axioms, because a BFO-based
    ontology connects everything to the upper-ontology spine and a logical module must follow it.
    """
    out = rdflib.Graph()
    for prefix, uri in graph.namespaces():
        out.bind(prefix, uri)

    def inside(node, subject):
        """Whether a statement belongs: every DOMAIN IRI it mentions is in the set.

        Vocabulary is not a domain term. `(process, rdf:type, owl:Class)` mentions owl:Class,
        which is obviously not a BFO term and must not disqualify the declaration - the first
        version of this filtered exactly that out and produced patterns with 78 triples and zero
        classes, because every type declaration had been dropped.
        """
        for name in names_in(graph, node):
            if is_vocabulary(name):
                continue
            if name not in terms:
                return False
        return subject in terms

    for subject in sorted(terms, key=str):
        for predicate, obj in graph.predicate_objects(subject):
            if predicate in (RDFS.label, SKOS.definition, IAO_DEFINITION):
                out.add((subject, predicate, obj))
                continue
            if isinstance(obj, Literal):
                continue
            if inside(obj, subject):
                out.add((subject, predicate, obj))
                # Blank-node expressions need their own triples carried across.
                if isinstance(obj, BNode):
                    for triple in walk(graph, obj):
                        out.add(triple)
    return out


def walk(graph, node, seen=None):
    """Every triple reachable from a blank node, so an expression survives the copy."""
    if seen is None:
        seen = set()
    if not isinstance(node, BNode) or node in seen:
        return []
    seen.add(node)
    triples = []
    for predicate, obj in graph.predicate_objects(node):
        triples.append((node, predicate, obj))
        triples.extend(walk(graph, obj, seen))
    return triples


def orphan_properties(out):
    """Properties with no domain, no range and no restriction - a class list wearing a relation.

    The check that stops this collection becoming the other 76. A property that survives
    extraction with nothing said about it is not modelling content; it is a name.
    """
    orphans = []
    for prop in set(out.subjects(RDF.type, OWL.ObjectProperty)) \
            | set(out.subjects(RDF.type, OWL.DatatypeProperty)):
        has_domain = any(out.objects(prop, RDFS.domain))
        has_range = any(out.objects(prop, RDFS.range))
        used = any(out.subjects(OWL.onProperty, prop))
        if not (has_domain or has_range or used):
            orphans.append(str(prop))
    return sorted(orphans)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the pinned artifact and report, writing nothing")
    args = parser.parse_args()

    cache = os.path.join(ROOT, "build", "bfo-notime.owl")
    os.makedirs(os.path.dirname(cache), exist_ok=True)
    data = fetch(cache)

    graph = rdflib.Graph()
    graph.parse(data=data, format="xml")
    versions = [str(v) for v in graph.objects(None, OWL.versionIRI)]
    if VERSION_IRI not in versions:
        raise SystemExit("the artifact's versionIRI is %s, expected %s" % (versions, VERSION_IRI))
    print("BFO verified: %d triples, versionIRI %s" % (len(graph), VERSION_IRI))
    print("  sha256 %s" % SHA256)
    if args.check:
        return 0

    total_classes = 0
    for spec in WANTED:
        seed = {URIRef(OBO + local) for local in spec["seed"]}
        missing = sorted(str(t) for t in seed if (t, None, None) not in graph)
        if missing:
            raise SystemExit("%s seeds terms this BFO does not declare: %s"
                             % (spec["id"], missing))

        closed = close_signature(graph, seed)
        terms = with_parents(graph, closed)
        out = induced(graph, terms)

        orphans = orphan_properties(out)
        if orphans:
            raise SystemExit("%s would ship properties with nothing said about them: %s"
                             % (spec["id"], orphans))

        classes = sorted({c for c in out.subjects(RDF.type, OWL.Class) if named(c)}, key=str)
        objprops = sorted({c for c in out.subjects(RDF.type, OWL.ObjectProperty) if named(c)},
                          key=str)
        dataprops = sorted({c for c in out.subjects(RDF.type, OWL.DatatypeProperty)
                            if named(c)}, key=str)

        folder = os.path.join(PATTERNS, spec["id"])
        os.makedirs(folder, exist_ok=True)
        out.serialize(destination=os.path.join(folder, "pattern.owl"), format="xml")
        out.serialize(destination=os.path.join(folder, "pattern.ttl"), format="turtle")
        metadata = {
            "id": spec["id"],
            "name": spec["name"],
            "description": spec["description"],
            "category": spec["category"],
            "domain": spec["domain"],
            "competency_questions": spec["competency_questions"],
            "pattern_iri": "https://ontoboard.org/patterns/" + spec["id"] + ".owl",
            "source": SOURCE,
            "collection": "bfo",
            "publisher": "basic-formal-ontology.org",
            "extracted_from": "bfo",
            "class_count": len(classes),
            "property_count": len(objprops) + len(dataprops),
        }
        with open(os.path.join(folder, "metadata.json"), "w", encoding="utf-8",
                  newline="\n") as handle:
            json.dump(metadata, handle, indent=2, ensure_ascii=False)
            handle.write("\n")

        total_classes += len(classes)
        print("  %-42s %2d classes, %2d object properties, %2d data properties, %3d triples"
              % (spec["id"], len(classes), len(objprops), len(dataprops), len(out)))

    print("%d patterns written under patterns/, %d classes in all"
          % (len(WANTED), total_classes))
    print("Now regenerate patterns/index.tsv - PatternIndexTest compares it with the "
          "directories and fails on a stale one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
