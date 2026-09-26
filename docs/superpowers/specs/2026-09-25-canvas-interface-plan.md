# The canvas as a working surface

*Written 2026-09-25 against `protege-plugin` 1.51.0. Grounded in a code audit of
`SchemaCanvasView` (2,114 lines), `canvas/*` and `model/OntologyProjection`, which produced 30
findings with file and line. Every defect named below was read in the source, not inferred from
behaviour.*

---

## 1. What "like a Miro board" should and should not mean

The request is that the canvas feel like a whiteboard: drag things, expand them, edit in place, and
have the edits land in the ontology. That is the right instinct, and it needs one qualification
before it becomes a plan.

A Miro board has no truth outside itself. A sticky note means whatever the reader decides. An
ontology diagram is the opposite: **every node is an entity with an IRI and every edge is an axiom**,
and a gesture that cannot be expressed as one has no business inventing something. A curator who
drags an arrow between two classes has asked a question — *which property, and is this
`some`, `only`, or a subclass relation?* — and the professional answer is to ask, not to guess.
Guessing is how `SubClassOf(A, ObjectSomeValuesFrom(rdfs:subClassOf, B))` ends up in somebody's
release, which is exactly what the inbound web-canvas path does today.

So the model is: **Miro's affordances, OWL's semantics.**

| Take from a whiteboard | Keep from the ontology |
|---|---|
| Direct manipulation — drag, place, group, annotate | Every node is an entity, every edge an axiom |
| Spatial memory — things stay where you put them | Layout is a view, stored beside the ontology, never inside it |
| Fluid navigation — search, focus, fit, zoom | Labels for reading, IRIs on demand, namespaces visible |
| In-place editing — no dialog round trip for small things | A change that cannot be an axiom is refused with a reason |
| Nothing is ever lost — undo everywhere | Protégé's undo owns axioms; the board owns its own history |

### Who this is for

Two audiences that want different things from the same picture.

**Ontology engineers** need precision: which axiom is this arrow, is it asserted or inferred, what
is the full IRI, why is this class red. They will accept a dialog if it is exact.

**Domain experts** — the pizza chef, the materials scientist — need to read the diagram without
knowing OWL. They need labels not identifiers, shapes and colours that mean something they can look
up, and a way to say "this is wrong" without learning Manchester syntax. Editorial notes are their
medium, which is why notes have to be first-class on the board rather than three menus away.

Nothing below asks either group to work the other's way.

---

## 2. Four things are losing work right now

Before any new capability. These are defects, not design, and they are ordered by how much a user
loses.

**A1 — Moving a sticky note or resizing a frame is permanently lost.**
`capturePositions` (`SchemaCanvasView.java:1237`) walks entity nodes only. The `CELLS_MOVED`
listener fires, captures nothing about notes or frames, and saves an unchanged sidecar. The next
`refresh()` — any edit anywhere in Protégé — redraws both from the stale stored position. A curator
drags a note next to the class it is about and it jumps back to the corner.

**A2 — `Arrange` never saves the layout it produces.**
The action applies a layout to the live graph but never calls `capturePositions()` /
`saveLayoutTo()`, which the `Add all` path does do. So a hierarchical arrangement of thirty classes
survives until the next `refresh()` and then reverts. The two paths should not differ.

**A3 — Four different coordinate bugs, one missing function.**
"New class here…" stores component pixels as graph coordinates (`:1138`), so on a zoomed board the
term lands at the wrong place. Sticky notes and frames ignore the click entirely and stack at
`(60,60)` (`:1078`). Drag-and-drop double-counts the scroll offset (`:2018`), so a drop on a
scrolled board lands off-screen. The double-click path alone is right, because it uses
`graphComponent.getPointForEvent`. One converter, used by every entry point, tested as a pure
function.

**A4 — `Arrange` treats notes and frames as terms.**
`CanvasLayouts` lays out the default parent's children (`CanvasLayouts.java:96`), so a frame is
slotted into the grid like a class and stops enclosing what it groups. Annotations should be
excluded from term layouts; `SchemaGraph.isAnnotationId` already exists to say which they are.

**A5 — No undo for anything the board owns.**
Selecting twelve arranged nodes and pressing Delete removes them and rewrites the sidecar in the
same call. Protégé's undo has no axiom change to reverse, so the arrangement is gone.
`docs/feature-parity.md:68` claims undo. A bounded deque of `CanvasLayout` snapshots pushed before
each board mutation fixes it; the class is a plain DTO, so copies are cheap.

