<!--
  Written for: somebody who has a pattern of their own and wants to use it in OntoBoard, or wants
  it in the shipped library, or has found something wrong with one already in it. Two routes for
  a pattern of your own, the second with a review they can read in advance - and a separate, short
  form for a problem, because a problem report has none of what a proposal needs.
-->

# Contributing a pattern

There are two routes, and they answer different needs. Use the first today; use the second when
the pattern should be in the library everyone gets.

!!! tip "Found a problem with a pattern that is already in the library?"
    That is neither route: **[report it with the short form][problem]**. It needs a title and a
    sentence on what is wrong, nothing else. See [the last section](#reporting-a-problem-with-a-pattern-already-in-the-library).

## Route 1 — use it now, in your own copy

**Drop the file in a folder.** No index to regenerate, no rebuild, no pull request.

1. Make a folder anywhere — say `~/my-patterns/`. A subfolder inside it becomes a *collection*
   name, so `~/my-patterns/our-group/thing.ttl` appears under the collection `our-group`.
2. Put your pattern in it as `.ttl`, `.owl`, `.rdf`, `.omn`, `.ofn` or `.owx`.
3. In Protégé: **OntoBoard → Pattern library…**, press **Your patterns…**, and set its *Folder*
   to it. Your patterns appear beside the bundled ones, searchable and importable the same way.

!!! tip "The pattern describes itself — no form to fill in"
    The name, the description and the competency questions are read out of the file. Measured
    across the bundled collection: 77 patterns carry `coversRequirements`, 73 `hasIntent`, 62
    `rdfs:comment` and 36 `rdfs:label` on the ontology itself. So annotate the ontology and the
    library picks it up:

    ```turtle
    <https://example.org/patterns/sample-preparation> a owl:Ontology ;
        rdfs:label "Sample Preparation" ;
        rdfs:comment "How a specimen is prepared before a measurement, and by whom." ;
        <http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#coversRequirements>
            "Which preparation steps produced this specimen? Who performed them?" .
    ```

    The competency questions are worth writing even if nothing else is: the library's search
    looks at them, and they are the field phrased in the words somebody will actually search for.

A folder on disk can be listed, which is why this needs no registration. (The shipped library
needs its `index.tsv` only because a jar cannot be enumerated — under Felix a classpath directory
resolves to a `bundle:` URL that answers nothing.)

## Route 2 — propose it for the shipped library

Open an issue: **[Propose a new pattern for the library][propose]**. The form asks for the
ontology file or a URL, what the pattern is for, its competency questions, the source to cite,
and the licence - all of them required, because the review below cannot run without them.

[propose]: https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-submission.yml
[problem]: https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-problem.yml

### What the review checks

These are not opinions about modelling style — they are the gates the library already runs
against all 162 patterns on every build, and a new pattern has to pass the same ones. You can run
most of them yourself before submitting.

| check | why it is there |
|---|---|
| **It parses with the OWL API** | Two patterns shipped for months that no OWL API parser could read — a single `owl:versionInfo 2.0.0`, which is not a valid Turtle literal. Protégé could not load them at all. rdflib accepted the file, which is why nobody noticed; `robot convert` is the honest test. |
| **It is OWL 2 DL** | Two patterns in the library are not, and no DL reasoner can answer anything about them. A cyclic property hierarchy or a non-simple property in an asymmetry axiom is enough to put a pattern outside the profile. |
| **HermiT finds it consistent, with no unsatisfiable class** | A class that cannot have an instance is inherited by every ontology that imports the pattern. |
| **It has a worked example, and the example is consistent WITH the pattern** | Merged, not loaded separately — an example that only reuses the pattern's IRIs by prefix cannot contradict anything on its own, which is how one contradictory example survived unnoticed. |
| **It has at least one competency question** | Otherwise nobody can find it and nobody can tell what it is for. 40 of the bundled patterns still lack them, which is a debt, not a precedent. |
| **The source is cited and the citation resolves** | Including who published it. If the pattern is yours, say so and give a stable URL — a w3id or a tagged GitHub release, not a branch. |
| **The licence allows redistribution** | The library ships inside a 66 MB jar; we cannot include something we may not distribute. |
| **It is not already there under another name** | Five pairs in the library are the same pattern under two names, which is why the index carries a `sameAs` column. |

### Running the checks yourself

```bash
# parses, and is OWL 2 DL
robot convert --input my-pattern.ttl --output /tmp/check.owl
robot validate-profile --input my-pattern.ttl --profile DL

# consistent, and the example agrees with it
robot merge --input my-pattern.ttl --input my-example.ttl \
      reason --reasoner hermit --output /tmp/reasoned.owl
```

The same reasoning the library runs is in `PatternSoundnessTest`; it reads every pattern
directory beside the module, so a pattern dropped into `patterns/<your-id>/` is picked up by it:

```bash
cd protege-plugin
mvn -o test -Dtest=PatternSoundnessTest
```

### What happens after you open the issue

1. We run the checks above and reply on the issue with the results, including anything that
   failed and why.
2. If it passes, the pattern is added to `patterns/<id>/` with a `metadata.json` recording the
   source, the publisher and the competency questions, `patterns/index.tsv` is regenerated, and
   `patterns/soundness.tsv` records what a reasoner says about it.
3. It ships in the next release, and gets its own page with the same **Should you use this one?**
   table every other pattern has.
4. If it does not pass, the issue stays open with what would need to change. A pattern that
   cannot be fixed upstream can still ship with the defect **named on its page** — that is what
   happens with the four flagged ones — but that is a decision taken deliberately, not a way
   around the checks.

Nothing is merged silently: the reasoner's verdict for a new pattern lands in a committed file,
so adding one is visible as a diff.

## Reporting a problem with a pattern already in the library

A different, shorter form: **[Report a problem with a pattern in the library][problem]**. It
requires a title and a description of what is wrong, and nothing else - which pattern, where the
problem is and how to see it are all optional. The proposal form's competency questions,
ontology file, source and licence are what somebody offering a pattern has, not somebody who
found a wrong axiom in one we ship.

Two ways in that fill the form for you:

- **Every pattern's page** on this site ends with a *Report a problem with this pattern* link.
- **In Protégé**, **OntoBoard → Pattern library…**, select the pattern, and press **Report a
  problem with this pattern…** under its description. It opens the form in your browser. It is
  greyed out for your own patterns: they are not in the library, so there is nobody else to tell.

Either way the title and the pattern arrive already filled in; add a sentence and submit.

Worth doing even when the defect is upstream: the page can carry a note about it while the
authors decide, which is better for the next reader than silence.

Both forms, and a blank issue for anything that is not about a pattern, are listed on
[the repository's new-issue page](https://github.com/ebrahimnorouzi/ontoboard/issues/new/choose).
