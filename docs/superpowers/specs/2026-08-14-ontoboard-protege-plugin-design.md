# OntoBoard as a Protégé Desktop Plugin — Design

**Date:** 2026-08-14
**Status:** Approved design, pending implementation plan
**Supersedes:** the OntoBoard web application (`frontend/`, `backend/`, `worker/`, `collab/`)

## 1. Decision

OntoBoard is reimplemented as a single-user **Protégé Desktop 5.6 plugin**. The web
application is retired.

A Miro app was evaluated and rejected. Miro cannot execute Java, ROBOT, a reasoner, or
Widoco, so the entire Python/Java backend would have survived unchanged — a Miro port
replaces the frontend only and adds a SaaS dependency rather than removing
infrastructure. Three further findings were disqualifying:

- App data is capped at 30 KB per board and item metadata at 6 KB per item, so OWL state
  cannot live in Miro. The board becomes a lossy view over an external database, and
  native Miro tools mutate items without the app running, creating permanent drift.
- Custom actions (item right-click menus) are available only to non-public apps
  distributed by private link, which disqualifies Marketplace distribution, and they
  disappear above 50 selected items. OntoBoard's canvas UX is context-menu-first.
- Rate limits are 100,000 credits/minute per user-per-app with Level 1 calls at 50
  credits (~2,000 calls/minute). Loading a 500-class ontology is ~1,300 item creations
  and one re-layout is ~500 position updates.

Protégé hosts the ontology-engineering core natively and better. ROBOT ships as
`robot-core` on Maven Central and already depends on the OWL API and on Protégé, so all
24 ROBOT commands run in-process with no subprocess, no Docker, and no Java-in-Docker.

## 2. Locked decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Protégé Desktop 5.6 plugin, not a Miro app | §1 |
| D2 | Native Swing canvas on JGraphX | Cytoscape.js is browser-only; no embedded browser |
| D3 | Single-user. No server, no collaboration | User decision |
| D4 | Layout in a sidecar JSON file | Keeps the ontology byte-clean |
| D5 | New `protege-plugin/` module; delete the web stack at parity | User decision |
| D6 | Reuse Protégé's stock editors rather than reimplement them | Avoids duplicating a better implementation |
| D7 | Build beside CoModIDE, never on it | CoModIDE has no license (§11) |

**D3 removes contribution #1** (the ontology-aware CRDT) from `paper/sections/crdt.tex`.
Contribution #2 — pipeline tooling made accessible without the command line — becomes
the paper's primary claim. This was raised and accepted.

**D4 forgoes diagram interoperability with CoModIDE**, which stores positions as OPLa-SD
annotations inside the ontology. This was raised and accepted.

## 3. Non-goals

Explicitly dropped, and not to be reintroduced without revisiting this spec:

Real-time collaboration (operation CRDT, semantic merge engine, conflict resolution UI,
cursor sharing, entity locking) · boards and dashboard · user accounts, JWT auth, admin
page · sharing, invite links, access requests, starring · comments and @mentions ·
notifications · Kanban task board · activity log · per-user server-side pattern storage ·
feedback dialog · Docker and Redis.

## 4. Architecture

### 4.1 Core principle

**The canvas is a view over Protégé's model, never a parallel model.**

Canvas edits are converted to `List<OWLOntologyChange>` and submitted through
`OWLModelManager.applyChanges(...)`. Model changes flow back through an
`OWLOntologyChangeListener` that refreshes affected cells incrementally.

This is load-bearing. It means:

| OntoBoard machinery | Replacement |
|---|---|
| `ontologyStore.ts` (531 LOC Zustand store) | `OWLModelManager` |
| 800 ms debounced auto-save | Protégé dirty-tracking and save |
| 50-snapshot undo stack | Protégé `UndoManager` |
| `canvas_to_owl` / `owl_to_canvas` round-trip | Direct axiom changes; no serialize/reparse cycle |

It also gives behaviour OntoBoard cannot: editing on the canvas updates Protégé's class
hierarchy, entity editors, and any active reasoner live, because all of them listen to
the same model.

Canvas selection is bridged to `OWLSelectionModel` in both directions, so selecting a
node drives Protégé's entity editors and vice versa.

