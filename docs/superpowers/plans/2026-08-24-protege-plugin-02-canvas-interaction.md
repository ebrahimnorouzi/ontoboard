# OntoBoard Protégé Plugin — Plan 2: Canvas Interaction and Axiom Editing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the read-only viewer Plan 1 produced into an editor: drag nodes, create entities, draw edges that write real OWL axioms, and make the diagram legible.

**Architecture:** Unchanged from Plan 1 — the canvas stays a *view over Protégé's model*. Every mutation becomes `List<OWLOntologyChange>` applied through `OWLModelManager`, so Protégé's undo, dirty-tracking and other tabs stay in sync. Nothing writes OWL files directly.

**Tech Stack:** Java 8 bytecode, `protege-editor-owl:5.5.0`, OWL API 4.5.9, `jgraphx:4.2.2`, JUnit 5.

**Spec:** [`docs/superpowers/specs/2026-08-14-ontoboard-protege-plugin-design.md`](../specs/2026-08-14-ontoboard-protege-plugin-design.md) §5.2 (edge→axiom mapping), §12 phase 3.

**Why this plan exists:** the user ran the 1.0.3 plugin and reported: *"should always right click and select add selected entity to canvas, then not possible to move, add anything, it's not interactive and it's ugly, there are not many things to change."* Every task below traces to one of those complaints.

## Global Constraints

- **Java 8 API surface only** — `<maven.compiler.release>8</maven.compiler.release>` hard-fails on Java 9+ APIs. No `var`, records, `List.of`/`Map.of`/`Set.of`, no-arg `Optional.orElseThrow()`, text blocks.
- **OWL API 4.5.9 collection idioms, not streams.** `getOntologyIRI()` returns a **Guava** `Optional`.
- Package root `de.fizkarlsruhe.ise.ontoboard`.
- **Every ontology mutation goes through `OWLModelManager.applyChanges(...)`.** Never write files, never mutate an `OWLOntology` directly.
- **Removing a node from the canvas must never delete axioms.** That invariant is from Plan 1 and holds here: canvas removal and axiom deletion are separate, differently-labelled actions.
- **`pom.xml` is guarded** by `BundleConfigurationTest` (10 assertions covering the OSGi settings that took three releases to get right). Do not change it except to bump the version. In particular do not touch `!java.*`, `_noee`, `org.protege.editor.owl.ui`, or `*;resolution:=optional`.
- **Any class referenced only from `plugin.xml` needs its package explicitly imported.** A wildcard will not force it. `everyThirdPartyClassInPluginXmlHasItsPackageImported` guards this.
- Tests run **headless**: construct `SchemaGraph` and plain value objects only. Never `mxGraphComponent`, `mxGraphOutline`, or `SchemaCanvasView` in a test.

## JGraphX API — verified against jgraphx-4.2.2.jar with javap

Use these exactly; they are confirmed to exist:

| Symbol | Purpose |
|---|---|
| `mxGraphComponent.setConnectable(boolean)` | enables edge drawing |
| `mxGraphComponent.getConnectionHandler()` | connection handler for styling/behaviour |
| `mxGraphComponent.setToolTips(boolean)` | hover tooltips |
| `mxGraphComponent.setPanning(boolean)` | background drag-to-pan |
| `mxGraphComponent.setDragEnabled(boolean)` | drag-and-drop |
| `mxGraph.setCellsMovable(boolean)` | node dragging |
| `mxGraph.setCellsEditable(boolean)` | in-place label editing |
| `mxGraph.setDropEnabled(boolean)` / `setSplitEnabled(boolean)` | drop/split behaviour |
| `mxGraph.convertValueToString(Object)` | label rendering hook |
| `mxEvent.CELLS_MOVED`, `mxEvent.CONNECT`, `mxEvent.CELL_CONNECTED` | event names |

**Not verified — check with `javap` before use:** anything on `mxConnectionHandler`, `mxKeyboardHandler`, `mxRubberband`, `mxCellMarker`. If a symbol differs, adapt and report it rather than forcing it.

---

### Task 1: Turn interaction on and persist positions when nodes move

The most visceral complaint. Today the graph is technically movable but nothing is configured, positions are only captured on view close, and there is no panning or tooltip.

**Files:**
- Modify: `canvas/SchemaGraph.java`
- Modify: `views/SchemaCanvasView.java`
- Test: `canvas/SchemaGraphTest.java`

