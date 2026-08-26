# Provenance, editorial notes, comments and the published version

Requirements analysis for the request: *extend the ontology, collaborate with multiple users, write
comments and sticky notes, keep track of who added what and when, add editorial notes, everything
annotated in the ontology, and two versions — an edit version and a published one carrying the
most recent release.*

Grounded in what OBO ontologies actually do (checked against released `omo.owl`, `ro.owl`,
`obi.owl`, `pato.owl` and the OBO Academy's metadata guidance), what the web application already
does, and what the `mmo` test project revealed.

---

## 1. The rule that decides what goes in the ontology

The request says "everything annotated with ontology and saved in the ontology". That is in direct
tension with the design decision that keeps canvas layout in a sidecar. The tension is real and
worth resolving with a rule rather than case by case, because new cases will keep arriving.

> **A thing belongs in the ontology when a consumer who imports it — with no access to this
> repository, this board, or this tool — would be worse off without it. Everything else belongs
> beside the ontology.**

Applying it:

| Thing | In the ontology? | Because |
|---|---|---|
| Who created a term, and when | **Yes** | An importer attributing or auditing a term needs it, and OBO ontologies carry it today |
| An editorial note on a term (*"definition needs work"*) | **Yes** | `IAO:0000116` exists for exactly this, and importers read it |
| A threaded discussion with replies and @mentions | **No** | An importer gains nothing, and it grows without bound in every diff |
| A sticky note at x=340, y=90 saying *"check with Bob"* | **No** | Meaningless without the diagram it is stuck to |
| A frame labelled *"Core module"* | **No**, unless it is a real module | If it is genuinely a module, make it one (an import, or `IAO:0000113` *in branch*) rather than a rectangle |
| Node positions and colours | **No** | Already settled; the sidecar exists for this |

So the answer to "everything in the ontology" is **most of what matters, yes — and the two things
that would damage the ontology, no, with a better home named for each.** The reasoning is below.

---

## 2. What the research changed

Three assumptions I would have shipped on, and the evidence against them.

**`dcterms:creator` is the wrong property.** It is effectively dead in OBO term-level practice:
zero uses in released `obi.owl`, `pato.owl` and `omo.owl`, and in `ro.owl` it appears only as a bare
declaration. It is not even declared in OMO. The properties actually used, and recommended by the
OBO Academy, are **`dcterms:contributor`** for the person and **`dcterms:created`** for the date,
with `dcterms:date` for last-modified. Real values from `ro.owl`:

```xml
<terms:contributor rdf:resource="https://orcid.org/0000-0001-9625-1899"/>
<terms:created rdf:datatype="http://www.w3.org/2001/XMLSchema#dateTime">2025-06-16T17:08:04Z</terms:created>
```

**`oboInOwl:created_by` is legacy.** It is what the OBO flat-file `created_by` tag becomes. It
exists in OMO for round-tripping, and it is not what a new ontology should be writing.

**Values are ORCIDs, not usernames.** `https://orcid.org/0000-…` as an IRI, not `"alice"`. This
matters for the plugin: a collaboration display name is not a usable provenance value, so the
identity used for provenance has to be configured separately from the one used for cursors.

One inconsistency worth recording rather than resolving: the OBO Academy recommends
`dcterms:created` and `dcterms:date`, but OMO declares neither `dcterms:creator` nor `dcterms:date`
— it carries the `/dc/elements/1.1/` variants instead. We follow the guidance and the released
files (`dcterms:` for contributor and created), and declare the properties ourselves rather than
relying on an import to have done it.

---

## 3. Requirements

Storage is stated exactly. Effort is honest.

### Provenance

| Id | Requirement | Storage | Effort |
|---|---|---|---|
| PROV-1 | Configure an identity for provenance — an ORCID, or a name if the user has none — separately from the collaboration display name | `de.fizkarlsruhe.ise.ontoboard` preferences, key `provenance.agent` | small |
| PROV-2 | A term created through the plugin carries who and when | Axiom annotations on the declaration: `dcterms:contributor` (ORCID IRI or literal) and `dcterms:created` (`xsd:dateTime`) | medium |
| PROV-3 | A term edited through the plugin records the last change | `dcterms:date` (`xsd:dateTime`), replacing any previous value | small, after PROV-2 |
| PROV-4 | Provenance can be turned off per project | Sidecar field `provenance.enabled`; default **on** for a new project, **off** for one whose ontology has no existing provenance, so it is never silently introduced into somebody else's ontology | small |
| PROV-5 | The declaring axioms are annotated, not just the entity, so *who added this subclass axiom* is answerable | `OWLAxiom.getAnnotations()` via `OWLDataFactory.getOWLSubClassOfAxiom(sub, sup, annotations)` | medium |
| PROV-6 | The scaffold declares the annotation properties it will use | `mmo-edit.owl`: `owl:AnnotationProperty` declarations for `dcterms:contributor`, `dcterms:created`, `dcterms:date`, `IAO:0000116`, `IAO:0000117`, `IAO:0000232` | small |

PROV-5 is the one that answers the request most directly, and the one most likely to be skipped as
"nice to have". *Who added what* for a class is PROV-2; for an axiom — which is where the ontology's
actual content lives — it needs axiom annotations. Note the cost: an annotated axiom is not equal
to its unannotated form, so `ROBOT diff` shows churn and `containsAxiom` checks must ignore
annotations. That has to be designed for, not discovered.

### Editorial notes

| Id | Requirement | Storage | Effort |
|---|---|---|---|
| NOTE-1 | Add and edit an editorial note on the selected entity, from the canvas | `IAO:0000116` (`http://purl.obolibrary.org/obo/IAO_0000116`) annotation assertion | small |
| NOTE-2 | A node with a note is marked on the canvas | Rendered from the annotation; nothing stored | small |
| NOTE-3 | Curator notes distinguished from editor notes where a project wants both | `IAO:0000232` | small |
| NOTE-4 | Notes travel between collaborators | A new `updateAnnotation` operation type — see COMMS-1 | medium |

`IAO:0000116`'s own definition licenses dropping it at release: *"It may not be included in the
publication version of the ontology, so it should contain nothing necessary for end users."* That is
permission, not instruction — see REL-4.

### Comments and sticky notes

| Id | Requirement | Storage | Effort |
|---|---|---|---|
| DISC-1 | A durable, per-entity note that outlives the session and reaches collaborators | `IAO:0000116` — i.e. NOTE-1. **This is what "comments in the ontology" should be.** | — |
| DISC-2 | Threaded discussion with replies and mentions | **Not in the ontology.** GitHub issues on the ODK repository, linked from the term by `IAO:0000233` *term tracker item* | medium |
| DISC-3 | Sticky notes and frames on the canvas | Sidecar `notes[]` and `frames[]`, already reserved with fields | medium |

The web application's comment model is a database table with `board_id`, `entity_iri`, `user_id`,
`text`, `parent_id`, `created_at` — unbounded nesting, `@name` scraped by regex at post time with no
mention record kept, no resolution state, and polled on a 15-second timer rather than synced. It is
not in the shared operation vocabulary at all. Reproducing that shape inside an ontology file would
put an unbounded, mutable, low-value blob into every release and every diff.

`IAO:0000233` is the OBO answer to the same need and is already an established property: the note
lives in the ontology, the conversation lives in the issue tracker, and the term points at it. The
ODK scaffold already generates a GitHub Actions workflow, so the repository is there.

### Edit and published versions

The ODK layout already provides both files. `mmo-edit.owl` is the source of truth; `mmo.owl` is the
product. What is missing is everything that makes the second one a *release*.

| Id | Requirement | Storage | Effort |
|---|---|---|---|
| REL-1 | A **Release** action in the plugin that produces the published file from the edit file | Runs `ROBOT reason` in-process, writes `<id>.owl` | medium |
| REL-2 | The release carries a version IRI | `owl:versionIRI` = `<base>/releases/YYYY-MM-DD/<id>.owl` — the OBO pattern; currently **absent from both files** | small |
| REL-3 | The release carries a version | `owl:versionInfo` = the release date; today a hand-written `0.1.0` that no target ever changes | small |
| REL-4 | Editorial notes optionally stripped at release | `ROBOT remove --term IAO:0000116`, off by default | small |
| REL-5 | Released copies are kept, not overwritten | `../../releases/YYYY-MM-DD/<id>.owl`; `prepare_release` currently copies unversioned, so each release destroys the last | small |
| REL-6 | The release refuses to run on a failing report | `ROBOT report --fail-on ERROR` first | small |

REL-6 matters because the scaffold's YAML *declares* `fail_on: ERROR` and `use_labels: TRUE` and the
generated Makefile passes neither, so CI's behaviour is whatever ROBOT defaults to rather than what
the project says. Requires Protégé 5.6.x for the ROBOT surface.

---

## 4. What should not be built as asked

Not padding — each of these was requested, and each has a better answer.

| Asked for | Why not | Instead |
|---|---|---|
| Comments stored in the ontology | A thread is unbounded and mutable; it would appear in every release, every `ROBOT report`, and every diff, and an importer gains nothing from it | `IAO:0000116` for the durable note (DISC-1) plus `IAO:0000233` pointing at a GitHub issue for the conversation (DISC-2) |
| Sticky notes stored in the ontology | A note at x=340,y=90 is meaningless without the diagram; putting it in the ontology means release artifacts carry someone's *"TODO: check this"* | Sidecar `notes[]`, which is now committed rather than gitignored, so they are shared (DISC-3) |
| `dcterms:creator` for authorship | Effectively unused in OBO term-level practice and not declared in OMO | `dcterms:contributor` + `dcterms:created` (PROV-2) |
| Provenance on everything, always | Stamping an ontology that has no provenance introduces a convention its maintainers did not choose, on every axiom, invisibly | Default on for projects the plugin scaffolds, off for ontologies opened without it (PROV-4) |

---

## 5. What is missing that was not asked for

The `mmo` project made these unavoidable. Each one breaks the requested scenario regardless of
anything above.

| Id | Gap | Why it breaks the scenario |
|---|---|---|
| GAP-1 | **A relative base IRI was accepted.** `uribase: o` became `file:/C:/Users/eno/Desktop/…/o`, so every term would be named after one machine's folder | Two people mint different IRIs for one concept and never converge. **Fixed** — the wizard now requires an absolute http/https IRI |
| GAP-2 | **ID ranges are a label and nothing else.** `mmo-idranges.owl` declares `has_id_policy "MMO"` with no ranges, no minimum, no maximum, no per-editor allocation | Two collaborators minting terms collide by construction. This is the mechanism ODK has for exactly this problem, and the scaffold generates an inert version of it |
| GAP-3 | **A board has no ontology identity.** The board id is free text in a dialog; nothing derives or checks it against the ontology IRI | Two people can share a board while editing unrelated ontologies, and see each other's axioms land in the wrong file, with no error |
| GAP-4 | **The release file is tracked, generated, and a `make clean` target.** `mmo.owl` is written by `make reason`, deleted by `make clean`, and committed | It conflicts on essentially every merge, and with a relative IRI each regenerated copy is a different ontology by identity while looking almost identical in the diff |
| GAP-5 | **The scaffold's YAML promises artefacts nothing builds** — `base`, `obo`, `json`, `primary_release: full` — and points at a `run.sh` that does not exist | A user following the generated instructions hits a dead end |
| GAP-6 | **The canvas sidecar was gitignored.** `*.ontoboard.json` was in the generated `.gitignore` | Two people cloning the repository each got an empty board and no way to share a diagram — defeating the file's entire purpose. **Fixed** |
| GAP-7 | **Stale sidecar entries persisted invisibly**, and a sidecar from another machine was adopted and then saved back over | `mmo`'s sidecar positions a class `o#apple` that exists nowhere in its ontology. **Fixed** — mismatched sidecars are refused, orphans pruned with a log line |

GAP-2 and GAP-3 are the two that still block the requested scenario. Everything in §3 can be built
and still leave two collaborators minting `MMO_0000001` twice.

---

## 6. Plan

Ordered by dependency. Every task names how it is verified; the ones needing a human say so.

### Phase A — make multi-user editing safe (blocking)

| # | Task | Delivers | Verified by |
|---|---|---|---|
| A1 | Per-editor ID ranges: generate real ranges in `<id>-idranges.owl`, and mint from the local range | Two people cannot mint the same identifier | Headless: allocation, exhaustion, and two allocators not overlapping |
| A2 | Derive the board id from the ontology IRI, with an override; warn loudly on a mismatch at `hello` | A board is tied to an ontology; a mismatch is reported instead of corrupting a file | Headless for the derivation and the comparison; **human** for the warning appearing in two running instances |
| A3 | Stop tracking the release file; add `<id>.owl` to the generated `.gitignore` and let the release action or CI produce it | Merges stop conflicting on a build product | Headless: the generated `.gitignore` contains it |

### Phase B — provenance (the direct request)

| # | Task | Delivers | Verified by |
|---|---|---|---|
| B1 | Provenance identity setting (PROV-1) and the annotation-property declarations in the scaffold (PROV-6) | Somewhere to put an ORCID; a project that declares what it uses | Headless |
| B2 | Entity provenance (PROV-2, PROV-3): `dcterms:contributor` and `dcterms:created` on creation, `dcterms:date` on edit | *Who added this term, and when* is answerable in the ontology | Headless: round-trip through `OperationMapper`, and that re-editing replaces `dcterms:date` rather than accumulating |
| B3 | Opt-out and detection (PROV-4) | An ontology without provenance does not silently acquire it | Headless: an ontology with no `dcterms:created` anywhere defaults to off |
| B4 | Axiom provenance (PROV-5) | *Who added this subclass axiom* is answerable | Headless — and must include the `ROBOT diff` churn and `containsAxiom` consequences, or it will break idempotency in `OperationMapper` |

### Phase C — editorial notes

| # | Task | Delivers | Verified by |
|---|---|---|---|
| C1 | Editor note editing on the selected entity (NOTE-1, NOTE-3) | Notes in the ontology, where an importer can read them | Headless for the axiom construction; **human** for the panel |
| C2 | A marker on annotated nodes (NOTE-2) | Which terms have notes, at a glance | **Human** — it is painting |
| C3 | An `updateAnnotation` operation type in all three places — `useOperationSync.ts`, `bridge.mjs`, `OntologyOperation.TYPES` (NOTE-4) | Notes travel between collaborators | Headless both sides, plus a `bridge-interop` round trip |

### Phase D — the published version

| # | Task | Delivers | Verified by |
|---|---|---|---|
| D1 | Fix the generated Makefile so the YAML's `fail_on` and `use_labels` are actually passed, and remove the promises nothing builds (GAP-5) | The generated project does what it says | Headless: assert the generated text |
| D2 | Release action: report-gate, reason, stamp `owl:versionIRI` and `owl:versionInfo`, write a dated copy (REL-1, 2, 3, 5, 6) | One button produces a real release; the published file carries the most recent version | Headless for the stamping and the file layout; **human** for the ROBOT run on 5.6.x |
| D3 | Optional editor-note stripping (REL-4) | A published file free of editorial chatter, when wanted | Headless |

### Phase E — discussion

| # | Task | Delivers | Verified by |
|---|---|---|---|
| E1 | `IAO:0000233` term tracker item editing, and opening the linked issue (DISC-2) | A term points at its discussion | Headless for the annotation; **human** for the browser |
| E2 | Sticky notes and frames on the canvas, in the sidecar (DISC-3) | Diagram annotation, shared through git | **Human** — drawing and dragging |

### Effort

Phase A is small but blocking. Phase B is the largest, and B4 is the hardest thing in this document
— axiom annotations change axiom equality, which touches `OperationMapper`'s idempotency, the
projection, and `ROBOT diff` output. Phases C and D are mostly small. Phase E is last because it is
the least load-bearing.

---

## 7. What no test can settle

- **Two people, two cursors, one board.** Needs a server, two Protégé instances and a person.
  [Local testing guide](../../testing-collaboration-locally.md).
- **The ROBOT release path.** Needs Protégé 5.6.x, since ROBOT's report and query operations fail on
  5.5.0's OWL API.
- **Whether the provenance convention suits your project.** `dcterms:contributor` with ORCIDs is
  what OBO does; a project with a different convention should be asked, not assumed.
