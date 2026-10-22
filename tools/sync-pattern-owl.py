"""Keeps each pattern's two serialisations saying the same thing, and its metadata counts honest.

WHY THIS IS NEEDED NOW. Every pattern ships twice: `pattern.ttl`, which the documentation site
renders, and `pattern.owl`, which the Protege plugin loads and which `PatternIndex` reads to
build `patterns/index.tsv`. Until the patterns were only ever generated, the two could not drift
because one run wrote both. Then thirty-six of them were corrected by hand - relations restored
from NFDIcore, PMDco and MWO, deprecated IRIs replaced - and only the `.ttl` was edited. The
result is the worst kind of inconsistency: the website shows the corrected pattern and the plugin
hands the user the old one, and nothing says so.

So: rewrite `pattern.owl` from `pattern.ttl` wherever they disagree, and refresh the
`class_count` and `property_count` in `metadata.json`, which are read by the index and were stale
in every corrected pattern.

    python -I tools/sync-pattern-owl.py            # bring the .owl and the counts into line
    python -I tools/sync-pattern-owl.py --check     # write nothing; exit 1 if any have drifted

The --check mode is for a working tree, not for CI: it only considers patterns this tree has
edited (see edited_here), and on a clean checkout that set is empty, so in CI it would pass
without comparing anything. Run it before committing a pattern. The drift it looks for is
invisible otherwise - both files parse, both are valid OWL, and the only symptom is a plugin
user importing terms the documentation does not mention.

AFTERWARDS, patterns/index.tsv has to be rebuilt - PatternIndexTest compares the shipped index
with one built from the directories and fails when they differ. The index is built by Java
(PatternIndex.buildFrom), so this script cannot do it; it prints the reminder instead.

WHAT IT DOES NOT DO. It never writes `pattern.ttl`: the Turtle is the source of truth here,
because it is what a person edits. It does not touch a pattern whose two files already agree, so
a run over an untouched library changes nothing and the diff stays readable.
"""
import argparse
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PATTERNS = os.path.join(ROOT, "patterns")


def endings_of(path):
    return "\r\n" if b"\r\n" in io.open(path, "rb").read() else "\n"


def counts_of(graph):
    """Named classes and named properties, counted the way the harvester counts them.

    ANNOTATION PROPERTIES ARE NOT COUNTED. tools/harvest-patterns.py collects only
    owl:ObjectProperty and owl:DatatypeProperty, and metadata.json's property_count came from
    that. Including annotation properties here reported 108 of 163 patterns as having stale
    counts when almost none of them did - a disagreement between two definitions, read as a
    defect in the data.
    """
    import rdflib
    from rdflib import OWL, RDF

    classes = {c for c in graph.subjects(RDF.type, OWL.Class) if isinstance(c, rdflib.URIRef)}
    properties = set()
    for kind in (OWL.ObjectProperty, OWL.DatatypeProperty):
        properties |= {p for p in graph.subjects(RDF.type, kind) if isinstance(p, rdflib.URIRef)}
    return len(classes), len(properties)


def edited_here():
    """The patterns whose Turtle this working tree has changed, according to git.

    THE TURTLE IS ONLY THE SOURCE OF TRUTH WHERE SOMEBODY EDITED IT. Across the library as it
    was harvested the two serialisations already disagree in about sixty patterns, and not by
    omission: bag's .owl carries an owl:cardinality restriction its .ttl does not, and the two
    assert opposite directions of the same inverse pair. Rewriting every .owl from its .ttl
    would have deleted those axioms - a hundred files quietly losing content, to fix a
    consistency problem nobody reported.

    So the default is to sync only what this tree changed. Everything else is reported and left
    alone, because reconciling a harvest-time divergence needs the source, not a preference for
    one of the two files.
    """
    import subprocess
    try:
        out = subprocess.check_output(["git", "diff", "--name-only", "HEAD", "--", "patterns"],
                                      cwd=ROOT, stderr=subprocess.DEVNULL).decode("utf-8")
    except Exception:                                # noqa: BLE001 - no git, no scoping
        return None
    edited = set()
    for line in out.splitlines():
        line = line.strip()
        if line.endswith("/pattern.ttl"):
            edited.add(line.split("/")[1])
    return edited


def same_file_form(graph):
    """A copy of the graph with every file: IRI in one spelling, for comparison only.

    THREE PATTERNS HAVE THEIR TERMS IN A LOCAL FILE PATH. airline, airline-owl and
    roledependentnames were harvested with no ontology IRI, so each of their terms is named after
    the file it was read from - and the two files disagree about how to write that path:

        in the .ttl   file:/C:/.../ontoboard/protege-plugin/../patterns/airline/pattern.owl#hasAircraft
        in the .owl   file:///C:/.../ontoboard/patterns/airline/pattern.owl#hasAircraft

    One carries the unresolved `protege-plugin/../patterns` of whichever run harvested it and the
    single-slash form; the other has both normalised. Measured on airline: 32 triples each side,
    17 differing on each, and every one of those 34 is the same triple under two spellings of one
    path. So the files say the same thing and isomorphism says they do not.

    Rewriting them to agree would mean choosing a spelling for somebody else's term IRIs, which
    is a maintainer's decision - these IRIs should not be local paths at all. Normalising for the
    comparison costs nothing and keeps --check about content. Only file: IRIs are touched, and
    only by removing dot segments and using the authority form, which can merge two IRIs only if
    they were already the same path written two ways.
    """
    import rdflib

    def fixed(term):
        if not isinstance(term, rdflib.URIRef) or not str(term).startswith("file:"):
            return term
        rest = str(term)[len("file:"):]
        while rest.startswith("/"):
            rest = rest[1:]
        fragment = ""
        if "#" in rest:
            rest, fragment = rest.split("#", 1)
            fragment = "#" + fragment
        kept = []
        for segment in rest.split("/"):
            if segment == "..":
                if kept:
                    kept.pop()
            elif segment != ".":
                kept.append(segment)
        return rdflib.URIRef("file:///" + "/".join(kept) + fragment)

    same = rdflib.Graph()
    for subject, predicate, obj in graph:
        same.add((fixed(subject), fixed(predicate), fixed(obj)))
    return same