### 4.2 Views

`OntoBoardTab` is a `WorkspaceTab` composed of `ViewComponent`s:

| View | Position | Contents |
|---|---|---|
| Schema Canvas | centre | JGraphX editor, palette, minimap |
| Pattern Library | left | ODP list, search, drag-to-canvas |
| Pipeline Console | bottom | ROBOT/ODK execution with streaming log |
| Quality | right | ROBOT report violations, OOPS!, OQuaRE |

Because it is a normal Protégé workspace, users can dock Protégé's own class hierarchy,
entity editor, and reasoner views alongside these.

## 5. Canvas ↔ OWL contract

### 5.1 Node and edge vocabulary

Node kinds: `OWLClass` (rounded rectangle), `OWLNamedIndividual` (rhombus), datatype
(oval), literal (small rectangle). Cell id is the entity IRI; literals and datatypes get
synthetic ids.

Permitted node–edge–node configurations, following OWLAx (Sarker, Krisnadhi & Hitzler,
arXiv:1808.10105, §2), where `A`,`B` are classes, `M` a datatype, `c` an individual,
`ℓ` a literal, `R` an object property, `Q` a data property:

```
A --R--> B      A --R--> c      A --Q--> M      A --Q--> ℓ
c --rdf:type--> A               A --rdfs:subClassOf--> B
```

### 5.2 Edge to axiom mapping

OntoBoard currently writes `rdfs:domain` and `rdfs:range` for every property edge
(`backend/app/services/canvas.py:265-277`). This is a defect worth fixing rather than
reproducing: drawing `Person --worksFor--> Organization` and then
`Robot --worksFor--> Factory` yields a global domain of `Person ⊓ Robot`, which is
essentially never the intent.

Two complementary mechanisms replace it, both implemented independently from the OWLAx
paper's published definitions (§11):

**(a) Immediate default.** Drawing an edge writes one axiom at once, preserving
OntoBoard's live feel. Default for `A --R--> B` is the existential axiom.

**(b) "Generate Axioms…" candidate dialog.** Systematic enrichment over the diagram or
the current selection, presenting candidates as checkboxes rendered in Manchester syntax.
Candidates are not mutually exclusive. For each `A --R--> B`:

| Candidate | DL form | OWL form |
|---|---|---|
| Unscoped (global) domain | `∃R.⊤ ⊑ A` | `ObjectPropertyDomain(R A)` |
| Scoped domain | `∃R.B ⊑ A` | `SubClassOf(ObjectSomeValuesFrom(R B) A)` |
| Unscoped (global) range | `⊤ ⊑ ∀R.B` | `ObjectPropertyRange(R B)` |
| Scoped range | `A ⊑ ∀R.B` | `SubClassOf(A ObjectAllValuesFrom(R B))` |
| Existential *(default)* | `A ⊑ ∃R.B` | `SubClassOf(A ObjectSomeValuesFrom(R B))` |
| Functionality | `A ⊑ ≤1 R.B` | `SubClassOf(A ObjectMaxCardinality(1 R B))` |

Plus `c --rdf:type--> A` → `ClassAssertion(A c)`; `A --rdfs:subClassOf--> B` → `A ⊑ B`;
analogous candidates for `A --Q--> M`, `A --R--> c`, `A --Q--> ℓ`; and class disjointness
for each pair of classes with no `rdfs:subClassOf` path between them.

Axioms already present in the ontology appear pre-checked, so the dialog doubles as a
review of what the diagram already asserts.

### 5.3 Opt-in canvas membership

OntoBoard loads every class onto the canvas and degrades past ~500 nodes. Protégé
routinely opens ontologies with 100,000 classes, so load-everything would hang on the
first realistic ontology.

The canvas is therefore **opt-in**: it opens empty, or restores the entity set recorded
in the sidecar. Entities are added by dragging from Protégé's class hierarchy, by "Add to
canvas" on a selection, or by expanding a node's neighbours one hop at a time. Removing a
node from the canvas is a view operation and never deletes axioms; axiom deletion is a
separate, explicit action.

## 6. Layout persistence

A sidecar JSON file `<ontology-file-name>.ontoboard.json` sits next to the ontology file.
Written on ontology save and on explicit save; read on ontology load. Absent file means
an empty canvas, never an error.

