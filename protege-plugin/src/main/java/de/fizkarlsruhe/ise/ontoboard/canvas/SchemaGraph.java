package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGraphModel;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Renders a {@link Projection}. Holds no ontology state of its own. */
public class SchemaGraph extends mxGraph {

    private static final double DEFAULT_W = 160;
    private static final double DEFAULT_H = 40;

    /** As wide as a box gets to fit its label on one line; past this it wraps and grows down. */
    private static final double WIDEST = 320;

    /**
     * More relations than a board can draw as lines and still be read. Measured on two published
     * ontologies with every term shown: 528 and 1,139 edges, and the relations between classes,
     * drawn at full strength, were a mesh across the whole board.
     */
    static final int DENSE = 150;

    /**
     * How strongly what is not in focus is drawn, in percent.
     *
     * <p>50 since 1.135.1, and text at {@link #FADED_TEXT}. At 22 a tester measured faded text at
     * a contrast of 1.58:1, and New subclass selects the class it makes - so the class the guide
     * named next was the hardest thing on the board to read.
     */
    static final int FADED = 50;

    /** The least a label is drawn at while faded, in percent, so that it can still be read. */
    static final int FADED_TEXT = 60;

    /** How strongly a relation on a dense board is drawn until one of its ends is in focus. */
    static final int QUIET = 30;

    /**
     * Longer than this, an edge on a dense board is drawn quietly whatever it is: a second
     * superclass far away, a subtree's way back to the block its parent is in. The structure is
     * in the short ones.
     */
    static final double LONG = 1600;

    /** The terms selected; empty when nothing is. */
    private final Set<String> focused = new HashSet<String>();

    /** The selected terms and everything an edge joins them to. */
    private final Set<String> lit = new HashSet<String>();

    private boolean dense;

    private final Map<String, Object> cellsById = new HashMap<>();

    public SchemaGraph() {
        SchemaStyles.install(this);
        setCellsResizable(true);
        setAllowDanglingEdges(false);
        setAllowLoops(false);
        setCellsDisconnectable(false);
        // A node is a term, and there is exactly one node per IRI. mxGraph starts with
        // cellsCloneable = true and mxGraphComponent with dragEnabled = true, so a Ctrl+drag ran
        // Swing's DnD copy path: mxGraphTransferHandler -> graph.cloneCells -> mxCell.clone, which
        // copies value, style, geometry AND the id. The dropped clone was a second vertex whose id
        // *was* the original IRI - clicking it pushed that term to Protege's selection, and Delete
        // on it reported one term removed while taking the original out of membership. Nothing ever
        // wrote it to the layout, so it vanished at the next refresh. One unheld default, and the
        // projection invariant is gone. Duplicating board furniture is Ctrl+D; duplicating an axiom
        // is not a gesture.
        setCellsCloneable(false);
        // Lines behind boxes: on a board of any size a relation passes other terms on its way,
        // and drawn on top it ran through their labels.
        setKeepEdgesInBackground(true);
        // Snapping has been on since the first version and invisible with it: mxGraph defaults
        // gridSize to 10 and gridEnabled to true, and mxGraphHandler already snaps every drag delta.
        // Ten pixels gives sixteen candidate columns across a 160px node, which is not alignment.
        // This is the snap step as well as the drawn pitch, so a board saved at odd coordinates
        // moves once on its next drag; no stored geometry is rewritten until then.
        setGridSize(20);
        setEdgeLabelsMovable(false);
        setCellsMovable(true);
        // Both default to false in mxGraph. With the hierarchical layout's routing turned on
        // (CanvasLayouts) every edge carries absolute control points, so a dragged node otherwise
        // keeps the channel the layout gave it and its wire doubles back on itself. resetEdge nulls
        // those points and the router runs again.
        //
        // The trade-off, stated rather than hidden: a bend somebody added by hand is lost when
        // either end moves. That is already true across a reload, because CanvasLayout persists node
        // geometry and has never persisted an edge waypoint.
        setResetEdgesOnMove(true);
        setResetEdgesOnResize(true);
        // Label editing arrives in Task 3; enabling it now would let a user rename a
        // cell's visible text without touching the ontology, which would be a lie.
        // Editable, but only for furniture - see isCellEditable. It was false outright, which is
        // the safe answer to the wrong question: double-clicking a sticky note to change its words
        // is the most ordinary gesture on a board, and the reason to refuse was never notes.
        setCellsEditable(true);
        setDropEnabled(false);
        setSplitEnabled(false);
        // Counted so that a line a selection shows is not drawn along a route worked out for boxes
        // that stood elsewhere: see routeOf.
        getModel().addListener(com.mxgraph.util.mxEvent.EXECUTE, (sender, event) -> {
            Object change = event.getProperty("change");
            if (change instanceof mxGraphModel.mxGeometryChange && getModel().isVertex(
                    ((mxGraphModel.mxGeometryChange) change).getCell())
                    || change instanceof mxGraphModel.mxChildChange && getModel().isVertex(
                            ((mxGraphModel.mxChildChange) change).getChild())) {
                boxChanges++;
            }
        });
    }

    /**
     * Draws the projection, changing only what changed.
     *
     * <p>It used to remove every cell and insert them all again: on a board of 660 terms, 1.2
     * seconds after every edit anywhere in Protege, with every route recomputed and every cell's
     * identity lost. Cells are kept by id now. A term, line, frame or note still wanted keeps its
     * cell and has its label, style and box updated where they differ; only what is new is
     * inserted, only what is gone is removed. A box nothing has placed keeps the place it was
     * given, where it used to be given a new one on every drawing.
     */
    public void render(Projection projection, CanvasLayout layout) {
        PrefixColours colours = new PrefixColours(layout.prefixColors);
        boolean wasDense = dense;
        int drawn = 0;
        for (CanvasEdge edge : projection.getEdges()) {
            // What is hidden until in focus is not what makes a board a mesh.
            if (edge.getKind() != CanvasEdge.Kind.DISJOINT
                    && edge.getKind() != CanvasEdge.Kind.DOMAIN_RANGE
                    && edge.getKind() != CanvasEdge.Kind.RULE_PART) {
                drawn++;
            }
        }
        dense = drawn > DENSE;
        java.util.List<Object> moved = new java.util.ArrayList<Object>();
        boolean insertedBox = false;
        getModel().beginUpdate();
        try {
            tooltipsById.clear();
            notedIds.clear();

            // Node labels, so an edge's tooltip can name its two ends the way they are drawn
            // rather than by IRI.
            Map<String, String> labels = new HashMap<String, String>();
            for (CanvasNode node : projection.getNodes()) {
                labels.put(node.getId(), node.getLabel());
            }

            // WHAT IS WANTED, so that what is not can go first: lines before boxes, because
            // removing a box takes its lines with it and the map would go on pointing at them.
            Set<String> boxes = new HashSet<String>();
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (frame != null && frame.id != null) {
                    boxes.add(frame.id);
                }
            }
            for (CanvasNode node : projection.getNodes()) {
                boxes.add(node.getId());
            }
            for (CanvasLayout.NoteLayout note : layout.notes) {
                if (note != null && note.id != null) {
                    boxes.add(note.id);
                }
            }
            Set<String> lines = new HashSet<String>();
            for (CanvasEdge edge : projection.getEdges()) {
                if (boxes.contains(edge.getSourceId()) && boxes.contains(edge.getTargetId())) {
                    lines.add(edge.getId());
                }
            }
            java.util.List<Object> goneLines = new java.util.ArrayList<Object>();
            java.util.List<Object> goneBoxes = new java.util.ArrayList<Object>();
            for (java.util.Iterator<Map.Entry<String, Object>> each =
                    cellsById.entrySet().iterator(); each.hasNext();) {
                Map.Entry<String, Object> entry = each.next();
                Object cell = entry.getValue();
                boolean line = getModel().isEdge(cell);
                if (!getModel().contains(cell)) {
                    each.remove();
                } else if (!(line ? lines : boxes).contains(entry.getKey())) {
                    (line ? goneLines : goneBoxes).add(cell);
                    each.remove();
                }
            }
            removeCells(goneLines.toArray(), false);
            removeCells(goneBoxes.toArray(), true);
            for (java.util.Iterator<Map.Entry<String, Object>> each =
                    cellsById.entrySet().iterator(); each.hasNext();) {
                if (!getModel().contains(each.next().getValue())) {
                    each.remove();
                }
            }

            // Frames behind everything else - mxGraph paints in model order, and a frame drawn
            // after the boxes in it would cover them. In the order the board keeps them, so a
            // frame drawn inside another stays above it and can still be clicked.
            java.util.List<Object> frameCells = new java.util.ArrayList<Object>();
            boolean insertedFrame = false;
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (frame == null || frame.id == null) {
                    continue;
                }
                String style = SchemaStyles.FRAME + ";strokeColor="
                        + (frame.stroke == null ? "#2D6FBF" : frame.stroke);
                String label = frame.label == null ? "" : frame.label;
                double w = frame.w <= 0 ? 320 : frame.w;
                double h = frame.h <= 0 ? 220 : frame.h;
                Object cell = cellsById.get(frame.id);
                if (cell == null) {
                    cell = insertVertex(getDefaultParent(), frame.id, label, frame.x, frame.y,
                            w, h, style);
                    cellsById.put(frame.id, cell);
                    insertedFrame = true;
                } else if (update(cell, label, style, frame.x, frame.y, w, h)) {
                    moved.add(cell);
                }
                frameCells.add(cell);
                tooltipsById.put(frame.id, CanvasTooltips.forFrame(frame.label));
            }
            if (insertedFrame) {
                // One call for all of them: sending cells to the back keeps the order they are
                // given in, where one frame at a time reversed it.
                orderCells(true, frameCells.toArray());
            }

