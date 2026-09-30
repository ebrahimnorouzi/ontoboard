<!--
Produced on 2026-09-26 by a six-dimension review of the rendered canvas - layout, node typography, edge
routing, colour and contrast, toolbar information architecture, and direct-manipulation affordances -
synthesised into one ordered spec. Every JGraphX symbol in it was checked with javap against
jgraphx-4.2.2.jar before it was proposed, and the before/after measurements in section 2 were taken by
running the pipeline headlessly against this project's own classes.

The evidence it reasons from is produced by CanvasDesignProofTest, which renders the board, a 31-node
board, the legend and the empty state to target/design/ without launching Protege. Re-run it with:
  mvn -o test -Dtest=CanvasDesignProofTest

Release 1 shipped in 1.64.0, Release 2 in 1.65.0, Release 3 in 1.66.0, and Release 4 across 1.67.0
(R4.1-R4.3, R4.5, R4.7, R4.8) and 1.68.0 (R4.4, R4.6, R4.9) - split because this spec's own regression
table calls the floating panels the most likely breakage in the whole plan, and a release that is
layout and labels should not carry that risk with them.

Every section is kept as written rather than edited afterwards, including the places where implementing
one corrected it. Those corrections are in the receipts: 1.64.0 has two, 1.65.0 the shadow colour,
1.67.0 the card background and the elision boundary, 1.68.0 the minimap default's evaluation point and
a status-line collision the spec did not anticipate.
-->

Everything checks out. The repo is untouched (all probes ran in the scratchpad against compiled classes). Here is the spec.

---

# OntoBoard canvas: board-quality design spec

**Target:** `C:\Users\eno\Documents\Git_repo\ebrahimnorouzi_github\ISE-FIZKarlsruhe\ontoboard\protege-plugin`
**Stack:** JGraphX 4.2.2, Java 8 source level (`maven.compiler.release=8`), Swing only, no new dependencies.
**Suggested filename:** `docs/superpowers/specs/2026-09-26-canvas-board-design.md`

All paths below are relative to `protege-plugin/`. Every JGraphX symbol named here was checked with `javap` against `~/.m2/repository/com/github/vlsi/mxgraph/jgraphx/4.2.2/jgraphx-4.2.2.jar` while writing this spec; the evidence is in §6.

---

## 0. What the renders actually show

Confirmed against `target/design/board.png` and `board-large.png`:

- **The subclass tree is upside down.** `board-large.png` is 4195×403 with `Thing` at the bottom. Measured cause: `SchemaGraph.render` inserts SUBCLASS as `subclass -> superclass`, so leaves are sources; `mxGraphHierarchyModel.initialRank` normalises sinks to rank 0, and `mxCoordinateAssignment` walks rank `maxRank -> 0` accumulating y when orientation is NORTH. `mxHierarchicalLayout(mxGraph)` is `this(graph, 1)` — `SwingConstants.NORTH`.
- **Unconnected terms are exiled.** In `board.png` the four edgeless terms sit thousands of pixels right of the diagram. `mxHierarchicalLayout.findRoots` only accepts `fanIn == 0 && fanOut > 0`, so an isolated vertex falls through to the single-node-hierarchy fallback and is laid out to the right of the previous hierarchy.
- **Edges are diagonal, not orthogonal.** This is the finding the brief did not list and it is the biggest single readability loss in `board-large.png`. `SchemaStyles.edge()` sets `STYLE_EDGE = EDGESTYLE_ORTHOGONAL` on all seven edge styles, but every Arrange stamps `noEdgeStyle=1` onto every edge and the router never runs.
- **`continuant` renders as a grey slab with full-black bold text.** Opacity fades the shape but not the label, and the opaque `#808080` shadow shows through the 55% fill.
- **The unsatisfiable fill is never drawn.** `Cheesey vegetable topping` is white with a red border; the legend shows a pink swatch.

**Corrections to the brief.** "Labels overflow the rhombus and hexagon; *Cheesey vegetable topping* touches its border" is half wrong. The class card is fine — at 13px bold its widest line is 114px inside 160px, 23px clear each side. The rhombus and hexagon overflow is real, and it only bites on **two-line** labels, because `mxGraphView.getWordWrapWidth` computes the wrap width from the cell's full box width and knows nothing about the shape's taper.

---

## 1. Decisions where reviewers contradicted each other

| # | Conflict | Decision | Why (one sentence) |
|---|---|---|---|
| 1 | L5 says `setDisableEdgeStyle(false)` is "actively harmful" (PNG 1280×2359 for an 851×881 bbox); E1 says it is the whole fix | **E1 wins — turn routing on.** | I measured it myself on the representative board and the 31-node tree: routed and unrouted give *identical* bounds and identical PNG sizes (2% × 0% overshoot both ways), the cell style stays clean `obSubClass` instead of `obSubClass;noEdgeStyle=1;orthogonal=1`, and the render is transformed (§6.1). |
| 2 | N2 wants `textOpacity=55` on imported terms; C6 forbids it | **C6 wins — `fontColor=#5A6472`, no `textOpacity`.** | 55% of `#1A1D21` over the composited fill is `#7D8083` at 3.87:1, under the 4.5:1 text floor, whereas `#5A6472` is 5.84:1 and still visibly secondary. |
| 3 | N7 and C1 both soften the shadow, at alpha 60 and alpha 38 | **C1's alpha — `new Color(0x0F, 0x17, 0x2A, 0x26)`.** | JGraphX has no blur, so the shadow is a hard offset copy; at alpha 60 with zero blur it reads as a second border rather than a lift. |
| 4 | L9 widens every new node to its label; N4 + N5 fix the same overflow with padding and a shape change | **N4 + N5 win; L9 is dropped.** | L9 changes default geometry, which the JSON sidecar persists and which the grid and ring pitches are derived from, at risk 3 — to solve a problem that 22px of `spacingLeft/Right` and one shape swap solve at risk 1 with no persisted-state consequence. |
| 5 | N8 recolours DATATYPE and LITERAL; C7 recolours all six node kinds | **C7 wins; N8 is subsumed.** | C7's values come from measured WCAG ratios and it resolves the datatype/data-property collision properly by moving the data-property node to teal, so N8's narrower edit would have to be redone. |
| 6 | C9 wants grid `#E3E6EB`; D4 wants `#D4D8DF` plus the correct observation that `setGridSize` is on `mxGraph`, not the component | **D4 wins on both.** | `javap` confirms `mxGraphComponent` has no `setGridSize`/`setGridEnabled` at all, and at 20px pitch `#E3E6EB` (1.18:1) is too faint to align against. |
| 7 | C2 restricts the namespace stroke to classes; C7/C8 assume kind strokes are visible | **Take C2 — these are the same change and must ship together.** | `board.svg` shows the individual `Italy` stroked `#4A90D9` where the legend promises `#7B61A8`; without C2, C7's new kind strokes would be invisible on five of six node kinds and the legend would still lie. |
| 8 | L's "Tree (left to right)" fifth `Algorithm` value | **Dropped.** | It breaks `CanvasLayoutsTest`'s `assertEquals(4, Algorithm.values().length)` and adds a permanent menu item for a rare need that Fit-after-Arrange already mitigates. |

---

## 2. Release 1 — Arrange produces a diagram you can read

*Everything in this release is in `canvas/CanvasLayouts.java` except two one-line changes. It is the largest visible failure and it ships first.*

### R1.1 `CanvasLayouts.createLibraryLayout` — orientation, spacing, routing

Replace the whole `switch`:

```java
private static mxIGraphLayout createLibraryLayout(SchemaGraph graph, Algorithm algorithm) {
    switch (algorithm) {
        case ORGANIC: {
            // mxOrganicLayout seeds from current positions and never re-centres. Measured on a
            // 300-node board: 41334x9819 starting at x=7835. mxFastOrganicLayout: 8230x3510 at
            // the origin, in half the time.
            mxFastOrganicLayout organic = new mxFastOrganicLayout(graph);
            organic.setForceConstant(240);
            organic.setMinDistanceLimit(90);
            organic.setInitialTemp(260);
            organic.setMaxIterations(500);
            organic.setDisableEdgeStyle(false);
            return organic;
        }
        case HIERARCHICAL:
        default: {
            // SOUTH puts the superclass above its subclasses, which is what every ontology tool
            // does and what SchemaGraph's edge direction (subclass -> superclass) inverts.
            mxHierarchicalLayout tree =
                    new mxHierarchicalLayout(graph, javax.swing.SwingConstants.SOUTH);
            tree.setIntraCellSpacing(24);
            tree.setInterRankCellSpacing(120);
            tree.setInterHierarchySpacing(120);
            tree.setParallelEdgeSpacing(20);
            // The stylesheet has asked for orthogonal routing since day one and has never got it:
            // mxGraphHierarchyModel.createInternalCells stamps noEdgeStyle=1 onto every edge
            // whenever isDisableEdgeStyle(), which defaults to true.
            tree.setDisableEdgeStyle(false);
            return tree;
        }
    }
}
```

Imports: drop `com.mxgraph.layout.mxOrganicLayout`, add `com.mxgraph.layout.mxFastOrganicLayout`. Keep the enum display name `"Organic (force-directed)"` — `mxFastOrganicLayout` is still force-directed.

`CIRCLE` leaves `createLibraryLayout` entirely (R1.4).

### R1.2 `CanvasLayouts.parkUnconnected` — new private method

```java
/**
 * Puts terms with no axioms in a block under the diagram.
 *
 * <p>mxHierarchicalLayout.findRoots only accepts a vertex with fanIn == 0 and fanOut > 0, so an
 * isolated term becomes a one-node hierarchy laid out to the RIGHT of everything else. On the
 * representative board the diagram ended at x=824 and the four loose terms sat at x=1465..2288.
 */
private static void parkUnconnected(SchemaGraph graph) {
    List<Object> loose = new ArrayList<Object>();
    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    boolean anyConnected = false;
    for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
        if (isAnnotation(graph, vertex)) {
            continue;
        }
        if (graph.getModel().getEdgeCount(vertex) == 0) {
            loose.add(vertex);
            continue;
        }
        mxGeometry geometry = graph.getModel().getGeometry(vertex);
        if (geometry == null) {
            continue;
        }
        anyConnected = true;
        minX = Math.min(minX, geometry.getX());
        maxX = Math.max(maxX, geometry.getX() + geometry.getWidth());
        maxY = Math.max(maxY, geometry.getY() + geometry.getHeight());
    }
    if (loose.isEmpty()) {
        return;
    }
    if (!anyConnected) {
        minX = 40;
        maxX = 40;
        maxY = -40;
    }
    int perRow = Math.min(
            Math.max(1, (int) Math.round((maxX - minX) / 200)),
            Math.max(1, (int) Math.ceil(Math.sqrt(loose.size() * 1.78))));
    for (int i = 0; i < loose.size(); i++) {
        mxGeometry geometry = graph.getModel().getGeometry(loose.get(i));
        if (geometry == null) {
            continue;
        }
        mxGeometry moved = (mxGeometry) geometry.clone();
        moved.setX(minX + (i % perRow) * 200);
        moved.setY(maxY + 80 + (i / perRow) * 100);
        graph.getModel().setGeometry(loose.get(i), moved);
    }
}
```

### R1.3 `CanvasLayouts.normalise` — new private method

SOUTH emits entirely negative y and `mxGraphComponent`'s viewport starts at the origin, so without this the board is off-screen. The **edges must translate with the vertices**: `mxHierarchicalLayout` writes absolute control points into each edge's `mxGeometry`, and a vertices-only translate leaves the routing behind.

```java
/** Moves the whole board so its top-left vertex sits at (40,40), routing included. */
private static void normalise(SchemaGraph graph) {
    double minX = Double.MAX_VALUE;
    double minY = Double.MAX_VALUE;
    for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
        mxGeometry geometry = graph.getModel().getGeometry(vertex);
        if (geometry != null) {
            minX = Math.min(minX, geometry.getX());
            minY = Math.min(minY, geometry.getY());
        }
    }
    if (minX == Double.MAX_VALUE) {
        return;
    }
    double dx = 40 - minX;
    double dy = 40 - minY;
    if (dx == 0 && dy == 0) {
        return;
    }
    // Vertices AND edges: mxGeometry.translate moves x/y, sourcePoint, targetPoint and every
    // mxPoint in points, because mxGeometry.TRANSLATE_CONTROL_POINTS is true.
    for (Object cell : mxGraphModel.getChildren(graph.getModel(), graph.getDefaultParent())) {
        mxGeometry geometry = graph.getModel().getGeometry(cell);
        if (geometry == null) {
            continue;
        }
        mxGeometry moved = (mxGeometry) geometry.clone();
        moved.translate(dx, dy);
        graph.getModel().setGeometry(cell, moved);
    }
}
```

Notes and frames are included in both the minimum and the translate, so a note keeps its position **relative to** the terms it comments on. That is the behaviour change `CanvasLayoutsTest` will catch — see §5.

### R1.4 `CanvasLayouts.apply` — new order, and CIRCLE handled locally

