package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Edges kept out of frames neither of their ends is in.
 *
 * <p>Seen on the pizza walkthrough's board once Add all framed its three patterns in a row: an
 * edge between the first and the third frame ran straight through the second.
 */
class AroundFramesTest {

    private static final String LEFT = "http://example.org/o#Left";
    private static final String RIGHT = "http://example.org/o#Right";
    private static final String BELOW = "http://example.org/o#Below";

    @Test
    void aRouteBetweenTheOuterFramesOfARowGoesAroundTheMiddleOne() {
        Rectangle2D middle = new Rectangle2D.Double(400, 60, 300, 300);
        Point2D from = new Point2D.Double(210, 210);
        Point2D to = new Point2D.Double(890, 210);

        List<Point2D> bends = AroundFrames.route(from, to, Collections.singletonList(middle),
                AroundFrames.CLEARANCE, AroundFrames.BEND);

        assertNotNull(bends);
        assertEquals(2, bends.size(), "out of the row, along it, back in: " + bends);
        Rectangle2D clear = new Rectangle2D.Double(400 - AroundFrames.CLEARANCE + 0.5,
                60 - AroundFrames.CLEARANCE + 0.5, 300 + 2 * AroundFrames.CLEARANCE - 1,
                300 + 2 * AroundFrames.CLEARANCE - 1);
        assertFalse(AroundFrames.crossesAny(from, bends, to, Collections.singletonList(clear)),
                "and not closer to it than the clearance: " + bends);
        assertTrue(AroundFrames.crossesAny(from, new ArrayList<Point2D>(), to,
                Collections.singletonList(middle)), "where the straight line went through it");
    }

    @Test
    void anEndInsideTheClearanceStillGetsOut() {
        Rectangle2D frame = new Rectangle2D.Double(100, 100, 200, 200);
        Point2D from = new Point2D.Double(95, 200);
        Point2D to = new Point2D.Double(400, 200);

        List<Point2D> bends = AroundFrames.route(from, to, Collections.singletonList(frame),
                AroundFrames.CLEARANCE, AroundFrames.BEND);

        assertNotNull(bends);
        assertFalse(AroundFrames.crossesAny(from, bends, to, Collections.singletonList(frame)));
    }

    /** Through the drawn board: what a user sees, not only the geometry. */
    @Test
    void onTheBoardAnEdgeAcrossARowOfFramesGoesAroundTheOneInBetween() {
        SchemaGraph graph = boardWithThreeFramesInARow();

        mxCellState across = graph.getView().getState(graph.getCellForId("across"));
        mxCellState middle = graph.getView().getState(graph.getCellForId("ontoboard-frame-b"));

        Rectangle2D box = new Rectangle2D.Double(middle.getX(), middle.getY(),
                middle.getWidth(), middle.getHeight());
        List<mxPoint> drawn = across.getAbsolutePoints();
        for (int i = 1; i < drawn.size(); i++) {
            assertFalse(box.intersectsLine(drawn.get(i - 1).getX(), drawn.get(i - 1).getY(),
                    drawn.get(i).getX(), drawn.get(i).getY()),
                    "segment " + i + " of " + drawn + " enters the middle frame " + box);
        }
    }

    /** Nothing changes for an edge no frame is in the way of - most edges on most boards. */
    @Test
    void anEdgeWithNoFrameInTheWayIsDrawnExactlyAsBefore() {
        SchemaGraph graph = boardWithThreeFramesInARow();
        Object down = graph.getCellForId("down");
        List<mxPoint> now = copy(graph.getView().getState(down).getAbsolutePoints());

        graph.setCellStyles(mxConstants.STYLE_EDGE, mxConstants.EDGESTYLE_ORTHOGONAL,
                new Object[] {down});
        graph.getView().revalidate();

        assertEquals(copy(graph.getView().getState(down).getAbsolutePoints()), now);
    }

    private static SchemaGraph boardWithThreeFramesInARow() {
        CanvasLayout layout = new CanvasLayout();
        layout.frames.add(frame("ontoboard-frame-a", 60));
        layout.frames.add(frame("ontoboard-frame-b", 400));
        layout.frames.add(frame("ontoboard-frame-c", 740));
        layout.nodes.put(LEFT, new CanvasLayout.NodeLayout(130, 180));
        layout.nodes.put(RIGHT, new CanvasLayout.NodeLayout(810, 180));
        layout.nodes.put(BELOW, new CanvasLayout.NodeLayout(130, 600));
        Projection projection = new Projection(
                Arrays.asList(new CanvasNode(LEFT, NodeKind.CLASS, "Left"),
                        new CanvasNode(RIGHT, NodeKind.CLASS, "Right"),
                        new CanvasNode(BELOW, NodeKind.CLASS, "Below")),
                Arrays.asList(new CanvasEdge("across", LEFT, RIGHT, "", CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("down", BELOW, LEFT, "", CanvasEdge.Kind.SUBCLASS)));
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection, layout);
        graph.getView().revalidate();
        return graph;
    }

    private static CanvasLayout.FrameLayout frame(String id, double x) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = id;
        frame.label = id;
        frame.x = x;
        frame.y = 60;
        frame.w = 300;
        frame.h = 300;
        return frame;
    }

    private static List<mxPoint> copy(List<mxPoint> points) {
        List<mxPoint> copy = new ArrayList<mxPoint>();
        for (mxPoint point : points) {
            copy.add(new mxPoint(Math.round(point.getX() * 100) / 100.0,
                    Math.round(point.getY() * 100) / 100.0));
        }
        return copy;
    }
}
