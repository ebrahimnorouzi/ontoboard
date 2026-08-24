package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.model.mxGeometry;

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

    public static void apply(SchemaGraph graph, Algorithm algorithm) {
        graph.getModel().beginUpdate();
        try {
            if (algorithm == Algorithm.GRID) {
                applyGrid(graph);
            } else {
                mxIGraphLayout layout = createLibraryLayout(graph, algorithm);
                layout.execute(graph.getDefaultParent());
            }
        } finally {
            graph.getModel().endUpdate();
        }
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
        Object[] vertices = graph.getChildVertices(graph.getDefaultParent());

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
