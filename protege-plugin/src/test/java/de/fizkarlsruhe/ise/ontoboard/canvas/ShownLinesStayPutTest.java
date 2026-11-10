package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A line a selection shows stays where it is drawn while it stays shown: the maintainer's "no
 * jumping", for these lines as for the board's own.
 *
 * <p>Found by a review of 1.135.1 on the egg-boiling study board: click "has specified output", and
 * its dashed domain runs to "completely executed planned process"; click that class, the line's
 * other end, and the line - still on the screen - was drawn again elsewhere, its label 850 px away.
 * Each selection routed every line it showed afresh, shortest first, so a line shown before had its
 * lane taken by a shorter one shown now: 13 of the 28 domain and range lines on that board were
 * drawn differently from one end than from the other, and adding a second property to the selection
 * moved a line the first showed in 96 of 414 pairs.
 */
class ShownLinesStayPutTest {

    private static final String NS = "http://example.org/egg#";
    private static final String INPUT = NS + "hasSpecifiedInput";
    private static final String OUTPUT = NS + "hasSpecifiedOutput";
    private static final String QUALITY = NS + "hasQuality";
    private static final String DOMAIN = "domain|" + INPUT + "|" + NS + "plannedProcess";
    private static final String RANGE = "range|" + INPUT + "|" + NS + "continuant";
    private static final String OUT_DOMAIN = "domain|" + OUTPUT + "|" + NS + "plannedProcess";
    private static final String OUT_RANGE = "range|" + OUTPUT + "|" + NS + "continuant";
    private static final String OTHER_RANGE = "range|" + QUALITY + "|" + NS + "quality";