**Interfaces:**
- Produces: `SchemaGraph.isCellsMovable()` returns true; a `positionsChanged` callback the view uses to persist.

- [ ] **Step 1: Write the failing test**

In `SchemaGraphTest`, assert the interaction flags the canvas needs:

```java
    @Test
    void graphIsConfiguredForDirectManipulation() {
        SchemaGraph graph = new SchemaGraph();
        assertTrue(graph.isCellsMovable(), "nodes must be draggable");
        assertFalse(graph.isAllowDanglingEdges(), "an edge with one end is not an axiom");
        assertFalse(graph.isCellsDisconnectable(),
                "detaching an edge endpoint would silently orphan an axiom");
    }
```

Verify `isAllowDanglingEdges()` and `isCellsDisconnectable()` exist on `mxGraph` with `javap` first; if the getter names differ, use the real ones.

- [ ] **Step 2: Run it, confirm it fails**

`mvn -B test -Dtest=SchemaGraphTest` — expect failure only if a flag is wrong; if it already passes, say so and move to Step 3 (the constructor may already set some).

- [ ] **Step 3: Configure the graph and component**

In `SchemaGraph`'s constructor add `setCellsMovable(true)`, `setCellsEditable(false)` (label editing arrives in Task 3, and enabling it now would let users rename cells without touching the ontology), `setDropEnabled(false)`, `setSplitEnabled(false)`.

In `SchemaCanvasView.initialiseOWLView()`, after constructing `graphComponent`:

```java
        graphComponent.setConnectable(false); // Task 4 turns this on with real axiom writing
        graphComponent.setToolTips(true);
        graphComponent.setPanning(true);
        graphComponent.getPanningHandler().setEnabled(true);
```

Verify `getPanningHandler()` exists before using it; drop that line if not.

- [ ] **Step 4: Persist positions when the user drags**

Today `capturePositions()` runs only on dispose, so a crash loses the layout and nothing is saved between sessions until the tab closes. Register an `mxIEventListener` for `mxEvent.CELLS_MOVED` on the graph that calls `capturePositions()` and then saves.

**Debounce it.** A drag fires many events; saving a 62 MB-bundle plugin's sidecar on every mouse move is wasteful. Use a `javax.swing.Timer` with a ~800 ms delay, restarted on each event — this mirrors the 800 ms debounce the retired web app used. Ensure the timer is stopped in `disposeOWLView()` and that a pending save is flushed there.

- [ ] **Step 5: Tooltip shows the full IRI**

Override `getToolTipForCell(Object)` on `SchemaGraph` to return the cell id (the entity IRI) so hovering a node shows the full IRI while the label stays short. Verify the method name with `javap` on `mxGraph`.

- [ ] **Step 6: Run the full suite and commit**

```bash
mvn -B clean test
git add protege-plugin/src && git commit -m "feat(canvas): enable dragging, panning, tooltips and debounced layout saving"
```

---

### Task 2: Make the diagram legible

"It's ugly." Today every node shows its IRI fragment in the same colour with the same shape.

**Files:**
- Modify: `model/OntologyProjection.java` (label selection)
- Modify: `canvas/SchemaStyles.java`
- Create: `canvas/PrefixColours.java`
- Test: `model/OntologyProjectionTest.java`, `canvas/PrefixColoursTest.java`

- [ ] **Step 1: Failing test — labels prefer `rdfs:label`**

Add to `fixture-restrictions.ttl` a class carrying `rdfs:label "Human Being"@en`, then assert `OntologyProjection` uses that label rather than the IRI fragment, and falls back to the fragment when no label exists. Do **not** modify `fixture-tiny.ttl` — Task 2 and Task 6 of Plan 1 assert exact counts against it.

- [ ] **Step 2: Implement label selection**

In `OntologyProjection`, when building a `CanvasNode`, look for an `rdfs:label` annotation on the entity (`EntitySearcher.getAnnotationObjects` or equivalent in OWL API 4.5.9 — verify the exact method) and prefer its literal value. Prefer an `@en` label when several exist; otherwise take the first deterministically (sort, do not rely on set order — Plan 1 already had one bug from unordered OWL API sets).

- [ ] **Step 3: Failing test — per-prefix colours are stable**

`PrefixColoursTest`: the same namespace must always map to the same colour within a session, different namespaces to different colours, and the mapping must round-trip through `CanvasLayout.prefixColors`.

