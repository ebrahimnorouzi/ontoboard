package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A property's domain and range, shown when it is selected, run round the boxes like every other
 * line - and the lines already on the board stay where they were.
 *
 * <p>Found on the egg-boiling study board (1.135.1): domains, ranges and disjointness wait until one
 * of their ends is selected, the board was routed without them, and selecting a term did not route
 * again. So a revealed line had no route of its own: the dashed "domain" of "has specified input"
 * ran from the property section straight up through a frame, then sideways through three boxes.
 * And a board drawn again while a term was selected was routed with that term's lines among its
 * own, which moved some of them: "realizes" below, by 50px, on the board here.
 */
class RevealedLinesAreRoutedTest {

    private static final String NS = "http://example.org/egg#";
    private static final String INPUT = NS + "hasSpecifiedInput";
    private static final String QUALITY = NS + "hasQuality";
    private static final String DOMAIN = "domain|" + INPUT + "|" + NS + "plannedProcess";
    private static final String RANGE = "range|" + INPUT + "|" + NS + "continuant";
    private static final String OTHER_RANGE = "range|" + QUALITY + "|" + NS + "quality";

    /**
     * A pattern's frame of classes over a section of properties, as the Outline draws them: the
     * property's domain stands straight above it with another class in between, so a line drawn
     * straight up runs through that class.
     */
    private static SchemaGraph board() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        Object[][] classes = {
            {"entity", 80, 120}, {"continuant", 120, 200}, {"object", 160, 280},
            {"quality", 160, 340}, {"occurrent", 600, 120}, {"process", 640, 200},
            {"plannedProcess", 680, 280}, {"assay", 720, 360}, {"plan", 1000, 200}};
        for (Object[] cls : classes) {
            nodes.add(new CanvasNode(NS + cls[0], NodeKind.CLASS, (String) cls[0]));
            place(layout, NS + cls[0], (Integer) cls[1], (Integer) cls[2], 200, 40);
        }
        String[][] subclasses = {{"continuant", "entity"}, {"object", "continuant"},
            {"quality", "continuant"}, {"process", "occurrent"}, {"plannedProcess", "process"},
            {"assay", "plannedProcess"}};
        for (String[] sub : subclasses) {
            edges.add(new CanvasEdge("sub|" + sub[0], NS + sub[0], NS + sub[1], "",
                    CanvasEdge.Kind.SUBCLASS));
        }
        edges.add(new CanvasEdge("rel|input", NS + "assay", NS + "object",
                "∃ has specified input", CanvasEdge.Kind.OBJECT_PROPERTY));
        edges.add(new CanvasEdge("rel|realizes", NS + "plannedProcess", NS + "plan",
                "∃ realizes", CanvasEdge.Kind.OBJECT_PROPERTY));
        edges.add(new CanvasEdge("rel|planOf", NS + "plan", NS + "plannedProcess",
                "∃ is plan of", CanvasEdge.Kind.OBJECT_PROPERTY));
        frame(layout, SchemaGraph.FRAME_ID_PREFIX + "pattern", "Measurement", 40, 40, 1200, 480);

