<!--
  Written for: somebody deciding whether to use OntoBoard, and for a reviewer asking what is
  already out there. One page, all the tools, points only. The long per-tool write-ups are
  compared-with-ontoeditor.md and compared-with-ontograf.md.
-->

# Compared with other tools

One table, then what each tool is actually for. Measured against the code in this repository, not
against the papers' claims.

## At a glance

| | OntoBoard | OntoEditor | OntoGraf | WebVOWL | WebProtégé | VSCode + ROBOT |
|---|---|---|---|---|---|---|
| Runs | Protégé plugin | Browser | Protégé plugin | Browser | Server | Editor + CLI |
| Draws a diagram | yes, editable | no | yes, read-only | yes, read-only | no | no |
| Edits the ontology | yes | yes, as text | no | no | yes | yes, as text |
| Unit of collaboration | axiom | character | — | — | revision | file |
| ODK project scaffolding | yes | no | no | no | no | by hand |
| ROBOT in-process | yes | no | no | no | no | as a CLI |
| Spreadsheet (ROBOT template) editing | yes | no | no | no | no | in a spreadsheet app |
| Pattern library | 163 patterns | no | no | no | no | no |
| Reasoner | Protégé's | none | Protégé's | none | yes | by CLI |
| Needs a server | no | yes | no | no | yes | no |

## What each is for

- **OntoEditor** — browser text editor with live co-editing. Strong at: no lost keystrokes, one
  shareable link, GitHub/GitLab/Bitbucket with no local git. Weak at: anything about meaning — it
  validates syntax only, and a converged document can be logically inconsistent.
- **OntoGraf** — the diagram Protégé already ships. Strong at: zero setup, familiar. Weak at:
  read-only, no layout persistence, no export beyond a screenshot.
- **WebVOWL** — a notation, not an editor. Strong at: one recognisable visual language. Weak at:
  no editing, no project.
- **WebProtégé** — server-hosted Protégé with revisions and per-entity history. Strong at: audit
  and review at scale. Weak at: needs hosting; no ODK, no ROBOT pipeline, no diagram.
- **VSCode + ROBOT** — what most ODK projects actually use. Strong at: the whole ROBOT pipeline,
  real git. Weak at: no diagram, no reasoner in the loop, every operation is a CLI round trip.

## Where OntoBoard is ahead

- The ODK/ROBOT pipeline runs **inside** Protégé: templates, quality report, explain, measure,
  SPARQL, module extraction, release.
- The canvas is **editable and persistent** — draw an arrow and it writes the axiom; the layout
  is saved beside the ontology.
- Collaboration is **axiom-level**, so two people editing different axioms never interleave into
  a third axiom neither wrote. See [Conflicts and concurrent editing](conflicts.md).
- A **pattern library** of 163 design patterns with a worked example each.

## Where it is behind

- No browser version, so no zero-install demo. ([why](feature-parity.md))
- No character-level co-editing; a plugin does not own the text buffer.
- No three-way merge or conflict resolution UI — and git conflicts are now refused rather than
  committed. See [Conflicts and concurrent editing](conflicts.md).
- `.xlsx` is read-only; `.tsv`/`.csv` round-trip.
- Not in Protégé's plugin registry yet.

Longer, per-tool: [OntoEditor](compared-with-ontoeditor.md) · [OntoGraf](compared-with-ontograf.md)
· [what is supported](requirements-status.md) · [limitations](limitations.md)