```java
public static void apply(SchemaGraph graph, Algorithm algorithm) {
    graph.getModel().beginUpdate();
    try {
        Map<Object, mxGeometry> annotations = annotationGeometries(graph);
        if (algorithm == Algorithm.GRID) {
            applyGrid(graph);
        } else if (algorithm == Algorithm.CIRCLE) {
            applyCircle(graph);
        } else {
            createLibraryLayout(graph, algorithm).execute(graph.getDefaultParent());
        }
        for (Map.Entry<Object, mxGeometry> kept : annotations.entrySet()) {
            graph.getModel().setGeometry(kept.getKey(), kept.getValue());
        }
        parkUnconnected(graph);
        normalise(graph);
    } finally {
        graph.getModel().endUpdate();
    }
    // Outside the update: mxEdgeLabelLayout reads mxCellState, so the view must be valid.
    graph.getView().revalidate();
    graph.getModel().beginUpdate();
    try {
        new com.mxgraph.layout.mxEdgeLabelLayout(graph).execute(graph.getDefaultParent());
    } finally {
        graph.getModel().endUpdate();
    }
}
```

Order matters: annotations are restored **before** `parkUnconnected` so the loose block can be placed relative to real term bounds, and `normalise` runs last so everything moves together.

`mxEdgeLabelLayout` writes an offset into each **edge's** `mxGeometry`, never a vertex's, so the sidecar and every saved board are untouched. It is the only layout in the jar that does **not** call `setEdgeStyleEnabled`, so it cannot undo R1.1.

New `applyCircle`, replacing the `mxCircleLayout` branch:

```java
/**
 * mxCircleLayout sizes the ring as vertexCount * maxNodeDimension / PI, which gives every node
 * an arc of two node widths - 31 terms produced a 3353x3249 ring. setRadius cannot shrink it;
 * it is a floor, not a setting (measured: 120, 300 and the default all give 3353x3249). And
 * execute() lays out every child vertex, so notes and frames went on the ring and were then
 * restored, leaving visible holes in the circle.
 */
private static void applyCircle(SchemaGraph graph) {
    List<Object> terms = new ArrayList<Object>();
    double pitch = 200;
    for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
        if (isAnnotation(graph, vertex) || graph.getModel().getEdgeCount(vertex) == 0) {
            continue;
        }
        terms.add(vertex);
        mxGeometry geometry = graph.getModel().getGeometry(vertex);
        if (geometry != null) {
            pitch = Math.max(pitch, Math.max(geometry.getWidth(), geometry.getHeight()) + 40);
        }
    }
    if (terms.isEmpty()) {
        return;
    }
    double radius = Math.max(200, terms.size() * pitch / (2 * Math.PI));
    new mxCircleLayout(graph).circle(terms.toArray(), radius, 40, 40);
}
```

`circle(Object[], double, double, double)` is public and, unlike `execute`, never calls `setEdgeStyleEnabled` — so CIRCLE needs no `setDisableEdgeStyle(false)`.

### R1.5 `CanvasLayouts.applyGrid` — derive the column count

Delete the `GRID_COLUMNS = 4` field and its javadoc paragraph. After `dx` and `dy` are computed, insert:

```java
// A 16:9 block rather than a fixed four columns. Four is defensible for a dozen nodes and
// wrong for anything bigger: 120 terms in four columns is a 3000px-tall strip, which is the
// hierarchy's failure rotated ninety degrees.
int columns = Math.max(1, Math.min(vertices.length,
        (int) Math.ceil(Math.sqrt(vertices.length * (dy / dx) * 1.78))));
```

and use `columns` in `i % columns` / `i / columns`. With the default 200×100 pitch: 6 terms → 3 columns, 31 terms → 6 columns (1200×600), 120 terms → 11 columns (2200×1100).

### R1.6 `views/SchemaCanvasView.arrangeWith` — fit after arranging

Even fixed, a 31-node tree is 3694px wide. Arrange currently leaves the zoom alone, so on a 1200px viewport the user sees six of thirty-one nodes and cannot tell it worked. In `arrangeWith(CanvasLayouts.Algorithm)` (~line 2170), insert `fitToWindow();` after `saveLayoutTo(layoutFile);` and replace the status text:

```java
setStatus("Arranged the board: " + algorithm.getDisplayName()
        + " - fitted to the window at " + CanvasZoom.readout(graph.getView().getScale())
        + ". Notes and frames kept their place on the board.");
```

`fitToWindow()` is at `SchemaCanvasView.java:1406` and already divides `mxGraphView.getGraphBounds()` by the current scale; it sets its own status, which this line then overwrites — that ordering is deliberate.

### R1.7 `canvas/SchemaGraph` constructor — re-route edges when a node moves

Once R1.1 turns routing on, every edge carries the layout's control points, so dragging a node keeps the old channel and the wire doubles back. Two lines after `setCellsMovable(true)`:

```java
// mxGraph defaults both to false. With routing on (CanvasLayouts) a dragged node otherwise
// keeps the channel the layout gave it: moving Mozzarella +320 sent its edge 240px left
// before turning round. mxGraph.resetEdge nulls the control points and the router runs again.
setResetEdgesOnMove(true);
setResetEdgesOnResize(true);
```

Accepted trade-off, stated in the comment: a hand-added bend is lost when either endpoint moves. That is already the situation across a reload, since `CanvasLayout` persists node geometry only and never edge waypoints.

### R1 measured result

Full pipeline run headlessly against the project's own classes (§6.1):

| | before | after |
|---|---|---|
| representative board | 1605×399, root at the bottom, loose terms 640px to the right, diagonal sweeps | 816×1809 incl. the user's note and frame; terms block 816×780; root above leaves; orthogonal trunks; loose terms in a block under the diagram |
| 31-node tree | 3808×399, ratio 9.5, `Thing` at the bottom | 3694×613, ratio 6.0, `Thing` at the top, clean right-angled trunks |
| 300-node ORGANIC | 41334×9819 starting at x=7835, 203ms | 8230×3510 at (39,39), 92ms |

One honest residue: `normalise` uses **vertex** minima, so an edge routed left of the leftmost vertex can land at slightly negative x. The 40px margin absorbs it in every board measured; if a render ever shows a clipped wire, widen the margin to 60 rather than switching to `getGraphBounds()` (which is scaled and needs a live view).

---

## 3. Release 2 — the stylesheet says what it means, and the legend stops lying

*All in `canvas/SchemaStyles.java`, `canvas/SchemaGraph.java`, `canvas/CanvasLegend.java`, `canvas/LegendPanel.java`. Every item here exports.*

### R2.1 `SchemaStyles` — resolve one installed font family (defect)

`FONT` is a CSS fallback chain. `java.awt.Font` takes one family name and silently falls back to the logical font `Dialog`. Probed on this machine: `new Font("Segoe UI, Helvetica Neue, Arial, sans-serif", BOLD, 13).getFamily()` → **`"Dialog"`**; `new Font("Segoe UI", …)` → `"Segoe UI"`. No board, screenshot or export has ever been drawn in the font the code names, and the SVG writes the chain verbatim, so the SVG and the PNG of one board are in different typefaces.

Replace line 100:

```java
private static final String FONT = resolveFont();

/** One installed family, because java.awt.Font takes a family name and not a CSS chain. */
private static String resolveFont() {
    String[] preferred = {"Segoe UI", "Inter", "Helvetica Neue", "Roboto",
            "DejaVu Sans", "Liberation Sans", "Arial"};
    try {
        java.util.Set<String> installed = new java.util.HashSet<String>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames(java.util.Locale.ENGLISH)));
        for (String family : preferred) {
            if (installed.contains(family)) {
                return family;
            }
        }
    } catch (Throwable noFontsAvailable) {
        // A headless or font-less JRE: the logical family below always exists.
    }
    return java.awt.Font.SANS_SERIF; // "SansSerif"
}
```

Java 8 clean, one call at class init. Verified headless: `-Djava.awt.headless=true` returned 223 families including `Segoe UI`, so the design-proof test and any CI render resolve correctly.

### R2.2 `SchemaStyles.install` — soften the drop shadow (defect)

Every card is drawn twice: `mxGraphics2DCanvas.fillShape(shape, true)` sets `mxSwingConstants.SHADOW_COLOR` — opaque `#808080`, 3.72:1 on the canvas, *more* contrast than four of the six node strokes — translates by (2,3) and fills. `board.svg` carries `<rect fill="gray" stroke="gray" transform="translate(2 3)"/>` under every class. It is also the direct cause of the grey imported node: a 55% grey shadow under a 55% white fill composites to `#DEDEDF`.

First statements of `install(mxGraph graph)`:

```java
// JGraphX paints a shadow as a hard offset copy of the same path in one flat colour - there is
// no blur, spread or gradient anywhere on that path. The default is opaque mid-grey, which
// reads as a smudge, and which bleeds through the 55% fill of an imported term.
com.mxgraph.swing.util.mxSwingConstants.SHADOW_COLOR = new java.awt.Color(0x0F, 0x17, 0x2A, 0x26);
mxConstants.SHADOW_OFFSETX = 0;
mxConstants.SHADOW_OFFSETY = 2;
// The SVG path takes a colour String with no alpha, so this is the pre-composited equivalent.
mxConstants.W3C_SHADOWCOLOR = "#D7D8DD";
// One global corner radius for every orthogonal bend; edges already carry STYLE_ROUNDED.
mxConstants.LINE_ARCSIZE = 20;
```

All five are non-final public statics (`javap -p` on both classes). Mutating them is safe in this build specifically: the pom embeds jgraphx into the plugin's own OSGi bundle, so `mxConstants` is loaded by the plugin's classloader. `LINE_ARCSIZE` is read only by `mxGraphics2DCanvas`, `mxSvgCanvas` and `mxConstants` — never by any vertex shape, which rounds via `STYLE_ARCSIZE` / `RECTANGLE_ROUNDING_FACTOR`.

### R2.3 `SchemaGraph.styleFor` — three defects in one method

```java
private static String styleFor(CanvasNode node, PrefixColours colours) {
    // The namespace colour applies to CLASSES ONLY. Appending it to every kind threw away the
    // kind's own stroke, so board.svg drew the individual Italy stroked #2D6FBF where the legend
    // promises #6B4FA0, and both property hexagons in the same blue - the legend was wrong for
    // four of its six node rows. Classes are the bulk of a schema, so the namespace channel
    // keeps the reach that matters.
    String stroke = node.isUnsatisfiable()
            ? SchemaStyles.UNSATISFIABLE_STROKE
            : (node.getKind() == NodeKind.CLASS ? colours.colourFor(node.getId()) : null);
    return baseStyleFor(node)
            + (stroke == null ? "" : ";strokeColor=" + stroke)
            // The pink was registered for the legend and never drawn. A red outline alone is one
            // channel, and it is the channel colour vision deficiency removes: #C0392B simulates
            // to #77771E under deuteranopia, deltaE 12.9 from the brown namespace colour.
            + (node.isUnsatisfiable() ? ";fillColor=" + SchemaStyles.UNSATISFIABLE_FILL : "")
            + (node.hasNote() || node.isUnsatisfiable()
                    ? ";strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH : "")
            // opacity fades the shape; mxGraphics2DCanvas.drawLabel reads a different key, so the
            // label stayed full-strength bold black on a ghosted box. textOpacity=55 is the wrong
            // fix - it composites to #7D8083 at 3.87:1, under the text floor - so set the ink.
            + (node.isImported()
                    ? ";opacity=" + SchemaStyles.IMPORTED_OPACITY + ";fontColor=" + SchemaStyles.IMPORTED_INK
                    : "");
}
```

Add `import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;`. New constants on `SchemaStyles`:

```java
/** The interior of a class a reasoner says can have no instances. */
public static final String UNSATISFIABLE_FILL = "#FDF0EE";

/** Secondary ink for an imported term: 5.84:1 on the composited fill, against #1A1D21's 16.9:1. */
public static final String IMPORTED_INK = "#5A6472";
```

Use `UNSATISFIABLE_FILL` at `SchemaStyles.install` line 123 in place of the literal, and `imported.put(mxConstants.STYLE_FONTCOLOR, IMPORTED_INK);` in the IMPORTED block so the legend swatch matches.

### R2.4 `SchemaStyles.install` — the node palette

Six fills, three of them near-identical, two of them *exactly* identical (DATATYPE and DATA_PROPERTY_NODE share `#EEF7F1` **and** `#3E8E5A`, so the legend draws two rows with the same swatch). Three strokes sit at 3.08–3.26:1. Label contrast is not a problem anywhere — `#1A1D21` is 14.8:1 to 16.9:1 on every fill — so nothing changes about the ink.