```json
{
  "version": 1,
  "ontologyIri": "http://example.org/myont",
  "onCanvas": ["http://example.org/Person", "http://example.org/Organization"],
  "nodes": { "http://example.org/Person": { "x": 120.0, "y": 40.0, "w": 160.0, "h": 60.0 } },
  "frames": [ { "id": "f1", "label": "Core", "x": 0, "y": 0, "w": 600, "h": 400,
                "fill": "#EEF3FA", "stroke": "#4A90D9" } ],
  "notes":  [ { "id": "n1", "text": "align with BFO", "x": 700, "y": 60,
                "w": 180, "h": 120, "color": "#FFF3B0", "fontSize": 12 } ],
  "prefixColors": { "ex": "#4A90D9" }
}
```

The ontology file itself gains no annotations from the canvas. ROBOT `report`, `diff`,
and release artifacts are unaffected.

## 7. Component disposition

### 7.1 Deleted — Protégé or the OWL API already does it

| OntoBoard | Replacement |
|---|---|
| `manchester_parser.py` + 49 tests | OWL API `ManchesterOWLSyntaxParser` / renderer, plus Protégé's expression editor with autocomplete |
| `axiom.py`, `restrictions.py`, `characteristics.py` | Protégé entity editors: all 7 property characteristics, property chains, XSD ranges, annotation CRUD |
| `tree.py` | Protégé's class / object property / data property / annotation property / individual hierarchy views |
| `reasoning.py`, owlready2 | `inference.reasonerfactory` reasoners (ELK, HermiT, JFact, Pellet) with incremental and background reasoning |
| ROBOT `explain` wrapper | Protégé's built-in explanation / justification support |
| `dl_query.py` | Protégé DL Query tab |
| `swrl.py` + SWRL editor | Protégé's bundled SWRLTab / SQWRLTab |
| `export.py`, `conversion.py` | `OWLOntologyManager.saveOntology` + `robot-core` `IOHelper` |
| `auth`, `users`, `boards`, `invite`, `comments`, `notifications`, `task`, `publish` | Dropped (§3) |
| `frontend/src/collab/` | Dropped (§3) |
| `worker/`, Redis, all Docker | `robot-core` runs in-process |

**Gained for free**, all current OntoBoard gaps: `owl:hasKey`, negative property
assertions, custom datatypes, annotations on axioms, incremental reasoning, background
reasoning, and ROBOT 1.9.8 (up from 1.9.6).

### 7.2 Ported to Java — the actual work

| OntoBoard | Port |
|---|---|
| Cytoscape canvas | JGraphX `mxGraphComponent` |
| dagre / cose-bilkent / grid / circle layouts | `mxHierarchicalLayout` / `mxOrganicLayout` / custom grid / `mxCircleLayout` |
| Minimap | `mxGraphOutline` |
| Canvas frames | mxGraph compound cells (groups) |
| Sticky notes | Styled mxGraph vertices, no OWL semantics |
| PNG / SVG export | `mxCellRenderer.createBufferedImage` / `createSvgDocument` |
| Per-prefix auto-colouring | `mxStylesheet` driven by the sidecar's `prefixColors` |
| Pattern library (13 ODPA + `patterns-repository/`) | `JList` + `TransferHandler` drag-to-canvas |
| CSV import, 6-step wizard | `CardLayout` wizard + `robot-core` `TemplateOperation` |
| SPARQL panel | Jena ARQ (already a `robot-core` dependency) + `JTable` |
| 7 ROBOT commands | **All 24**, via `robot-core` Operation classes |
| ODK scaffold, config, Makefile targets | File generation + `ProcessBuilder` for `make` |
| Import resolution + `catalog-v001.xml` | `OWLOntologyIRIMapper` + catalog writer |
| ROBOT report UI | `ReportOperation` → violation table + TSV export |
| OOPS! integration | HTTP call to the OOPS! web service |
| OQuaRE metrics | Port `backend/app/services/quality.py` |
| Analysis (unused, deprecated, circular imports, batch annotate) | OWL API traversals |
| Provenance (PROV-O + Dublin Core) | `OWLOntologyChangeListener` stamping `dcterms:*` |
| GitHub import | JGit clone + `robot convert` |
| Widoco HTML documentation | Widoco fat JAR via `ProcessBuilder`, optional (§9) |
| ID ranges (OWL Functional Syntax) | OWL API functional-syntax parser; port `idranges.py` |
| Docs page | Bundled HTML via Protégé's help menu |

