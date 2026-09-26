package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.model.mxGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Automatic arrangements, mapping the retired web app's layout menu onto JGraphX. */
public final class CanvasLayouts {

    /**
     * JGraphX ships no grid layout at all: {@code mxStackLayout} is a single-row/column
     * stack with no wrapping capability (no {@code setWrap} method exists on it, despite
     * some documentation implying otherwise). GRID is therefore implemented here as a
     * small custom wrapping arrangement rather than delegating to a library class.
     *
     * <p>{@link #GRID_COLUMNS} is a fixed column count rather than one derived from the
     * node count, so a call to {@link #apply} always wraps at the same place regardless of
     * how many vertices the graph holds - simple and fully deterministic. Four columns of
     * the default {@value #DEFAULT_NODE_WIDTH}x{@value #DEFAULT_NODE_HEIGHT} node plus
     * {@value #GRID_GAP}px gap keeps a row at (160+40)*4 = 800px, a comfortable width for
     * the canvas viewport without the diagram becoming a single wide strip.
     */
    private static final int GRID_COLUMNS = 4;
    private static final double GRID_GAP = 40;
    private static final double DEFAULT_NODE_WIDTH = 160;
    private static final double DEFAULT_NODE_HEIGHT = 60;

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
     * Arranges the terms on the board, leaving sticky notes and frames where the user put them.
     *
     * <p>Annotations are not terms and must not be laid out as if they were. A frame is a region
     * drawn around a group of classes; slotted into a grid it lands in a cell of its own and
     * encloses nothing, which destroys the only thing it was for. A note pinned beside the class it
     * comments on ends up in a row among the classes.
     *
     * <p>The library layouts take a parent rather than a vertex list, so their reach cannot be
     * restricted up front. Annotation positions are therefore snapshotted and put back afterwards,
     * which works identically for all three algorithms and needs no knowledge of what each does.
     * {@link #applyGrid} builds its own vertex list and filters instead.
     */
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

    public static void apply(SchemaGraph graph, Algorithm algorithm) {
        graph.getModel().beginUpdate();
        try {
            Map<Object, mxGeometry> annotations = annotationGeometries(graph);
            if (algorithm == Algorithm.GRID) {
                applyGrid(graph);
            } else {
                mxIGraphLayout layout = createLibraryLayout(graph, algorithm);
                layout.execute(graph.getDefaultParent());
            }
            for (Map.Entry<Object, mxGeometry> kept : annotations.entrySet()) {
                graph.getModel().setGeometry(kept.getKey(), kept.getValue());
            }
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
        switch (algorithm) {
            case ORGANIC:
                return new mxOrganicLayout(graph);
            case CIRCLE:
                return new mxCircleLayout(graph);
            case HIERARCHICAL:
            default:
                return new mxHierarchicalLayout(graph);
        }
    }

    /**
     * Places every vertex at {@code (col * dx, row * dy)}, wrapping to the next row after
     * {@link #GRID_COLUMNS} columns. Vertices are iterated in {@code getChildVertices}'
     * order, which is the order they were inserted into the model by
     * {@link SchemaGraph#render} - stable and deterministic, unlike a hash-based
     * iteration order would be.
     *
     * <p>The row/column pitch ({@code dx}/{@code dy}) is derived from the largest
     * width/height actually present among the graph's own cells (falling back to the
     * defaults {@link SchemaGraph} itself uses when a cell somehow has no geometry), so a
     * canvas with resized nodes still gets a non-overlapping grid. Column and row indices
     * are always {@code >= 0}, so every resulting coordinate is non-negative.
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

        for (int i = 0; i < vertices.length; i++) {
            Object vertex = vertices[i];
            mxGeometry existing = graph.getModel().getGeometry(vertex);
            double w = existing != null ? existing.getWidth() : DEFAULT_NODE_WIDTH;
            double h = existing != null ? existing.getHeight() : DEFAULT_NODE_HEIGHT;

            int column = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            graph.getModel().setGeometry(vertex, new mxGeometry(column * dx, row * dy, w, h));
        }
    }
}