| style | fill | stroke | stroke : canvas | stroke : own fill |
|---|---|---|---|---|
| `CLASS`, `NOTED`, `IMPORTED` | `#FFFFFF` | `#2D6FBF` | 4.78 | 5.08 |
| `INDIVIDUAL` | `#F3EEFB` | `#6B4FA0` | 6.08 | 5.67 |
| `DATATYPE` | `#EAF5EF` | `#2F7A4C` | 4.93 | 4.69 |
| `LITERAL` | `#FFF6DB` | `#8A6A00` | 4.77 | 4.70 |
| `OBJECT_PROPERTY_NODE` | `#FFEFDC` | `#B35C00` | 4.44 | 4.19 |
| `DATA_PROPERTY_NODE` | `#E6F2F4` | `#0E6E78` | 5.61 | 5.22 |
| `UNSATISFIABLE` | `#FDF0EE` | `#C0392B` (unchanged) | — | — |
| `STICKY_NOTE` | `#FFF3B0` | `#A8871C` | 3.21 | 3.04 |
| `FRAME` | `none` | `#2D6FBF` | 4.78 | — |

The one hue change is the data-property node moving to teal, so it stops being the datatype's identical twin. The sticky note's old `#D9C066` was 1.69:1 — the lowest-contrast line on the board, on the one object whose whole purpose is to be noticed, and which also has `shadow=false`.

Also change the hardcoded frame fallback in `SchemaGraph.render` line 64 from `"#4A90D9"` to `"#2D6FBF"`, and replace the literal `"#F7F8FA"` at `SchemaStyles` line 238 with `CANVAS_BACKGROUND` (it is already defined at line 98).

### R2.5 `SchemaStyles` — type scale (`N6`)

Font size is currently a kind channel: five sizes across six kinds, and 11px is genuinely hard to read on the 150% scaled Windows displays most Protégé users run. Identity is already carried by shape, fill and italic.

`INDIVIDUAL` 12 → **13** (plain). `OBJECT_PROPERTY_NODE`, `DATA_PROPERTY_NODE` 12 → **13** (keep `FONT_ITALIC`). `DATATYPE`, `LITERAL` 11 → **12**. `CLASS` stays 13 + `FONT_BOLD`; edges stay 11; sticky note stays 12. Line height is `mxConstants.LINE_HEIGHT` 1.2, so a 13px line is 15.6px and three lines (46.8px) still fit a 60px node.

Caveat, measured not guessed: at 12px `"xsd:nonNegativeInteger"` is 125px against a 127.8px wrap width — one line, but only just. If a datatype name splits mid-token in the render, put `DATATYPE` back to 11 rather than widening the node.

### R2.6 `SchemaStyles` — property hexagons pay for their taper (`N5`)

`mxGraphView.getWordWrapWidth` is `width/scale - 2*LABEL_INSET - 2*spacing - spacingLeft - spacingRight`, then `× LABEL_SCALE_BUFFER` (0.9) — no knowledge of the shape. A hexagon's corners sit at 0.25w and 0.75w, so 22px above the centre a 160-wide hexagon offers ~101px while the wrap width is 127.8px; any two-line label hangs out of both slanted edges.

Build the two hexagon maps into locals and add:

```java
style.put(mxConstants.STYLE_SPACING_LEFT, 22);
style.put(mxConstants.STYLE_SPACING_RIGHT, 22);
```

Wrap width becomes `(160 - 6 - 12 - 44) × 0.9 = 88.2px`. `"has topping"` stays one line; `"has calorific content"` becomes two contained lines; worst measured case is three lines at 13px = 46.8px inside a 60px box.

### R2.7 `SchemaStyles` — the individual becomes a card (`N4`, risk 3)

A 160×60 rhombus offers ~77px of interior at the top of a two-line block against a 127.8px wrap width. Padding cannot rescue it: at the `spacingLeft/Right = 34` the geometry actually demands, `"Cheesey vegetable topping"` wraps to three lines and `"American hot spicy pizza topping"` to four (62.4px, taller than the box). The shape wastes half its area and still overflows.

```java
Map<String, Object> individual = vertex(mxConstants.SHAPE_RECTANGLE, "#F3EEFB", "#6B4FA0",
        true, false, 13, mxConstants.FONT_UNDERLINE);
individual.put(mxConstants.STYLE_ARCSIZE, 10);
sheet.putCellStyle(INDIVIDUAL, individual);
```

In `LegendPanel.paintNode`, delete the `SchemaStyles.INDIVIDUAL` rhombus branch (the `fillPolygon`/`drawPolygon` block) and change `boolean rounded = SchemaStyles.CLASS.equals(entry.getStyleName());` to accept `SchemaStyles.INDIVIDUAL` too.

Two documented consequences: the `SchemaStyles` class javadoc claims "shape carries the entity kind" and must be amended to "shape carries the entity kind, except that an individual is a card with an underlined name — the UML instance-specification convention, because a rhombus cannot hold an ontology label"; and the underline does **not** survive SVG export (§4), while the lilac fill does.

### R2.8 `SchemaStyles.edge` — arrowheads, weights, edge colours

Give the helper two more parameters:

```java
private static Map<String, Object> edge(String stroke, String dashPattern, String endArrow,
        float width, int endSize, boolean endFilled) {
    // ... existing body ...
    style.put(mxConstants.STYLE_ENDSIZE, endSize);
    if (!endFilled) {
        style.put(mxConstants.STYLE_ENDFILL, Boolean.FALSE);
    }
    // White reads as a raised chip on the #F7F8FA canvas and is invisible on a white export;
    // the old #F7F8FA backing painted 3598 grey pixels onto every exported PNG. The hairline is
    // what makes it a chip rather than a hole punched through the wire.
    style.put(mxConstants.STYLE_LABEL_BACKGROUNDCOLOR, "#FFFFFF");
    style.put(mxConstants.STYLE_LABEL_BORDERCOLOR, "#C9D2DC");
    style.put(mxConstants.STYLE_SPACING, 2);
    // A hair of air so a 13px hollow triangle does not weld itself to the 1.6px node outline.
    style.put(mxConstants.STYLE_TARGET_PERIMETER_SPACING, 3);
    // ...
}
```

Leave `STYLE_SOURCE_PERIMETER_SPACING` alone — a gap at the tail reads as a broken connection.

The seven call sites:

```java
SUBCLASS          -> edge("#3B4756", null,  mxConstants.ARROW_BLOCK,   1.6f, 13, false)
SUB_PROPERTY      -> edge("#B35C00", null,  mxConstants.ARROW_BLOCK,   1.6f, 13, false)
INFERRED_SUBCLASS -> edge("#707B89", "1 5", mxConstants.ARROW_BLOCK,   1.4f, 13, false)
OBJECT_PROPERTY   -> edge("#2D6FBF", null,  mxConstants.ARROW_CLASSIC, 1.4f, 10, true)
DATA_PROPERTY     -> edge("#0E6E78", null,  mxConstants.ARROW_OPEN,    1.1f, 10, true)
TYPE              -> edge("#6B4FA0", "2 4", mxConstants.ARROW_OPEN,    1.1f, 10, true)
INFERRED_TYPE     -> edge("#707B89", "1 5", mxConstants.ARROW_OPEN,    1.4f, 10, true)
```

Three things change at once and they interlock:

- **The hollow generalisation triangle.** `block` + `endFill=0` is the only route — 4.2.2 ships exactly five arrow values and none is a hollow triangle. `mxMarkerRegistry$1` reads `"endFill"` through `mxUtils.isTrue` and skips `fillShape`, drawing only the outline, and still returns the full marker length so the connector stops at the triangle's base.
- **Asserted is-a goes solid.** The hollow head now carries "is-a", which frees the dash to mean "the reasoner derived this". Today asserted (`"8 4"`, `#556677`) and inferred (`"1 5"`, `#8A94A0`) differ only by dash length and one step of grey, which is a legend lookup rather than a glance — on the one distinction the canvas must never blur. Stroke width drops 1.8f → 1.6f because a solid line at 1.8 reads heavier than a dashed one.
- **Contrast.** `#8A94A0` was 2.90:1, failing WCAG 1.4.11's 3:1 non-text floor at 1.4px. `#707B89` is 4.05:1 and still the palest of the six. The data-property edge moves to `#0E6E78` to agree with its node — the edge and the node for one OWL construct should be the same colour.

Then, after building the `INFERRED_SUBCLASS` and `INFERRED_TYPE` maps, add to both:

```java
// A small open circle where the edge leaves its source: the conventional "derived, not stated"
// tail. Reading becomes: solid + hollow triangle = asserted is-a; dotted + open circle tail +
// hollow triangle = inferred is-a. Two independent channels, both of which export.
inferred.put(mxConstants.STYLE_STARTARROW, mxConstants.ARROW_OVAL);
inferred.put(mxConstants.STYLE_STARTSIZE, 7);
inferred.put(mxConstants.STYLE_STARTFILL, Boolean.FALSE);
```

The `"1 5"` dot pattern on inferred edges stays and is load-bearing: after deuteranopia the closest pair in the new edge set is `#0E6E78` vs `#707B89` at deltaE 12.0, which is survivable only because the dash is redundant with the hue.

### R2.9 `CanvasLegend.entries()` and `LegendPanel.paintEdge` — keep the key honest

**Mandatory in the same commit**, because `CanvasLegendTest.legendColoursMatchTheStylesheet` compares every row's fill, stroke, dash pattern and opacity against the live stylesheet. Update:

| row | fill | stroke | dash |
|---|---|---|---|
| `CLASS` | `#FFFFFF` | `#2D6FBF` | null |
| `INDIVIDUAL` | `#F3EEFB` | `#6B4FA0` | null |
| `DATATYPE` | `#EAF5EF` | `#2F7A4C` | null |
| `LITERAL` | `#FFF6DB` | `#8A6A00` | null |
| `OBJECT_PROPERTY_NODE` | `#FFEFDC` | `#B35C00` | null |
| `DATA_PROPERTY_NODE` | `#E6F2F4` | `#0E6E78` | null |
| `SUBCLASS` | — | `#3B4756` | **null** |
| `OBJECT_PROPERTY` | — | `#2D6FBF` | null |
| `DATA_PROPERTY` | — | `#0E6E78` | null |
| `TYPE` | — | `#6B4FA0` | `"2 4"` |
| `SUB_PROPERTY` | — | `#B35C00` | **null** |
| `INFERRED_SUBCLASS` | — | `#707B89` | `"1 5"` |
| `INFERRED_TYPE` | — | `#707B89` | `"1 5"` |
| `UNSATISFIABLE` | `#FDF0EE` | `#C0392B` | null |
| `NOTED` | `#FFFFFF` | `#2D6FBF` | null |
| `IMPORTED` | `#FFFFFF` | `#2D6FBF` | null |

Reword the `SUBCLASS` meaning from "Dashed and heavier, so the hierarchy stays readable…" to **"A hollow triangle points at the parent — the UML convention for a generalisation. Solid, because a dash now means the reasoner derived it."**

In `LegendPanel.paintEdge(Graphics2D, int, int)`, replace the single `g.fillPolygon(xs, ys, 3)`:

```java
if (SchemaStyles.SUBCLASS.equals(entry.getStyleName())
        || SchemaStyles.SUB_PROPERTY.equals(entry.getStyleName())
        || SchemaStyles.INFERRED_SUBCLASS.equals(entry.getStyleName())) {
    g.drawPolygon(xs, ys, 3);
} else {
    g.fillPolygon(xs, ys, 3);
}
```

and widen the head to `{end + 9, end, end}` / `{middle, middle - 5, middle + 5}` so a hollow triangle is readable at swatch size. `LegendPanel` is a Swing component, so this is the one item in R2 that does **not** export — correctly, since the legend is not diagram content.

---

## 4. Release 3 — the board behaves like a board

*Gestures, grid, shortcuts. `canvas/SchemaGraph.java`, `canvas/CanvasZoom.java`, `views/SchemaCanvasView.java`.*

### R3.1 `SchemaGraph` — a Ctrl+drag must not forge a second cell for one IRI (defect)

`mxGraph`'s constructor sets `cellsCloneable = true` and `mxGraphComponent`'s sets `dragEnabled = true`, and `SchemaGraph` never touches either. A Ctrl+drag from a node runs Swing DnD with COPY; `mxGraphTransferHandler.createGraphTransferable` calls `graph.cloneCells`, and `mxCell.clone()` copies value, style, geometry and everything else but **never resets the id**. The dropped clone is a second vertex whose id *is* the original IRI: `getIdForCell` returns that IRI for it, clicking it pushes the term to Protégé's selection model, and Delete on it reports "1 term taken off the board" while removing the original from membership. Nothing writes it to `layout.nodes` or membership, so it vanishes at the next `refresh()`. This is the projection invariant broken by a single unheld default.

Next to `setCellsDisconnectable(false)`:

```java
// A node is a term, and there is exactly one node per IRI. mxGraph's DnD clone path
// (mxGraphTransferHandler -> graph.cloneCells -> mxCell.clone) copies the cell id verbatim,
// so a Ctrl+drag produced a second cell claiming to be the same term. Duplicating board
// furniture is Ctrl+D; duplicating an axiom is not a gesture.
setCellsCloneable(false);
```

`mxGraphHandler` consults `graph.getCloneableCells()` before starting a clone, so this one line is sufficient.

### R3.2 `SchemaCanvasView.installWheelZoom` — zoom at the cursor, and honour `MAX_SCALE` (defect)