def drifted(pattern_id):
    """(ttl graph, owl graph, True when they disagree), or None when a file is missing."""
    import rdflib
    import rdflib.compare

    folder = os.path.join(PATTERNS, pattern_id)
    ttl_path = os.path.join(folder, "pattern.ttl")
    owl_path = os.path.join(folder, "pattern.owl")
    if not (os.path.isfile(ttl_path) and os.path.isfile(owl_path)):
        return None
    ttl = rdflib.Graph()
    owl = rdflib.Graph()
    try:
        ttl.parse(ttl_path, format="turtle")
    except Exception as broken:                      # noqa: BLE001 - the reason is the report
        return (None, None, "the .ttl does not parse: " + type(broken).__name__ + ": "
                + str(broken)[:90])
    try:
        # SNIFF, DO NOT TRUST THE EXTENSION. winstonpartwhole/pattern.owl is Turtle - it begins
        # with @prefix - and has been since it was harvested. The OWL API sniffs content, so the
        # plugin reads it and its terms are in index.tsv; only a parser that infers RDF/XML from
        # the name fails, which is what this did and what made it look like a broken pattern.
        owl.parse(owl_path)
    except Exception:                                # noqa: BLE001
        try:
            owl = rdflib.Graph()
            owl.parse(owl_path, format="turtle")
        except Exception as broken:                  # noqa: BLE001
            return (None, None, "the .owl parses as neither RDF/XML nor Turtle: "
                    + type(broken).__name__ + ": " + str(broken)[:80])
    return (ttl, owl,
            not rdflib.compare.isomorphic(same_file_form(ttl), same_file_form(owl)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="write nothing; exit 1 if any pattern has drifted")
    parser.add_argument("--only", help="one pattern id")
    parser.add_argument("--all", action="store_true",
                        help="sync every drifted pattern, not only the ones this tree edited. "
                             "This DELETES axioms that exist only in a .owl - read edited_here.")
    args = parser.parse_args()

    try:
        import rdflib  # noqa: F401
    except ImportError:
        print("this needs rdflib: python -m pip install rdflib", file=sys.stderr)
        return 2

    ids = sorted(d for d in os.listdir(PATTERNS) if os.path.isdir(os.path.join(PATTERNS, d)))
    if args.only:
        ids = [i for i in ids if i == args.only]
        if not ids:
            print("no pattern called " + args.only, file=sys.stderr)
            return 2

    mine = None if args.all else edited_here()
    apart, synced, counted, broken, inherited = [], 0, 0, [], []
    for pattern_id in ids:
        state = drifted(pattern_id)
        if state is None:
            continue
        ttl, owl, differs = state
        if isinstance(differs, str):
            broken.append((pattern_id, differs))
            continue
        if differs and mine is not None and pattern_id not in mine:
            # Divergence that was already there. Reported, never rewritten.
            inherited.append(pattern_id)
        elif differs:
            apart.append(pattern_id)
            if not args.check:
                path = os.path.join(PATTERNS, pattern_id, "pattern.owl")
                newline = endings_of(path)
                text = ttl.serialize(format="xml")
                if isinstance(text, bytes):
                    text = text.decode("utf-8")
                io.open(path, "wb").write(
                    text.replace("\r\n", "\n").replace("\n", newline).encode("utf-8"))
                synced += 1

        # The counts come from the Turtle either way, because that is the source of truth.
        meta_path = os.path.join(PATTERNS, pattern_id, "metadata.json")
        if not os.path.isfile(meta_path):
            continue
        meta = json.load(io.open(meta_path, encoding="utf-8"))
        classes, properties = counts_of(ttl)
        if meta.get("class_count") == classes and meta.get("property_count") == properties:
            continue
        counted += 1
        if args.check:
            apart.append(pattern_id + " (metadata counts)")
            continue
        newline = endings_of(meta_path)
        meta["class_count"] = classes
        meta["property_count"] = properties
        text = json.dumps(meta, indent=2, ensure_ascii=False) + "\n"
        io.open(meta_path, "wb").write(text.replace("\n", newline).encode("utf-8"))

    print("patterns:                 %d" % len(ids))
    print("edited in this tree:      %s" % ("every one" if mine is None else len(mine)))
    print("the .owl had drifted:     %d" % len([a for a in apart if "(metadata" not in a]))
    print("metadata counts stale:    %d" % counted)
    if inherited:
        print("drifted before this work: %d  (left alone; --all would rewrite them and lose"
              % len(inherited))
        print("                             axioms that exist only in a .owl)")
    if not args.check:
        print("rewritten from the .ttl:  %d" % synced)
    if broken:
        print("could not read:           %d" % len(broken))
        for pattern_id, why in broken:
            print("    %-34s %s" % (pattern_id, why))

    if args.check and (apart or broken):
        print()
        print("These patterns' .owl or metadata no longer matches their .ttl:", file=sys.stderr)
        for pattern_id in sorted(set(apart)):
            print("    " + pattern_id, file=sys.stderr)
        print("Run tools/sync-pattern-owl.py, then rebuild patterns/index.tsv.",
              file=sys.stderr)
        return 1

    if synced or counted:
        print()
        print("NOW REBUILD patterns/index.tsv - PatternIndexTest compares the shipped index with")
        print("one built from these directories, so it fails until the index is regenerated.")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
