package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxUtils;
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
 * What the board brings forward and what it holds back.
 *
 * <p>On two published ontologies with every term shown, 528 and 1,139 edges: drawn alike, the
 * relations were a mesh across the whole board and the hierarchy could not be read under it.
 */
class CanvasFocusTest {

    private static final String NS = "http://example.org/o#";

    private static int strength(SchemaGraph graph, String id) {
        return mxUtils.getInt(graph.getCellStyle(graph.getCellForId(id)),
                mxConstants.STYLE_OPACITY, 100);
    }

    @Test
    void focusingATermFadesWhatItIsNotConnectedTo() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(
                java.util.Arrays.asList(new CanvasNode(NS + "A", NodeKind.CLASS, "A"),
                        new CanvasNode(NS + "B", NodeKind.CLASS, "B"),
                        new CanvasNode(NS + "C", NodeKind.CLASS, "C")),
                Collections.singletonList(new CanvasEdge("sub|a|b", NS + "A", NS + "B", "",
                        CanvasEdge.Kind.SUBCLASS))), new CanvasLayout());

        graph.setFocus(Collections.singletonList(NS + "A"));
        assertEquals(100, strength(graph, NS + "A"));
        assertEquals(100, strength(graph, NS + "B"), "connected to it");
        assertEquals(SchemaGraph.FADED, strength(graph, NS + "C"));

        graph.setFocus(Collections.<String>emptyList());
        assertEquals(100, strength(graph, NS + "C"), "and nothing is faded without a focus");
    }

    @Test
    void onADenseBoardDisjointnessWaitsForFocusAndRelationsAreQuiet() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        nodes.add(new CanvasNode(NS + "Root", NodeKind.CLASS, "Root"));
        for (int i = 0; i <= SchemaGraph.DENSE; i++) {
            nodes.add(new CanvasNode(NS + "C" + i, NodeKind.CLASS, "C" + i));
            edges.add(new CanvasEdge("sub|" + i, NS + "C" + i, NS + "Root", "",
                    CanvasEdge.Kind.SUBCLASS));
        }
        edges.add(new CanvasEdge("disj|1", NS + "C1", NS + "C2", "", CanvasEdge.Kind.DISJOINT));
        edges.add(new CanvasEdge("rest|1", NS + "C3", NS + "C4", "r",
                CanvasEdge.Kind.OBJECT_PROPERTY));
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), new CanvasLayout());

        assertTrue(graph.isDense());
        assertFalse(graph.isCellVisible(graph.getCellForId("disj|1")));
        assertEquals(SchemaGraph.QUIET, strength(graph, "rest|1"));

        graph.setFocus(Collections.singletonList(NS + "C1"));
        assertTrue(graph.isCellVisible(graph.getCellForId("disj|1")), "shown for its class");
        graph.setFocus(Collections.singletonList(NS + "C3"));
        assertEquals(100, strength(graph, "rest|1"), "full strength for its class");
    }

    /** "classification:Conce" over "pt" - a 160px box for every label - is not a label. */
    @Test
    void aBoxIsWideEnoughForItsLongestLine() {
        SchemaGraph graph = new SchemaGraph();
        double[] size = graph.fittedSize("classification:Concept", SchemaStyles.CLASS);
        java.awt.FontMetrics metrics = mxUtils.getFontMetrics(mxUtils.getFont(
                graph.getStylesheet().getCellStyle(SchemaStyles.CLASS,
                        graph.getStylesheet().getDefaultVertexStyle())));

        assertTrue(size[0] > metrics.stringWidth("classification:Concept"), size[0] + "");
        assertEquals(160, graph.fittedSize("Pizza", SchemaStyles.CLASS)[0], 0.001,
                "and no wider than before for a short one");
    }

    @Test
    void takingATermOffABoardThatShowsEverythingHidesItUntilItIsPutBack() {
        CanvasLayout layout = new CanvasLayout();
        layout.showsEverything = Boolean.TRUE;
        CanvasMembership membership = new CanvasMembership(layout);
        membership.add(NS + "A");

        membership.remove(NS + "A");
        assertEquals(Collections.singletonList(NS + "A"), layout.hidden);

        membership.add(NS + "A");
        assertTrue(layout.hidden.isEmpty());
    }
}