Two faults. `mxGraphComponent.centerZoom` defaults to true and is never set, so the board zooms toward the viewport centre — on a 3694px board the node you are pointing at slides off screen on every click. And `mxGraphComponent.zoom(double)` is guarded only by `newScale > 0.04` with **no upper bound**, so the wheel reaches 800% while `CanvasZoom.MAX_SCALE = 4.0` documents a range nothing enforces.

Replace the listener body:

```java
double scale = graph.getView().getScale();
double target = CanvasZoom.clamp(event.getWheelRotation() < 0
        ? scale * graphComponent.getZoomFactor()
        : scale / graphComponent.getZoomFactor());
event.consume();
if (target == scale) {
    return;
}
final javax.swing.JViewport port = graphComponent.getViewport();
final java.awt.Point inPort = javax.swing.SwingUtilities.convertPoint(
        (java.awt.Component) event.getSource(), event.getPoint(), port);
final java.awt.Point origin = port.getViewPosition();
final double k = target / scale;
graphComponent.zoomTo(target, false);
// zoomTo defers maintainScrollBar to invokeLater, so a synchronous setViewPosition is overwritten.
javax.swing.SwingUtilities.invokeLater(new Runnable() {
    @Override
    public void run() {
        java.awt.Dimension size = graphComponent.getGraphControl().getPreferredSize();
        int x = (int) Math.round((origin.x + inPort.x) * k) - inPort.x;
        int y = (int) Math.round((origin.y + inPort.y) * k) - inPort.y;
        x = Math.max(0, Math.min(x, Math.max(0, size.width - port.getWidth())));
        y = Math.max(0, Math.min(y, Math.max(0, size.height - port.getHeight())));
        port.setViewPosition(new java.awt.Point(x, y));
    }
});
```

The arithmetic is exact because `zoomTo` calls `view.scaleAndTranslate(newScale, 0, 0)` whenever `pageVisible` is false — it is, the field is `iconst_0` in the constructor and nothing sets it — so the view translate is permanently (0,0) and a graph point's pixel is exactly `graphCoord × scale`. Do **not** also set `setKeepSelectionVisibleOnZoom(true)`: `mxGraphComponent.zoom` scrolls to the selection when that flag is set and would fight this.

### R3.3 A grid you can align to

`SchemaGraph` constructor, next to `setCellsMovable(true)`:

```java
// Snapping has been on since the first version and invisible: mxGraph defaults gridSize to 10
// and gridEnabled to true, and mxGraphHandler.mouseReleased already snaps every drag delta.
// A 10px step gives sixteen candidate columns per 160px node, which is not alignment.
setGridSize(20);
```

`SchemaCanvasView.initialiseOWLView`, replacing the bare `setGridVisible(true)` at line 245:

```java
graphComponent.setGridVisible(true);
graphComponent.setGridStyle(mxGraphComponent.GRID_STYLE_DOT);
graphComponent.setGridColor(java.awt.Color.decode("#D4D8DF"));
```

`#D4D8DF` is about 1.5:1 on `#F7F8FA` — present as texture, never competing with a 1.6px node stroke. Note in the comment that `gridSize` is also the snap step, so a board saved at odd coordinates jumps once on the next drag; `DEFAULT_W`/`DEFAULT_H` and the persisted sidecar geometry are untouched.

`SchemaGraph.dropEntities`: change the drop pitch `column * 190` → `column * 200` and `row * 90` → `row * 100` so a dropped cluster lands on the grid.

`mxGraphComponent` has **no** `setGridSize` or `setGridEnabled` — the component owns appearance, `mxGraph` owns the model-side step and flag. Any proposal calling `graphComponent.setGridEnabled(...)` would not compile.

### R3.4 `CollaborativeGraphComponent` — two dead or hostile gestures

```java
/**
 * Alt is the library's "ignore the grid this once" modifier - mxGraphComponent.isGridEnabledEvent
 * is exactly !isAltDown - and the library's own default hands the same key to a forced marquee,
 * which this canvas does not want on a cell. mxGraphHandler.mousePressed returns before getCellAt
 * when isForceMarqueeEvent is true, and this plugin's rubberband only starts on Ctrl or Shift, so
 * Alt+drag reached neither handler and did nothing at all.
 */
@Override
public boolean isForceMarqueeEvent(java.awt.event.MouseEvent event) {
    return false;
}

/**
 * Shift is what a board user holds to add to a selection. mxGraphComponent.isToggleEvent only
 * knows Ctrl (and left+meta / right+ctrl on Mac), so Shift+drag silently threw away whatever was
 * already selected - which installSelection's own doc comment claims it does not.
 */
@Override
public boolean isToggleEvent(java.awt.event.MouseEvent event) {
    return event != null && (event.isShiftDown() || super.isToggleEvent(event));
}
```

`selectCellsForEvent` is the single branch point for both Shift+click and Shift+marquee, so one override fixes both.

### R3.5 `SchemaCanvasView.installContextMenu` — a right-drag pan must not end in a menu (defect)

`isPanningEvent` delegates to `super` for non-left buttons, so a right-drag pans by design. But the context-menu `MouseAdapter` opens on `mouseReleased` whenever `isPopupTrigger()`, and on Windows the popup trigger *is* the button-3 release. The documented pan gesture therefore always ends with an eleven-item menu opening wherever the user dragged to.

```java
private java.awt.Point pressedAt;

@Override
public void mousePressed(java.awt.event.MouseEvent event) {
    pressedAt = event.getPoint();
}
```

and at the top of the existing `mouseReleased`:

```java
if (!event.isPopupTrigger()) {
    return;
}
if (pressedAt != null
        && pressedAt.distance(event.getPoint()) > graphComponent.getTolerance()) {
    // That was a pan, not a click.
    return;
}
```

`getTolerance()` is 4 by default — the same threshold `mxGraphComponent.isSignificant` uses to tell a click from a drag.

### R3.6 `SchemaCanvasView` — one undo step per drag, not per 800ms window (defect)

The `CELLS_MOVED` listener records an undo step only when `!positionSaveTimer.isRunning()`, on the stated grounds that "mxGraph fires CELLS_MOVED repeatedly while a drag is in progress". It does not: `mxGraphHandler`'s constructor sets `livePreview = false` and `imagePreview = true`, so a drag shows a ghost bitmap and `graph.moveCells` — and therefore `CELLS_MOVED` — runs exactly once, on `mouseReleased`. What the guard actually does is swallow the undo step for any second drag started within 800ms of the first: move node A, immediately move node B, press Ctrl+Z, and both are undone with no way to take back only the second.

```java
private boolean movingProgrammatically;

cellsMovedListener = (sender, event) -> {
    // One step per completed drag. mxGraphHandler.livePreview is false in 4.2.2, so the model
    // is updated once on release and this fires once - the old debounce guarded against a flood
    // that cannot happen, and dropped the second of two quick drags.
    if (!movingProgrammatically) {
        rememberBoard("moving things on the board");
    }
    capturePositions();
    positionSaveTimer.restart();
};
```

Set `movingProgrammatically = true` around `CanvasLayouts.apply(graph, algorithm)` in `arrangeWith`, which already calls `rememberBoard("arranging the board")` and must not push a second step.

### R3.7 `CanvasZoom` + shortcuts — the five zoom keys and Fit-to-selection

`CanvasZoom.java`, pure arithmetic and unit-testable:

```java
/**
 * Like {@link #scaleToFit} but allowed to magnify.
 *
 * <p>Fit-the-board must not magnify - everything is already shown, and drawing it four times
 * bigger is a zoom level nobody asked for. Fit-the-selection is the opposite case: framing one
 * 160x60 node in a 1200px window is the whole point of the gesture.
 */
public static double scaleToFill(double contentWidth, double contentHeight,
        double viewWidth, double viewHeight) {
    // identical body to scaleToFit, returning clamp(fit) instead of clamp(Math.min(fit, 1.0))
}
```

`SchemaCanvasView`: extract the body of `fitToWindow` into `private void fitTo(mxRectangle bounds, boolean allowMagnify, String what)`; `fitToWindow()` becomes `fitTo(graph.getView().getGraphBounds(), false, "board")`; add

```java
private void fitToSelection() {
    Object[] selected = graph.getSelectionCells();
    if (selected == null || selected.length == 0) {
        setStatus("Select something first, then Ctrl+2 frames it.");
        return;
    }
    fitTo(graph.getView().getBoundingBox(selected), true, selected.length + " selected");
}
```

In `installKeyboardShortcuts`, inside the existing `CTRL_DOWN_MASK` / `META_DOWN_MASK` loop:

| key | action |
|---|---|
| `VK_0` | `graphComponent.zoomActual(); updateZoomReadout();` |
| `VK_1` | `fitToWindow()` |
| `VK_2` | `fitToSelection()` |
| `VK_EQUALS`, `VK_ADD` | zoom in through the clamped helper from R3.2, never raw `zoomIn()` |
| `VK_MINUS`, `VK_SUBTRACT` | zoom out, same |
| `VK_A` | `graph.selectCells(true, false)` — vertices only, then `setStatus(graph.getSelectionCount() + " nodes selected. Delete takes them off the board; the ontology is unchanged.")` |
| `VK_D` | `duplicateSelectedAnnotations()` (R3.9) |

`selectCells(true, false)` excludes edges deliberately: Delete on a selection containing edges produces the "an arrow is an axiom, so use the right-click menu" message once per edge and reads as a half-broken shortcut.

Update the readout tooltip to `"How far the board is zoomed. Click, or Ctrl+0, for 100%."` and the Fit tooltip to `"Zoom so the whole board is visible (Ctrl+1). Ctrl+2 frames the selection."`

### R3.8 Sticky-note and frame colours — the cheapest large win on the board

`CanvasLayout.NoteLayout.color` already exists (default `"#FFF3B0"`), is copied by `copy()`, is persisted in the sidecar, and is read by `SchemaGraph.render` as `";fillColor=" + note.color`. **Nothing in the plugin ever writes it** — the annotation branch of `installContextMenu` offers only Edit and Delete. Every board is monochrome yellow. `FrameLayout.stroke` has the identical problem. Model, persistence and renderer are all already in place.

In the `SchemaGraph.isAnnotationId(iri)` branch of `installContextMenu`, after "Edit this note or frame…":

```java
if (iri.startsWith(SchemaGraph.NOTE_ID_PREFIX)) {
    JMenu colours = new JMenu("Note colour");
    String[][] palette = {
        {"Yellow", "#FFF3B0"}, {"Green", "#D8F0D5"}, {"Blue", "#D6E8FB"},
        {"Pink", "#FBD9E6"}, {"Orange", "#FFE2C4"}, {"Violet", "#E5DCF6"}};
    for (final String[] swatch : palette) {
        JMenuItem item = new JMenuItem(swatch[0], new SwatchIcon(Color.decode(swatch[1])));
        item.addActionListener(a -> recolourNote(iri, swatch[1]));
        colours.add(item);
    }
    menu.add(colours);
}
```

plus `recolourNote(String id, String hex)` — find the note in `layout.notes`, `rememberBoard("recolouring a sticky note")`, set `note.color`, `refresh()`, `saveLayoutTo(layoutFile)` — and a ~12-line `private static final class SwatchIcon implements javax.swing.Icon` painting a 14×14 rounded rect filled with the colour and stroked `#9AA3AF`. All six fills are light enough for the note's existing `#1A1D21` ink and distinct at 100%.

Same shape for `FrameLayout.stroke` under a "Frame colour" submenu: `#2D6FBF` blue, `#2F7A4C` green, `#B35C00` amber.

This is a cell style, so recoloured notes export.

### R3.9 Double-click edits furniture; Ctrl+D duplicates it; neither touches a term

`SchemaGraph`: change `setCellsEditable(false)` to `true` and add the predicate that makes it selective:

```java
/**
 * Only board furniture. A term node's label is its rdfs:label - renaming it is an ontology edit
 * with its own conversation, not a side effect of a double click - and an edge's label is the
 * property it stands for.
 */
@Override
public boolean isCellEditable(Object cell) {
    String id = getIdForCell(cell);
    return id != null && isAnnotationId(id) && getModel().isVertex(cell);
}
```

`SchemaCanvasView.initialiseOWLView`: `graphComponent.setEnterStopsCellEditing(true); graphComponent.setEscapeEnabled(true);` and a `mxEvent.LABEL_CHANGED` listener that writes the new text back into `layout.notes` / `layout.frames`, calls `rememberBoard` and `saveLayoutTo` — without it the model change is lost at the next `refresh()`.

In `installDoubleClickToCreate`, replace the `if (getCellAt(...) != null) return;` early exit with a branch that calls `expandNeighbours(iri)` when the cell is a term already on the board. `expandNeighbours` (line 1090) already calls `rememberBoard` and already reports what it did, so Ctrl+Z takes it back.

`duplicateSelectedAnnotations()` (bound to Ctrl+D in R3.7) copies selected notes and frames only — `copy()` on both already exists at `CanvasLayout.java:172` and `:196`, widen them to public — assigning fresh ids from `nextAnnotationSuffix()` and offsetting +20,+20. When the selection holds only terms:

```
"A node is a term, and a term appears once. Ctrl+D duplicates sticky notes and frames."
```