- [ ] **Step 4: Implement `PrefixColours`**

Derive a namespace from an entity IRI (everything up to the last `#` or `/`), then assign colours from a fixed, readable palette in first-seen order, persisting assignments into `CanvasLayout.prefixColors` so they survive a restart. Keep the palette small (8–10 colours) and legible against the node fill.

- [ ] **Step 5: Apply the colours and improve the styles**

`SchemaStyles.install` gains a per-prefix variant: keep the four base styles, and set stroke colour per node from `PrefixColours`. Also improve legibility: increase the default node height so two-line labels fit, enable word wrap on shapes if `mxConstants` supports it (verify), and give `SUBCLASS` edges a clearly different weight from property edges.

- [ ] **Step 6: Run the suite and commit**

---

### Task 3: Create classes and individuals on the canvas

"Not possible to add anything."

**Files:**
- Create: `axiom/EntityFactory.java`
- Modify: `views/SchemaCanvasView.java`
- Test: `axiom/EntityFactoryTest.java`

**Interfaces:**
- Produces: `EntityFactory.createClass(OWLModelManager, OWLOntology, String localName)` returning the new `OWLClass`, and `createIndividual(...)`. Both apply their changes through `OWLModelManager.applyChanges(...)` and return the created entity.

- [ ] **Step 1: Failing test**

`EntityFactoryTest` must assert, against an in-memory ontology:
- the new entity's IRI is built from the ontology's own IRI plus `#localName`, so it lands in the ontology's namespace rather than a hard-coded one;
- a `Declaration` axiom is added;
- creating an entity whose IRI already exists returns the existing entity and adds no duplicate axiom;
- an empty or whitespace-only name is rejected rather than producing a bare `#` IRI.

Do not use `OWLModelManager` in the test — it needs a live editor kit. Structure `EntityFactory` so the axiom-building logic is testable with a plain `OWLOntology` + `OWLDataFactory`, and keep the `applyChanges` call in a thin wrapper.

- [ ] **Step 2: Implement**

Derive the base IRI from `ontology.getOntologyID().getOntologyIRI()` (Guava `Optional` — use `.isPresent()`/`.get()`, not `java.util.Optional` methods). If absent, fall back to a documented default and say so in the UI.

- [ ] **Step 3: Wire into the canvas**

Add to the existing context menu, and add a double-click handler on empty canvas space that prompts for a name and creates a class there. The new node must appear at the click position — write its coordinates into `CanvasLayout.nodes` before refreshing, otherwise `render()` will place it at the default position.

Add the created entity to `CanvasMembership` so it is visible immediately.

- [ ] **Step 4: Run the suite and commit**

---

### Task 4: Draw an edge that writes a real OWL axiom

The heart of the plan, and spec §5.2.

**Files:**
- Create: `axiom/EdgeAxioms.java`
- Create: `axiom/PropertyPickerDialog.java`
- Modify: `views/SchemaCanvasView.java`, `canvas/SchemaGraph.java`
- Test: `axiom/EdgeAxiomsTest.java`

**Interfaces:**
- Produces: `EdgeAxioms.existential(OWLDataFactory, OWLClass subject, OWLObjectProperty property, OWLClass filler)` returning the `OWLSubClassOfAxiom` for `SubClassOf(subject, ObjectSomeValuesFrom(property, filler))`, plus `subClassOf(...)`, and the other OWLAx candidate forms from spec §5.2.

- [ ] **Step 1: Failing test**

`EdgeAxiomsTest` asserts each candidate produces exactly the OWL form the spec tabulates. Take the forms from spec §5.2 verbatim — they were derived from arXiv:1808.10105 §2:

| Candidate | OWL form |
|---|---|
| Existential *(default)* | `SubClassOf(A ObjectSomeValuesFrom(R B))` |
| Scoped domain | `SubClassOf(ObjectSomeValuesFrom(R B) A)` |
| Scoped range | `SubClassOf(A ObjectAllValuesFrom(R B))` |
| Global domain | `ObjectPropertyDomain(R A)` |
| Global range | `ObjectPropertyRange(R B)` |
| Functionality | `SubClassOf(A ObjectMaxCardinality(1 R B))` |

Assert on the constructed axiom objects, not on rendered strings.

- [ ] **Step 2: Implement `EdgeAxioms`** — pure functions over `OWLDataFactory`, no Protégé dependency, fully unit-testable.

