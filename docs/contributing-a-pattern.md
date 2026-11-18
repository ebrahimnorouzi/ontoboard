<!--
  Written for: somebody who has a pattern of their own and wants to use it in OntoBoard, or wants
  it in the shipped library, or has found something wrong with one already in it.
-->

# Contributing a pattern

Two routes: use your pattern now, in your own copy, or propose it for the library everyone gets.

!!! tip "Found a problem with a pattern that is already in the library?"
    **[Report it with the short form][problem]** - see
    [the last section](#reporting-a-problem-with-a-pattern-already-in-the-library).

## Route 1 - use it now, in your own copy

1. Make a folder anywhere, such as `~/my-patterns/`; a subfolder becomes a collection name.
2. Put your pattern in it as `.ttl`, `.owl`, `.rdf`, `.omn`, `.ofn` or `.owx`.
3. **OntoBoard → Reuse → Pattern library…**, **Your patterns…**, and set its *Folder*. Your
   patterns appear beside the bundled ones, searchable and importable.

The name, description and competency questions are read from the ontology's own annotations:

```turtle
<https://example.org/patterns/sample-preparation> a owl:Ontology ;
    rdfs:label "Sample Preparation" ;
    rdfs:comment "How a specimen is prepared before a measurement, and by whom." ;
    <http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#coversRequirements>
        "Which preparation steps produced this specimen? Who performed them?" .
```

Write the competency questions at least: the library's search reads them.

## Route 2 - propose it for the shipped library

Open an issue: **[Propose a new pattern for the library][propose]**, with the ontology file or a
URL, what the pattern is for, its competency questions, the source to cite and the licence.

[propose]: https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-submission.yml
[problem]: https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-problem.yml

### What the review checks

The same gates every bundled pattern passes on each build:

| check | why |
|---|---|
| **It parses with the OWL API** | `robot convert` is the honest test; rdflib accepts files Protégé cannot load |
| **It is OWL 2 DL** | Otherwise no DL reasoner can answer anything about it |
| **HermiT finds it consistent, with no unsatisfiable class** | Every importing ontology inherits such a class |
| **A worked example, consistent with the pattern when merged** | An example alone cannot contradict anything |
| **At least one competency question** | Otherwise nobody can find it |
| **A cited, resolving source** | A stable URL - a w3id or a tagged release, not a branch |
| **A licence that allows redistribution** | The library ships inside the jar |
| **Not already there under another name** | The index records same-as pairs |

Run them yourself:

```bash
robot convert --input my-pattern.ttl --output /tmp/check.owl
robot validate-profile --input my-pattern.ttl --profile DL
robot merge --input my-pattern.ttl --input my-example.ttl \
      reason --reasoner hermit --output /tmp/reasoned.owl
```

`PatternSoundnessTest` runs the library's own reasoning on every pattern directory, a new
`patterns/<your-id>/` included: `mvn -o test -Dtest=PatternSoundnessTest` in `protege-plugin`.

A pattern that passes is added under `patterns/<id>/`, the index and `patterns/soundness.tsv` are
regenerated, and it ships in the next release with its own page. One that fails stays an open
issue with what would need to change.

## Reporting a problem with a pattern already in the library

**[Report a problem with a pattern in the library][problem]** needs a title and a sentence,
nothing else. Every pattern's page ends with a *Report a problem with this pattern* link, and in
**OntoBoard → Reuse → Pattern library…** the button of that name under a pattern's description
opens the form with the pattern filled in. All forms are on
[the repository's new-issue page](https://github.com/ebrahimnorouzi/ontoboard/issues/new/choose).