Do **not** add Ctrl+C / Ctrl+V: a board clipboard that could only ever carry notes and frames is more surface than it is worth, and Ctrl+C with a term selected would be read as "copy the IRI", which is a different feature.

### R3.10 `PrefixColours` — five colours, keyed to the namespace (risk 3)

Two faults. The javadoc claims the eight colours "remain distinguishable for the most common forms of colour blindness"; measured, three pairs collapse — `#C2554D`/`#8A6D3B` at deltaE 5.2 under deuteranopia, `#B08900`/`#C2554D` at 2.9 under tritanopia. And `PALETTE[assignments.size() % PALETTE.length]` keys the colour to *how many* namespaces have been seen, so removing one and adding another hands out a duplicate, and the same vocabulary gets a different colour on a different board — the opposite of what the persistence in this class exists for.

```java
/**
 * A lightness ladder, not a hue wheel. Eight hues cannot survive a dichromatic collapse - the
 * best eight-colour candidate I could construct still had a worst pair at deltaE 1.7. Five on a
 * 3.9-to-9.1 contrast spread give a worst pair of deltaE 18.6 across normal, deuteranopic,
 * protanopic and tritanopic vision, because lightness is a second channel no deficiency removes.
 * A board with more than five namespaces reuses a colour, which is honest: the legend's
 * "Namespaces on this board" section remains the authority.
 */
private static final String[] PALETTE = {
    "#2B7FD4", // blue,   3.88:1 on #F7F8FA
    "#7B3FA0", // violet, 6.46:1
    "#1E7F5C", // green,  4.65:1
    "#B35C00", // orange, 4.44:1
    "#3B4652", // slate,  9.05:1
};
```

```java
public String colourFor(String iri) {
    String namespace = namespaceOf(iri);
    String existing = assignments.get(namespace);
    if (existing != null) {
        return existing;
    }
    // Derived from the namespace, not from the map size: a vocabulary gets the same colour on
    // every board, and removing one namespace cannot re-colour another.
    int hash = 0;
    for (int i = 0; i < namespace.length(); i++) {
        hash = 31 * hash + namespace.charAt(i);
    }
    int start = Math.floorMod(hash, PALETTE.length);
    String colour = PALETTE[start];
    for (int k = 0; k < PALETTE.length; k++) {
        String candidate = PALETTE[(start + k) % PALETTE.length];
        if (!assignments.containsValue(candidate)) {
            colour = candidate;
            break;
        }
    }
    assignments.put(namespace, colour);
    return colour;
}
```

`Math.floorMod(int, int)` is Java 8. State the sidecar consequence in the javadoc: existing boards keep their stored hexes because `colourFor` returns `assignments.get(namespace)` first, so nothing re-colours itself; only new namespaces take the new palette. Converting old boards would be a separate one-line migration in `CanvasLayout` dropping any `prefixColors` value not in `PALETTE` — **not** in this release.

---

## 5. Release 4 — the screen furniture stops getting in the way

*Pure Swing chrome. None of it appears in a PNG or SVG export, because `CanvasExport` renders through `mxCellRenderer` from the model and never sees the component tree.*

### R4.1 `canvas/CanvasIcons.java` — new file, prerequisite for the rest

Unicode glyph icons are not an option (§7). A new final class of static nested `javax.swing.Icon` implementations, each 16×16 (`Caret` 8×5, `Dot` 8×8), each drawing into a `Graphics2D` copy with `KEY_ANTIALIASING = VALUE_ANTIALIAS_ON` and disposing it in a `finally` — the exact shape `LegendPanel.SwatchIcon` and `NamespaceIcon` already use. Palette drawn from the constants already in the codebase: accent `#2563EB`, ink `#1A1D21`, muted `#5A6470`, border `#D8DDE3`.

`Plus` (2px accent cross, 10px arms) · `PlusStack` (three 3px `#D8DDE3` bars plus a small accent cross top-right) · `Magnifier` (9px circle, `BasicStroke(1.8f)`, 4px handle at 45°, muted) · `Tree` (a 5×3 rect above two 5×3 rects with 1px connectors, muted) · `Dashed` (a 12×12 rounded rect in `BasicStroke(1.6f, CAP_BUTT, JOIN_MITER, 10f, new float[]{3f,3f}, 0f)`, quoting the dash the canvas uses for inferred edges) · `Key` (three 4×4 swatches) · `Download` (10px open frame plus a 6px down arrow) · `Kebab` (three 2.5px muted dots) · `Caret` (7×4 filled muted triangle) · `Dot(Color)` (7px filled circle) · `KeyCap(String)` (rounded rect with a centred 11pt label).

On the toolbar use icon **and** text; in the floating clusters use icon-only with `setToolTipText`.

### R4.2 `buildToolBar` — stop clipping, stop twitching

Measured with the real components under Java 11: the toolbar's preferred width is **1261px**, it starts clipping at 1030px, and at the 857px a 1440px Protégé window gives, `"Collaborate..."` is laid out at x=812 w=86 — past the right edge, unpainted and unclickable, with no chevron and no scroll. `JToolBar` uses `BoxLayout`, which lays overflowing children out past the container edge; there is no wrap or overflow API to turn on (§7).

1. **Delete the `JComboBox<CanvasLayouts.Algorithm>`** (201px at preferred, to choose between four items nobody reopens). Make `Arrange` a menu button that pops a `JPopupMenu` of `Algorithm.values()`, each item calling `arrangeWith(alg)`, with `new CanvasIcons.Caret()` and `setHorizontalTextPosition(SwingConstants.LEFT)`. **Saves 211px net.**
2. **Add a `Kebab` overflow button** carrying Legend…, Export image…, — , Disconnect / Collaborate…, — , Snap to grid (a `JCheckBoxMenuItem`, selected, toggling `graph.setGridEnabled` + `graphComponent.setGridVisible` + repaint), — , Keyboard shortcuts… . **Saves 284px, costs 36px.** Keep *Add selected*, *Add all*, *Find* and *Inferences* visible — Inferences is a mode with a visible consequence and must not hide.
3. **Floor the Find field.** After `searchField = new JTextField(13)`: `searchField.setMinimumSize(new Dimension(140, searchField.getPreferredSize().height))` and `box.setMaximumSize(new Dimension(320, Short.MAX_VALUE))`, so the bar's slack stops being poured into a text box that reaches 407px at a 1400px bar.
4. **Pin the Add button.** `describeSelectionOnButton` sets the text to `"Add " + label` on every selection change: `"Add selected"` is 108px, `"Add Cheesey vegetable topping"` is 211px, so clicking through the class tree shifts everything to its right by up to **103px** — including the Find field, which slides out from under the pointer. The codebase already knows this is unacceptable; `searchCount` carries `setPreferredSize(new Dimension(92, ...))` with the comment "Fixed width so the toolbar does not reflow on every keystroke". Do the same:

```java
Dimension fixed = new Dimension(150, addSelectedButton.getPreferredSize().height);
addSelectedButton.setPreferredSize(fixed);
addSelectedButton.setMinimumSize(fixed);
addSelectedButton.setMaximumSize(fixed);
```

and in `describeSelectionOnButton` elide rather than grow: `addSelectedButton.setText("Add " + (label.length() > 14 ? label.substring(0, 13) + "\u2026" : label));`. The tooltip already carries the full IRI. U+2026 is present in Tahoma, Segoe UI and Dialog.

Measured result: preferred width **1261 → 777px**; first clip moves from a 1030px toolbar to a 639px one.

### R4.3 `buildStatusBar` — two channels, two labels

One `collabStatus` label serves two independent producers. `setStatus()` writes transient board confirmations; `CanvasCollabHost.onStatus` / `onUnshareable` write persistent session state that requires action ("3 changes not shared"). Whichever fires last wins, permanently — so arranging the board erases the only notice that a colleague will never see your edit, and it never comes back.

Add a second field `private JLabel boardStatus`, built the same way. Lay out `collabStatus` in `BorderLayout.WEST` at `new Dimension(200, h)` with a leading `new CanvasIcons.Dot(...)` — `#16A34A` when connected, `#D97706` from `onUnshareable`, `#9CA3AF` otherwise — and `boardStatus` in `CENTER`. Repoint the writers: `setStatus()` → `boardStatus` only; `onStatus`, `onUnshareable`, `onSessionEnded` and `toggleCollaboration` → `collabStatus` only. Reuse the existing `say(JLabel, String, String)` helper at line 1391 so the tooltip always carries the untruncated text.

### R4.4 Float the zoom cluster and the minimap over the board

The board is framed on all four sides by fixed chrome — toolbar NORTH, minimap EAST, status bar SOUTH — and the 180px minimap is a permanent tax, present even while `StartPanel` is showing, where it is a blank grey rectangle beside a "nothing here yet" card. On the board it exists for, it fails: a 3694×613 strip scales to fit width and paints as a 200×33 smear in a 140px box.

In `initialiseOWLView`, replace `centre.add(graphComponent, "canvas")` with a hand-laid `JLayeredPane` overriding `doLayout()` (rather than adding a `ComponentListener`, so it also runs on the first layout): `graphComponent` at full bounds in `DEFAULT_LAYER`; `zoomCluster` and `minimapPanel` in `PALETTE_LAYER`, anchored bottom-right with a 12px margin, the minimap stacked above the cluster. Remove `add(outline, BorderLayout.EAST)`.

`zoomCluster`: a `JPanel(new FlowLayout(CENTER, 2, 3))`, opaque `#FFFFFF`, `createLineBorder(new Color(0xD8,0xDD,0xE3))`, ~216×30, holding `−` (zoom out), the existing `zoomReadout` (keep its 64px width and its `zoomActual()` action), `+` (zoom in), `Fit` (moved verbatim out of `buildStatusBar`), `?` (`showShortcuts()`). Both zoom buttons route through R3.2's clamped helper. U+2212 renders in Tahoma, Segoe UI and Dialog; everything else uses `CanvasIcons`.

`minimapPanel`: `JPanel(new BorderLayout())`, opaque `#FFFFFF`, 1px `#D8DDE3`, 200×168, with a 22px header (11pt `#5A6470` "Overview" plus a `CanvasIcons.Caret` collapse button) and the existing `mxGraphOutline` at 200×140. Collapsing sets `outline.setVisible(false)` and the panel to 200×22; the flag persists in `Preferences.userNodeForPackage(SchemaCanvasView.class).putBoolean("ontoboard.minimap.open", open)` — deliberately not the JSON sidecar, so a panel toggle never dirties a committed file. Default open when `membership.size() >= 25`, collapsed below, evaluated in `showAppropriateCard()`.

Fix the outline's own defaults while there: `outline.setAntiAlias(true)` (constructor sets false) and `graphComponent.setPageBackgroundColor(Color.decode(SchemaStyles.CANVAS_BACKGROUND))` so the minimap's field matches the board. Leave `setDrawLabels` alone — already false, and labels at 4% scale are noise. Do **not** call `setFitPage(false)`: `updateScaleAndTranslate` only honours `fitPage` when `graphComponent.isPageVisible()`, which is permanently false, so it would be a no-op that misleads the next reader.

**The load-bearing detail.** Every button in a floating cluster gets `setFocusable(false)` and `setRequestFocusEnabled(false)`, and every action ends with `graphComponent.requestFocusInWindow()`. Delete, Escape, Ctrl+Z and Ctrl+F are all bound on `graphComponent`'s `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` input map; once focus moves to a sibling of `graphComponent`, every one of them stops firing. Keep the panels small and opaque: `CollaborativeGraphComponent`'s own javadoc records that a full-size transparent overlay swallowed mouse events and broke drag-and-drop once already.

### R4.5 `installContextMenu` — four groups, destructive last

Eleven items in three arbitrary groups. "Sticky note here" and "Frame here" are creation actions pinned above everything while "New class here" and "New individual here" sit five items below; the two axiom-writing items are five items apart; the two destructive items are neither adjacent nor last; and **"Add selected entity to canvas" is offered even when you right-clicked a node**, where it reads as "add the thing I clicked" but acts on Protégé's tree selection, which may be something else entirely.

| group | items | shown when |
|---|---|---|
| 1 | Expand neighbours · Collapse (N added) · Editorial note… / Editorial note (has one)… | a term is under the cursor |
| 2 | Set parent or type… · Relate to another term… *(from "Create relation from this node...")* | a term is under the cursor |
| 3 | New class here… · New individual here… · Sticky note here… · Frame here… | always |
| 4 | Remove from board *(tooltip "The axioms stay in the ontology.")* · Delete axiom from ontology… / disabled "Inferred — no axiom to delete" · Delete this note or frame | always, last |

Delete "Add selected entity to canvas" entirely. Use U+2026 throughout, matching `StartPanel`. Teach the key: `remove.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0))` — `JMenuItem` paints it right-aligned, and a `WHEN_IN_FOCUSED_WINDOW` binding on a popup item that is not showing is not consulted, so it cannot double-fire. If a smoke test disagrees, drop the accelerator and append `"  (Del)"` to the label. Count drops from 11 to 9 on a term, 4 on empty canvas.

