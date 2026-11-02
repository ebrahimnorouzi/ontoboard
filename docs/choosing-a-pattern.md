<!--
  Written for: an ontology engineer deciding whether to adopt one of the 162 bundled patterns,
  and a reviewer asking what "checked" means here. Says what is measured and what is not.
-->

# Choosing a pattern

162 patterns is too many to read. This page is about deciding which one — or whether to use one
at all — and about what OntoBoard has actually checked on your behalf.

## Start from the question, not the catalogue

A pattern exists to answer a question about your domain. So search for the question:

!!! tip "Search the library by what you want to ask"
    **OntoBoard → Reuse → Pattern library…** opens the library with a search box. Type
    `working for which organization` and you get the pattern that answers it. The search looks
    at each pattern's id, name, description, category, domain **and its competency questions** —
    that last field is the one written in the words you would use, so it is usually the one that
    finds things.

    Every word has to appear somewhere in those fields. `time part` finds the patterns that
    mention both, which is narrower than `time` — a query is a filter, not a suggestion. Case
    does not matter.

If you do not know what to ask yet, use the grouping dropdown beside the search box. It offers:

| grouping | when it helps |
|---|---|
| **Suggested** | ranked against the ontology you have open — the default whenever that is possible |
| **By publisher** | who actually published the pattern; the ODP portal is a catalogue of submissions from thirteen different places, so this is the division that carries information |
| **By collection** | which family it came from: the ODP portal, BFO, MWO, NFDIcore or PMDco |
| **By category** | structural, behavioral, information, conceptual, temporal, spatial, observation |
| **By name** | when you know roughly what it is called |

Search and grouping work together: filter first, then group what is left.

## Let the ontology you have suggest patterns

If you already have an ontology open, OntoBoard can rank the library against it instead of
making you browse. It uses two signals, and the second exists because the first is not enough:

| signal | what it means |
|---|---|
| **Shared IRIs** | your ontology already uses terms the pattern declares |
| **Shared names** | your ontology has a term with the same local name, in a different namespace |

A word match counts for half of an IRI match, and a pattern needs at least **two** shared terms
before it is offered at all. Five are shown.

!!! note "Why both signals, measured"
    Against a real BFO-based project, **zero** patterns shared an IRI with it — the ODP
    collection and the OBO world have no vocabulary in common — while **48** shared two or more
    names. On a project that imports a harvested ontology directly, the IRI signal is the precise
    one. Neither alone would work.

    The five namespaces every ontology contains (`owl:`, `rdf:`, `rdfs:`, `xsd:`, `skos:`) are
    excluded. Without that the recommender looked like it worked: 43 patterns "shared an IRI"
    with a real project and every single match was `owl:Thing`, which 42 patterns declare. A
    signal that fires on everything ranks nothing.

## Read the pattern's page before adopting it

Every pattern has a page with a **Should you use this one?** table. It is built from measurements,
not opinions:

- **Size** — how many classes, properties and axioms. A four-term pattern is a shape to copy; a
  forty-term one is a commitment.
- **Brings in** — how many ontologies it imports. A pattern that imports DUL brings DUL, and
  everything DUL imports, into your project. A pattern that stands on its own costs you nothing
  but its own terms.
- **Reasoning** — what HermiT says about it. See below.
- **Duplicate of** — when the library lists the same pattern under two names, which it does for
  five pairs, because the portal does.
- **Check it yourself** — the published source, an archived copy, and the ontology file.

## What "a reasoner has checked this" means here

Every pattern and every worked example is reasoned over by HermiT on each build. The result is
recorded in `patterns/soundness.tsv` and quoted on each page, and a test fails if the two ever
disagree — so a page cannot claim something a reasoner would not say today.

!!! warning "You do not have to read this page to be warned"
    The same verdicts ship inside the plugin. Select one of the flagged patterns in
    **OntoBoard → Reuse → Pattern library…** and the chooser says so in red before you import it,
    with the reason. A pattern a reasoner is happy with says nothing — a box on all 162 would be
    a box nobody reads, and it would make the four that matter harder to notice.

**159 of the 162 patterns are consistent with no unsatisfiable class, and their examples are
consistent with them.** Four are not, and each page says so:

| pattern | what a reasoner says |
|---|---|
| `roledependentnames` | outside OWL 2 DL: a cyclic property hierarchy breaks the regularity restriction, so no DL reasoner can answer about it |
| `winstonpartwhole` | outside OWL 2 DL: a non-simple property appears in an asymmetry axiom |
| `spatial-graph-adapter-sga` | `Attribute` and `ContextualizedRelation` are unsatisfiable, because two properties declare both as `rdfs:domain` — which OWL reads as their intersection — while the pattern declares them disjoint |
| `template-instance` | consistent, but declares no classes or properties, so no example can be built for it |

### Two things that are checked, and are different questions

1. **Is the pattern consistent on its own?** A class that cannot have an instance is one nobody
   can use, and every ontology importing the pattern inherits it.
2. **Is the example consistent with the pattern?** Nothing checked this until recently, and the
   reason is worth knowing: **not one of the examples imports its pattern.** They reuse the
   pattern's IRIs by prefix, so an example loaded on its own is a document of undeclared names
   that cannot contradict anything. Merging the two found a real defect — the generated example
   for `spatial-graph-adapter-sga` put an individual into two classes its pattern declares
   disjoint — and that example has been withdrawn.

### What is NOT checked, and why

**Imports are not resolved.** 101 of the 162 patterns declare `owl:imports`. Among those targets
are five on a server that stopped answering, two unresolvable `file:/schemas/` URIs, and a portal
whose HTTPS endpoint does not respond. Resolving them would make the check depend on the weather:
the same pattern would be verified today and unverified tomorrow, and a pattern that passed only
because its import failed to download would be indistinguishable from one that passed on its own
merits.

So a verdict is about **what the pattern file itself asserts**. A contradiction that arises only
against DUL or BFO is not caught. The **Brings in** row on each page tells you how much was left
out of that pattern's own check — if it imports four ontologies, the verdict covers less of what
you are actually adopting.

**Nothing here judges whether a pattern is a good model of your domain.** That is the one
question a tool cannot answer for you, and it is the reason the competency questions and the
worked example are on the page.

## What to do when a reasoner objects

The four flagged patterns are flagged as their authors published them. OntoBoard reports that
rather than quietly repairing somebody else's pattern — a repaired copy under the same name would
be a different pattern wearing its citation.

- **Outside OWL 2 DL** — you can still borrow the pattern's terms and its shape; just do not
  expect a reasoner to work with it. If you need reasoning, drop the axiom that breaks the
  profile (the page names it) in your own copy, and say so in your ontology's documentation.
- **Unsatisfiable classes** — do not assert anything into those classes. In
  `spatial-graph-adapter-sga` the cause is a pair of `rdfs:domain` axioms read as an
  intersection; keeping one of the two domains makes the pattern usable again.
- **Either way**, raise it upstream with the pattern's authors, and
  [tell us](https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-problem.yml)
  so the note on the page can be corrected when they fix it. That form needs a title and a
  sentence, nothing more; the *Report a problem with this pattern* link at the foot of every
  pattern's page, and the button of that name in the plugin's pattern library, open it with the
  pattern already filled in.

## Honest caveats about the sources

Most of this library comes from `ontologydesignpatterns.org`, and as of the last check that site
**does not answer over HTTPS** and its wiki redirects submission pages to its front page. The
ontology files themselves are still served over plain HTTP. Every pattern page therefore offers
an archived copy beside the citation, because a reference nobody can follow is not a reference.

Five of the pattern IRIs point at a server that no longer answers at all. That is recorded rather
than hidden: it is why the pattern index can only compare the rows it could fetch, and why
reasoning here deliberately does not depend on any of them.

## Using one, once you have chosen

**OntoBoard → Reuse → Pattern library…** hands the pattern to *Import terms…*, which writes the
term list, the module, the `owl:imports` and the catalog entry the way ODK expects. See
[Working with ODK](odk-workflow.md).

Your own patterns can sit beside the bundled ones — see
[Contributing a pattern](contributing-a-pattern.md). To offer one for the library everyone gets,
use [Propose a new pattern for the library](https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-submission.yml);
for something wrong with one already in it,
[Report a problem with a pattern in the library](https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-problem.yml).