## 8. Module layout

```
protege-plugin/
  pom.xml
  src/main/resources/
    plugin.xml                    WorkspaceTab + ViewComponent extension declarations
    patterns/                     13 ODPA seed patterns from backend/seed/patterns/
    docs/                         bundled help HTML
  src/main/java/de/fizkarlsruhe/ise/ontoboard/
    OntoBoardTab.java             WorkspaceTab
    views/                        the four ViewComponents (§4.2)
    canvas/                       JGraphX editor, palette, edge tools, layouts, export
    model/                        OWLEntity ⇄ mxCell mapping, change listeners, selection bridge
    axiom/                        OWLAx candidate generation and the Generate Axioms dialog
    layout/                       sidecar JSON read/write
    robot/                        robot-core operations, log console plumbing
    odk/  patterns/  csv/  sparql/  quality/  analysis/  imports/  idranges/  provenance/
  src/test/java/...
```

## 9. Dependencies

Versions verified present on Maven Central on 2026-08-14.

| Artifact | Version | Note |
|---|---|---|
| `edu.stanford.protege:protege-editor-owl` | 5.6.6 | `provided`; OWL API arrives transitively |
| `org.obolibrary.robot:robot-core` | 1.9.8 | Embedded; brings Jena ARQ, OWL API, Jackson |
| `com.github.vlsi.mxgraph:jgraphx` | 4.2.2 | Embedded; published **with OSGi manifest entries** |
| `org.eclipse.jgit:org.eclipse.jgit` | 7.3.0.202506031305-r | Embedded; GitHub import |

**Widoco is not a Maven dependency.** It is not published to Maven Central; it is
distributed only as a 39 MB fat JAR per JDK line from GitHub releases (Apache-2.0,
v1.4.25, JDK-11 variant matches our target). Embedding it alongside `robot-core` would
bloat the bundle unacceptably.

Widoco is therefore an **optional external tool**, mirroring how the current backend
treats it — installed alongside, not inside. A preference holds the JAR path; when unset,
the plugin offers to download `widoco-1.4.25-jar-with-dependencies_JDK-11.jar` into the
plugin data directory on first use. It is invoked via `ProcessBuilder` as a local
subprocess. Documentation generation is unavailable until the JAR is present, and the UI
says so plainly rather than failing obscurely.

Build: Maven with the Apache Felix `maven-bundle-plugin`, `Embed-Dependency` for
everything except `protege-editor-owl`. Target Java 11 bytecode (Protégé 5.6 requires
Java 11+; runs on 17). Output is a single OSGi bundle JAR.

Distribution: copy the JAR into Protégé's `plugins/` directory, plus an
`update.properties` entry for Protégé's auto-update registry.

**Risk:** JGraphX upstream was archived in November 2020 and is end-of-life. It is BSD
licensed and stable, `4.2.2` is republished to Maven Central with OSGi metadata, and both
CoModIDE and OWLAx ship on it. Accepted: the alternatives (Prefuse, JUNG, GraphStream)
are visualization-first and lack edge drawing, grouping, and undo.

## 10. Testing

JUnit 5 against real OWL fixtures. `mwo301.ttl` and `test_upload.ttl` are already in the
repository and are reused.

The 500+ Python tests do not port, because most of their subjects are deleted (§7.1).
Tests are written fresh for behaviour that survives:

| Area | What is asserted |
|---|---|
| Canvas ⇄ axiom | Every §5.1 configuration round-trips; drawing an edge produces exactly the §5.2 default axiom |
| OWLAx candidates | Each candidate in §5.2 generates its stated OWL form; existing axioms are detected and pre-checked |
| Sidecar | Write/read round-trip; missing file yields an empty canvas; unknown `version` fails loudly |
| Canvas membership | Removing a node from the canvas leaves the ontology unchanged |
| ROBOT | Template building, report parsing, format conversion against fixtures |
| ID ranges | OWL Functional Syntax parse/serialise round-trip |
| OQuaRE | Metric values match the Python implementation on a fixture ontology |
| Provenance | Change listener stamps `dcterms:*` only when enabled |