### R4.6 `canvas/ShortcutsPanel.java` — new file, and the gesture inversion it makes safe

Six interactions exist and none are advertised anywhere except the Find field's tooltip, which you can only read after you have already found the Find field. Two of them are documented deliberate inversions of `mxGraph`'s and every other tool's default.

A `JPanel` on `GridBagLayout`, two columns, styled like `StartPanel` (ink `#1A1D21`, muted `#5A6470`, card `#FFFFFF`, border `#D8DDE3`). Left column a `CanvasIcons.KeyCap`; right column the meaning. Rows, each verified against the source line that implements it:

| key | meaning |
|---|---|
| Ctrl+F | Find a term on the board |
| Enter / Shift+Enter | Next / previous match |
| Ctrl+Enter | Add a match that is in the ontology but not on the board |
| Ctrl+Z / Ctrl+Shift+Z | Undo / redo **the board** — axioms are Protégé's own Edit > Undo |
| Ctrl+A | Select every term on the board |
| Ctrl+D | Duplicate a sticky note or frame |
| Delete | Take the selection off the board. The axioms stay. |
| Escape | Clear the selection |
| Ctrl+0 / Ctrl+1 / Ctrl+2 | 100% / fit the board / frame the selection |
| Double-click empty board | New class here |
| Double-click a term | Expand its neighbours |
| Drag from a node's edge handle | Draw an axiom between two terms |
| **Drag on empty board** | **Select a region** |
| **Space-drag, middle-drag or right-drag** | **Pan** |
| Wheel | Zoom at the pointer |
| Alt-drag | Place a node off the grid |

`private void showShortcuts()` → `JOptionPane.showMessageDialog(this, new ShortcutsPanel(), "Canvas shortcuts", JOptionPane.PLAIN_MESSAGE)`. Reachable three ways: the `?` in the floating cluster, the overflow menu, and a one-shot status hint at the end of `initialiseOWLView`:

```java
java.util.prefs.Preferences prefs = Preferences.userNodeForPackage(SchemaCanvasView.class);
if (!prefs.getBoolean("ontoboard.hintsSeen", false)) {
    setStatus("Drag to select, space-drag to pan, wheel to zoom, double-click for a new class. "
            + "Press ? for every shortcut.");
    prefs.putBoolean("ontoboard.hintsSeen", true);
}
```

which lands in `boardStatus` once R4.3 splits the label, so it cannot clobber a collaboration warning.

**Then, and only then, invert the pan gesture.** `CollaborativeGraphComponent`: add `private transient boolean spaceHeld;` with a setter and getter, and rewrite `isPanningEvent`:

```java
@Override
public boolean isPanningEvent(java.awt.event.MouseEvent event) {
    if (event == null) {
        return false;
    }
    if (javax.swing.SwingUtilities.isMiddleMouseButton(event)) {
        return true;
    }
    if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
        return spaceHeld && getCellAt(event.getX(), event.getY()) == null;
    }
    return super.isPanningEvent(event);
}
```

`SchemaCanvasView.installSelection`: replace the modifier guard with an explicit cell test —

```java
if (graphComponent.getCellAt(event.getX(), event.getY()) == null
        && !((CollaborativeGraphComponent) graphComponent).isSpaceHeld()) {
    super.mousePressed(event);
}
```

The explicit null-cell test is **required**, not belt-and-braces: `mxRubberband.mousePressed` in 4.2.2 checks only `isConsumed` / `isEnabled` / `isRubberbandTrigger` / `isPopupTrigger` and never calls `getCellAt`; `isRubberbandTrigger` is literally `iconst_1; ireturn`. It works today only because `mxGraphHandler` is registered first by the `mxGraphComponent` constructor and consumes the event on a cell hit.

`installKeyboardShortcuts`: bind `KeyStroke.getKeyStroke(VK_SPACE, 0, false)` → `setSpaceHeld(true)` and `(VK_SPACE, 0, true)` → `setSpaceHeld(false)`, each also setting `graphComponent.getGraphControl().setCursor(Cursor.getPredefinedCursor(held ? MOVE_CURSOR : DEFAULT_CURSOR))`.

Update `CollaborativeGraphComponent`'s class javadoc: the old justification — "out of the box there is no way to move the diagram with the mouse at all" — was true before drag-to-connect and the outline panel existed and is no longer the only way to move the view. Also fix `reportRemoval`'s status text at line 1553, which already advises "drag a box around several", a gesture that does not currently work as written.

### R4.7 `StartPanel` — stop naming a widget that does not exist

`showAppropriateCard` does `cards.show(centre, "start")`, which replaces `graphComponent` entirely. `StartPanel`'s closing lines then say "Double-click the board to add a class, or drag one in from the palette on the left." There is no board to double-click — the card is covering it — and there is no palette: `viewconfig-ontoboardtab.xml`'s left column is six Protégé entity views, the first labelled **"Classes"**.

1. Replace those two closing lines with a fourth action row: `action("Add every term", "Puts all " + n + " classes, properties and individuals on the board", onAddAll)`, wired to a new `Runnable` constructor parameter bound to `SchemaCanvasView::addEverythingToCanvas`, which already guards at 300 terms.
2. Add one footer line in `MUTED`: **"Taking something off the board never deletes it from the ontology."** Nothing anywhere currently says this, and `reportRemoval`'s own javadoc flags the consequence: "A curator who believes they have retired a term and has only hidden it will find out at the next release."
3. Fix the remaining noun: "the palette on the left" → **"the Classes tab on the left"**.
4. In `showAppropriateCard()`, compute `boolean empty = membership.size() == 0` once and `setEnabled(!empty)` on `searchField`, the Arrange button, `inferencesButton` and the overflow's Export item; `minimapPanel.setVisible(!empty)`.

### R4.8 Dark mode: keep the canvas light, on purpose, and stop it looking like a fault

Protégé 5.6 ships a dark look and feel. Nothing inside the canvas breaks — every fill is a near-white tint and the ink is 14.8:1 to 16.9:1 on all of them, and nothing throws. What breaks is everything around it: the viewport background is `Color.decode("#F7F8FA")` unconditionally with no border, so the board is a bright borderless slab floating in a dark IDE; and `StartPanel` — the first screen anyone sees — hardcodes `INK`, `MUTED`, `CARD` and `EDGE` and calls `setBackground(CANVAS_BACKGROUND)`.

Four edits, no theming engine:

```java
java.awt.Color panel = javax.swing.UIManager.getColor("Panel.background");
boolean dark = panel != null
        && (0.2126 * panel.getRed() + 0.7152 * panel.getGreen() + 0.0722 * panel.getBlue()) / 255.0 < 0.4;
graphComponent.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1,
        dark ? new Color(0x3A, 0x40, 0x4A) : new Color(0xD8, 0xDD, 0xE3)));
```

The file already reaches for `UIManager` at line 2142, so the pattern exists. The canvas **stays light deliberately**: it is the surface every exported PNG is composed on, and a diagram whose colours depend on the author's IDE theme is not reproducible. In `StartPanel`, replace the four constants with `UIManager` lookups — `Label.foreground`, `Label.disabledForeground`, `Panel.background`, `controlShadow` — each with the current hex as the null fallback, and delete the `setBackground(CANVAS_BACKGROUND)` so the start screen follows the IDE instead of pretending to be an empty board.

### R4.9 A frame carries what is inside it (risk 3)

`SchemaGraph.render` inserts frames, terms, edges and notes all with `getDefaultParent()`, so a frame is a *sibling* of the nodes it visually encloses and `mxGraph.moveCells` moves only the frame. On `board.png` the "Toppings" frame is an empty dashed rectangle; drag it and it stays empty. That is the one behaviour that makes a Miro frame a frame.

Real grouping is the wrong fix: making nodes children of the frame cell makes `mxGeometry` relative to the frame, and `SchemaCanvasView.captureInto` (line 2538) reads `geometry.getX()` as an absolute board coordinate straight into the sidecar — so every saved board would shift by the frame origin on first load. `constrainChildren` and `extendParents` (both default true) would also start resizing frames behind the user's back.

Instead, an `mxEvent.MOVE_CELLS` listener alongside the existing `CELLS_MOVED` registration: for each moved cell whose id starts with `SchemaGraph.FRAME_ID_PREFIX`, reconstruct its pre-drag box as `(x - dx, y - dy, w, h)`, collect every other child vertex whose **centre point** falls inside it, and move those by the same `(dx, dy)` under the `movingProgrammatically` flag from R3.6, then `capturePositions()` and `positionSaveTimer.restart()`.

`moveCells(Object[], double, double, boolean, Object, Point)` fires `mxEventObject("moveCells", "cells", "dx", "dy", …)` once at the end, after `cellsMoved` has already fired — so the listener sees final geometry, which is why the box is reconstructed by subtracting the delta.

Centre-point containment, not full containment: a 160-wide node overlapping a frame edge by 4px is one the user considers inside. **Resizing** a frame deliberately does not move its contents — a frame here is a reading aid, not a canvas. Add to the frame creation dialog text (`createFrame`, line 633): "Dragging the frame takes whatever is inside it along."

---

## 6. Regression risk, per release

### R1 — Arrange

| what | risk | action |
|---|---|---|
| **`CanvasLayoutsTest.noAlgorithmMovesAStickyNoteOrAFrame`** | **Will fail.** `normalise` translates notes and frames along with everything else. | This is the intended behaviour change and the test must be rewritten to assert the *invariant that matters*: the note's offset from a named term is unchanged before and after. Rename it `everyAlgorithmKeepsANoteInThePlaceItHasRelativeToTheTerms`. Do not weaken it to "the note moved" — that would pass for a note flung to the far corner. |
| `CanvasLayoutsTest.gridLayoutWrapsToASecondRowAndIsDeterministic` | Passes. The test uses 6 nodes; with `dy/dx = 100/200`, `ceil(sqrt(6 × 0.5 × 1.78)) = 3` columns → 2 rows, x increases within a row, and the arithmetic is deterministic. Its javadoc names `GRID_COLUMNS = 4` and must be reworded. | Reword the javadoc; keep the assertions. |
| `CanvasLayoutsTest.gridLayoutKeepsEveryNodeAtNonNegativeCoordinates` | Passes. `applyGrid` emits from 0 and `normalise` then shifts to 40. | none |
| `CanvasLayoutsTest.everyAlgorithmRunsWithoutThrowingOnAnEmptyGraph`, incl. `assertEquals(4, Algorithm.values().length)` | Passes — no fifth algorithm is added. `parkUnconnected` and `normalise` both return early on an empty graph. | none |
| `theTermsAreStillArrangedWhenAnnotationsArePresent`, `hierarchicalLayoutSeparatesOverlappingNodes` | Pass — both `assertNotEquals` on y, which SOUTH preserves. | none |
| **Saved sidecars** | No migration needed. R1 changes positions only when the user presses Arrange, and `arrangeWith` already calls `capturePositions()` + `saveLayoutTo(layoutFile)`. No default geometry changes. | none |
| **New unit tests required** (project rule: pure logic is extracted and unit-tested) | `parkUnconnected` and the derived `columns` formula are pure arithmetic on geometry. | Extract both as package-private static methods and test: a board with only loose terms; a board with none; `perRow` at 1, 4 and `loose.size()`; the columns formula at `vertices.length` 1, 6, 31, 120. |
| **Re-render** | Mandatory. | `mvn -o test -Dtest=CanvasDesignProofTest`, then open `target/design/board.png` and `board-large.png` and confirm: root above leaves, right-angled trunks, loose block under the diagram, no negative-x clipping. |

### R2 — Stylesheet

| what | risk | action |
|---|---|---|
| **`CanvasLegendTest.legendColoursMatchTheStylesheet`** | **Will fail on any half-done edit** — it compares every row's `STYLE_FILLCOLOR`, `STYLE_STROKECOLOR`, `STYLE_DASH_PATTERN` and `STYLE_OPACITY` against the live stylesheet. | Apply the full table in §R2.9 in the same commit. This test is the guard and doing its job is the point. |
| `CanvasLegendTest.legendColoursMatchTheStylesheet`, dash column | Fails for `SUBCLASS` and `SUB_PROPERTY` specifically, because R2.8 makes them solid. | Set both Entry dash patterns to `null`. |
| `SchemaGraphTest` style fragments | **All pass.** Every one of the eight style assertions uses `NodeKind.CLASS`, so C2's restriction of the namespace stroke to classes changes nothing they observe. `contains("opacity=55")` still holds; `assertFalse(style.contains("opacity"))` is on a non-imported node; `assertFalse(style.contains("strokeWidth="))` is on a plain node. | none, but **add** a test asserting a non-CLASS node emits no `;strokeColor=` — that is the new invariant and nothing currently guards it. |
| `CanvasLegendTest.nodeKindsWithoutAnEntry` / `edgeKindsWithoutAnEntry` | Pass — no kinds added or removed. | none |
| `CanvasExportTest` | Check whether it asserts on pixel colours or SVG attributes; the label backing changes from `#F7F8FA` to `#FFFFFF` and every stroke hex moves. | Update any hardcoded hex. |
| `SchemaStyles` class javadoc | R2.7 makes "shape carries the entity kind" false for individuals. | Amend it, as spelled out in R2.7. |
| **Mutating library statics** | `SHADOW_COLOR`, `SHADOW_OFFSETX/Y`, `W3C_SHADOWCOLOR`, `LINE_ARCSIZE` are process-global. | Safe here specifically because the pom embeds jgraphx into this plugin's own OSGi bundle, so `mxConstants` and `mxSwingConstants` load in the plugin's classloader. Say so in the comment; if the embed ever changes, this is the code to revisit. |
| **Re-render** | Mandatory, and this is the release where looking at the image is the whole test. | Re-run `CanvasDesignProofTest` and confirm in `board.png`: `continuant` faded with grey ink and no grey slab; `Cheesey vegetable topping` pink-filled; the individual a lilac card with an underlined name; hollow triangles on is-a; both property hexagons containing their labels; the two grey inferred edges carrying open-circle tails; edge label chips white with a hairline. Also open `board.svg` and grep for `fill="gray"` — it must be gone. |

