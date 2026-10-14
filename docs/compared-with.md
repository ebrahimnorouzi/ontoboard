<!--
  Written for: the OntoBoard maintainer, writing the related-work section, and for a reviewer
  checking whether its claims hold.

  Every row was checked against a primary source - the tool's own paper, its LICENSE via the
  GitHub API, or its official documentation - and not against memory. Where a cell disagrees
  with what this project previously claimed, the correction is called out rather than quietly
  applied, because the previous claim is in paper/sections/related.tex and has to be fixed there.
-->

# Compared with

## The claim that survives contact with the sources

Not "OntoBoard has features X, Y and Z". Almost every item in OntoBoard's feature list has at
least one prior claimant, and a table that implies otherwise is a table a reviewer dismantles.
Two narrow claims are genuinely uncontested:

1. **No ontology editor of any kind runs ROBOT operations or the ODK project lifecycle from
   inside itself.** An exhaustive sweep of the `protegeproject` organisation returns
   `cellfie-plugin`, `swrltab-plugin`, `sparql-query-plugin`, `owlviz`,
   `lucene-search-plugin`, `csv-export-plugin`, `dlquery`, `existentialquery`,
   `protege-server-history-plugin` and `protege-server-admin-tab`. None touches ROBOT or the
   ODK. A GitHub-wide search for a Protégé plugin wrapping either returns exactly one hit, and
   it is this repository.

2. **Nothing validates a spreadsheet's *data* before it becomes axioms.** `robot template`
   validates template-string syntax and the presence of an identifier, reads only CSV/TSV/TAB
   (never `.xlsx`), and stops at the first error. Cellfie and Mapping Master validate their
   mapping DSL, not the cells. TopBraid applies SHACL after import, to the graph. WebProtégé's
   rule-based auto-tagging runs on the ontology. No tool audits the sheet itself.

Everything else is a claim about the *combination*.

## The sentence to quote, from the ODK's own authors

> The ODK was developed not as a replacement for Protégé, but rather a complement to it. The ODK
> is not aimed at the actual process of ontology editing and therefore lacks any support for data
> entry (such as visual editors).

and

> The ODK does not currently integrate with WebProtégé editing workflows, but exploring this
> integration is on our list for the future.