---

## 3. Then: say what you already know

The canvas holds more information than it shows. Nothing here needs new data.

**B1 — Tooltips that answer the question the diagram raises.** Hovering an edge today gives
`rest|some|http://…#Pizza|http://…#hasTopping|http://…#PizzaTopping`. It should give the axiom in
Manchester syntax and whether it is asserted or inferred. A node should give its label, its full
IRI, and its editorial note if it has one. A sticky note should give its text, not
`ontoboard-note-3f2a1b9c`.

**B2 — A legend that explains the channel it actually uses.** The board colours node borders by
namespace, and the legend never mentions namespaces — so purple next to a purple Individual swatch
reads as "individual". It should render the live `prefixColors` map with the colours this board
assigned, add rows for notes and frames, and be openable beside the canvas rather than as a modal
you must close to look at what it describes.

**B3 — Feedback on every action.** Delete on a note does nothing and says nothing. Expand
neighbours that finds nothing new does nothing and says nothing, so the user clicks again to check
the menu works. Each action ends with one sentence: what changed, and whether the ontology was
touched. That last clause matters more here than in most software, because "removed from the board"
and "deleted from the ontology" are a world apart and the current UI distinguishes them nowhere.

**B4 — Where am I.** A zoom readout, `Fit to window`, and `Escape` to clear the selection — the
last is documented and does not exist.

> **Done.** Escape in 1.54.0; the readout and *Fit* in 1.58.0. The readout is a button in the new
> status bar and returns to 100% when clicked. It is bound to the graph view's scale event rather than
> to the wheel handler, so a zoom from anywhere updates it. `CanvasZoom` holds the fit arithmetic, with
> nine tests; the one thing to get right is that `mxGraphView.getGraphBounds()` reports *scaled*
> pixels, so a fit that does not divide by the current scale is correct at 100% and wrong at every
> other zoom level.

---

## 4. Then: the whiteboard parts

**C1 — Find a term.** A search field that matches on label and IRI as you type, centres the match
and selects it. On a hundred-term board there is currently no way to find Margherita except
dragging the canvas around or leaving for the class hierarchy.

> **Done in 1.58.0**, with one addition the plan did not ask for and the implementation argued for.
> Matching is ranked, not filtered, because centring "the match" requires deciding which one: on the
> pizza ontology a plain substring search answers `marg` with `Margherita` or
> `VegetarianMargheritaBase` depending on which the ontology happens to list first. Twelve tests in
> `CanvasSearchTest` pin the order.
>
> The addition: when nothing on the board matches, the box looks at the whole ontology and says which
> of the two reasons applies - the term does not exist, or it exists and has not been drawn - because
> those lead to opposite next actions and "no match" withholds the difference. Ctrl+Enter then puts it
> on the board, at the centre of the current view, which is the round trip to the class hierarchy that
> this item exists to remove.
>
> Not done here: no highlight of the other matches, and no results list. The count and Enter-to-step
> stand in for both.

**C2 — Expand and collapse, properly.** Expansion exists and discards its own result: new nodes
stack in a row at the origin, the count is thrown away, nothing is saved, and there is no collapse.
It should place new neighbours in a ring around the source, say how many arrived, save, and offer
to collapse back to what was there before.

> **Done in 1.59.0.** All four symptoms were the same cause - `expandOneHop` returned a count, so the
> caller had nothing to place, nothing to save and nothing to collapse. It returns the identifiers now.
> The ring is `CanvasLayouts.ringOffsets`, which fills outward once a ring is full, its capacity being
> the circumference over a node's width plus a gap, so two neighbours cannot overlap by construction
> rather than by a magic number that happens to work for eight.
>
> Collapse is scoped honestly: it takes back exactly what one expansion added, it is held for the
> session rather than the sidecar, and it is not undo. A5 remains open.

**C3 — Notes on the board, kept in the ontology.** A term with an editorial note is drawn with a
heavier border and there is no way to read it without leaving the canvas. Add *Editorial note…* to
the node menu, writing the same `IAO:0000116` the Notes menu writes, put the text in the tooltip,
and draw the marker as a corner badge so "has a note" and "is unsatisfiable" stop competing for the
same border. This is the item that matters most to domain experts: it is how they say "this is
wrong" in a form the ontology keeps.