### R3 — Direct manipulation

| what | risk | action |
|---|---|---|
| `CanvasZoomTest` | New method `scaleToFill` is untested. | Add tests: magnifies where `scaleToFit` caps at 1.0; still clamps at `MAX_SCALE`; still returns `MIN_SCALE` for a zero-size window. |
| `PrefixColoursTest` | **Passes.** It asserts only: same namespace → same colour, two namespaces → different colours, persistence into the map, an existing stored value wins, and hex format. The hash-plus-probe satisfies all five for ≤5 live namespaces, and the loop that exercises more only checks the hex format. | Add a test that the **same namespace string yields the same colour from a fresh empty map** — the property the old size-keyed implementation did not have and the reason for the change. |
| `graph.setGridSize(20)` | A board saved at odd coordinates jumps once on the next drag. Persisted geometry is untouched until then. | Accept; document in the comment. If it is judged unacceptable, ship the grid **colour** alone, which carries most of the visual gain. |
| `setCellsEditable(true)` + `isCellEditable` override | A bug in the predicate would let a user rename a term's visible text without touching the ontology — the exact lie the original `false` was guarding against. | Unit-test `isCellEditable` directly: true for a note id, true for a frame id, **false** for an IRI, **false** for an edge cell, false for null. |
| `setCellsCloneable(false)` | Check `installEntityDropTarget` — if the palette drop path depends on `dragEnabled`, leave `graphComponent.setDragEnabled` alone. `setCellsCloneable(false)` is sufficient on its own. | Smoke-test drag from the Classes tree onto the board. |
| **Re-render** | Only R3.8 (note/frame colours) changes what is drawn. | Re-run the proof test if the note palette lands; the rest is behaviour and needs a Protégé smoke test. |

### R4 — Chrome

| what | risk | action |
|---|---|---|
| Unit tests | None exist for Swing chrome. | Extract what can be extracted: the Add-button elision rule (`label → displayed text` at lengths 0, 14, 15, 40) and the minimap's open-by-default predicate (`membership.size() >= 25`) are pure functions and should be package-private statics with tests. |
| Focus | **The most likely breakage in the whole plan.** Delete, Escape, Ctrl+Z and Ctrl+F are bound on `graphComponent`'s `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` map; a focusable floating button silently kills all four. | `setFocusable(false)` + `setRequestFocusEnabled(false)` on every floating button, `requestFocusInWindow()` at the end of every floating action. Smoke-test each of the four keys **after** clicking a floating button. |
| Mouse events | A transparent overlay swallowed events and broke drag-and-drop on this canvas once already. | Keep both floating panels small and opaque (216×30 and 200×168); an opaque panel only consumes events inside its own bounds. |
| The in-host self-test | It proves the views construct. `JLayeredPane` with an overridden `doLayout` is new construction surface. | Run it; it will catch a null `zoomCluster` or `minimapPanel` at first layout. |
| `StartPanel` constructor | Gains a fourth `Runnable`. | Update the one call site in `initialiseOWLView`. |
| Export | Nothing in R4 exports, by construction. | Re-run `CanvasDesignProofTest` anyway to prove nothing in R4 leaked into a cell style. |

---

## 7. Not worth doing, and not possible

### Dropped from the reviewers' proposals

| proposal | why dropped |
|---|---|
| **L9** — width every new node to its label | Changes default geometry, which the sidecar persists and which the grid pitch, ring pitch and drop pitch are all derived from, at risk 3 — to fix an overflow that R2.6 and R2.7 fix at risk 1 with no persisted-state consequence. |
| **L5's "leave `disableEdgeStyle` true"** | Refuted by my own measurement: routed and unrouted give identical bounds and identical PNG sizes on both test boards. See §6 evidence. |
| **N2's `textOpacity=55`** | 3.87:1 composited — under the text floor. C6's `fontColor` is the correct fix. N2's per-cell `shadow=0` becomes redundant once R2.2 makes the shadow translucent. |
| **N8** — recolour DATATYPE and LITERAL only | Subsumed by R2.4, which resolves the same collision with measured ratios across all six kinds. |
| **E9** — `align=left` + `spacingLeft` on edge labels | Moves the chip off a *vertical* wire and merely slides it along a *horizontal* one. Once R2.8 makes the chip opaque and bordered, the remaining gain does not justify a setting that helps half the edges and does nothing for the other half. |
| **E12** — post-process the exported SVG DOM to hollow the marker paths | Risk 4 for impact 2. It identifies markers by "a closed path whose fill equals its stroke, in one of three hex colours" — a heuristic over generated markup, in the export path, that breaks silently the day a stroke colour changes. Accept that the SVG shows a filled generalisation head and say so in the legend's own text. |
| **D11** — hand-painted alignment guides | Risk 4. It requires subclassing `mxGraphHandler` and reading its undocumented `protected transient previewBounds` and `cells` fields from an anonymous subclass, in the drag path everything else depends on. R3.3's 20px visible grid recovers most of the alignment benefit at risk 1. Revisit only if the grid proves insufficient in use. |
| **TB-10** — stamp the legend onto the board as real cells | The only piece of screen furniture that *could* export, and genuinely valuable — but it needs a new annotation kind in the JSON sidecar, new rendering in `SchemaGraph`, and its own carve-out from "nothing may be drawn that no axiom backs". That is a feature with a spec of its own, not a design fix. Park it. |
| **A fifth `Algorithm`, "Tree (left to right)"** | Breaks `assertEquals(4, Algorithm.values().length)` and adds a permanent menu item for a rare need. Fit-after-Arrange (R1.6) addresses the case it was aimed at. |
| **Seeding `mxHierarchicalLayout` with an explicit roots list** | `execute(Object, List)` exists, but `initialRank` ranks by following edges source → target and `Thing` is a *sink*, so seeding it strands the traversal and the rest is laid out as separate side-by-side hierarchies. Measured at 6200×263 against 4192×399 for doing nothing. R1.1's orientation is the fix. |
| **`mxCompactTreeLayout`** | Right shape of algorithm, wrong shape of graph. Its DFS follows whatever edges it finds, and this canvas carries object-property, data-property, type and inferred edges alongside `subClassOf`. On the representative board it produced one 2560px chain: Pizza → Named pizza → Pizza topping → Country → Italy → xsd:integer. It is safe only when the projection happens to be a pure subclass forest, which the code cannot promise. |

### Not possible in JGraphX 4.2.2

| want | evidence |
|---|---|
| **`STYLE_CURVED` / curved edges as a style flag** | `javap -p -constants com.mxgraph.util.mxConstants \| grep -ic CURVED` → **0**. The only curve constant is `SHAPE_CURVE = "curve"`, a shape name. Setting it *does* work — `mxGraphics2DCanvas` registers `mxCurveShape` — and is clearly worse for a schema: the splines wander through node interiors and arrowheads arrive at arbitrary angles. R1.1 + `LINE_ARCSIZE = 20` is the readable version of the same intent. |
| **A blurred, Miro-style card shadow** | The shadow is a hard offset copy of the same path in one flat colour. `javap -p -c mxGraphics2DCanvas` shows `SHADOW_OFFSETX/Y` → `translate` → `fill(Shape)` with no filter and no convolve. The only knobs are two ints, a `Color` (so alpha works) and a `String` for SVG. `STENCIL_SHADOW_OPACITY` applies to `mxStencil`, not to basic shapes. A real soft shadow would have to be painted in a component overlay, and an overlay is absent from every export. |
| **Alpha on the SVG shadow** | `mxSvgCanvas` writes `mxConstants.W3C_SHADOWCOLOR` as both fill and stroke with no opacity attribute. R2.2's pre-composited `#D7D8DD` is the closest equivalent. |
| **The hollow generalisation head in the SVG export** | `grep -rl endFill` over the extracted jar returns `mxMarkerRegistry$1`, `$3`, `$4` and `mxConstants` — **`mxSvgCanvas` is absent**. It builds its own path element and emits the triangle with `fill` set to the stroke colour whatever the style says. The Graphics2D path (screen and PNG) is a different implementation and does honour it. |
| **A pill / stadium property node that survives SVG export** | `javap -c mxSvgCanvas` never reads `STYLE_ARCSIZE`; it rounds at `RECTANGLE_ROUNDING_FACTOR` (0.15) of the smaller side — two references, no others. A 160×44 node at `arcSize=50` exports as `rx="6.6"`. |
| **`SHAPE_DOUBLE_RECTANGLE` as the individual's silhouette** | `mxGraphics2DCanvas` registers `"doubleRectangle"`; `mxSvgCanvas`'s shape-name branches are actor, cloud, cylinder, doubleEllipse, ellipse, hexagon, image, label, line, rhombus, swimlane, triangle — no `doubleRectangle`, so the distinguishing inner border vanishes in the SVG. |
| **An underlined individual name that survives SVG export** | `mxSvgCanvas` writes `font-decoration="underline"`. That attribute does not exist in SVG or CSS — the real one is `text-decoration` — so browsers, Inkscape and Illustrator ignore it. By contrast `font-style="italic"` and `font-weight="bold"` are emitted correctly and do export. R2.7's lilac fill is what carries the individual in exports. |
| **Fade an imported node's fill without fading its stroke** | `STYLE_FILL_OPACITY` and `STYLE_STROKE_OPACITY` exist, but scanning every class in the jar for `getstatic` references: only `mxSvgCanvas` and `mxVmlCanvas` read them. `mxGraphics2DCanvas` — which backs both the live Swing view and the `mxCellRenderer` PNG export — reads `STYLE_OPACITY` and `STYLE_TEXT_OPACITY` only. Setting `fillOpacity` would work in an SVG and silently do nothing everywhere the user actually works, which is worse than not setting it. |
| **`STYLE_LABEL_POSITION` / `STYLE_VERTICAL_LABEL_POSITION` on edges** | Both keys exist and are read in exactly one place, `mxGraphView.updateVertexLabelOffset`, called only from `updateVertexState`. Edge labels never go through it. Measured: an edge styled `verticalLabelPosition=top;labelPosition=left` gives byte-identical label bounds to one with neither key. `STYLE_ALIGN` / `STYLE_VERTICAL_ALIGN` / `STYLE_SPACING_*` *are* read for edges, via `mxUtils.getScaledLabelBounds`. |
| **A per-edge corner radius** | The rounded-polyline painter takes its radius from the global static `mxConstants.LINE_ARCSIZE` and never consults the cell style; `mxConnectorShape` reads `STYLE_ROUNDED` but never `STYLE_ARCSIZE`. One global radius for the whole board is all there is. |
| **A left accent bar on a node** | No per-side border styling anywhere: `mxConstants` exposes `STYLE_STROKECOLOR` and `STYLE_STROKEWIDTH` and nothing per-edge, and the shape vocabulary is a closed list of seventeen names, none of which paints an accent. The nearest real options are a `STYLE_GRADIENTCOLOR` + `STYLE_GRADIENT_DIRECTION="west"` wash (read by `mxGraphics2DCanvas.createFillPaint`, and it exports) or a custom `mxIShape` via `mxGraphics2DCanvas.putShape` — which is new drawing code plus a matching `mxSvgCanvas` path. R2.3's one-line restriction gets most of the benefit. |
| **Per-edge routing lanes / jetty size** | The Java port exposes no equivalent of the JavaScript `jettySize`. `mxEdgeStyle.orthBuffer` is a package static (10.0), and `mxCoordinateAssignment`'s `minEdgeJetty`, `prefHozEdgeSep`, `prefVertEdgeOff` and `channelBuffer` are protected with no setters — and `mxHierarchicalLayout.placementStage` constructs it inline forwarding only six values. `setParallelEdgeSpacing` widens edges sharing a source/target pair; nothing gives lane assignment for many-to-one fan-in. |
| **A 16:9 aspect ratio out of `mxHierarchicalLayout`** | Twelve protected fields, no `setMaxWidth`, no `setAspectRatio`, no wrap. The width is `(widest rank) × (node width + intraCellSpacing)` and nothing more. Measured sweep on the 31-node tree: `{30,50}` = 4192×399 (r 10.5), `{24,120}` = 4066×609 (r 6.7), `{20,140}` = 3982×669 (r 5.95). R1.6 (fit after Arrange) is the answer, not tuning. |
| **A wrapping or chevron-overflow `JToolBar`** | `javap javax.swing.JToolBar` lists only `isFloatable`/`setFloatable`. `BoxLayout` lays overflowing children out past the container edge, painted nowhere and clickable never. `FlowLayout` wraps visually but its `preferredLayoutSize` always reports one row, so inside `BorderLayout.NORTH` the wrapped rows get no height and are clipped vertically instead — a different symptom, the same loss. A hand-written `WrapLayout` would also make the toolbar's height change as the window resizes, shifting the canvas under the pointer. R4.2 cuts the width instead. |
| **Unicode glyph icons** | `Font.canDisplay` under Java 11 on Windows 11: Tahoma (`Button.font` under the Windows L&F) and Segoe UI (`MenuItem.font`) each display only U+2261, U+25CF, U+25A0 and U+2192. Both are **missing** U+2295, U+229E, U+2922, U+29C9, U+21BA, U+2699, U+1F50D, U+2318, U+2704, U+2B07, U+2B1A, U+2B24, U+22EF, U+25A6, U+2750, U+2937 — and U+25BE, the dropdown triangle. The logical fonts Dialog and SansSerif (Metal, Nimbus) display all of them, which is exactly what makes the failure invisible during development and visible as tofu on a user's machine. The only safe text glyphs are U+2212, U+00D7 and U+2026. |
| **`mxGraphComponent.setGridEnabled` / `isGridEnabled` / `setGridSize`** | Do not exist. The component owns `gridVisible`, `gridColor`, `gridStyle`, `isGridEnabledEvent`, `snapScaledPoint`, `paintGrid`; `mxGraph` owns `gridSize`, `gridEnabled`, `snap(double)`. Code calling them on the component would not compile. |
| **Guides while dragging** | `unzip -l jgraphx-4.2.2.jar \| grep -i guide` → no matches. `mxGuide` exists in the JavaScript mxGraph and was never ported. The swing handler package is `mxCellHandler, mxCellMarker, mxConnectionHandler, mxEdgeHandler, mxElbowEdgeHandler, mxGraphHandler, mxGraphTransferHandler, mxKeyboardHandler, mxMovePreview, mxPanningHandler, mxRotationHandler, mxRubberband, mxVertexHandler`. |
| **Eight colour-blind-safe namespace hues** | A fact about dichromatic vision, not about JGraphX. The canonical Okabe-Ito set darkened until every entry clears 3:1 on white still gives a worst pair at deltaE 1.7 under deuteranopia; three other eight-colour candidates were all below deltaE 3. Once the space collapses to one chromatic axis plus lightness, eight hues cannot be separated. R3.10's five, spread 3.9:1 to 9.1:1 in contrast, give a worst pair of 18.6. |
| **A canvas that follows Protégé 5.6's dark theme** | Achievable but not minimal, and self-defeating: every fill, ink, edge stroke, label backing, shadow, grid colour and all fourteen `LegendPanel` swatches are absolute values installed once into `mxStylesheet` with no re-install path. Flipping them needs a second stylesheet, a swap at render time, and — because the export must stay light for publication — a light override at export time too. That is a theming engine. R4.8's deliberate light canvas with a theme-aware border is the honest answer. |
| **An export that looks like the application** | `CanvasExport` calls `mxCellRenderer.createBufferedImage` and `createSvgDocument`, neither of which takes the `mxGraphComponent`. Everything painted by Swing or by a paint hook is structurally absent: the toolbar, the legend, the status bar, the minimap, the grid, the peer cursors and the note badge. The only way to make furniture export is to stop it being furniture — which is what TB-10 proposed and why it is parked rather than refused. |

