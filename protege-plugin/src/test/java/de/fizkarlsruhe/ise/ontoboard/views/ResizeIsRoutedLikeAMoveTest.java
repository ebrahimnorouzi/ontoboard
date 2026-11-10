package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A box or a frame resized by its handles is followed by what a move is followed by: its position
 * kept, and the board routed again.
 *
 * <p>Found by a review of 1.135.1: terms, notes and frames can be resized on the board, and only a
 * move was listened for. After widening "object", the range line a selection showed ran through it,
 * and on the next click every revealed line was drawn straight through the boxes, until something
 * else moved.
 */
class ResizeIsRoutedLikeAMoveTest {

    private static final String NS = "http://example.org/egg#";
    private static final String INPUT = NS + "hasSpecifiedInput";
    private static final String RANGE = "range|" + INPUT + "|" + NS + "continuant";

    /** Between a property's section and its range class, a class that a wider box gets in the way of. */
    private static SchemaGraph board() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        Object[][] classes = {
            {"entity", 80, 120}, {"continuant", 120, 200}, {"object", 160, 280},
            {"quality", 160, 340}, {"plannedProcess", 680, 280}};
        for (Object[] cls : classes) {
            nodes.add(new CanvasNode(NS + cls[0], NodeKind.CLASS, (String) cls[0]));
            CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout((Integer) cls[1],
                    (Integer) cls[2]);
            node.w = 200;
            node.h = 40;
            layout.nodes.put(NS + cls[0], node);
        }
        edges.add(new CanvasEdge("sub|continuant", NS + "continuant", NS + "entity", "",
                CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("sub|object", NS + "object", NS + "continuant", "",
                CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("sub|quality", NS + "quality", NS + "continuant", "",
                CanvasEdge.Kind.SUBCLASS));
        nodes.add(new CanvasNode(INPUT, NodeKind.OBJECT_PROPERTY, "has specified input"));
        CanvasLayout.NodeLayout property = new CanvasLayout.NodeLayout(700, 644);
        property.w = 200;
        property.h = 60;
        layout.nodes.put(INPUT, property);
        edges.add(new CanvasEdge(RANGE, INPUT, NS + "continuant", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("domain|" + INPUT + "|" + NS + "plannedProcess", INPUT,
                NS + "plannedProcess", "domain", CanvasEdge.Kind.DOMAIN_RANGE));
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    /** The boxes the line runs through, but for its own two ends, as drawn. */
    private static List<String> boxesPassed(SchemaGraph graph, Object edge) {
        List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
        List<String> passed = new ArrayList<String>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object box = graph.getModel().getChildAt(parent, i);
            String style = graph.getModel().getStyle(box);
            if (!graph.getModel().isVertex(box) || box == graph.getModel().getTerminal(edge, true)
                    || box == graph.getModel().getTerminal(edge, false)
                    || style != null && style.startsWith("obFrame")) {
                continue;
            }
            mxCellState state = graph.getView().getState(box);
            for (int k = 1; k < points.size(); k++) {
                double x1 = Math.min(points.get(k - 1).getX(), points.get(k).getX());
                double x2 = Math.max(points.get(k - 1).getX(), points.get(k).getX());
                double y1 = Math.min(points.get(k - 1).getY(), points.get(k).getY());
                double y2 = Math.max(points.get(k - 1).getY(), points.get(k).getY());
                if (x2 > state.getX() + 1 && x1 < state.getX() + state.getWidth() - 1
                        && y2 > state.getY() + 1 && y1 < state.getY() + state.getHeight() - 1) {
                    passed.add(String.valueOf(graph.getModel().getValue(box)));
                    break;
                }
            }
        }
        return passed;
    }

    @Test
    void aBoxResizedByItsHandlesIsRoutedRoundAsAMovedOneIs() {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.setFocus(Collections.singletonList(INPUT));
        graph.getView().validate();
        Object range = graph.getCellForId(RANGE);
        assertEquals(new ArrayList<String>(), boxesPassed(graph, range), "before the resize");
        List<String> heard = new ArrayList<String>();
        // What the board's own listener does that matters here: the board routed again.
        SchemaCanvasView.listenForMovesAndResizes(graph, (sender, event) -> {
            heard.add(event.getName());
            graph.routeEdges();
        });

        graph.resizeCell(graph.getCellForId(NS + "object"), new mxRectangle(160, 280, 300, 40));
        graph.getView().validate();

        assertEquals(Collections.singletonList(mxEvent.CELLS_RESIZED), heard,
                "a resize reaches what a move reaches");
        assertTrue(graph.isCellVisible(range), "the range still shown");
        assertEquals(new ArrayList<String>(), boxesPassed(graph, range),
                "the range routed round the widened box");
    }
}