        // The section's boxes stand 44px under its top, off the 20px grid, as the Outline puts them.
        frame(layout, CanvasLayouts.SECTION_PREFIX + "object-properties", "Object properties", 40,
                600, 1200, 200);
        Object[][] properties = {{INPUT, "has specified input", 700}, {QUALITY, "has quality", 160},
            {NS + "realizes", "realizes", 1000}};
        for (Object[] property : properties) {
            nodes.add(new CanvasNode((String) property[0], NodeKind.OBJECT_PROPERTY,
                    (String) property[1]));
            place(layout, (String) property[0], (Integer) property[2], 644, 200, 60);
        }
        edges.add(new CanvasEdge(DOMAIN, INPUT, NS + "plannedProcess", "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(RANGE, INPUT, NS + "continuant", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(OTHER_RANGE, QUALITY, NS + "quality", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));

        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    private static void place(CanvasLayout layout, String id, double x, double y, double w,
            double h) {
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = w;
        node.h = h;
        layout.nodes.put(id, node);
    }

    private static void frame(CanvasLayout layout, String id, String label, double x, double y,
            double w, double h) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = id;
        frame.label = label;
        frame.x = x;
        frame.y = y;
        frame.w = w;
        frame.h = h;
        layout.frames.add(frame);
    }

    @Test
    void aSelectedPropertysDomainAndRangeAreRoutedRoundTheBoxesAndNothingElseMoves() {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.getView().validate();
        assertFalse(graph.isCellVisible(graph.getCellForId(DOMAIN)), "waits for a selection");
        Map<String, String> before = whatIsDrawn(graph);
        assertTrue(before.size() >= 8, "the board's own lines: " + before.keySet());

        graph.setFocus(Collections.singletonList(INPUT));
        graph.getView().validate();

        for (String id : new String[] {DOMAIN, RANGE}) {
            Object edge = graph.getCellForId(id);
            assertTrue(graph.isCellVisible(edge), id + " shown for its property");
            assertNotNull(graph.routeOf(edge), id + " has a route of its own");
            assertEquals(new ArrayList<String>(), boxesPassed(graph, edge),
                    id + " runs round the boxes");
        }
        assertFalse(graph.isCellVisible(graph.getCellForId(OTHER_RANGE)),
                "another property's range still waits");
        assertEquals(before, whatIsDrawn(graph, before.keySet()),
                "every line already on the board where it was, its label too");

        // A redraw with the property still selected - after an edit - routes the board again.
        graph.routeEdges();
        graph.getView().validate();
        assertEquals(before, whatIsDrawn(graph, before.keySet()),
                "routed again with the selection on: nothing that was there moves");
        for (String id : new String[] {DOMAIN, RANGE}) {
            assertNotNull(graph.routeOf(graph.getCellForId(id)), id + " still routed");
            assertEquals(new ArrayList<String>(), boxesPassed(graph, graph.getCellForId(id)),
                    id + " still runs round the boxes");
        }

        graph.setFocus(Collections.<String>emptyList());
        graph.getView().validate();
        assertFalse(graph.isCellVisible(graph.getCellForId(DOMAIN)), "hidden again");
        assertEquals(before, whatIsDrawn(graph), "and the board is as it was");
    }

    /**
     * The board's own lines do not depend on what is selected when it is routed: a board drawn
     * again after an edit, with a property still selected, is routed as it is with nothing selected,
     * and what the selection shows is routed among those lines.
     */
    @Test
    void aBoardRoutedWithAPropertySelectedHasItsOwnLinesWhereTheyAreWithNone() {
        SchemaGraph none = board();
        none.routeEdges();
        none.getView().validate();
        Map<String, String> unselected = whatIsDrawn(none);

        SchemaGraph selected = board();
        selected.setFocus(Collections.singletonList(INPUT));
        selected.routeEdges();
        selected.getView().validate();

        assertEquals(unselected, whatIsDrawn(selected, unselected.keySet()),
                "the lines on the board routed as with nothing selected");
        for (String id : new String[] {DOMAIN, RANGE}) {
            assertNotNull(selected.routeOf(selected.getCellForId(id)), id + " routed");
            assertEquals(new ArrayList<String>(), boxesPassed(selected, selected.getCellForId(id)),
                    id + " runs round the boxes");
        }
    }

    /** Every visible line: its route, the points drawn, and where its label sits. */
    private static Map<String, String> whatIsDrawn(SchemaGraph graph) {
        List<String> ids = new ArrayList<String>();
        for (Object edge : edges(graph)) {
            if (graph.isCellVisible(edge)) {
                ids.add(graph.getIdForCell(edge));
            }
        }
        return whatIsDrawn(graph, ids);
    }

    private static Map<String, String> whatIsDrawn(SchemaGraph graph,
            java.util.Collection<String> ids) {
        Map<String, String> drawn = new LinkedHashMap<String, String>();
        for (String id : ids) {
            Object edge = graph.getCellForId(id);
            List<double[]> route = graph.routeOf(edge);
            StringBuilder text = new StringBuilder("route");
            if (route == null) {
                text.append(" none");
            } else {
                for (double[] bend : route) {
                    text.append(' ').append(Arrays.toString(bend));
                }
            }
            text.append(" drawn");
            for (double[] point : points(graph, edge)) {
                text.append(' ').append(Arrays.toString(point));
            }
            text.append(" label at ").append(graph.getModel().getGeometry(edge).getX());
            drawn.put(id, text.toString());
        }
        return drawn;
    }

    private static List<Object> edges(SchemaGraph graph) {
        List<Object> edges = new ArrayList<Object>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            if (graph.getModel().isEdge(cell)) {
                edges.add(cell);
            }
        }
        return edges;
    }

    private static double[][] points(SchemaGraph graph, Object edge) {
        mxCellState state = graph.getView().getState(edge);
        if (state == null || state.getAbsolutePoints() == null) {
            return new double[0][];
        }
        List<mxPoint> drawn = state.getAbsolutePoints();
        double[][] line = new double[drawn.size()][];
        for (int i = 0; i < line.length; i++) {
            line[i] = new double[] {drawn.get(i).getX(), drawn.get(i).getY()};
        }
        return line;
    }

    /** The boxes the line runs through, but for its own two ends. */
    private static List<String> boxesPassed(SchemaGraph graph, Object edge) {
        double[][] line = points(graph, edge);
        assertTrue(line.length >= 2, "drawn");
        Object from = graph.getModel().getTerminal(edge, true);
        Object to = graph.getModel().getTerminal(edge, false);
        List<String> passed = new ArrayList<String>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object box = graph.getModel().getChildAt(parent, i);
            String style = graph.getModel().getStyle(box);
            if (!graph.getModel().isVertex(box) || box == from || box == to
                    || (style != null && style.startsWith(SchemaStyles.FRAME))) {
                continue;
            }
            mxCellState state = graph.getView().getState(box);
            double[] rect = {state.getX(), state.getY(), state.getWidth(), state.getHeight()};
            if (BoardMeasures.crosses(line, rect)) {
                passed.add(String.valueOf(graph.getModel().getValue(box)));
            }
        }
        return passed;
    }
}