— Matentzoglu, Goutte-Gattat, Tan, Balhoff, Carbon, Caron et al., *Ontology Development Kit: a
toolkit for building, maintaining and standardizing biomedical ontologies*, Database 2022:baac087,
[10.1093/database/baac087](https://doi.org/10.1093/database/baac087).

Note the title: `references.bib` currently cites this as "A Practical Guide to Implementing the
Ontology Development Kit (ODK)", which is not its name.

## What has to be cited before a reviewer finds it

Mark Musen and Matthew Horridge hold a **CZI Essential Open Source Software for Science (Cycle 5)**
grant whose summary says the project "will work to develop new mechanisms for WebProtégé to
integrate with the Ontology-Development Kit (ODK)". ROBOT is not mentioned.

So the gap is recognised by the field's two most authoritative teams and is being worked on — on
the web side. That strengthens the motivation, but only if the claim is worded as *no shipped tool
does this*, and *in the desktop editor where OBO curators actually work*. "Nobody has considered
this" is false and checkable.

## The table

Legend: ● yes · ◐ partial, with the limit named · ○ no.

| | OntoBoard | ROBOT | ODK | Protégé Desktop | WebProtégé | CoModIDE | Cellfie / Mapping Master | OntoEditor | VocBench 3 | OnToology | Chowlk |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Runs **inside an ontology editor** | ● Protégé 5.5–5.6 | ○ CLI/library | ○ CLI | — | — | ● Protégé | ● Protégé | ○ browser | ○ browser | ○ CI service | ○ web form |
| **ROBOT operations** in-process | ● robot-core 1.9.8, 11 operations | ● it *is* ROBOT | ◐ shells out to it | ○ | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| **ODK project lifecycle** | ● scaffold, build, imports, ID ranges, release | ○ | ● it *is* the ODK | ◐ reads `-idranges.owl` | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| **Spreadsheet editing** | ● grid, editable template row | ○ | ○ | ○ | ○ | ○ | ◐ mapping DSL over a sheet | ○ | ◐ import/export only | ○ | ○ |
| **Validates sheet data** | ● 10 kinds, bulk repair | ○ syntax only, stops at first error | ○ | ○ | ◐ rules, on the ontology | ○ | ○ DSL only | ○ | ○ | ○ | ○ |
| Reads **`.xlsx`** | ● | ○ CSV/TSV/TAB | ○ | ○ | ○ | ○ | ● | ○ | ◐ | ○ | ○ |
| **Editable visual canvas** | ● | ○ | ○ | ○ | ◐ **view only** (4.0 graph view) | ● | ○ | ○ | ○ | ○ | ◐ one-way, diagram → Turtle |
| **Pattern library** | ● 163 patterns | ○ | ○ | ○ | ○ | ● 139 ODPs | ○ | ○ | ○ | ○ | ○ |
| **Reasoning / explanations** | ● | ● | ● | ● | ● | ○ | ○ | ○ | ◐ | ○ | ○ |
| **Real-time co-editing** | ◐ canvas only | ○ | ○ | ○ *(client-server never finished, dormant since 2018)* | ● "a Google Docs for ontologies" | ○ | ○ | ● OT over ShareDB, text | ○ **role-based workflow, not co-editing** | ○ | ○ |
| **git integration** | ● clone, commit, push, PR | ○ | ● | ◐ repository-aware | ○ own server | ○ | ○ | ● GitHub/GitLab/Bitbucket, whole-file commits | ○ | ● **commits and pull requests** | ○ |
| **Documentation generation** | ● Widoco | ○ | ● | ○ | ○ | ○ | ○ | ○ | ○ | ● | ○ |
| **Published peer-reviewed paper** | ○ *not yet* | ● | ● | ● | ● | ● ISWC 2019 + n=21 study | ● | ● ESWC 2024 In-Use | ● SWJ | ● JWS | ● |
| Licence | Apache-2.0 | BSD-3 | BSD-3 | BSD-2 | BSD-2 | Apache-2.0 | LGPL | MIT | BSD-3 | Apache-2.0 | Apache-2.0 |

### Three cells that correct the current draft

`paper/sections/related.tex` is wrong in three places, each contradicted by the tool's own paper:

1. **WebProtégé does not use pessimistic locking.** The draft says it "employs pessimistic
   locking that serializes concurrent writes to the same entity". The WebProtégé paper says the
   opposite three times — "supports simultaneous collaborative editing of OWL ontologies for
   geographically distributed users", "essentially a 'Google Docs' for editing ontologies",
   "allows ontology edits to be seen by all users as they take place" — and the word *lock* does
   not appear in it. The ODK paper independently credits WebProtégé with "better support for
   simultaneous collaborative editing".
2. **VocBench does not use locking either.** The only locking passage in the VocBench 3 paper
   describes *iQvoc*, a related work. VocBench's model is role-based access control with a Prolog
   capability language, a triple-level change-tracking sail, and a publication workflow.
3. **WebProtégé does have a graph view.** Section 2.5 of its paper describes a "brand new graph
   visualization" in 4.0 with path isolation, node hiding and SVG download. It is read-only, so
   the honest cell is ◐ *view only* — not a dash.

### Two rows OntoBoard must not lead with

- **ID ranges are not new.** Protégé Desktop already parses ODK `-idranges.owl` files natively
  and resolves a range by the git user name.
- **git plus pull requests and documentation generation are not new.** OnToology has done both
  since its Journal of Web Semantics paper, and has a live service.

### The most dangerous comparator

**CoModIDE** is a Protégé plugin with an editable schema canvas, 139 bundled ontology design
pattern files, an ISWC 2019 Resource-track paper *and* a published 21-subject controlled study
against stock Protégé. Any claim of the form "the first Protégé plugin with an editable canvas and
a pattern library" is already taken. What CoModIDE does not have is ROBOT, the ODK, or anything to
do with spreadsheets.

## Also checked, and why each is not in the table

| Tool | Why |
|---|---|
| OWLAx | Protégé plugin with a drawing canvas, but strictly one-way diagram → axioms, no pattern library, no round trip. |
| VoCol | MIT, webhook-driven git with syntax/semantics validation and documentation generation — dormant since 2022. |
| SousLeSens | MIT, web, graph canvas and a reasoner, but no peer-reviewed paper. Cite as software. |
| TopBraid EDG / Composer | Proprietary. Web, SHACL-centric, git commit/push and working-copy workflows. |
| Fluent Editor | Proprietary, Windows desktop, controlled English; last confirmed release 2014. |
| TurtleEditor | Only reachable through OntoEditor's comparison; a Turtle text editor with GitLab integration. |

## Where the OntoEditor comparison is

[compared-with-ontoeditor.md](compared-with-ontoeditor.md) — the nearest published neighbour,
dissected feature by feature, with what to build and what deliberately not to.
