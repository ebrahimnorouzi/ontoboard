package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * After Relate, the new arrow and both its boxes are in view (1.135.1, Windows study rehearsal).
 * A tester relating egg boiling to raw egg saw nothing happen: raw egg stood 1,160 px away, off
 * the window, and the arrow with it.
 */
class RelationInViewTest {

    private static final String A = "http://example.org/egg#boiling";
    private static final String B = "http://example.org/egg#rawEgg";
    private static final String C = "http://example.org/egg#report";

    @Test
    void anArrowAcrossTheFrameIsShownWithBothEndsAsLargeAsTheWindowAllows() {
        assertEquals(662.0 / 1340, RelationInView.scaleToShow(1340, 80, 710, 480, 0.46, false), 1e-9);
    }

    @Test
    void afterFitBothEndsOnScreenButUnreadableAreBroughtCloser() {
        double scale = RelationInView.scaleToShow(1340, 240, 710, 480, 0.26, true);
        assertTrue(scale > 0.26 && Math.abs(scale - 662.0 / 1340) < 1e-9, String.valueOf(scale));
    }

    @Test
    void bothEndsAlreadyOnScreenAtAReadableZoomMoveNothing() {
        assertTrue(Double.isNaN(RelationInView.scaleToShow(1340, 240, 710, 480, 0.5, true)));
        assertTrue(Double.isNaN(RelationInView.scaleToShow(300, 60, 710, 480, 1.0, true)));
        assertTrue(Double.isNaN(RelationInView.scaleToShow(300, 60, 0, 0, 1.0, false)),
                "a window not laid out yet is left alone");
    }

    @Test
    void aRelationTooLongToReadIsStillShownWhole() {
        assertEquals(662.0 / 1840, RelationInView.scaleToShow(1840, 240, 710, 480, 1.0, false), 1e-9);
    }

    @Test
    void theCellsAreTheArrowAndBothEndsOrNothing() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(A, NodeKind.CLASS, "egg boiling"),
                new CanvasNode(B, NodeKind.CLASS, "raw egg"),
                new CanvasNode(C, NodeKind.CLASS, "egg boiling report")),
                Collections.singletonList(new CanvasEdge("e1", A, B, "∃ has specified input",
                        CanvasEdge.Kind.OBJECT_PROPERTY))), new CanvasLayout());
        assertEquals(3, RelationInView.cellsOf(graph, A, B).length);
        assertEquals(3, RelationInView.cellsOf(graph, B, A).length, "either direction");
        assertEquals(0, RelationInView.cellsOf(graph, A, C).length, "no arrow drawn, nothing moves");
        assertEquals(0, RelationInView.cellsOf(graph, A, "http://example.org/egg#gone").length);
    }
}
