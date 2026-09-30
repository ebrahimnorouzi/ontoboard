package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxEdgeLabelLayout;
import com.mxgraph.layout.mxFastOrganicLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.model.mxGeometry;
import com.mxgraph.model.mxGraphModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingConstants;

/** Automatic arrangements, mapping the retired web app's layout menu onto JGraphX. */
public final class CanvasLayouts {

    /**
     * JGraphX ships no grid layout at all: {@code mxStackLayout} is a single-row/column
     * stack with no wrapping capability (no {@code setWrap} method exists on it, despite
     * some documentation implying otherwise). GRID is therefore implemented here as a
     * small custom wrapping arrangement rather than delegating to a library class.
     *
     * <p>The column count is derived from how many terms there are - see {@link #gridColumns}.
     * It was a fixed four until 1.64.0, which is defensible for a dozen terms and wrong for
     * anything bigger: 120 terms in four columns is a 3000px-tall strip, which is the
     * hierarchy's own failure rotated ninety degrees.
     */
    private static final double GRID_GAP = 40;
    private static final double DEFAULT_NODE_WIDTH = 160;
    private static final double DEFAULT_NODE_HEIGHT = 60;

    /** Where the board's top-left term sits after any arrangement. */
    private static final double MARGIN = 40;

    /** Horizontal pitch for the block of terms that have no axioms to connect them. */
    private static final double LOOSE_PITCH_X = 200;

    /** Vertical pitch for that block, and how far under the diagram it starts. */
    private static final double LOOSE_PITCH_Y = 100;

    /** Names match the web app's menu so existing users recognise them. */
    public enum Algorithm {
        HIERARCHICAL("Hierarchical (SubClassOf tree)"),
        ORGANIC("Organic (force-directed)"),
        CIRCLE("Circle"),
        GRID("Grid");

        private final String displayName;

        Algorithm(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private CanvasLayouts() {
    }

    /**
     * Where to put the neighbours of a term, as offsets from its centre.
     *
     * <p><em>Expand neighbours</em> added them and placed them nowhere: with no stored geometry they
     * came out of {@code SchemaGraph.render} in a row along the top of the board, overlapping whatever
     * was already there and nowhere near the term they belong to. On a board of any size the visible
     * result of expanding was that the diagram got worse.
     *
     * <p>A ring around the source, because that is what the relationship is: these are its
     * neighbours, and a ring says so at a glance while a row says nothing. Rings fill outward once
     * full - capacity is the circumference divided by a node's width plus a gap, so nodes on a ring
     * never overlap by construction rather than by choice of magic number.
     *
     * <p>Starts at the top and goes clockwise. Not for any deep reason, but so that expanding the same
     * term twice puts things in the same places, which matters more than which places.
     *
     * @return one {@code {dx, dy}} per neighbour, relative to the centre of the source node
     */
    public static List<double[]> ringOffsets(int count) {
        List<double[]> offsets = new ArrayList<double[]>();
        if (count <= 0) {
            return offsets;
        }
        int placed = 0;
        int ring = 0;
        while (placed < count) {
            double radius = FIRST_RING + ring * RING_GAP;
            int capacity = capacityOf(radius);
            int onThisRing = Math.min(capacity, count - placed);
            for (int i = 0; i < onThisRing; i++) {
                // -90 degrees so the first neighbour sits above the source rather than to its right.
                double angle = -Math.PI / 2 + 2 * Math.PI * i / onThisRing;
                offsets.add(new double[] {
                        radius * Math.cos(angle), radius * Math.sin(angle) });
            }
            placed += onThisRing;
            ring++;
        }
        return offsets;
    }

    /** How many nodes fit on a ring of this radius without touching. */
    private static int capacityOf(double radius) {
        int capacity = (int) Math.floor(2 * Math.PI * radius / NODE_PITCH);
        // Three is the smallest count for which "a ring" means anything; below that the arithmetic
        // would put two nodes on top of each other on a very small radius.
        return Math.max(3, capacity);
    }

    /** Radius of the first ring: far enough out that an edge to the centre is visible. */
    private static final double FIRST_RING = 240;

    /** How much further out each subsequent ring sits. */
    private static final double RING_GAP = 200;

    /** A default node is 160 wide; the rest is the gap that keeps two neighbours apart. */
    private static final double NODE_PITCH = 200;

    /**
     * Arranges the terms on the board, leaving sticky notes and frames where the user put them.
     *
     * <p>Annotations are not terms and must not be laid out as if they were. A frame is a region
     * drawn around a group of classes; slotted into a grid it lands in a cell of its own and
     * encloses nothing, which destroys the only thing it was for. A note pinned beside the class it
     * comments on ends up in a row among the classes.
     *
     * <p>The library layouts take a parent rather than a vertex list, so their reach cannot be
     * restricted up front. Annotation positions are therefore snapshotted and put back afterwards,
     * which works identically for every algorithm and needs no knowledge of what each does.
     * {@link #applyGrid} builds its own vertex list and filters instead.
     *
     * <p>The order of the three steps at the end is load-bearing. Annotations are restored
     * <em>before</em> {@link #parkUnconnected}, so the block of loose terms is placed relative to
     * where the real terms actually ended up; {@link #normalise} runs last, so everything - terms,
     * loose block, notes, frames and edge routing - moves together and the note keeps its position
     * relative to the class it comments on.
     */
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

        // Outside the update, and in a second one of its own: mxEdgeLabelLayout reads mxCellState,
        // so the view has to be valid before it runs. It writes an offset into each EDGE's geometry
        // and never a vertex's, so no saved board is touched - and it is the only layout in the jar
        // that does not call setEdgeStyleEnabled, so it cannot undo the routing turned on above.
        graph.getView().revalidate();
        graph.getModel().beginUpdate();
        try {
            new mxEdgeLabelLayout(graph).execute(graph.getDefaultParent());
        } finally {
            graph.getModel().endUpdate();
        }
    }