            // EVERY BOX ALREADY SPOKEN FOR, so a term with no position goes somewhere free: the
            // frames, the stored positions, and the boxes already on the board. Frames count - a
            // box that is not in a frame has no business inside one, and on Windows a pattern's
            // frame came back with boxes piled on it when frames were free space.
            java.util.List<java.awt.Rectangle> taken = new java.util.ArrayList<java.awt.Rectangle>();
            for (CanvasLayout.FrameLayout frame : layout.frames) {
                if (frame != null && frame.id != null) {
                    taken.add(new java.awt.Rectangle((int) frame.x, (int) frame.y,
                            (int) (frame.w <= 0 ? 320 : frame.w),
                            (int) (frame.h <= 0 ? 220 : frame.h)));
                }
            }
            for (CanvasNode node : projection.getNodes()) {
                CanvasLayout.NodeLayout stored = layout.nodes.get(node.getId());
                Object cell = cellsById.get(node.getId());
                if (stored != null) {
                    taken.add(new java.awt.Rectangle((int) stored.x, (int) stored.y,
                            (int) (stored.w <= 0 ? DEFAULT_W : stored.w),
                            (int) (stored.h <= 0 ? DEFAULT_H : stored.h)));
                } else if (cell != null && getModel().getGeometry(cell) != null) {
                    com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
                    taken.add(new java.awt.Rectangle((int) at.getX(), (int) at.getY(),
                            (int) at.getWidth(), (int) at.getHeight()));
                }
            }
            for (CanvasNode node : projection.getNodes()) {
                CanvasLayout.NodeLayout stored = layout.nodes.get(node.getId());
                String label = de.fizkarlsruhe.ise.ontoboard.model.Curies.displayLabel(
                        node.getCurie(), node.getLabel(), node.getId());
                String style = styleFor(node, colours);
                Object cell = cellsById.get(node.getId());
                if (cell != null) {
                    com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
                    double w = stored != null && stored.w > 0 ? stored.w : at.getWidth();
                    double h = stored != null && stored.h > 0 ? stored.h : at.getHeight();
                    if (!label.equals(getModel().getValue(cell))) {
                        // A new label - renamed, or a folded class's count - gets a box it fits,
                        // never smaller than one somebody sized by hand.
                        double[] fits = fittedSize(label, style);
                        w = Math.max(w, fits[0]);
                        h = Math.max(h, fits[1]);
                    }
                    boolean changed = stored == null
                            ? update(cell, label, style, at.getX(), at.getY(), w, h)
                            : update(cell, label, style, stored.x, stored.y, w, h);
                    if (changed) {
                        moved.add(cell);
                    }
                } else {
                    double[] fitted = stored != null && stored.w > 0 && stored.h > 0 ? null
                            : fittedSize(label, style);
                    double w = fitted == null ? stored.w : fitted[0];
                    double h = fitted == null ? stored.h : fitted[1];
                    double x;
                    double y;
                    if (stored != null) {
                        x = stored.x;
                        y = stored.y;
                    } else {
                        java.awt.Point free = CanvasPlacement.freeSlot(taken, (int) w, (int) h);
                        x = free.x;
                        y = free.y;
                        // Claimed before the next one is asked for, or they would all be offered
                        // the same slot and stack.
                        taken.add(new java.awt.Rectangle(free.x, free.y, (int) w, (int) h));
                    }
                    cell = insertVertex(getDefaultParent(), node.getId(), label, x, y, w, h, style);
                    cellsById.put(node.getId(), cell);
                    insertedBox = true;
                }
                tooltipsById.put(node.getId(), CanvasTooltips.forNode(node));
                if (node.hasNote()) {
                    notedIds.add(node.getId());
                }
            }

            for (CanvasEdge edge : projection.getEdges()) {
                Object source = cellsById.get(edge.getSourceId());
                Object target = cellsById.get(edge.getTargetId());
                if (source == null || target == null) {
                    continue; // spec section 5.1: both endpoints must be on the canvas
                }
                String style = styleFor(edge);
                Object cell = cellsById.get(edge.getId());
                if (cell != null && (getModel().getTerminal(cell, true) != source
                        || getModel().getTerminal(cell, false) != target)) {
                    removeCells(new Object[] {cell}, false);
                    cellsById.remove(edge.getId());
                    cell = null;
                }
                if (cell == null) {
                    cell = insertEdge(getDefaultParent(), edge.getId(), edge.getLabel(), source,
                            target, style);
                    cellsById.put(edge.getId(), cell);
                } else {
                    if (!edge.getLabel().equals(getModel().getValue(cell))) {
                        getModel().setValue(cell, edge.getLabel());
                    }
                    if (!style.equals(getModel().getStyle(cell))) {
                        getModel().setStyle(cell, style);
                    }
                }
                tooltipsById.put(edge.getId(), CanvasTooltips.forEdge(edge, labels));
            }