> **Done across 1.56.0 and 1.59.0, with the last clause partly done.** The menu item and the writing in
> 1.56.0; the text on hover and the badge in 1.59.0. The reason the text took three releases is one type:
> `CanvasNode` carried a boolean, so the projection the tooltip is built from knew only *that* a note
> existed. It carries the words now, as a list, since a term can hold one note per editor.
>
> The badge exists and the heavier border stays, which is the partly. `CanvasExport` renders through
> `mxCellRenderer`, which draws from the graph model and never calls the component's `paint` - so an
> overlay badge is absent from every exported PNG and SVG. Verified before the badge was written.
> Removing the border would have made notes invisible in published diagrams, so the two markers no
> longer *both* live on the border, but the border is still one of them.

**C4 — Draw a relation and get an axiom.** `docs/feature-parity.md:59` claims the plugin can author
edges from the canvas; connection handling is switched off, and the capability is buried in a
context menu. Turn connection on with dangling edges refused, and on connect open a small picker:
which object property, and `some` / `only` / `SubClassOf`. Cancel leaves no edge. The picker is not
friction, it is the question the gesture asked.

> **Done in 1.60.0.** The picker is a popup at the drop point rather than a modal, because the answer is
> one click and a dialog in the middle of the screen would cover the two terms being talked about. It
> offers the applicable hierarchy link *and* the restriction dialog, not only the restriction: which of
> the two the user wanted is the actual ambiguity in a dragged line, and the plan's wording assumed a
> restriction.
>
> Cancel leaves no edge, as asked - achieved by deleting the edge mxGraph inserts before asking
> anything, so the board never shows a line without an axiom.
>
> One thing the plan could not have known: `mxConstants.CONNECT_HANDLE_ENABLED` is `false` in JGraphX
> 4.2.2, and turning connection on without also enabling the handle makes a press inside a node start
> an edge instead of moving the node - trading this canvas's most-used gesture for its newest.

**C5 — Imported terms can be drawn.** Dragging `bfo:continuant` onto the board silently does
nothing: `OntologyProjection` looks only at the edit file's own signature, so no node is drawn, and
`pruneStaleMembers` then removes the entry. Project with `Imports.INCLUDED` and draw imported terms
distinctly — they are the ones a curator must not edit.

---

## 5. And: presence that does not mislead

Smaller, but these are lies rather than gaps, and live mode is newly working as of 1.50.0.

**D1** Selection is published only as a side effect of mouse motion, and the heartbeat re-sends the
stale frame — so clicking a class in the hierarchy rings nothing on a peer's board, and the peer
keeps a ring round whatever was selected before. Publish on selection change.

**D2** No presence is sent until the mouse first moves over the canvas, so a peer who is reading the
class hierarchy shows as a cursor parked at the graph origin and then vanishes after ten seconds.
Seed a frame at connect, and distinguish "connected, no position" from "at (0,0)".

**D3** Both collaboration documents say a new node's position travels to a peer. The geometry is
sent and the receiving plugin ignores it. Either honour it or correct the documents — honouring it
is better, because a term that arrives where its author put it is the whole point of a shared board.

**D4** After a refused token the button reads "Collaborate…" and the first click silently abandons
the session instead of opening the dialog. Drive the label from whether a session exists.

---

## 6. Order, and why

1. **Section 2** first, all of it. Losing a user's arrangement is worse than lacking a feature, and
   A3 is a precondition for anything that creates something where the user clicked.
2. **Section 3**, because it is cheap, it needs no new state, and it makes the board legible enough
   to be worth improving further.
3. **C1 and C3**, the two that change what the canvas is for: findable terms, and notes that live on
   the board and in the ontology.
4. **Section 5**, now that live mode works.
5. **C2, C4, C5** last — the largest, and the ones where a wrong guess about semantics would be
   hardest to withdraw.

Every step keeps the invariant the sidecar exists for: **axioms in the ontology, arrangement beside
it.** A conflict over a diagram must never be able to touch a term.

---

## 7. What this plan does not do

- **No rewrite of `SchemaCanvasView`.** It is 2,114 lines and the largest class in the plugin, and
  splitting it is a separate piece of work with its own risk. Pure functions get extracted where a
  test needs them — the coordinate converter, the capture loop — and nothing else moves.
- **No new dependency.** JGraphX is already embedded; a nicer graph library is not worth another jar
  in a bundle with 103 of them.
- **No styling for its own sake.** Every visual change below either encodes information or makes an
  existing channel explicable. A prettier board that still cannot tell you what an arrow means is
  not the goal.
- **No threaded comments, locking, or sticky notes over the wire.** Those are in
  `docs/feature-parity.md`'s roadmap and are not interface work.
