package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGraphModel;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
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

    /**
     * The route of this line while it still describes where its ends are, or null - a box has moved
     * and the line is drawn by the plain connector until {@link #routeEdges} has run again.
     */
    java.util.List<double[]> routeOf(Object edge) {
        String id = getIdForCell(edge);
        java.util.List<double[]> route = id == null ? null : routes.get(id);
        if (route == null) {
            return null;
        }
        double[] ends = routedEnds.get(id);
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
        StringBuilder signature = new StringBuilder();
        Object parent = getDefaultParent();
        java.awt.FontMetrics label = mxUtils.getFontMetrics(mxUtils.getFont(
                getStylesheet().getCellStyle(SchemaStyles.OBJECT_PROPERTY,
                        getStylesheet().getDefaultEdgeStyle())));
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            String id = getIdForCell(cell);
            com.mxgraph.model.mxGeometry at = getModel().getGeometry(cell);
            if (id == null || !getModel().isVertex(cell) || at == null) {
                continue;
            }
            de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box box =
                    new de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Box(id, at.getX(), at.getY(),
                            at.getWidth(), at.getHeight());
            (SchemaStyles.FRAME.equals(baseStyleOf(cell)) ? frames : boxes).add(box);
            signature.append(id).append(at.getX()).append(',').append(at.getY()).append(',')
                    .append(at.getWidth()).append(',').append(at.getHeight()).append(';');
        }
        for (int i = 0; i < getModel().getChildCount(parent); i++) {
            Object cell = getModel().getChildAt(parent, i);
            String id = getIdForCell(cell);
            Object from = getModel().getTerminal(cell, true);
            Object to = getModel().getTerminal(cell, false);
            if (id == null || !getModel().isEdge(cell) || from == null || to == null
                    || isHiddenUntilInFocus(cell)) {
                continue;
            }
            String kind = baseStyleOf(cell);
            boolean hierarchy = SchemaStyles.SUBCLASS.equals(kind)
                    || SchemaStyles.SUB_PROPERTY.equals(kind) || SchemaStyles.TYPE.equals(kind);
            if (dense && !hierarchy) {
                continue;
            }
            if (hierarchy && !sameFrame(at(from), at(to), frames)) {
                betweenFrames.add(id);
            } else {
                betweenFrames.remove(id);
            }
            String[] lines = convertValueToString(cell).split("\n");
            double wide = 0;
            for (String line : lines) {
                wide = Math.max(wide, label.stringWidth(line));
            }
            // The chip as JGraphX draws it, measured: the text, its 2px spacing and the hairline
            // round it - 6px wider and 5.3px taller than the text. The height was 1.3px short.
            double high = lines.length * label.getHeight();
            links.add(new de.fizkarlsruhe.ise.ontoboard.layout.EdgeRouter.Link(id, getIdForCell(from),
                    getIdForCell(to), hierarchy, wide <= 0 ? 0 : wide + 6, high + 6));
            signature.append(id).append('|');
        }
        String now = signature.toString();
        long budget = deadline - System.nanoTime();
        // "More time" means clearly more: a review found that every refresh of an unchanged board
        // the router could not finish routed it again for the whole two seconds, because one
        // call's budget came out a few microseconds longer than the last one's.
        if (now.equals(routedFor) && (cutShortAfter == Long.MIN_VALUE
                || budget <= cutShortAfter + CLEARLY_MORE)) {
            return;
        }
        routedFor = now;
        routes.clear();
        routedEnds.clear();
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
        refresh();
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
        if (focused == null || !getModel().isEdge(cell)) {
            return false;
        }
        String kind = baseStyleOf(cell);
        return (SchemaStyles.DISJOINT.equals(kind) || SchemaStyles.DOMAIN_RANGE.equals(kind)
                || SchemaStyles.RULE_PART.equals(kind)) && !touchesFocus(cell);
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
            return dense && (SchemaStyles.OBJECT_PROPERTY.equals(kind)
                    || SchemaStyles.ONLY.equals(kind) || SchemaStyles.COUNT.equals(kind)
                    || SchemaStyles.NOT.equals(kind) || SchemaStyles.DATA_PROPERTY.equals(kind)
                    || SchemaStyles.INVERSE.equals(kind) || lengthOf(cell) > LONG) ? QUIET : 100;
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