            // Sticky notes on top, so they are never hidden behind a node - the whole point of
            // one is that somebody reads it.
            java.util.List<Object> notes = new java.util.ArrayList<Object>();
            for (CanvasLayout.NoteLayout note : layout.notes) {
                if (note == null || note.id == null) {
                    continue;
                }
                String style = SchemaStyles.STICKY_NOTE + ";fillColor="
                        + (note.color == null ? "#FFF3B0" : note.color);
                String text = note.text == null ? "" : note.text;
                double w = note.w <= 0 ? 180 : note.w;
                double h = note.h <= 0 ? 120 : note.h;
                Object cell = cellsById.get(note.id);
                if (cell == null) {
                    cell = insertVertex(getDefaultParent(), note.id, text, note.x, note.y, w, h,
                            style);
                    cellsById.put(note.id, cell);
                    insertedBox = true;
                } else {
                    update(cell, text, style, note.x, note.y, w, h);
                }
                notes.add(cell);
                tooltipsById.put(note.id, CanvasTooltips.forNote(note.text));
            }
            if (insertedBox && !notes.isEmpty()) {
                orderCells(false, notes.toArray());
            }
            // Which domain lines are drawn always is read again from the board as drawn now. They
            // are relations on a dense board - see routeEdges and strengthOf - but not counted:
            // short lines beside their class, they would tip pizza-bfo, 145 lines and 18
            // properties beside their class, over DENSE, every relation then drawn straight.
            drawnAlwaysAt = -1;
        } finally {
            getModel().endUpdate();
        }
        if (dense != wasDense) {
            refresh();
            return;
        }
        // A line's strength on a dense board depends on how long it is, and a style is worked
        // out when a cell is first drawn - so the lines of a box that moved are asked again.
        for (Object box : moved) {
            for (Object line : getEdges(box)) {
                com.mxgraph.view.mxCellState state = getView().getState(line);
                if (state != null) {
                    state.setStyle(getCellStyle(line));
                }
            }
        }
    }

    /**
     * Sets a cell's label, style and box, each only where it differs, so an unchanged cell is
     * not touched at all. Returns whether the box moved or changed size.
     */
    private boolean update(Object cell, String value, String style, double x, double y,
            double w, double h) {
        if (!java.util.Objects.equals(value, getModel().getValue(cell))) {
            getModel().setValue(cell, value);
        }
        if (!java.util.Objects.equals(style, getModel().getStyle(cell))) {
            getModel().setStyle(cell, style);
        }
        com.mxgraph.model.mxGeometry geometry = getModel().getGeometry(cell);
        if (geometry != null && geometry.getX() == x && geometry.getY() == y
                && geometry.getWidth() == w && geometry.getHeight() == h) {
            return false;
        }
        com.mxgraph.model.mxGeometry box = geometry == null
                ? new com.mxgraph.model.mxGeometry(x, y, w, h)
                : (com.mxgraph.model.mxGeometry) geometry.clone();
        box.setX(x);
        box.setY(y);
        box.setWidth(w);
        box.setHeight(h);
        getModel().setGeometry(cell, box);
        return true;
    }

    /**
     * A box its label fits in without a word broken across two lines.
     *
     * <p>Every new box was 160 by 60, so {@code classification:Concept} came out as
     * "classification:Conce" over "pt", and a prefixed name over a two-word label ran out of the
     * bottom of its box. Wider up to {@link #WIDEST}, then taller.
     */
    public double[] fittedSize(String label, String style) {
        Map<String, Object> resolved = getStylesheet().getCellStyle(style,
                getStylesheet().getDefaultVertexStyle());
        java.awt.Font font = mxUtils.getFont(resolved);
        java.awt.FontMetrics metrics = mxUtils.getFontMetrics(font);
        // Generous: JGraphX wraps a few pixels inside the box and measures with fractional
        // metrics where these are whole pixels, so a label measured to fit exactly broke its
        // last character onto a line of its own.
        // The hexagon a property is drawn in keeps 22px on either side for its slanted edges,
        // and counting only the plain spacing put obo:BFO_0000050 on two lines in it.
        double sides = 2 * mxUtils.getDouble(resolved, mxConstants.STYLE_SPACING, 6)
                + mxUtils.getDouble(resolved, mxConstants.STYLE_SPACING_LEFT, 0)
                + mxUtils.getDouble(resolved, mxConstants.STYLE_SPACING_RIGHT, 0);
        double padding = sides + 30;
        String[] lines = (label == null ? "" : label).split("\n");
        double longest = 0;
        for (String line : lines) {
            longest = Math.max(longest, metrics.stringWidth(line) * MEASURED_TO_DRAWN);
        }
        double w = Math.ceil(Math.max(DEFAULT_W, Math.min(WIDEST, longest + padding)) / 20) * 20;
        int rows = 0;
        for (String line : lines) {
            rows += Math.max(1, (int) Math.ceil(
                    metrics.stringWidth(line) * MEASURED_TO_DRAWN / (w - sides - 4)));
        }
        // Text height and a little air, on the board's 20px grid: compact enough that a pattern
        // fits on the screen at a size its text can be read at.
        double h = Math.max(DEFAULT_H, rows * metrics.getHeight() + 6);
        return new double[] {w, Math.ceil(h / 20) * 20};
    }

    /**
     * The hierarchy lines from a frame to somewhere else, drawn quietly until an end is selected:
     * two patterns often share BFO's upper classes, each frame's own hierarchy is what a reader has
     * come for, and these otherwise ran across all of it.
     */
    private final Set<String> betweenFrames = new HashSet<String>();

    /**
     * How much wider JGraphX draws text than these whole-pixel metrics say. At 1.08 a label that
     * measured to fit one line wrapped to two, and three lines ran out of a two-line box.
     */
    private static final double MEASURED_TO_DRAWN = 1.15;

    /** The bends of each routed line, by id, in board coordinates. */
    private final Map<String, java.util.List<double[]>> routes =
            new HashMap<String, java.util.List<double[]>>();

    /** Where each routed line's two ends stood when it was routed: x, y, w, h, x, y, w, h. */
    private final Map<String, double[]> routedEnds = new HashMap<String, double[]>();

    /** How many lines the last routing was asked for. */
    private int linesToRoute;

    /**
     * Whether every line drawn has a route. Not on a board with more than {@link #DENSE} relations,
     * whose relations keep the plain connector, nor where a line found no way or the time ran out:
     * those lines keep it too, and may run through boxes. What a status line can say when it is not.
     */
    public boolean isFullyRouted() {
        return !dense && routes.size() >= linesToRoute;
    }

    /** What the routes were worked out for, so a redraw that changed nothing costs nothing. */
    private String routedFor = "";

    /**
     * How long the last routing had, in nanoseconds, when it ran out of time before the end - less
     * than nothing when it was called already late; {@link Long#MIN_VALUE} when it did not run out.
     */
    private long cutShortAfter = Long.MIN_VALUE;

    /** The route each line of the last routing was given, its label's place with it, by id. */
    private final Map<String, EdgeRouter.Route> routedLines =
            new HashMap<String, EdgeRouter.Route>();

    /** Where the boxes stood when the board was last routed, as {@link #boxesOnTheBoard} says it. */
    private String routedBoxes = "";

    /**
     * The route of each line a selection shows, its label's place with it, by id, and where its two
     * ends stood: kept while the line stays shown and the board's own lines stay as they were
     * routed, so that a line on the screen does not move when the selection changes - see
     * {@link #routeShown}.
     */
    private final Map<String, EdgeRouter.Route> shownLines = new HashMap<String, EdgeRouter.Route>();
    private final Map<String, double[]> shownEnds = new HashMap<String, double[]>();

    /** Lines a selection shows that are not routed yet: not drawn until they are, a moment later. */
    private final Set<String> waiting = new java.util.LinkedHashSet<String>();

    /** Lines a selection shows that have no way round the boxes: drawn by the plain connector. */
    private final Set<String> noWay = new HashSet<String>();

    /** How many times the board's own lines have been routed. */
    private int routings;

    /** Which of those routings the shown lines were routed among, and where the boxes stood then. */
    private int shownAmong = -1;
    private String shownBoxes = "";

    /** How many times a box has moved, changed size, come or gone; see {@link #boxesNow}. */
    private long boxChanges;
    private long boxesAskedAt = -1;
    private String boxesAsked = "";

    /** What a selection shows that one moment did not route, routed a slice at a time. */
    private javax.swing.Timer later;

    /**
     * How long the routing a click starts may hold up the board, in nanoseconds; what is left is
     * routed in slices of {@link #SLICE_TIME}, {@link #BETWEEN_SLICES} ms apart, so that whatever
     * a person does meanwhile is answered first. A review of 1.135.1 measured 2 seconds for a click
     * on a class 19 lines met at, with the board held up throughout.
     */
    static final long CLICK_TIME = 200_000_000L;
    static final long SLICE_TIME = 150_000_000L;
    private static final int BETWEEN_SLICES = 25;

    /**
     * The route of this line while it still describes where its ends are, or null - a box has moved
     * and the line is drawn by the plain connector until {@link #routeEdges} has run again.
     */
    java.util.List<double[]> routeOf(Object edge) {
        String id = getIdForCell(edge);
        java.util.List<double[]> route = id == null ? null : routes.get(id);
        double[] ends = route == null ? null : routedEnds.get(id);
        if (route == null && id != null && shownLines.containsKey(id)) {
            // A line a selection shows, routed among the others when it was shown - while every box
            // stands where it stood then: a box resized or moved since may stand in its way, and its
            // own two ends are not all it was routed round (1.135.1 review).
            if (!boxesNow().equals(shownBoxes)) {
                return null;
            }
            route = shownLines.get(id).bends;
            ends = shownEnds.get(id);
        }
        if (route == null) {
            return null;
        }
        com.mxgraph.model.mxGeometry from = getModel().getGeometry(getModel().getTerminal(edge, true));
        com.mxgraph.model.mxGeometry to = getModel().getGeometry(getModel().getTerminal(edge, false));
        if (from == null || to == null || ends == null || ends[0] != from.getX()
                || ends[1] != from.getY() || ends[2] != from.getWidth()
                || ends[3] != from.getHeight() || ends[4] != to.getX() || ends[5] != to.getY()
                || ends[6] != to.getWidth() || ends[7] != to.getHeight()) {
            return null;
        }
        return route;
    }

    /**
     * Works out where every line on the board runs - see
     * {@link de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter}. Worked out for the board as a whole
     * because whether a line can have a lane of its own depends on the others; nothing about it is
     * saved, and a board with too many lines to route keeps the hierarchy's lanes only.
     */
    public void routeEdges() {
        routeEdges(System.nanoTime() + de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.MOST_TIME);
    }

    /**
     * The same, with no line looked for after {@code deadline}, a {@link System#nanoTime()}: one
     * moment for everything one action does, however often it routes. In 1.135.0 each routing had
     * two seconds of its own, and a single New subclass... routed up to three times on the Swing
     * thread: on a board of 45 classes and 139 lines, 1.3 seconds each time.
     *
     * <p>A board routed in full, or as far as the same time took it, is not routed again until
     * something on it changes; one cut short is, when a later call brings more time.
     */
    /** How much more time a board cut short must be given before it is routed again: 250 ms. */
    private static final long CLEARLY_MORE = 250_000_000L;

    public void routeEdges(long deadline) {
        java.util.List<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box> boxes =
                new java.util.ArrayList<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box>();
        java.util.List<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box> frames =
                new java.util.ArrayList<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box>();
        java.util.List<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Link> links =
                new java.util.ArrayList<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Link>();
        String placed = boxesOnTheBoard(boxes, frames);
        StringBuilder signature = new StringBuilder(placed);
        Object parent = getDefaultParent();
        java.awt.FontMetrics label = labelMetrics();
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            String id = getIdForCell(cell);
            Object from = getModel().getTerminal(cell, true);
            Object to = getModel().getTerminal(cell, false);
            // Not a line that waits for a selection, whether one is made or not: a selection that
            // routed the board again moved the lines already drawn. See routeShown (1.135.1).
            if (id == null || !getModel().isEdge(cell) || from == null || to == null
                    || waitsForFocus(cell)) {
                continue;
            }
            String kind = baseStyleOf(cell);
            boolean hierarchy = isHierarchyLine(kind);
            if (dense && !hierarchy) {
                continue;
            }
            if (hierarchy && !sameFrame(at(from), at(to), frames)) {
                betweenFrames.add(id);
            } else {
                betweenFrames.remove(id);
            }
            links.add(lineOf(cell, id, hierarchy, label));
            signature.append(id).append('|');
        }
        String now = signature.toString();
        long budget = deadline - System.nanoTime();
        // "More time" means clearly more: a review found that every refresh of an unchanged board
        // the router could not finish routed it again for the whole two seconds, because one
        // call's budget came out a few microseconds longer than the last one's.
        if (now.equals(routedFor) && (cutShortAfter == Long.MIN_VALUE
                || budget <= cutShortAfter + CLEARLY_MORE)) {
            if (routeShown(Math.min(deadline, System.nanoTime() + firstLook()))) {
                refresh();
            }
            routeTheRest();
            return;
        }
        routedFor = now;
        routedBoxes = placed;
        routings++;
        routes.clear();
        routedEnds.clear();
        routedLines.clear();
        linesToRoute = links.size();
        Map<String, de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Route> found =
                de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.route(boxes, frames, links, deadline);
        cutShortAfter = found.size() < links.size() && System.nanoTime() > deadline ? budget
                : Long.MIN_VALUE;
        for (Map.Entry<String, de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Route> routed
                : found.entrySet()) {
            Object edge = cellsById.get(routed.getKey());
            if (edge == null) {
                continue;
            }
            com.mxgraph.model.mxGeometry from = getModel().getGeometry(
                    getModel().getTerminal(edge, true));
            com.mxgraph.model.mxGeometry to = getModel().getGeometry(
                    getModel().getTerminal(edge, false));
            routes.put(routed.getKey(), routed.getValue().bends);
            routedLines.put(routed.getKey(), routed.getValue());
            routedEnds.put(routed.getKey(), new double[] {from.getX(), from.getY(), from.getWidth(),
                from.getHeight(), to.getX(), to.getY(), to.getWidth(), to.getHeight()});
            // Where on the line its label sits: -1 at the source to 1 at the target. Directly on
            // the geometry and not through the model, which would be a change to undo.
            com.mxgraph.model.mxGeometry geometry = getModel().getGeometry(edge);
            if (geometry != null) {
                geometry.setX(2 * routed.getValue().labelAt - 1);
                geometry.setY(0);
                geometry.setOffset(null);
            }
        }
        routeShown(Math.min(deadline, System.nanoTime() + firstLook()));
        routeTheRest();
        refresh();
    }

    /**
     * Routes the lines a selection shows - a property's domain and range, a class's disjointness,
     * a rule's parts - among the lines already on the board, which keep their routes and their
     * labels' places: see {@link EdgeRouter#routeAmong}. Returns whether a route changed.
     *
     * <p>Found on the egg-boiling study board (1.135.1): these lines were left out of the routing,
     * and selecting a term did not route again, so a line it showed had no route of its own - the
     * dashed "domain" of has specified input ran from the property section straight up through a
     * frame and sideways through three boxes. Routing the board again with them would have moved
     * the lines already drawn on every click, and a board whose arrows jump when a term is
     * selected reads worse than one whose new lines cut across it.
     *
     * <p>Only on a board routed as it stands: while an arrangement moves the boxes there is
     * nothing to route among, and {@link #routeEdges} routes these at its end. Not on a board of
     * more than {@link #DENSE} lines, which routes the hierarchy only: there these keep the plain
     * connector, as its relations do.
     *
     * <p><b>No jumping, for these lines too</b> (1.135.1 review). Every line a selection showed
     * was routed again on every change of it, shortest first, so a line still on the screen gave
     * its lane to a shorter one shown now: click "has specified output" on the study board, then
     * "completely executed planned process", its domain's other end, and the line was drawn
     * elsewhere, its label 850 px away. Now a line keeps its route and its label's place for as
     * long as it stays shown and the board's own lines stay as they are, and only the lines a
     * change shows anew are routed, round both. What is no longer shown gives its route up.
     *
     * <p>Routes what is waiting until {@code deadline}, the first line always, and leaves the rest
     * waiting: see {@link #routeTheRest}. A waiting line is not drawn.
     */
    private boolean routeShown(long deadline) {
        java.util.List<EdgeRouter.Box> boxes = new java.util.ArrayList<EdgeRouter.Box>();
        java.util.List<EdgeRouter.Box> frames = new java.util.ArrayList<EdgeRouter.Box>();
        if (focused.isEmpty() || dense || !boxesOnTheBoard(boxes, frames).equals(routedBoxes)) {
            boolean had = !shownLines.isEmpty() || !waiting.isEmpty() || !noWay.isEmpty();
            forgetShown();
            return had;
        }
        boolean changed = false;
        if (shownAmong != routings) {
            // The board's own lines routed again: what is shown is routed again among them.
            changed = !shownLines.isEmpty() || !noWay.isEmpty();
            forgetShown();
            shownAmong = routings;
            shownBoxes = routedBoxes;
        }
        java.awt.FontMetrics label = labelMetrics();
        java.util.List<EdgeRouter.Link> fixed = new java.util.ArrayList<EdgeRouter.Link>();
        Map<String, EdgeRouter.Route> routed = new HashMap<String, EdgeRouter.Route>();
        Map<String, Object> shown = new java.util.LinkedHashMap<String, Object>();
        Object parent = getDefaultParent();
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            String id = getIdForCell(cell);
            if (id == null || !getModel().isEdge(cell)
                    || getModel().getTerminal(cell, true) == null
                    || getModel().getTerminal(cell, false) == null) {
                continue;
            }
            if (waitsForFocus(cell)) {
                if (touchesFocus(cell)) {
                    shown.put(id, cell);
                }
            } else if (routedLines.containsKey(id) && routeOf(cell) != null) {
                fixed.add(lineOf(cell, id, isHierarchyLine(baseStyleOf(cell)), label));
                routed.put(id, routedLines.get(id));
            }
        }
        changed |= shownLines.keySet().retainAll(shown.keySet());
        shownEnds.keySet().retainAll(shown.keySet());
        changed |= noWay.retainAll(shown.keySet());
        waiting.retainAll(shown.keySet());
        for (String id : shown.keySet()) {
            if (!shownLines.containsKey(id) && !noWay.contains(id) && waiting.add(id)) {
                changed = true;
            }
        }
        if (waiting.isEmpty() || System.nanoTime() >= deadline) {
            return changed;
        }
        // The lines shown already stay where they are drawn: the new ones go round them as round
        // the board's own.
        for (Map.Entry<String, EdgeRouter.Route> one : shownLines.entrySet()) {
            fixed.add(lineOf(shown.get(one.getKey()), one.getKey(), false, label));
            routed.put(one.getKey(), one.getValue());
        }
        java.util.List<EdgeRouter.Link> links = new java.util.ArrayList<EdgeRouter.Link>();
        for (String id : waiting) {
            links.add(lineOf(shown.get(id), id, false, label));
        }
        Set<String> failed = new HashSet<String>();
        Map<String, EdgeRouter.Route> found = EdgeRouter.routeAmong(boxes, frames, framesNames(),
                fixed, routed, links, deadline, failed);
        for (Map.Entry<String, EdgeRouter.Route> one : found.entrySet()) {
            Object edge = shown.get(one.getKey());
            if (edge == null || !waiting.remove(one.getKey())) {
                continue;
            }
            com.mxgraph.model.mxGeometry from = getModel().getGeometry(
                    getModel().getTerminal(edge, true));
            com.mxgraph.model.mxGeometry to = getModel().getGeometry(
                    getModel().getTerminal(edge, false));
            shownLines.put(one.getKey(), one.getValue());
            shownEnds.put(one.getKey(), new double[] {from.getX(), from.getY(), from.getWidth(),
                from.getHeight(), to.getX(), to.getY(), to.getWidth(), to.getHeight()});
            labelAt(edge, 2 * one.getValue().labelAt - 1);
        }
        if (found.isEmpty() && failed.isEmpty()) {
            // Not a call that routes the first line it is given, which every call is: a line
            // waiting for ever would never be drawn.
            failed.addAll(waiting);
        }
        for (String id : failed) {
            if (waiting.remove(id)) {
                noWay.add(id);
                labelAt(shown.get(id), 0);
            }
        }
        return true;
    }

    /** Where on the line its label sits, -1 at the source to 1 at the target. */
    private void labelAt(Object edge, double at) {
        // Directly on the geometry and not through the model, which would be a change to undo.
        com.mxgraph.model.mxGeometry geometry = edge == null ? null : getModel().getGeometry(edge);
        if (geometry != null) {
            geometry.setX(at);
            geometry.setY(0);
            geometry.setOffset(null);
        }
    }

    /** The lines a selection shows given up: none routed, none waiting. */
    private void forgetShown() {
        shownLines.clear();
        shownEnds.clear();
        waiting.clear();
        noWay.clear();
        if (later != null) {
            later.stop();
        }
    }

    /**
     * How long the routing of what a selection shows may take before the board is drawn: a moment
     * on the Swing thread, where somebody is waiting for the board; elsewhere, all the time a
     * routing has.
     */
    private static long firstLook() {
        return javax.swing.SwingUtilities.isEventDispatchThread() ? CLICK_TIME : EdgeRouter.MOST_TIME;
    }

    /**
     * Routes what a selection shows and one moment left waiting: on the Swing thread a slice at a
     * time, each drawn as it is routed, whatever else is to be done going first in between; off
     * it, at once.
     */
    private void routeTheRest() {
        if (waiting.isEmpty()) {
            if (later != null) {
                later.stop();
            }
            return;
        }
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) {
            // Each call routes or gives up the first line waiting, so this ends.
            for (int tries = waiting.size(); tries > 0 && !waiting.isEmpty(); tries--) {
                routeShown(System.nanoTime() + EdgeRouter.MOST_TIME);
            }
            return;
        }
        if (later == null) {
            later = new javax.swing.Timer(BETWEEN_SLICES, event -> {
                if (routeShown(System.nanoTime() + SLICE_TIME)) {
                    // The lines that came, drawn: those on the board keep what they have.
                    getView().validate();
                    repaint();
                }
                routeTheRest();
            });
            later.setRepeats(false);
        }
        later.restart();
    }

    /**
     * Routes every line a selection shows that still waits for its slice, as the slices would but
     * at once and on the calling thread, and draws them: for a caller that needs every shown line
     * now, as an export does. A picture exported in the moment after a click on a class many lines
     * meet had only the lines that moment routed - 5 of the 19 a click showed on a board of 150
     * boxes (1.135.1 review). Whatever a selection shows after this is routed in slices as before.
     */
    public void routeWaitingLinesNow() {
        if (waiting.isEmpty()) {
            return;
        }
        // Each call routes or gives up the first line waiting, so this ends.
        for (int tries = waiting.size(); tries > 0 && !waiting.isEmpty(); tries--) {
            routeShown(System.nanoTime() + EdgeRouter.MOST_TIME);
        }
        // With nothing left, the slices' timer stops.
        routeTheRest();
        // The lines that came, drawn: those on the board keep what they have.
        getView().validate();
        repaint();
    }

    /**
     * Where each frame's name is written, as the board draws it: what the lines a selection shows
     * keep off.
     */
    private java.util.List<EdgeRouter.Box> framesNames() {
        java.util.List<EdgeRouter.Box> names = new java.util.ArrayList<EdgeRouter.Box>();
        Object parent = getDefaultParent();
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
            if (at == null || !getModel().isVertex(cell)
                    || !SchemaStyles.FRAME.equals(baseStyleOf(cell))) {
                continue;
            }
            String name = getLabel(cell);
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            com.mxgraph.util.mxRectangle text = mxUtils.getLabelPaintBounds(name,
                    getCellStyle(cell), false, new com.mxgraph.util.mxPoint(),
                    new com.mxgraph.util.mxRectangle(at.getX(), at.getY(), at.getWidth(),
                            at.getHeight()), 1);
            names.add(new EdgeRouter.Box(getIdForCell(cell), text.getX(), text.getY(),
                    text.getWidth(), text.getHeight()));
        }
        return names;
    }

    /**
     * Where the boxes stand, as {@link #boxesOnTheBoard} says it: worked out again only after a
     * box has moved, changed size, come or gone.
     */
    private String boxesNow() {
        if (boxesAskedAt != boxChanges) {
            boxesAsked = boxesOnTheBoard(new java.util.ArrayList<EdgeRouter.Box>(),
                    new java.util.ArrayList<EdgeRouter.Box>());
            boxesAskedAt = boxChanges;
        }
        return boxesAsked;
    }

    /**
     * Every box and frame on the board, as the router takes them, into {@code boxes} and
     * {@code frames}; returns what they are and where they stand, as text.
     */
    private String boxesOnTheBoard(java.util.List<EdgeRouter.Box> boxes,
            java.util.List<EdgeRouter.Box> frames) {
        StringBuilder signature = new StringBuilder();
        Object parent = getDefaultParent();
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            String id = getIdForCell(cell);
            com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
            if (id == null || !getModel().isVertex(cell) || at == null) {
                continue;
            }
            EdgeRouter.Box box = new EdgeRouter.Box(id, at.getX(), at.getY(), at.getWidth(),
                    at.getHeight());
            (SchemaStyles.FRAME.equals(baseStyleOf(cell)) ? frames : boxes).add(box);
            signature.append(id).append(at.getX()).append(',').append(at.getY()).append(',')
                    .append(at.getWidth()).append(',').append(at.getHeight()).append(';');
        }
        return signature.toString();
    }

    /** How an arrow's label is measured: in the font an arrow's label is drawn in. */
    private java.awt.FontMetrics labelMetrics() {
        return mxUtils.getFontMetrics(mxUtils.getFont(getStylesheet().getCellStyle(
                SchemaStyles.OBJECT_PROPERTY, getStylesheet().getDefaultEdgeStyle())));
    }

    /** A line as the router takes it: its two ends, whether it is the hierarchy, its label's size. */
    private EdgeRouter.Link lineOf(Object cell, String id, boolean hierarchy,
            java.awt.FontMetrics label) {
        String[] lines = convertValueToString(cell).split("\n");
        double wide = 0;
        for (String line : lines) {
            wide = Math.max(wide, label.stringWidth(line));
        }
        // The chip as JGraphX draws it, measured: the text, its 2px spacing and the hairline
        // round it - 6px wider and 5.3px taller than the text. The height was 1.3px short.
        double high = lines.length * label.getHeight();
        return new EdgeRouter.Link(id, getIdForCell(getModel().getTerminal(cell, true)),
                getIdForCell(getModel().getTerminal(cell, false)), hierarchy,
                wide <= 0 ? 0 : wide + 6, high + 6);
    }

    /** Whether a line of this kind is the hierarchy: a subclass, sub-property or type line. */
    private static boolean isHierarchyLine(String kind) {
        return SchemaStyles.SUBCLASS.equals(kind) || SchemaStyles.SUB_PROPERTY.equals(kind)
                || SchemaStyles.TYPE.equals(kind);
    }

    private double[] at(Object box) {
        com.mxgraph.model.mxGeometry geometry = getModel().getGeometry(box);
        return geometry == null ? new double[] {0, 0}
                : new double[] {geometry.getX() + 1, geometry.getY() + 1};
    }

    /** Whether two points are in the same frame, or in none: either way the line is at home. */
    private static boolean sameFrame(double[] one, double[] other,
            java.util.List<de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box> frames) {
        for (de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box frame : frames) {
            boolean first = one[0] >= frame.x && one[0] <= frame.x + frame.w && one[1] >= frame.y
                    && one[1] <= frame.y + frame.h;
            boolean second = other[0] >= frame.x && other[0] <= frame.x + frame.w
                    && other[1] >= frame.y && other[1] <= frame.y + frame.h;
            if (first != second) {
                return false;
            }
        }
        return true;
    }

    /**
     * An arrow's label in lines short enough to stand in the gap between two columns. Only what is
     * drawn: the label itself, and the tooltip, stay whole.
     */
    @Override
    public String convertValueToString(Object cell) {
        String text = super.convertValueToString(cell);
        if (!getModel().isEdge(cell)) {
            return text;
        }
        // The arrowhead says it, as a subclass's does; "rdfs:subPropertyOf" on each line of a
        // property hierarchy only sat on the boxes beside it.
        return SchemaStyles.SUB_PROPERTY.equals(baseStyleOf(cell)) ? "" : brokenIntoLines(text);
    }

    /** The longest a line of an arrow's label gets, in characters, before it breaks at a space. */
    private static final int LABEL_LINE = 18;

    static String brokenIntoLines(String label) {
        if (label == null || label.length() <= LABEL_LINE) {
            return label;
        }
        StringBuilder lines = new StringBuilder();
        int used = 0;
        for (String word : label.split(" ")) {
            if (used > 0 && used + 1 + word.length() > LABEL_LINE) {
                lines.append('\n');
                used = 0;
            } else if (used > 0) {
                lines.append(' ');
                used++;
            }
            lines.append(word);
            used += word.length();
        }
        return lines.toString();
    }

    /**
     * What is in focus: these terms, and everything an edge joins them to. The rest of the board
     * fades, and on a dense board their quiet relations come forward. Empty for none.
     *
     * <p>Drawing only - nothing in the model changes, so focusing is never an undo step, never
     * saved, and never sent to a collaborator.
     */
    public void setFocus(Collection<String> ids) {
        boolean none = ids == null || ids.isEmpty();
        if (none && focused.isEmpty()) {
            // Nothing in focus before or after: the board is drawn as it is. It used to be
            // reloaded anyway, after every drawing - 180ms each time on a board of 660 terms.
            return;
        }
        Set<String> before = new HashSet<String>(focused);
        focused.clear();
        lit.clear();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !isAnnotationId(id)) {
                    focused.add(id);
                }
            }
        }
        if (!focused.isEmpty()) {
            lit.addAll(focused);
            for (Object edge : mxGraphModel.getChildCells(getModel(), getDefaultParent(), false,
                    true)) {
                String source = getIdForCell(getModel().getTerminal(edge, true));
                String target = getIdForCell(getModel().getTerminal(edge, false));
                if (focused.contains(source) || focused.contains(target)) {
                    lit.add(source);
                    lit.add(target);
                }
            }
        }
        // What the selection shows, routed round the boxes and the lines already drawn, which
        // stay where they are (1.135.1): for a moment, and what that leaves a moment later, so a
        // click on a class many lines meet does not hold the board up (1.135.1 review). The same
        // selection again, while lines it shows still wait, leaves them to the slices.
        if (!focused.equals(before) || waiting.isEmpty()) {
            routeShown(System.nanoTime() + firstLook());
        }
        routeTheRest();
        refresh();
    }

    public boolean isDense() {
        return dense;
    }

    /**
     * Lines behind boxes in a picture too. The board's own component honours
     * {@link #isKeepEdgesInBackground}; the renderer behind Export image draws in model order, and
     * edges are inserted after the nodes they join - so every exported line ran over the labels it
     * passed.
     */
    @Override
    public void drawCell(com.mxgraph.canvas.mxICanvas canvas, Object cell) {
        if (cell != getDefaultParent() || !isKeepEdgesInBackground()) {
            super.drawCell(canvas, cell);
            return;
        }
        drawState(canvas, getView().getState(cell), true);
        int count = getModel().getChildCount(cell);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < count; i++) {
                Object child = getModel().getChildAt(cell, i);
                if (getModel().isEdge(child) == (pass == 0)) {
                    super.drawCell(canvas, child);
                }
            }
        }
    }

    @Override
    public Map<String, Object> getCellStyle(Object cell) {
        Map<String, Object> style = super.getCellStyle(cell);
        int strength = strengthOf(cell);
        if (strength >= 100) {
            return style;
        }
        Map<String, Object> drawn = new HashMap<String, Object>(style);
        drawn.put(mxConstants.STYLE_OPACITY, Math.min(strength,
                mxUtils.getInt(style, mxConstants.STYLE_OPACITY, 100)));
        drawn.put(mxConstants.STYLE_TEXT_OPACITY, getModel().isVertex(cell)
                ? Math.max(strength, FADED_TEXT) : strength);
        return drawn;
    }

    /**
     * Disjointness, domains and ranges wait until one of their ends is in focus: on the larger of
     * the two ontologies measured they were 104 and 112 lines, crossing the board in every
     * direction, and each says something only about the term it touches.
     */
    @Override
    public boolean isCellVisible(Object cell) {
        if (!super.isCellVisible(cell)) {
            return false;
        }
        return !isHiddenUntilInFocus(cell);
    }

    /**
     * Whether this is a line that waits until one of its ends is selected: on any board, since 1.135.1.
     * A property's domain and range run from its box in the property section across the whole board,
     * and on a pattern of forty terms those were most of what the eye met. Each says something only
     * about the term it touches, so that is when it is drawn.
     */
    private boolean isHiddenUntilInFocus(Object cell) {
        if (focused == null || waiting == null || !getModel().isEdge(cell)) {
            return false;
        }
        // Shown by the selection and not routed yet: drawn a moment later, when it is, rather than
        // straight through the boxes meanwhile (1.135.1 review).
        return waitsForFocus(cell) && (!touchesFocus(cell) || waiting.contains(getIdForCell(cell)));
    }

    /**
     * Whether this line is of a kind that waits until one of its ends is selected - see above -
     * but for the domain line of a property beside its class, which is drawn always, and a line
     * between two properties, which waits only when they stand apart (1.135.1).
     */
    private boolean waitsForFocus(Object cell) {
        String kind = baseStyleOf(cell);
        if (SchemaStyles.DOMAIN_RANGE.equals(kind)) {
            return !domainLinesDrawnAlways().contains(getIdForCell(cell));
        }
        if (SchemaStyles.SUB_PROPERTY.equals(kind) || SchemaStyles.INVERSE.equals(kind)) {
            return standApart(getModel().getTerminal(cell, true),
                    getModel().getTerminal(cell, false));
        }
        return SchemaStyles.DISJOINT.equals(kind) || SchemaStyles.RULE_PART.equals(kind);
    }

    /**
     * Whether two properties stand apart: one beside a class, its domain line drawn always, and
     * the other not beside that class - beside another, or in its section (1.135.1). A line
     * between two such would cross the board from the one place to the other, as a range does,
     * and waits for a selection as a range does.
     *
     * <p>Found on the egg-boiling board: has role stood beside independent continuant in a
     * pattern's frame at the top, has characteristic, its super-property, with no domain class on
     * the board, in the Object properties frame at the bottom, and their is-a line ran 1,000 to
     * 1,900 px down the board, the parent below its child. Keeping has role in the section with
     * its super-property instead sent pizza's has base and has topping back there as well, under
     * has component, which has no domain. Two properties beside the same class, or both in their
     * section, stand together, and the line between them is drawn as it always was; so is a
     * chain's line, which ends at the chain's node.
     */
    private boolean standApart(Object one, Object other) {
        if (one == null || other == null || !isPropertyBox(one) || !isPropertyBox(other)) {
            return false;
        }
        domainLinesDrawnAlways();
        String here = standingBeside.get(getIdForCell(one));
        String there = standingBeside.get(getIdForCell(other));
        return here == null ? there != null : !here.equals(there);
    }

    private int strengthOf(Object cell) {
        String id = getIdForCell(cell);
        // Null while mxGraph's constructor builds the view, before this class's fields exist.
        if (focused == null || id == null || isAnnotationId(id)) {
            return 100;
        }
        if (getModel().isEdge(cell)) {
            if (!focused.isEmpty()) {
                return touchesFocus(cell) ? 100 : FADED;
            }
            String kind = baseStyleOf(cell);
            if (betweenFrames.contains(id)) {
                return QUIET;
            }
            // A domain line shown without a selection is one drawn always, a relation (1.135.1).
            return dense && (SchemaStyles.OBJECT_PROPERTY.equals(kind)
                    || SchemaStyles.ONLY.equals(kind) || SchemaStyles.COUNT.equals(kind)
                    || SchemaStyles.NOT.equals(kind) || SchemaStyles.DATA_PROPERTY.equals(kind)
                    || SchemaStyles.INVERSE.equals(kind) || SchemaStyles.DOMAIN_RANGE.equals(kind)
                    || lengthOf(cell) > LONG) ? QUIET : 100;
        }
        return focused.isEmpty() || lit.contains(id) ? 100 : FADED;
    }

    private double lengthOf(Object edge) {
        com.mxgraph.model.mxGeometry from = getModel().getGeometry(getModel().getTerminal(edge,
                true));
        com.mxgraph.model.mxGeometry to = getModel().getGeometry(getModel().getTerminal(edge,
                false));
        return from == null || to == null ? 0 : Math.hypot(from.getCenterX() - to.getCenterX(),
                from.getCenterY() - to.getCenterY());
    }

    private boolean touchesFocus(Object edge) {
        return focused.contains(getIdForCell(getModel().getTerminal(edge, true)))
                || focused.contains(getIdForCell(getModel().getTerminal(edge, false)));
    }

    private String baseStyleOf(Object cell) {
        String style = getModel().getStyle(cell);
        return style == null ? "" : style.split(";", 2)[0];
    }

    /**
     * Whether this id belongs to a sticky note or a frame rather than to a term.
     *
     * <p>The canvas keys everything by id, and a note's id is a generated one rather than an IRI.
     * Anything that treats a cell as a term - selection, axiom removal, expanding neighbours -
     * has to be able to tell them apart, and asking the ontology would say "not found" for both a
     * note and a term that has been deleted.
     */
    public static boolean isAnnotationId(String id) {
        return id != null && (id.startsWith(NOTE_ID_PREFIX) || id.startsWith(FRAME_ID_PREFIX));
    }

    /** Prefixes that make a canvas annotation recognisable by its id alone. */
    public static final String NOTE_ID_PREFIX = "ontoboard-note-";
    public static final String FRAME_ID_PREFIX = "ontoboard-frame-";

    /** Cell id to the tooltip {@link #render} worked out for it. Cleared and rebuilt with the
     * cells, so it can never describe a cell that is no longer there. */
    private final Map<String, String> tooltipsById = new HashMap<String, String>();

    /**
     * Which drawn terms carry an editorial note, for {@link NoteBadgeLayer}.
     *
     * <p>Filled during {@code render} from the projection, like the tooltips, rather than read from the
     * ontology at paint time. Paint runs on every scroll and hover; a lookup per node per repaint would
     * put the OWL API on the paint path, and a badge that disagreed with the tooltip beside it would be
     * worse than either.
     */
    private final java.util.Set<String> notedIds = new java.util.LinkedHashSet<String>();

    /** The terms drawn with a note, in the order they were drawn. */
    public java.util.Set<String> getNotedIds() {
        return java.util.Collections.unmodifiableSet(notedIds);
    }

    public Object getCellForId(String id) {
        return id == null ? null : cellsById.get(id);
    }

    /** Takes a frame or a note off the board, its entry in the index with it. */
    void removeAnnotation(String id) {
        Object cell = isAnnotationId(id) ? cellsById.remove(id) : null;
        if (cell != null) {
            removeCells(new Object[] {cell}, false);
        }
    }

    /**
     * Every cell that stands for one entity: a box with that IRI, and each arrow about it.
     *
     * <p><b>Why this is not a second index.</b> Since 1.75.0 a property on the board is usually
     * an arrow rather than a box, and one property owns as many arrows as it has axioms - a
     * {@code hasTopping} is on every pizza. Keyed into a map, the first arrow would win, which
     * arrow that is depends on the order the projection happened to emit its axioms, and
     * selecting the property from Prot&eacute;g&eacute;'s tree would jump the board to an
     * arbitrary one of them - measured 400px away from the one the reader was looking at, and not
     * stable across reloads. So the question is answered by asking every cell, and the answer is
     * all of them.
     *
     * <p>A linear scan because a board is a few hundred cells and this runs on a click, and
     * because an index would be one more thing that can disagree with {@link #render} - which
     * destroys and recreates every cell.
     */
    public java.util.List<Object> getCellsForEntity(String iri) {
        java.util.List<Object> found = new java.util.ArrayList<Object>();
        if (iri == null) {
            return found;
        }
        Object box = cellsById.get(iri);
        if (box != null) {
            // First, so a property that is on the board as a box is what gets centred rather
            // than one of its own arrows.
            found.add(box);
        }
        for (Map.Entry<String, Object> entry : cellsById.entrySet()) {
            if (entry.getValue() == box) {
                continue;
            }
            if (iri.equals(de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId
                    .propertyIn(entry.getKey()))) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    /**
     * Only board furniture can be edited in place.
     *
     * <p>A term node's label is its {@code rdfs:label}. Renaming it is an ontology edit with its own
     * conversation - a dialog, provenance, a shared session to publish to - and not a side effect of
     * a double click. An edge's label is the property it stands for, which is the same argument.
     * Sticky notes and frames belong to the board alone, so they are exactly the cells this allows.
     */
    @Override
    public boolean isCellEditable(Object cell) {
        String id = getIdForCell(cell);
        return id != null && isAnnotationId(id) && getModel().isVertex(cell);
    }

    public String getIdForCell(Object cell) {
        return cell instanceof mxCell ? ((mxCell) cell).getId() : null;
    }

    /**
     * What the cell is, in words, from the tooltip {@link #render} built for it.
     *
     * <p>This used to return the cell's id. For a node that is its IRI, which is true but partial;
     * for everything else it was an internal string, so hovering an arrow gave
     * {@code rest|some|http://…#Pizza|http://…#hasTopping|http://…#PizzaTopping} and hovering a
     * sticky note gave {@code ontoboard-note-3f2a1b9c}. See {@link CanvasTooltips} for what they say
     * now.
     *
     * <p>Falls back to the id, which keeps a cell inserted by something other than {@code render}
     * from having no tooltip at all, and then to mxGraph's own behaviour for a cell with no id.
     */
    /**
     * Every tooltip, by cell id, for something that needs the text without a pointer.
     *
     * <p>A pinned card is built from the same string a hover would show, so the two cannot
     * disagree about what a term is.
     */
    public Map<String, String> tooltips() {
        return java.util.Collections.unmodifiableMap(tooltipsById);
    }

    @Override
    public String getToolTipForCell(Object cell) {
        String id = getIdForCell(cell);
        if (id == null) {
            return super.getToolTipForCell(cell);
        }
        String tooltip = tooltipsById.get(id);
        return tooltip != null ? tooltip : id;
    }

    /**
     * The style a node would be drawn with, for a test.
     *
     * <p>Rendering needs a live mxGraph; the decision about which marker wins does not, and it is
     * the part that can be wrong in a way nobody notices.
     */
    static String styleForTesting(CanvasNode node) {
        return styleFor(node, new PrefixColours(new java.util.HashMap<String, String>()));
    }

    /**
     * Appends an inline {@code strokeColor} override to the named style, so every namespace
     * gets a distinguishable outline while the shape still says what kind of thing it is.
     * mxGraph reads {@code "styleName;key=value"} as style-plus-overrides.
     */
    private static String styleFor(CanvasNode node, PrefixColours colours) {
        // Border WEIGHT for a note, because every other channel is taken and says something
        // else: the shape says what kind of thing it is, the stroke colour says which namespace
        // it came from, and a dash would read as "inferred", which is what dashed edges mean two
        // lines down. Weight is the one free channel, and it reads as emphasis rather than as a
        // different kind of thing - which is right, since a note does not change what the term is.
        // An unsatisfiable class takes the namespace colour's channel. Which vocabulary a term
        // came from stops mattering the moment the reasoner says it can have no instances, and a
        // modelling error visible only to somebody who knows which shade of blue to look for is
        // not visible.
        String stroke = node.isUnsatisfiable()
                ? SchemaStyles.UNSATISFIABLE_STROKE : colours.colourFor(node.getId());
        return baseStyleFor(node) + ";strokeColor=" + stroke
                // A defined class has a badge as well as its label's ≡, which reads as = at 13 px.
                + (node.getKind() == de.fizkarlsruhe.ise.ontoboard.model.NodeKind.CLASS
                        && node.getLabel() != null
                        && node.getLabel().startsWith("≡ ")
                        ? ";shape=" + DefinedClassShape.NAME : "")
                + (node.hasNote() || node.isUnsatisfiable()
                        ? ";strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH : "")
                // Opacity for an imported term - the one channel the four above leave free, and the
                // only one that also survives a PNG or SVG export.
                + (node.isImported() ? ";opacity=" + SchemaStyles.IMPORTED_OPACITY : "");
    }

    /**
     * Each property on the board whose domain lines reach exactly one class box, with that class,
     * by property: which properties stand beside a class, as the board's lines say it - see
     * {@link de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside#classes}. Every line counts,
     * drawn or waiting for a selection.
     */
    public Map<String, String> propertiesBesideTheirClass() {
        java.util.List<String[]> domains = new java.util.ArrayList<String[]>();
        for (Object edge : mxGraphModel.getChildCells(getModel(), getDefaultParent(), false,
                true)) {
            Object from = getModel().getTerminal(edge, true);
            Object to = getModel().getTerminal(edge, false);
            if (from == null || to == null || !de.fizkarlsruhe.ise.ontoboard.layout
                    .PropertiesBeside.isDomainLine(getIdForCell(edge))) {
                continue;
            }
            String property = baseStyleOf(from);
            if ((SchemaStyles.OBJECT_PROPERTY_NODE.equals(property)
                    || SchemaStyles.DATA_PROPERTY_NODE.equals(property))
                    && SchemaStyles.CLASS.equals(baseStyleOf(to))) {
                domains.add(new String[] {getIdForCell(from), getIdForCell(to)});
            }
        }
        return de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside.classes(domains);
    }

    private boolean isPropertyBox(Object cell) {
        String style = baseStyleOf(cell);
        return SchemaStyles.OBJECT_PROPERTY_NODE.equals(style)
                || SchemaStyles.DATA_PROPERTY_NODE.equals(style);
    }

    /** The domain lines drawn whatever is selected, and the box changes they were found at. */
    private Set<String> drawnAlways = new HashSet<String>();
    /** The class each property whose domain line is drawn always stands beside, by property. */
    private Map<String, String> standingBeside = new HashMap<String, String>();
    private long drawnAlwaysAt = -1;

    /**
     * The domain lines of the properties standing beside their class: a property's line to the
     * one class it stands beside, while its box stands where the arrangement puts it beside that
     * class - see {@link de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside#standsBeside} -
     * and the two stand in the same frame or both in none (1.135.1).
     *
     * <p>Not every such property's line. A board saved by an earlier build keeps its places until
     * it is arranged, and there a property whose domain is one class stands in the Object
     * properties frame, far from its class: its line waits for a selection, as it did, rather than
     * crossing the board for good. So does the line of a property Hierarchical, Organic, Circle or
     * Grid placed, or a hand moved away. Worked out again when a box has moved, changed size,
     * come or gone, and after every drawing.
     */
    private Set<String> domainLinesDrawnAlways() {
        if (drawnAlways != null && standingBeside != null && drawnAlwaysAt == boxChanges) {
            return drawnAlways;
        }
        Set<String> found = new HashSet<String>();
        Map<String, String> standing = new HashMap<String, String>();
        Map<String, String> beside = propertiesBesideTheirClass();
        if (!beside.isEmpty()) {
            java.util.List<EdgeRouter.Box> frames = new java.util.ArrayList<EdgeRouter.Box>();
            for (Object cell : getChildVertices(getDefaultParent())) {
                com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
                if (at != null && SchemaStyles.FRAME.equals(baseStyleOf(cell))) {
                    frames.add(new EdgeRouter.Box(getIdForCell(cell), at.getX(), at.getY(),
                            at.getWidth(), at.getHeight()));
                }
            }
            // How far down each class's properties may reach: one height for each property.
            Map<String, java.util.List<Double>> heights =
                    new HashMap<String, java.util.List<Double>>();
            for (Map.Entry<String, String> property : beside.entrySet()) {
                Object box = getCellForId(property.getKey());
                com.mxgraph.model.mxGeometry at = box == null ? null : getModel().getGeometry(box);
                if (at != null) {
                    java.util.List<Double> of = heights.get(property.getValue());
                    if (of == null) {
                        of = new java.util.ArrayList<Double>();
                        heights.put(property.getValue(), of);
                    }
                    of.add(at.getHeight());
                }
            }
            for (Object edge : mxGraphModel.getChildCells(getModel(), getDefaultParent(), false,
                    true)) {
                String id = getIdForCell(edge);
                Object from = getModel().getTerminal(edge, true);
                Object to = getModel().getTerminal(edge, false);
                String cls = from == null ? null : beside.get(getIdForCell(from));
                if (to != null && cls != null && cls.equals(getIdForCell(to))
                        && de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside.isDomainLine(id)
                        && standsBeside(from, to, heights.get(cls))
                        && sameFrame(at(from), at(to), frames)) {
                    found.add(id);
                    standing.put(getIdForCell(from), cls);
                }
            }
        }
        drawnAlways = found;
        standingBeside = standing;
        drawnAlwaysAt = boxChanges;
        return found;
    }

    /** Whether a property's box stands beside a class's box, as the Outline puts it there. */
    private boolean standsBeside(Object property, Object cls, java.util.List<Double> heights) {
        com.mxgraph.model.mxGeometry p = getModel().getGeometry(property);
        com.mxgraph.model.mxGeometry c = getModel().getGeometry(cls);
        return p != null && c != null && heights != null
                && de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside.standsBeside(
                        new double[] {p.getX(), p.getY(), p.getWidth(), p.getHeight()},
                        new double[] {c.getX(), c.getY(), c.getWidth(), c.getHeight()}, heights);
    }

    /** The style a node is drawn with, without its colours: what kind of box it is. */
    static String nodeStyle(CanvasNode node) {
        return baseStyleFor(node);
    }

    /** The style a line is drawn with. */
    static String lineStyle(CanvasEdge edge) {
        return styleFor(edge);
    }

    private static String baseStyleFor(CanvasNode node) {
        switch (node.getKind()) {
            case INDIVIDUAL: return SchemaStyles.INDIVIDUAL;
            case DATATYPE:   return SchemaStyles.DATATYPE;
            case LITERAL:    return SchemaStyles.LITERAL;
            case EXPRESSION: return SchemaStyles.EXPRESSION;
            case OBJECT_PROPERTY: return SchemaStyles.OBJECT_PROPERTY_NODE;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY_NODE;
            case CLASS:
            default:         return SchemaStyles.CLASS;
        }
    }

    private static String styleFor(CanvasEdge edge) {
        switch (edge.getKind()) {
            case SUBCLASS:        return SchemaStyles.SUBCLASS;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY;
            case TYPE:            return SchemaStyles.TYPE;
            case SUB_PROPERTY:    return SchemaStyles.SUB_PROPERTY;
            case DISJOINT:        return SchemaStyles.DISJOINT;
            case INVERSE:         return SchemaStyles.INVERSE;
            case DOMAIN_RANGE:    return SchemaStyles.DOMAIN_RANGE;
            case RULE_PART:       return SchemaStyles.RULE_PART;
            case INFERRED_SUBCLASS: return SchemaStyles.INFERRED_SUBCLASS;
            case INFERRED_TYPE:   return SchemaStyles.INFERRED_TYPE;
            case OBJECT_PROPERTY:
            default:              return restrictionStyle(edge.getLabel());
        }
    }

    /** By the symbol its label starts with: {@code ∀}, a count, {@code ¬}, or existential. */
    static String restrictionStyle(String label) {
        String text = label == null ? "" : label.trim();
        if (text.startsWith("\u2200")) {
            return SchemaStyles.ONLY;
        }
        if (text.startsWith("\u2265") || text.startsWith("\u2264") || text.startsWith("= ")) {
            return SchemaStyles.COUNT;
        }
        if (text.startsWith("\u00AC")) {
            return SchemaStyles.NOT;
        }
        return SchemaStyles.OBJECT_PROPERTY;
    }
}
