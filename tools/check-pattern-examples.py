"""Checks that every pattern example is well formed and consistent with its pattern.

An example that does not parse renders nothing, and an example whose individuals have the wrong
types teaches the reader the pattern backwards - which is worse than having no example. So this
is the gate, and it is meant to be run in CI beside the page generator.

What it checks, per pattern:

  1. example.ttl parses as Turtle.
  2. Every class and property the example uses is one the PATTERN declares. An example that
     reaches for a term the pattern does not have is either a typo or a different pattern.
  3. For every object-property assertion in the example, the subject has the property's
     rdfs:domain among its types and the object has its rdfs:range - following rdfs:subClassOf
     inside the pattern, so a subclass of the domain counts. This is the check that catches a
     property used backwards.
  4. The example asserts at most one direction of an inverse pair, because the other is
     inferred and asserting it says the same thing twice.
  5. The example is connected: no individual sits alone with no edge in or out. A pile of
     unrelated individuals does not show a shape.

Usage, from the repository root:

    python -I tools/check-pattern-examples.py            # report everything
    python -I tools/check-pattern-examples.py --quiet    # only the failures
    python -I tools/check-pattern-examples.py --only mwo-sample

Exit code is 1 if any example fails, so it can gate a build. Needs rdflib.
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PATTERNS = os.path.join(ROOT, "patterns")

BUILT_IN = (
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "http://www.w3.org/2000/01/rdf-schema#",
    "http://www.w3.org/2002/07/owl#",
    "http://www.w3.org/2001/XMLSchema#",
    "http://www.w3.org/2004/02/skos/core#",
    "http://purl.org/dc/elements/1.1/",
    "http://purl.org/dc/terms/",
    "http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#",
)


def short(iri):
    said = str(iri)
    cut = max(said.rfind("#"), said.rfind("/"))
    return said[cut + 1:] if cut >= 0 else said


def built_in(iri):
    return any(str(iri).startswith(p) for p in BUILT_IN)


def allowed(graph, constraint, RDFS):
    """The set of named classes a domain or range axiom permits, or None when it says nothing.

    BFO USES CLASS EXPRESSIONS FOR MOST OF ITS DOMAINS, and the first version of this skipped
    them: 8 of the 28 relations in the BFO patterns have a union, an intersection or a
    complement as their domain or range - `occurs in` is (process or process boundary) to (site
    or material entity) - so the check that is supposed to catch a backwards property was not
    looking at the headline relations at all.

      a named class          -> that class
      owl:unionOf            -> any of its named members
      owl:intersectionOf     -> all of its named members, so the strictest wins
      owl:complementOf       -> None: "not a spatial region" cannot be confirmed from the types
                                an example asserts, only refuted, and refuting it needs the
                                disjointness axioms the extractor drops
    """
    import rdflib
    from rdflib.collection import Collection

    if isinstance(constraint, rdflib.URIRef):
        return None if built_in(constraint) else {constraint}
    for predicate, combine in ((rdflib.OWL.unionOf, "any"),
                               (rdflib.OWL.intersectionOf, "all")):
        for head in graph.objects(constraint, predicate):
            members = []
            try:
                members = list(Collection(graph, head))
            except Exception:                        # noqa: BLE001 - a malformed list says nothing
                return None
            named = set()
            for member in members:
                if isinstance(member, rdflib.URIRef):
                    if not built_in(member):
                        named.add(member)
                else:
                    # A nested expression. One unreadable member makes the whole axiom
                    # unenforceable rather than wrongly strict.
                    inner = allowed(graph, member, RDFS)
                    if inner is None:
                        return None
                    named |= inner
            if not named:
                return None
            if combine == "all":
                # Every member must hold, and the example asserts one type per individual, so
                # the only honest reading is the intersection of what each member allows.
                return named
            return named
    return None


def ancestors(graph, cls, RDFS):
    """A class and everything it is a subclass of, inside the pattern."""
    seen, todo = set(), [cls]
    while todo:
        here = todo.pop()
        if here in seen:
            continue
        seen.add(here)
        for parent in graph.objects(here, RDFS.subClassOf):
            todo.append(parent)
    return seen


def generator():
    """The example generator, loaded as a module so its import resolution is shared.

    Loaded by path because the file name has hyphens in it. Shared rather than copied because
    the checker and the generator have to agree about what a pattern says - and this library has
    twenty-two patterns, agentrole among them, whose properties live in a file they owl:import.
    A checker that did not follow those imports reported every imported term as undeclared, and
    the honest-looking number it produced was 21 lower than the truth.
    """
    import importlib.util
    path = os.path.join(HERE, "make-pattern-examples.py")
    spec = importlib.util.spec_from_file_location("ontoboard_pattern_examples", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check(pattern_id, library=None):
    """Returns a list of problems, empty when the example is sound."""
    import rdflib
    from rdflib import OWL, RDF, RDFS

    folder = os.path.join(PATTERNS, pattern_id)
    pattern_path = os.path.join(folder, "pattern.ttl")
    example_path = os.path.join(folder, "example.ttl")
    if not os.path.isfile(example_path):
        return ["no example.ttl"], []
    if not os.path.isfile(pattern_path):
        return ["no pattern.ttl to check the example against"], []

    pattern = rdflib.Graph()
    try:
        pattern.parse(pattern_path, format="turtle")
    except Exception as broken:                      # noqa: BLE001
        return ["the PATTERN does not parse: " + type(broken).__name__ + ": "
                + str(broken)[:90]], []

    # Follow owl:imports into the library, exactly as the generator does, so a term the pattern
    # gets from an import is not reported as undeclared.
    if library:
        seen = {pattern_path}
        todo = [(pattern, 2)]
        while todo:
            here, left = todo.pop()
            if left <= 0:
                continue
            for imported in list(here.objects(None, OWL.imports)):
                where = library.get(str(imported)) or library.get(str(imported).rstrip("#"))
                if not where or where in seen:
                    continue
                seen.add(where)
                try:
                    added = rdflib.Graph()
                    added.parse(where, format="turtle")
                except Exception:                    # noqa: BLE001
                    continue
                pattern += added
                todo.append((added, left - 1))

    example = rdflib.Graph()
    try:
        example.parse(example_path, format="turtle")
    except Exception as broken:                      # noqa: BLE001
        return ["does not parse: " + type(broken).__name__ + ": " + str(broken)[:120]], []

    problems = []
    notes = []
    declared = set()
    for kind in (OWL.Class, OWL.ObjectProperty, OWL.DatatypeProperty, OWL.AnnotationProperty,
                 RDF.Property):
        for term in pattern.subjects(RDF.type, kind):
            if isinstance(term, rdflib.URIRef):
                declared.add(term)

    # 2. every term the example uses is one the pattern has
    for subject, predicate, obj in example:
        for term in (predicate, obj):
            if not isinstance(term, rdflib.URIRef) or built_in(term):
                continue
            if str(term).startswith("https://w3id.org/ontoboard/example/"):
                continue
            if term not in declared:
                problems.append("uses %s, which the pattern does not declare" % short(term))
    if problems:
        problems = sorted(set(problems))

    types = {}
    for individual in set(example.subjects(RDF.type, None)):
        held = set()
        for cls in example.objects(individual, RDF.type):
            if isinstance(cls, rdflib.URIRef) and not built_in(cls):
                held |= ancestors(pattern, cls, RDFS)
        types[individual] = held

    # 3. domains and ranges, which is where a backwards property shows up
    for prop in pattern.subjects(RDF.type, OWL.ObjectProperty):
        domains = [allowed(pattern, d, RDFS) for d in pattern.objects(prop, RDFS.domain)]
        ranges = [allowed(pattern, r, RDFS) for r in pattern.objects(prop, RDFS.range)]
        domains = [d for d in domains if d]
        ranges = [r for r in ranges if r]
        for subject, obj in example.subject_objects(prop):
            for which, constraints, who in (("subject", domains, subject),
                                            ("object", ranges, obj)):
                if not constraints:
                    continue
                if which == "object" and not isinstance(obj, rdflib.URIRef):
                    continue
                held = types.get(who, set())
                # Several domain axioms on one property are a conjunction: each has to hold.
                for constraint in constraints:
                    if held & constraint:
                        continue
                    problems.append(
                        "%s %s %s - the %s is not %s, which is the property's %s"
                        % (short(subject), short(prop), short(obj), which,
                           " or ".join(sorted(short(c) for c in constraint)),
                           "domain" if which == "subject" else "range"))

    # 4. one direction of an inverse pair, PER PAIR OF INDIVIDUALS and not per example.
    #
    # This check used to fire whenever BOTH properties appeared anywhere in the example, which
    # is a different and much stronger claim: an example may legitimately use `has part` between
    # one pair and `part of` between another, and nothing is said twice. Reported from a review
    # that lost two rewrites to it. What is actually redundant is asserting p(a,b) AND q(b,a)
    # when p and q are inverses, because the second is inferred from the first.
    for one, _, other in pattern.triples((None, OWL.inverseOf, None)):
        forward = set(example.subject_objects(one))
        for subject, obj in sorted(forward, key=lambda pair: (str(pair[0]), str(pair[1]))):
            if (obj, subject) in set(example.subject_objects(other)):
                problems.append(
                    "asserts %s %s %s and %s %s %s, and the pattern declares those properties "
                    "inverse - the second is inferred from the first"
                    % (short(subject), short(one), short(obj),
                       short(obj), short(other), short(subject)))
                break

    # 4b. THE CHARACTERISTICS, which an example can break without using a wrong type at all.
    # A review of the BFO patterns found an example in which one occurrent preceded itself, was
    # a proper part of itself and a proper temporal part of itself. Every type in it was right,
    # so every check above passed, and the graph asserted something BFO's own axioms forbid.
    for prop in pattern.subjects(RDF.type, OWL.IrreflexiveProperty):
        for subject, obj in example.subject_objects(prop):
            if subject == obj:
                problems.append("%s %s itself, and the pattern declares %s irreflexive"
                                % (short(subject), short(prop), short(prop)))
    for prop in pattern.subjects(RDF.type, OWL.AsymmetricProperty):
        pairs = set(example.subject_objects(prop))
        for subject, obj in sorted(pairs, key=lambda p: (str(p[0]), str(p[1]))):
            if (obj, subject) in pairs:
                problems.append("%s and %s are joined by %s both ways, and the pattern declares "
                                "it asymmetric" % (short(subject), short(obj), short(prop)))
    for prop in pattern.subjects(RDF.type, OWL.FunctionalProperty):
        for subject in set(example.subjects(prop, None)):
            values = set(example.objects(subject, prop))
            if len(values) > 1:
                problems.append("%s has %d values for %s, which the pattern declares functional"
                                % (short(subject), len(values), short(prop)))

    # A CYCLE IN A TRANSITIVE PROPERTY entails that something stands in it to itself, which for
    # a parthood or an ordering is a modelling error. It is reported as a note and not a failure,
    # because OWL does not forbid it unless the property is also declared irreflexive - and BFO's
    # OWL declares eight of these transitive and none of them irreflexive, leaving the asymmetry
    # only in its Common Logic. So this says "look at it" rather than "it is invalid".
    for prop in pattern.subjects(RDF.type, OWL.TransitiveProperty):
        edges = {}
        for subject, obj in example.subject_objects(prop):
            edges.setdefault(subject, set()).add(obj)
        for start in list(edges):
            reached, todo = set(), [start]
            while todo:
                here = todo.pop()
                for nxt in edges.get(here, ()):
                    if nxt in reached:
                        continue
                    reached.add(nxt)
                    todo.append(nxt)
            if start in reached:
                notes.append("%s reaches itself through %s, which the pattern declares "
                             "transitive - so the example entails that it stands in that "
                             "relation to itself" % (short(start), short(prop)))
                break

    # 5. connected
    individuals = set(types)
    joined = set()
    for subject, predicate, obj in example:
        if predicate == RDF.type or built_in(predicate):
            continue
        if isinstance(obj, rdflib.URIRef) and subject in individuals and obj in individuals:
            joined.add(subject)
            joined.add(obj)
    # IS THE PATTERN THIN? That is a question about the PATTERN, so it is asked of the pattern.
    #
    # It used to be asked only when the example had an isolated individual, which measured the
    # example instead: a curator who connects everything with properties that have no declared
    # domain or range makes a thin pattern report as fine, and the count silently fell from 31
    # to 23 for that reason alone. A pattern with no object property having both a domain and a
    # range cannot be instantiated as a shape by anything, however good the example.
    connectable = False
    for prop in pattern.subjects(RDF.type, OWL.ObjectProperty):
        has_domain = any(allowed(pattern, d, RDFS) for d in pattern.objects(prop, RDFS.domain))
        has_range = any(allowed(pattern, r, RDFS) for r in pattern.objects(prop, RDFS.range))
        if has_domain and has_range:
            connectable = True
            break
    if not connectable:
        notes.append("the pattern declares no object property with both a domain and a range, "
                     "so nothing derived from it can show an edge")

    alone = sorted(individuals - joined)
    if alone and len(individuals) > 1 and connectable:
        # Only the example's fault where the pattern gave it something to connect.
        problems.append("%d individual(s) with no edge: %s"
                        % (len(alone), ", ".join(short(a) for a in alone[:5])))

    return sorted(set(problems)), sorted(set(notes))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true", help="only the failures")
    parser.add_argument("--only", help="one pattern id")
    args = parser.parse_args()

    try:
        import rdflib  # noqa: F401
    except ImportError:
        print("this needs rdflib: python -m pip install rdflib", file=sys.stderr)
        return 2

    ids = sorted(d for d in os.listdir(PATTERNS) if os.path.isdir(os.path.join(PATTERNS, d)))
    if args.only:
        ids = [i for i in ids if i == args.only] or ids[:0]
        if not ids:
            print("no pattern called " + args.only, file=sys.stderr)
            return 2

    library = generator().local_library()
    sound, failed, noted, missing = 0, {}, {}, []
    for pattern_id in ids:
        problems, notes = check(pattern_id, library)
        if problems == ["no example.ttl"]:
            missing.append(pattern_id)
            continue
        if notes:
            noted[pattern_id] = notes
        if problems:
            failed[pattern_id] = problems
        else:
            sound += 1

    print("examples checked: %d" % (len(ids) - len(missing)))
    print("sound:            %d" % sound)
    print("with problems:    %d" % len(failed))
    print("no example:       %d%s" % (len(missing),
                                      "  (" + ", ".join(missing) + ")" if missing else ""))
    print("patterns too thin for an example: %d" % len(noted))
    if failed:
        print()
        print("THE EXAMPLE IS WRONG")
        for pattern_id in sorted(failed):
            print(pattern_id)
            for problem in failed[pattern_id]:
                print("    " + problem)
    if noted and not args.quiet:
        print()
        print("THE PATTERN IS TOO THIN - these need a person, not a generator")
        for pattern_id in sorted(noted):
            print("    %-36s %s" % (pattern_id, noted[pattern_id][0]))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