Canvas behaviour is tested headlessly through the `mxGraph` model rather than through UI
automation.

## 11. Relationship to CoModIDE and OWLAx

**CoModIDE** (`comodide/CoModIDE`, Kansas State + Jönköping; ESWC 2020 best-paper nominee)
is a Protégé plugin providing a schema editor and an ODP pattern library. It
independently validates D2 and the packaging: its `pom.xml` uses `jgraphx`,
`protege-editor-owl`, and the Felix `maven-bundle-plugin`. OWLAx likewise reports being
built on the OWL API with mxGraph.

**Licensing constraint.** CoModIDE has no `LICENSE` file and the GitHub API reports no
license, which means all rights reserved by default. No CoModIDE source may be copied,
vendored, or forked. Ideas and architecture are not copyrightable and OPLa-SD is a
published vocabulary, but every line here is written independently. The OWLAx candidate
vocabulary in §5.2 is implemented from the definitions published in arXiv:1808.10105 §2,
not from any implementation.

**Scope overlap.** CoModIDE covers the canvas and the pattern library — roughly 20% of
OntoBoard. It has no ROBOT integration, no ODK pipeline, no CSV/template wizard, no
SPARQL, no reasoning or quality UI, no OOPS! or OQuaRE, no Widoco, no import resolution,
no ID ranges, and no provenance. That remaining 80% is exactly the "command-line
complexity of pipeline tools" barrier from `paper/sections/introduction.tex`, and it is
where this work is positioned: CoModIDE brought graphical ODP modelling into Protégé;
this brings the ODK/ROBOT release pipeline into Protégé.

## 12. Delivery phases

1. **Skeleton** — Maven/OSGi build, empty `OntoBoardTab` loads in Protégé 5.6. Proves
   packaging end to end before any feature work.
2. **Canvas** — JGraphX editor, OWL model binding, selection bridge, opt-in membership,
   sidecar persistence, layouts, minimap, frames, sticky notes, export.
3. **Axiomatization** — immediate default edges plus the Generate Axioms dialog (§5.2).
4. **Pipeline** — `robot-core` operations, all 24 commands, log console, ODK scaffold and
   config, Makefile execution, import resolution and catalog.
5. **Data integration** — pattern library, CSV/template wizard, SPARQL, GitHub import,
   multi-format export, Widoco.
6. **Quality and metadata** — ROBOT report UI, OOPS!, OQuaRE, analysis tools, provenance,
   ID ranges.
7. **Retirement** — rewrite `README.md` and `docs/`, then delete `frontend/`, `backend/`,
   `worker/`, `collab/`, `docker-compose*.yml`, `build.sh`, `run.sh` in one labelled
   commit. Git history preserves the web application.

## 13. Risks

| Risk | Mitigation |
|---|---|
| JGraphX is end-of-life | BSD, stable, OSGi-published, two shipping plugins depend on it (§9) |
| OSGi classpath conflicts between `robot-core` and Protégé's OWL API | Both target the same OWL API line; pin versions and resolve at phase 1, before feature work |
| `robot-core` embedded fat-bundle size | Acceptable for a desktop plugin; measure at phase 1 |
| ODK `make` targets need `make` and ODK on the user's PATH | Detect and report clearly; scaffold generation works without them |
| Widoco is a 39 MB external JAR, not a Maven artifact | Optional external tool with on-demand download (§9); every other feature works without it |
| Canvas performance on large diagrams | Opt-in membership (§5.3) caps on-canvas entities by construction |
| Scope: ~34k LOC of behaviour to re-establish | §7.1 deletes most of it; phases are independently shippable |

## 14. Open items for the implementation plan

- Pin the OWL API version explicitly once phase 1 reveals what `protege-editor-owl:5.6.6`
  and `robot-core:1.9.8` each resolve to.
- Decide whether `patterns-repository/` ships inside the JAR or is referenced from a
  configurable local directory; measure bundle size at phase 1 before deciding.
