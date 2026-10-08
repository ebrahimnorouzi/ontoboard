#!/usr/bin/env python3
"""Measures whether several ontologies share any modelling they actually authored.

Asked for: a way to extract ontology design patterns from more than one ontology, finding "the
recurrent modelling problems" several ontologies solve the same way - and that the result "be
scientific and everything should be reproducible and sound". A pattern is recurrent by
definition, so one ontology cannot evidence one. This tool answers the question that has to come
first: **is there anything there to extract?**

It is a measurement, not an extractor. It reports what is shared; it does not propose patterns.

THE TRAP IT EXISTS TO AVOID, which is the whole reason it is in the repo. An OBO project's
release carries several artefacts, and the obvious one to compare is the complete ontology -
`<name>-full.ttl` or plain `<name>.ttl`. Comparing those answers a different question than the
one being asked, because everything the projects import is in all of them. Measured on NFDIcore
3.0.4, MWO 3.0.2 and PMDco 3.0.0-rc2 on 2026-10-08:

    -full artefacts:  23 restriction shapes shared by all three, 19 of which have a BFO property
                      AND a BFO filler. All of them arrive with the imports.
    -base artefacts:   0 restriction shapes shared by all three.

Zero. Nothing these three projects each authored is shared at the level of a restriction, and
every apparent commonality between them is BFO's. An extractor that mines the `-full` files and
matches axioms would report twenty-three shared "patterns", all of them an artefact of a shared
import - which is a confident, precise, wrong answer, and worse than no answer.

So the honest finding is that these three reach the same shapes through different vocabulary -
the role pattern is in all three, via `RO_0000087 has role` and `BFO_0000196 bearer of` in
NFDIcore and MWO and via OBI's `characteristic of` in PMDco - and that matching exact IRIs
cannot see it. Multi-ontology pattern extraction here needs shapes with variable vertices, not
axiom intersection. This tool is the evidence for that claim and the way to re-check it.

WHAT A SHAPE IS HERE. `(restriction kind, property, filler)`, with the subject class deliberately
dropped: two ontologies that both say "<something> part_of only continuant" share that modelling
whatever they hang it on, and keeping the subject would make every shape unique and the
intersection trivially empty. Only named fillers count - an anonymous filler is a blank node and
cannot be compared across files without aligning blank nodes first, which is a harder problem
and not one this answers. Both of those are limitations of the measurement and are printed with
the result rather than hidden in it.

WHY RESTRICTIONS AND NOT SUBCLASSOF. Every OBO ontology has thousands of SubClassOf axioms and
sharing them evidences nothing: `SubClassOf(X, owl:Thing)` is not a pattern. A restriction says
something about how a relation is used, which is the smallest unit that can carry modelling.
That choice is why the numbers above are small enough to look at by hand.

    python tools/shared-shapes.py a-base.ttl b-base.ttl c-base.ttl
    python tools/shared-shapes.py --kinds someValuesFrom a.ttl b.ttl    # one kind only

The measurement above was taken with, from the three project checkouts:

    python tools/shared-shapes.py nfdicore-full.ttl mwo-full.ttl pmdco-full.ttl
    python tools/shared-shapes.py nfdicore-base.ttl mwo-base.ttl pmdco-base.ttl

Needs rdflib. Reads only; writes nothing.
"""
import argparse
import os
import sys

try:
    from rdflib import Graph, RDF, OWL, URIRef
except ImportError:
    sys.exit("This needs rdflib: python -m pip install rdflib")

# The restriction forms that say something about how a relation is used. Cardinality
# restrictions carry onClass; value restrictions carry the filler directly.
ALL_KINDS = ["someValuesFrom", "allValuesFrom", "hasValue", "onClass", "onDataRange"]

BFO = "http://purl.obolibrary.org/obo/BFO_"


def shapes_in(path, kinds):
    """Every (kind, property, named filler) the file states, and how big the file was."""
    graph = Graph()
    graph.parse(path)
    found = set()
    anonymous = 0
    for restriction in graph.subjects(RDF.type, OWL.Restriction):
        prop = graph.value(restriction, OWL.onProperty)
        if not isinstance(prop, URIRef):
            continue
        for kind in kinds:
            filler = graph.value(restriction, getattr(OWL, kind))
            if filler is None:
                continue
            if isinstance(filler, URIRef):
                found.add((kind, str(prop), str(filler)))
            else:
                anonymous += 1
    return found, len(graph), anonymous


def short(iri):
    return iri.rsplit("/", 1)[-1].rsplit("#", 1)[-1]


def main():
    parser = argparse.ArgumentParser(
        description="What modelling do these ontologies share, that they each authored?")
    parser.add_argument("files", nargs="+",
                        help="two or more ontology files; prefer the -base artefacts")
    parser.add_argument("--kinds", nargs="+", default=ALL_KINDS, choices=ALL_KINDS,
                        help="restriction forms to count (default: all)")
    parser.add_argument("--show", type=int, default=25,
                        help="how many shared shapes to print (default 25)")
    options = parser.parse_args()

    if len(options.files) < 2:
        sys.exit("Comparing one ontology with itself answers nothing - give at least two.")

    missing = [path for path in options.files if not os.path.isfile(path)]
    if missing:
        sys.exit("No such file: " + ", ".join(missing))

    sets = {}
    for path in options.files:
        name = os.path.basename(path)
        found, triples, anonymous = shapes_in(path, options.kinds)
        sets[name] = found
        print("  %-28s %8d triples  %5d shapes  (%d skipped, anonymous filler)"
              % (name, triples, len(found), anonymous))

    common = set.intersection(*sets.values())
    print()
    print("  shared by all %d: %d" % (len(sets), len(common)))

    if not common:
        print()
        print("  Nothing. At the level of a restriction these ontologies have no modelling in")
        print("  common. If they were compared on their complete artefacts rather than the ones")
        print("  holding only their own terms, whatever they share would be their imports.")
        return

    inherited = [s for s in common if s[1].startswith(BFO) and s[2].startswith(BFO)]
    print("  of which both property and filler are BFO's: %d" % len(inherited))
    if inherited and len(inherited) == len(common):
        print()
        print("  Every shared shape is BFO's own. That is a shared import, not shared design.")
    print()
    for shape in sorted(common)[:options.show]:
        mark = "import" if shape in inherited else "      "
        print("    %s  %-15s %-28s %s"
              % (mark, shape[0], short(shape[1]), short(shape[2])))
    if len(common) > options.show:
        print("    ... and %d more" % (len(common) - options.show))

    print()
    print("  Two limits of this measurement, stated rather than buried: the subject class is")
    print("  dropped, so a shape says what is asserted and not of what; and an anonymous filler")
    print("  is skipped, because comparing blank nodes across files needs them aligned first.")


if __name__ == "__main__":
    main()