    /** Where every note and frame sits now, so a layout cannot move it. */
    private static Map<Object, mxGeometry> annotationGeometries(SchemaGraph graph) {
        Map<Object, mxGeometry> geometries = new LinkedHashMap<Object, mxGeometry>();
        for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
            if (isAnnotation(graph, vertex)) {
                mxGeometry geometry = graph.getModel().getGeometry(vertex);
                if (geometry != null) {
                    geometries.put(vertex, (mxGeometry) geometry.clone());
                }
            }
        }
        return geometries;
    }

    /** Whether a cell is a sticky note or a frame rather than a term. */
    static boolean isAnnotation(SchemaGraph graph, Object vertex) {
        return SchemaGraph.isAnnotationId(graph.getIdForCell(vertex));
    }

    private static mxIGraphLayout createLibraryLayout(SchemaGraph graph, Algorithm algorithm) {
        // Aliased because mxGraphLayout has its own protected field called `graph`, of type mxGraph,
        // which shadows this parameter inside the anonymous subclasses below.
        final SchemaGraph board = graph;
        switch (algorithm) {
            case ORGANIC: {
                // mxOrganicLayout seeds from the positions it finds and never re-centres, so it
                // amplifies whatever it starts from: measured on a 300-node board it produced
                // 41334x9819 starting at x=7835. mxFastOrganicLayout gives 8230x3510 at the origin
                // in half the time, and is still force-directed, which is what the menu promises.
                // Annotations are excluded from the simulation explicitly, rather than only being
                // put back afterwards. A force-directed layout treats every vertex as a body that
                // repels the others, so a note left in it would arrange the diagram around
                // something that is not part of the ontology, and the restore afterwards would
                // hide that it had.
                //
                // Measured honestly: mxFastOrganicLayout's own isVertexIgnored already excludes a
                // vertex with no connections, which every note and frame is, so today this changes
                // nothing. It is here so the property is guaranteed by this code rather than by a
                // library's incidental behaviour, and so the same override can be written for any
                // layout added later that does not happen to share it.
                mxFastOrganicLayout organic = new mxFastOrganicLayout(graph) {
                    @Override
                    public boolean isVertexIgnored(Object vertex) {
                        return super.isVertexIgnored(vertex) || isAnnotation(board, vertex);
                    }
                };
                organic.setForceConstant(240);
                organic.setMinDistanceLimit(90);
                organic.setInitialTemp(260);
                organic.setMaxIterations(500);
                organic.setDisableEdgeStyle(false);
                return organic;
            }
            case HIERARCHICAL:
            default: {
                // SOUTH, because SchemaGraph draws a subclass edge from the subclass TO the
                // superclass and mxGraphHierarchyModel ranks by following edges source to target.
                // With the default NORTH that put the leaves at the top and owl:Thing at the
                // bottom - upside down against every ontology tool there is, and the single most
                // visible thing wrong with this canvas.
                mxHierarchicalLayout tree =
                        new mxHierarchicalLayout(graph, SwingConstants.SOUTH) {
                            @Override
                            public boolean isVertexIgnored(Object vertex) {
                                return super.isVertexIgnored(vertex) || isAnnotation(board, vertex);
                            }
                        };
                tree.setIntraCellSpacing(24);
                tree.setInterRankCellSpacing(120);
                tree.setInterHierarchySpacing(120);
                tree.setParallelEdgeSpacing(20);
                // The stylesheet has asked for orthogonal edges since the first version and has
                // never once got them: mxGraphHierarchyModel.createInternalCells stamps
                // noEdgeStyle=1 onto every edge while isDisableEdgeStyle() is true, which is the
                // default. Every arrow on every board has been a diagonal sweep because of it.
                tree.setDisableEdgeStyle(false);
                return tree;
            }
        }
    }

