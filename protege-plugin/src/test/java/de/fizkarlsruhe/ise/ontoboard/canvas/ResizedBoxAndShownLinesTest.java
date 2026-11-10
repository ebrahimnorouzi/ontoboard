package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxRectangle;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A line a selection shows is not drawn along a route worked out for boxes that have since moved or
 * changed size.
 *
 * <p>Found by a review of 1.135.1: select "has specified input", then widen "object" by its handle
 * from 200 to 260 px. The range line kept the route it had, which now ran through "object" - a
 * line's route was checked against its own two ends only, and nothing routed the board after a
 * resize.
 */
class ResizedBoxAndShownLinesTest {

    static final String NS = "http://example.org/egg#";
    static final String INPUT = NS + "hasSpecifiedInput";
    static final String DOMAIN = "domain|" + INPUT + "|" + NS + "plannedProcess";
    static final String RANGE = "range|" + INPUT + "|" + NS + "continuant";

    /** A pattern's frame over a section of properties, as in RevealedLinesAreRoutedTest. */
    static SchemaGraph board() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        Object[][] classes = {
            {"entity", 80, 120}, {"continuant", 120, 200}, {"object", 160, 280},
            {"quality", 160, 340}, {"occurrent", 600, 120}, {"process", 640, 200},
            {"plannedProcess", 680, 280}, {"assay", 720, 360}, {"plan", 1000, 200}};
        for (Object[] cls : classes) {
            nodes.add(new CanvasNode(NS + cls[0], NodeKind.CLASS, (String) cls[0]));
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout((Integer) cls[1],
                    (Integer) cls[2]);
            node.w = 200;
            node.h = 40;
            layout.nodes.put(NS + cls[0], node);
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
        CanvasLayout.FrameLayout pattern = new CanvasLayout.FrameLayout();
        pattern.id = SchemaGraph.FRAME_ID_PREFIX + "pattern";
        pattern.label = "Measurement";
        pattern.x = 40;
        pattern.y = 40;
        pattern.w = 1200;
        pattern.h = 480;
        layout.frames.add(pattern);
        CanvasLayout.FrameLayout section = new CanvasLayout.FrameLayout();
        section.id = CanvasLayouts.SECTION_PREFIX + "object-properties";
        section.label = "Object properties";
        section.x = 40;
        section.y = 600;
        section.w = 1200;
        section.h = 200;
        layout.frames.add(section);
        Object[][] properties = {{INPUT, "has specified input", 700},
            {NS + "hasQuality", "has quality", 160}};
        for (Object[] property : properties) {
            nodes.add(new CanvasNode((String) property[0], NodeKind.OBJECT_PROPERTY,
                    (String) property[1]));
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout((Integer) property[2], 644);
            node.w = 200;
            node.h = 60;
            layout.nodes.put((String) property[0], node);
        }
        edges.add(new CanvasEdge(DOMAIN, INPUT, NS + "plannedProcess", "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(RANGE, INPUT, NS + "continuant", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    private static boolean same(List<double[]> one, List<double[]> other) {
        if (one == null || other == null || one.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < one.size(); i++) {
            if (!Arrays.equals(one.get(i), other.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Test
    void aRouteMadeBeforeABoxWasWidenedIsNotHandedOutAfter() {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.setFocus(Collections.singletonList(INPUT));
        graph.getView().validate();
        Object range = graph.getCellForId(RANGE);
        List<double[]> before = graph.routeOf(range);
        assertNotNull(before, "the range is routed while its property is selected");
        assertEquals(new ArrayList<String>(), new BoardMeasures("before", graph).through);

        // The person drags the right handle of "object", as mxVertexHandler does on release.
        graph.resizeCell(graph.getCellForId(NS + "object"), new mxRectangle(160, 280, 260, 40));
        graph.getView().validate();

        List<double[]> after = graph.routeOf(range);
        List<String> through = new BoardMeasures("resized", graph).through;
        assertTrue(after == null || !same(before, after),
                "the route worked out before the resize, handed out after it; drawn now: " + through);
    }
}