    /**
     * A pattern's frame of classes over a section of four properties, as the Outline draws them:
     * two of the properties share their domain and their range, so a line one of them shows and a
     * line the other shows meet at the same class.
     */
    private static Projection projection(boolean outputHasARange) {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        for (Object[] cls : CLASSES) {
            nodes.add(new CanvasNode(NS + cls[0], NodeKind.CLASS, (String) cls[0]));
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
        for (Object[] property : PROPERTIES) {
            nodes.add(new CanvasNode((String) property[0], NodeKind.OBJECT_PROPERTY,
                    (String) property[1]));
        }
        edges.add(new CanvasEdge(DOMAIN, INPUT, NS + "plannedProcess", "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(RANGE, INPUT, NS + "continuant", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(OUT_DOMAIN, OUTPUT, NS + "plannedProcess", "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        if (outputHasARange) {
            edges.add(new CanvasEdge(OUT_RANGE, OUTPUT, NS + "continuant", "range",
                    CanvasEdge.Kind.DOMAIN_RANGE));
        }
        edges.add(new CanvasEdge(OTHER_RANGE, QUALITY, NS + "quality", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        return new Projection(nodes, edges);
    }

    private static final Object[][] CLASSES = {
        {"entity", 80, 120}, {"continuant", 120, 200}, {"object", 160, 280},
        {"quality", 160, 340}, {"occurrent", 600, 120}, {"process", 640, 200},
        {"plannedProcess", 680, 280}, {"assay", 720, 360}, {"plan", 1000, 200}};

    private static final Object[][] PROPERTIES = {{INPUT, "has specified input", 700},
        {QUALITY, "has quality", 160}, {OUTPUT, "has specified output", 440},
        {NS + "realizes", "realizes", 1000}};

    private static CanvasLayout layout() {
        CanvasLayout layout = new CanvasLayout();
        for (Object[] cls : CLASSES) {
            place(layout, NS + cls[0], (Integer) cls[1], (Integer) cls[2], 200, 40);
        }
        // The section's boxes stand 44px under its top, off the 20px grid, as the Outline puts them.
        for (Object[] property : PROPERTIES) {
            place(layout, (String) property[0], (Integer) property[2], 644, 200, 60);
        }
        frame(layout, SchemaGraph.FRAME_ID_PREFIX + "pattern", "Measurement", 40, 40, 1200, 480);
        frame(layout, CanvasLayouts.SECTION_PREFIX + "object-properties", "Object properties", 40,
                600, 1200, 200);
        return layout;
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

    private static SchemaGraph routed(boolean outputHasARange) {
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection(outputHasARange), layout());
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    /** A selection change as the board's selection listener makes it: one call, then a drawing. */
    private static void select(SchemaGraph graph, String... ids) {
        graph.setFocus(Arrays.asList(ids));
        graph.getView().validate();
    }

    /** What a reader sees of a line: whether it is drawn, the points drawn, and its label's box. */
    private static String seen(SchemaGraph graph, String id) {
        Object edge = graph.getCellForId(id);
        if (!graph.isCellVisible(edge)) {
            return "not drawn";
        }
        mxCellState state = graph.getView().getState(edge);
        StringBuilder text = new StringBuilder(graph.routeOf(edge) == null ? "plain" : "routed");
        for (mxPoint p : state.getAbsolutePoints()) {
            text.append(" (").append(p.getX()).append(',').append(p.getY()).append(')');
        }
        mxRectangle label = state.getLabelBounds();
        if (label != null) {
            text.append(" label ").append(label.getX()).append(',').append(label.getY()).append(' ')
                    .append(label.getWidth()).append('x').append(label.getHeight());
        }
        return text.toString();
    }

    /** Routed, and through no box but its own two ends. */
    private static void routedRoundTheBoxes(SchemaGraph graph, String id) {
        Object edge = graph.getCellForId(id);
        assertTrue(graph.isCellVisible(edge), id + " is drawn");
        assertNotNull(graph.routeOf(edge), id + " has a route of its own");
        BoardMeasures measured = new BoardMeasures(id, graph);
        for (String through : measured.through) {
            assertFalse(through.startsWith("domain ") || through.startsWith("range "),
                    "a line the selection shows runs through a box: " + through);
        }
    }

    @Test
    void aShownLineStaysWhereItIsWhenItsOtherEndIsClicked() {
        SchemaGraph graph = routed(true);
        select(graph, INPUT);
        String range = seen(graph, RANGE);
        assertTrue(range.startsWith("routed"), range);

        // The line's other end: continuant, where has specified output's range arrives too.
        select(graph, NS + "continuant");
        assertEquals(range, seen(graph, RANGE), "the range of has specified input, its class clicked");
        routedRoundTheBoxes(graph, OUT_RANGE);
        assertEquals("not drawn", seen(graph, DOMAIN), "the domain, no longer touching the selection");

        // And the domain, from its class.
        select(graph, INPUT);
        String domain = seen(graph, DOMAIN);
        select(graph, NS + "plannedProcess");
        assertEquals(domain, seen(graph, DOMAIN), "the domain of has specified input, its class clicked");
        routedRoundTheBoxes(graph, OUT_DOMAIN);
    }

    @Test
    void aShownLineStaysWhereItIsWhenASecondTermJoinsTheSelection() {
        SchemaGraph graph = routed(true);
        select(graph, INPUT);
        String domain = seen(graph, DOMAIN);
        String range = seen(graph, RANGE);

        // Ctrl+click: has specified output added, its domain and range to the same two classes.
        select(graph, INPUT, OUTPUT);
        assertEquals(domain, seen(graph, DOMAIN), "the domain of has specified input");
        assertEquals(range, seen(graph, RANGE), "the range of has specified input");
        routedRoundTheBoxes(graph, OUT_DOMAIN);
        routedRoundTheBoxes(graph, OUT_RANGE);

        // And taken away again: what stays shown stays put.
        select(graph, INPUT);
        assertEquals(domain, seen(graph, DOMAIN), "the domain, the second property deselected");
        assertEquals(range, seen(graph, RANGE), "the range, the second property deselected");
        assertEquals("not drawn", seen(graph, OUT_RANGE));
    }

    /**
     * An edit while continuant is selected gives has specified output the range continuant too,
     * and the board is drawn again as SchemaCanvasView.refresh draws it: render, routeEdges, then
     * the selection applied again.
     */
    @Test
    void aShownLineStaysWhereItIsWhenAnEditAddsAnotherToItsClass() {
        SchemaGraph graph = routed(false);
        select(graph, NS + "continuant");
        String range = seen(graph, RANGE);
        assertTrue(range.startsWith("routed"), range);

        graph.render(projection(true), layout());
        graph.routeEdges();
        select(graph, NS + "continuant");
        assertEquals(range, seen(graph, RANGE), "the range shown before the edit");
        routedRoundTheBoxes(graph, OUT_RANGE);
    }
}