    /**
     * Puts terms with no axioms in a block under the diagram.
     *
     * <p>{@code mxHierarchicalLayout.findRoots} only accepts a vertex with {@code fanIn == 0} and
     * {@code fanOut > 0}, so a term with no edges at all is not a root, is not reachable from one,
     * and falls through to the single-node-hierarchy path - which lays it out to the <em>right</em>
     * of everything else. On the representative board the diagram ended at x=824 and the four loose
     * terms sat between x=1465 and x=2288, with nothing in between.
     *
     * <p>A term with no edges is not a mistake: a class nobody has related to anything yet is the
     * normal state of a term five minutes old. It belongs near the work, not exiled off-screen.
     */
    private static void parkUnconnected(SchemaGraph graph) {
        List<Object> loose = new ArrayList<Object>();
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
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
            // Every term is loose - a board somebody has just started. The block becomes the board.
            minX = MARGIN;
            maxX = MARGIN;
            maxY = -LOOSE_PITCH_Y + MARGIN;
        }

        int perRow = looseTermsPerRow(loose.size(), maxX - minX);
        for (int i = 0; i < loose.size(); i++) {
            mxGeometry geometry = graph.getModel().getGeometry(loose.get(i));
            if (geometry == null) {
                continue;
            }
            mxGeometry moved = (mxGeometry) geometry.clone();
            moved.setX(minX + (i % perRow) * LOOSE_PITCH_X);
            moved.setY(maxY + 80 + (i / perRow) * LOOSE_PITCH_Y);
            graph.getModel().setGeometry(loose.get(i), moved);
        }
    }

    /**
     * How wide to make the block of unconnected terms.
     *
     * <p>Two bounds, and the tighter wins. The block should not be wider than the diagram it sits
     * under, because a row of loose terms running off past the tree is the exiled row again in
     * miniature; and it should not be so tall and narrow that it becomes a column, which is what a
     * fixed width does to forty loose terms. The second bound is a 16:9-ish block.
     *
     * <p>Package-private and pure so it can be tested: it is arithmetic, and arithmetic that is
     * wrong by one puts a term on top of another.
     */
    static int looseTermsPerRow(int looseCount, double connectedWidth) {
        int acrossTheDiagram = Math.max(1, (int) Math.round(connectedWidth / LOOSE_PITCH_X));
        int asABlock = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, looseCount) * 1.78)));
        // And never more columns than there are terms: ceil(sqrt(1 * 1.78)) is 2, which would
        // reserve a second column for a board with one loose term.
        int columns = Math.min(acrossTheDiagram, asABlock);
        return Math.max(1, Math.min(Math.max(1, looseCount), columns));
    }

    /**
     * Moves the whole board so its top-left vertex sits at the margin, routing included.
     *
     * <p>SOUTH emits entirely negative y - the layout ranks downward from zero - and
     * {@code mxGraphComponent}'s viewport starts at the origin, so without this the diagram is
     * simply off-screen above the canvas.
     *
     * <p>Edges are translated as well as vertices, and that is not decoration: with routing on,
     * {@code mxHierarchicalLayout} writes <em>absolute</em> control points into each edge's geometry,
     * so moving the vertices alone leaves every wire behind at the old coordinates.
     * {@code mxGeometry.translate} moves the point list, the source point and the target point
     * together, because {@code TRANSLATE_CONTROL_POINTS} is true.
     *
     * <p>Notes and frames are included in both the minimum and the move, so a note keeps its
     * position <em>relative to</em> the terms it comments on rather than being pinned to an absolute
     * spot the terms have left.
     */
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
        double dx = MARGIN - minX;
        double dy = MARGIN - minY;
        if (dx == 0 && dy == 0) {
            return;
        }
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

    /**
     * A ring of the terms that have edges, sized to its contents.
     *
     * <p>Not {@code mxCircleLayout.execute}, for two reasons measured on the 31-node board. It sizes
     * the ring as {@code vertexCount * maxNodeDimension / PI}, giving every node an arc two node
     * widths long - 3353x3249 for 31 terms - and {@code setRadius} cannot shrink that, because the
     * computed radius is a floor rather than a setting. And {@code execute} lays out every child
     * vertex, so notes and frames went onto the ring and were then restored to where they belong,
     * leaving visible holes in the circle where they had been.
     *
     * <p>{@code circle(Object[], double, double, double)} takes the cells to place, so both problems
     * go away: this picks the cells, and computes a radius from the real pitch.
     */
    private static void applyCircle(SchemaGraph graph) {
        List<Object> terms = new ArrayList<Object>();
        double pitch = LOOSE_PITCH_X;
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
        new mxCircleLayout(graph).circle(terms.toArray(), radius, MARGIN, MARGIN);
    }

    /**
     * How many columns a grid of this many terms should use.
     *
     * <p>A block roughly 16:9, rather than a fixed count. Four columns is defensible for a dozen
     * terms and wrong for anything larger - 120 terms in four columns is a 3000px strip, which is
     * exactly the failure the hierarchy had, rotated ninety degrees.
     *
     * <p>Package-private and pure so it can be tested at the sizes that matter rather than only at
     * the one the test fixture happens to use.
     */
    static int gridColumns(int vertexCount, double dx, double dy) {
        if (vertexCount <= 0 || dx <= 0 || dy <= 0) {
            return 1;
        }
        int columns = (int) Math.ceil(Math.sqrt(vertexCount * (dy / dx) * 1.78));
        return Math.max(1, Math.min(vertexCount, columns));
    }

    /**
     * Places every vertex at {@code (col * dx, row * dy)}, wrapping at {@link #gridColumns}
     * columns. Vertices are iterated in {@code getChildVertices}' order, which is the order they
     * were inserted into the model by {@link SchemaGraph#render} - stable and deterministic, unlike
     * a hash-based iteration order would be.
     *
     * <p>The row/column pitch ({@code dx}/{@code dy}) is derived from the largest width/height
     * actually present among the graph's own cells (falling back to the defaults
     * {@link SchemaGraph} itself uses when a cell somehow has no geometry), so a canvas with resized
     * nodes still gets a non-overlapping grid. Column and row indices are always {@code >= 0}, so
     * every coordinate this produces is non-negative before {@link #normalise} shifts it.
     */
    private static void applyGrid(SchemaGraph graph) {
        // Terms only. An annotation left in this list would take a grid cell and be moved into it.
        List<Object> terms = new ArrayList<Object>();
        for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
            if (!isAnnotation(graph, vertex)) {
                terms.add(vertex);
            }
        }
        Object[] vertices = terms.toArray();

        double pitchWidth = DEFAULT_NODE_WIDTH;
        double pitchHeight = DEFAULT_NODE_HEIGHT;
        for (Object vertex : vertices) {
            mxGeometry geometry = graph.getModel().getGeometry(vertex);
            if (geometry != null) {
                pitchWidth = Math.max(pitchWidth, geometry.getWidth());
                pitchHeight = Math.max(pitchHeight, geometry.getHeight());
            }
        }
        double dx = pitchWidth + GRID_GAP;
        double dy = pitchHeight + GRID_GAP;
        int columns = gridColumns(vertices.length, dx, dy);

        for (int i = 0; i < vertices.length; i++) {
            Object vertex = vertices[i];
            mxGeometry existing = graph.getModel().getGeometry(vertex);
            double w = existing != null ? existing.getWidth() : DEFAULT_NODE_WIDTH;
            double h = existing != null ? existing.getHeight() : DEFAULT_NODE_HEIGHT;

            int column = i % columns;
            int row = i / columns;
            graph.getModel().setGeometry(vertex, new mxGeometry(column * dx, row * dy, w, h));
        }
    }
}