- [ ] **Step 3: Property picker**

A modal listing the ontology's object properties with a filter box, plus a "new property…" option that creates one via `EntityFactory`, and a choice of candidate axiom type defaulting to **Existential**. Swing only; no test (needs a display) — say so in the report.

- [ ] **Step 4: Connect the handler**

Enable `graphComponent.setConnectable(true)`, then listen for `mxEvent.CONNECT` (verify whether the payload gives you the edge cell or source/target — check with `javap` on `mxEventObject` and inspect at runtime if needed). On connect: resolve source and target cell ids to `OWLEntity`, open the picker, build the axiom, apply it through `OWLModelManager`, and **let the resulting model-change event drive the re-render** rather than inserting the edge manually. That keeps the single-source-of-truth invariant: if the axiom fails to apply, no edge appears.

- [ ] **Step 5: Cancel path**

If the user cancels the picker, the visually-drawn edge must disappear. Because the re-render is driven by the model change, cancelling means simply refreshing from the unchanged model. Test that no axiom was added on cancel if you can structure it headlessly; otherwise state it as manually verified.

- [ ] **Step 6: Run the suite and commit**

---

### Task 5: Delete axioms from the canvas

Distinct from "remove from canvas", which must stay non-destructive.

**Files:**
- Modify: `views/SchemaCanvasView.java`
- Create: `axiom/AxiomRemoval.java`
- Test: `axiom/AxiomRemovalTest.java`

- [ ] **Step 1: Failing test** — given a projected edge, `AxiomRemoval` produces the `RemoveAxiom` changes that retract exactly the axiom that edge represents, and nothing else. Cover the subclass edge, the existential-restriction edge, and the legacy `rdfs:domain`/`rdfs:range` pair (retracting a domain/range edge must not remove the property declaration).

- [ ] **Step 2: Implement**, keyed off `CanvasEdge.getId()`, whose format Plan 1 defined (`sub|…`, `rest|some|…`, `dr|…`, `type|…`, `data|…`). Parse defensively and fail loudly on an unrecognised id rather than deleting the wrong thing.

- [ ] **Step 3: Wire two clearly different menu items** — "Remove from canvas (keeps axioms)" already exists; add "Delete axiom from ontology" with a confirmation dialog naming the axiom in Manchester syntax. The wording must make the difference obvious.

- [ ] **Step 4: Run the suite and commit**

---

### Task 6: Frames and sticky notes

Purely organisational, no OWL semantics. The sidecar already has `frames` and `notes` fields from Plan 1, unused.

**Files:**
- Modify: `canvas/SchemaGraph.java`, `canvas/SchemaStyles.java`, `views/SchemaCanvasView.java`
- Test: `canvas/SchemaGraphTest.java`

- [ ] **Step 1: Failing test** — `render` places frames behind nodes and sticky notes on top; both round-trip through `CanvasLayout`; neither produces any `CanvasEdge` or affects the projection.

- [ ] **Step 2: Implement** frames as mxGraph vertices sent to back with a translucent fill, notes as styled vertices with editable text. Persist both to the sidecar on change through the same debounced save as Task 1.

- [ ] **Step 3: Context-menu entries** to add a frame or a note at the click position, and to delete them.

- [ ] **Step 4: Run the suite and commit**

---

## Definition of Done

- Nodes drag, the background pans, hovering shows the full IRI, and layout survives a restart without closing the tab.
- Labels come from `rdfs:label` when present; namespaces are colour-coded and stable across restarts.
- Classes and individuals can be created from the canvas, landing in the ontology's own namespace.
- Drawing an edge writes a real OWL axiom chosen from the OWLAx candidate set, defaulting to the existential form; cancelling writes nothing.
- Axiom deletion exists and is unmistakably distinct from removing a node from the canvas.
- Frames and sticky notes work and persist.
- Every mutation is visible in Protégé's other tabs and undoable with Protégé's own undo.
- `BundleConfigurationTest`'s OSGi assertions all still pass.

## What this plan does NOT deliver

State this in any user-facing summary. After Plan 2 the plugin still has **no** ROBOT command execution, ODK scaffolding, pattern library, CSV/template wizard, SPARQL, quality reporting, GitHub integration, or collaboration. Those are Plans 3–5 and the collaboration work that spec decision D3a reopened.
