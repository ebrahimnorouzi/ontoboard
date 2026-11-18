<!--
  Written for: an ontology engineer deciding whether to adopt one of the 162 bundled patterns,
  and a reviewer asking what "checked" means here. Says what is measured and what is not.
-->

# Choosing a pattern

## Search for your question

**OntoBoard → Reuse → Pattern library…** has a search box: type the question you want to answer -
`working for which organization` - and it searches each pattern's name, description, category,
domain and competency questions. Every word must appear; case does not matter.

The grouping beside it:

| grouping | when it helps |
|---|---|
| **Suggested** | ranked against the ontology you have open - the default when one is |
| **By publisher** | who published the pattern |
| **By collection** | the ODP portal, BFO, MWO, NFDIcore or PMDco |
| **By category** | structural, behavioral, information, conceptual, temporal, spatial, observation |
| **By name** | when you know roughly what it is called |

**Suggested** ranks by terms your ontology shares with a pattern - the same IRIs, or the same
local names in another namespace (worth half) - and offers the five best with at least two shared
terms. OWL's, RDF's and SKOS's own terms do not count.

## Read the pattern's page

Each pattern's page has a **Should you use this one?** table: its size, the ontologies it imports
(**Brings in**), what a reasoner says about it, any duplicate in the library, and links to its
source and an archived copy.

## What the reasoner checked

HermiT reasons over every pattern and its worked example on each build; the result is in
`patterns/soundness.tsv`, quoted on each page and shown in red in the plugin's pattern library
before you import a flagged one. 159 of the 162 are consistent with no unsatisfiable class, and
consistent with their examples. The others:

| pattern | what a reasoner says |
|---|---|
| `roledependentnames` | outside OWL 2 DL: a cyclic property hierarchy |
| `winstonpartwhole` | outside OWL 2 DL: a non-simple property in an asymmetry axiom |
| `spatial-graph-adapter-sga` | `Attribute` and `ContextualizedRelation` are unsatisfiable: two `rdfs:domain` axioms read as an intersection of disjoint classes |
| `template-instance` | consistent, but declares no classes or properties, so it has no example |

**Not checked:** a pattern's imports are not resolved (101 of 162 declare some, and several
servers no longer answer), so a verdict covers what the pattern file itself says. Nothing judges
whether a pattern models your domain well - that is what its competency questions and example are
for.

## When a reasoner objects

The flagged patterns are reported as their authors published them, not repaired. Outside OWL 2 DL,
borrow the terms and shape, or drop the axiom the page names in your own copy. With unsatisfiable
classes, assert nothing into them - for `spatial-graph-adapter-sga`, keeping one of the two
domains makes it usable. Either way, tell the authors, and
[tell us](https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-problem.yml) -
every pattern's page and the plugin's library have a *Report a problem with this pattern* link.

Most patterns come from `ontologydesignpatterns.org`, which no longer answers over HTTPS; every
page links an archived copy beside the citation.

## Using one

**OntoBoard → Reuse → Pattern library…** hands the pattern to *Import terms…*, which writes the
term list, the module, the import and the catalog entry as ODK expects - see
[Working with ODK](odk-workflow.md). Your own patterns can sit beside the bundled ones
([Contributing a pattern](contributing-a-pattern.md)); to offer one for everybody,
[propose it](https://github.com/ebrahimnorouzi/ontoboard/issues/new?template=pattern-submission.yml).