---

## 8. Evidence

### 8.1 The measurement that settled R1

A standalone harness built against `target/classes` and `jgraphx-4.2.2.jar` (no project source was modified; it lives in the session scratchpad at `C:\Users\eno\AppData\Local\Temp\claude\c--Users-eno-Documents-Git-repo-ebrahimnorouzi-github-ISE-FIZKarlsruhe-ontoboard\a51d30c0-3579-4c04-b769-11f603bde3c8\scratchpad\probe\`), rendering through the same `mxCellRenderer` path `CanvasDesignProofTest` uses:

```
--- representative board ---
today NORTH 30/50 noRoute   vertexBBox=1605x399  png=1644x400  overshoot=2%x0%  style=obSubClass;noEdgeStyle=1;orthogonal=1
SOUTH 24/120 noRoute        vertexBBox=1943x609  png=1976x610  overshoot=2%x0%  style=obSubClass;noEdgeStyle=1;orthogonal=1
SOUTH 24/120 ROUTED         vertexBBox=1943x609  png=1976x610  overshoot=2%x0%  style=obSubClass
NORTH 30/50 ROUTED          vertexBBox=1605x399  png=1644x400  overshoot=2%x0%  style=obSubClass
--- 31-node tree ---
today NORTH 30/50 noRoute   vertexBBox=3808x399  png=3811x403  overshoot=0%x1%
SOUTH 24/120 noRoute        vertexBBox=3694x609  png=3697x613  overshoot=0%x1%
SOUTH 24/120 ROUTED         vertexBBox=3694x609  png=3697x613  overshoot=0%x1%
```

Routing costs **nothing** in bounds and removes the permanent `noEdgeStyle=1` style-string pollution. The renders `out/r-routed.png` and `out/t-routed.png` show clean right-angled trunks in place of the diagonal spaghetti.

Full R1 pipeline (SOUTH + 24/120 + routing + restore annotations + park + normalise + edge-label layout), with a sticky note and a frame present:

```
bbox=(40,40)-(856,1849)  ratio=0.45  png=850x1810   note.x 300 -> 56
Pizza.y=220  Margherita.y=586        (root above leaves)
```

Nothing threw; `mxEdgeLabelLayout` ran cleanly after `revalidate()`; the annotations moved with the board rather than being left behind.

Organic, three sizes:

```
mxOrganicLayout     n=30  origin=(3060,40)  size=821x845     17ms
mxFastOrganicLayout n=30  origin=(39,39)    size=1910x1960   15ms
mxOrganicLayout     n=120 origin=(4977,40)  size=12198x4806  75ms
mxFastOrganicLayout n=120 origin=(39,39)    size=4520x3260   30ms
mxOrganicLayout     n=300 origin=(7835,40)  size=41334x9819  203ms
mxFastOrganicLayout n=300 origin=(39,39)    size=8230x3510   92ms
```

### 8.2 API confirmations (`javap` against `jgraphx-4.2.2.jar`)

- `mxHierarchicalLayout(mxGraph, int)`; `setOrientation`, `setIntraCellSpacing`, `setInterRankCellSpacing`, `setInterHierarchySpacing`, `setParallelEdgeSpacing`, `setDisableEdgeStyle`. Constructor defaults read from bytecode: intra 30, inter 50, interHierarchy 60, parallelEdge 10, `orientation = iconst_1` (NORTH), `disableEdgeStyle = iconst_1`, `fineTuning = iconst_1`.
- `com/mxgraph/layout/mxFastOrganicLayout.class` present, `extends mxGraphLayout`; `setForceConstant(double)`, `setMinDistanceLimit(double)`, `setInitialTemp(double)`, `setMaxIterations(double)`, `setDisableEdgeStyle(boolean)`.
- `mxCircleLayout.circle(Object[], double, double, double)` is public.
- `mxEdgeLabelLayout(mxGraph)`, `execute(Object)`. `grep -rl setEdgeStyleEnabled` over the extracted jar returns `mxGraphHierarchyModel`, `mxCircleLayout`, `mxCompactTreeLayout`, `mxFastOrganicLayout`, `mxGraphLayout`, `mxOrganicLayout` — **`mxEdgeLabelLayout` is absent**, so it cannot undo the routing fix.
- `mxGeometry.translate(double, double)` and `public static transient boolean TRANSLATE_CONTROL_POINTS`; `mxGraphModel.getChildren(mxIGraphModel, Object)`; `mxIGraphModel.getEdgeCount(Object)`.
- `mxConstants`: `STYLE_TEXT_OPACITY`, `STYLE_FILL_OPACITY`, `STYLE_STROKE_OPACITY`, `STYLE_ENDSIZE`, `STYLE_ENDFILL`, `STYLE_STARTARROW`, `STYLE_STARTSIZE`, `STYLE_STARTFILL`, `STYLE_ARCSIZE`, `STYLE_SPACING_LEFT/RIGHT`, `STYLE_LABEL_BORDERCOLOR`, `STYLE_TARGET_PERIMETER_SPACING`, `LINE_ARCSIZE`, `SHADOW_OFFSETX/Y`, `W3C_SHADOWCOLOR`, `DEFAULT_MARKERSIZE`; `ARROW_CLASSIC/BLOCK/OPEN/OVAL/DIAMOND`; `FONT_BOLD=1`, `FONT_ITALIC=2`, `FONT_UNDERLINE=4`. **No `STYLE_CURVED`.**
- `mxSwingConstants`: all twelve `Color` fields and three `Stroke` fields are non-final public statics.
- `mxGraphics2DCanvas` reads `STYLE_OPACITY`, `STYLE_TEXT_OPACITY`, `STYLE_LABEL_BORDERCOLOR`, `SHADOW_OFFSETX/Y`, `mxSwingConstants.SHADOW_COLOR` — and **not** `STYLE_FILL_OPACITY` or `STYLE_STROKE_OPACITY`, which only `mxSvgCanvas` and `mxVmlCanvas` read.
- `mxSvgCanvas` reads `LINE_ARCSIZE`, `W3C_SHADOWCOLOR`, `STYLE_OPACITY`, `STYLE_TEXT_OPACITY`, `STYLE_FILL_OPACITY`, `STYLE_STROKE_OPACITY` — and **not** `STYLE_ARCSIZE` (it uses `RECTANGLE_ROUNDING_FACTOR`, two references).
- `mxGraphComponent`: `zoomIn`, `zoomOut`, `zoomActual`, `zoomTo(double, boolean)`, `getZoomFactor`, `setCenterZoom`, `getViewport`, `getGraphControl`, `getCellAt`, `getTolerance` (default `iconst_4`), `createGraphHandler`, `selectCellsForEvent`, `isPanningEvent`, `isToggleEvent`, `isForceMarqueeEvent`, `isGridEnabledEvent`, `setGridVisible/Color/Style`, `GRID_STYLE_DOT/CROSS/LINE/DASHED`, `setPageBackgroundColor`, `setEnterStopsCellEditing`, `setEscapeEnabled`, `setDragEnabled`. **No `setGridSize`, no `setGridEnabled`.** `zoom(double)` is guarded only by `> 0.04d` — no upper bound.
- `mxGraph`: `setCellsCloneable`, `setGridSize`, `setGridEnabled`, `setResetEdgesOnMove`, `setResetEdgesOnResize`, `selectCells(boolean, boolean)`, `isCellEditable`, `setCellsEditable`, `cellLabelChanged`, `moveCells`, `getChildVertices`, `getSelectionCells`, `setSelectionCells`, `snap(double)`.
- `mxRubberband.mousePressed` bytecode: `isConsumed` → `isEnabled` → `isRubberbandTrigger` → `isPopupTrigger` → `start(getPoint())`. **No `getCellAt`.** `isRubberbandTrigger` is `iconst_1; ireturn`.
- `mxGraphHandler` constructor: `livePreview = iconst_0`, `imagePreview = iconst_1`. Fields `protected transient java.awt.Rectangle previewBounds` and `protected transient Object[] cells`; `public void paint(Graphics)`, `isVisible()`, `getGraphComponent()`.
- `mxGraphOutline`: `setAntiAlias(boolean)`, `setDrawLabels(boolean)`, `setFitPage(boolean)`.
- `mxEvent`: `MOVE_CELLS = "moveCells"`, `CELLS_MOVED = "cellsMoved"`, `LABEL_CHANGED = "labelChanged"`.
- `mxUtils.getLabelSize(String, Map, boolean, double)`, `getSizeForString(String, Font, double)`, `wordWrap(String, FontMetrics, double)`.
- `grep -rl endFill` over the extracted jar: `mxMarkerRegistry$1`, `$3`, `$4`, `mxConstants` only.

### 8.3 Font probe (Java 11, headless, Windows 11)

```
new Font("Segoe UI, Helvetica Neue, Arial, sans-serif", BOLD, 13).getFamily()  ->  "Dialog"
new Font("Segoe UI", BOLD, 13).getFamily()                                     ->  "Segoe UI"
getAvailableFontFamilyNames() under -Djava.awt.headless=true                    ->  223 families
  Segoe UI = true   Inter = false   Helvetica Neue = false   Roboto = false
  DejaVu Sans = false   Liberation Sans = false   Arial = true
```

R2.1's resolution order therefore yields `Segoe UI` here, `Helvetica Neue` on macOS, `DejaVu Sans` or `Liberation Sans` on Linux, and the always-present logical `SansSerif` as the last resort — and it works headlessly, so the design-proof test renders in the same face the canvas uses.