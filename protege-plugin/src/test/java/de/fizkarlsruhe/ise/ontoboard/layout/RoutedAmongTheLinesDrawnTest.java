package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A line routed among the lines already drawn - what a selection shows - finds a way wherever the
 * board's own routing would have found one.
 *
 * <p>Found by a review of 1.135.1: on a board of 120 boxes, selecting a property drew its range
 * straight through five boxes, in 9 ms, so not for want of time. Its class stood in a column of
 * classes 20 px apart, its own subclass line on its left and two arrows already arriving at its
 * right side. The lines drawn were put on the routing grid before the class's points were worked
 * out, so every point of its right side counted as taken by a line beside it and was never offered
 * - not even for sharing, which is how the board's own routing gets a line to a box with no point
 * left free.
 */
class RoutedAmongTheLinesDrawnTest {

    private static final long FAR = 30_000_000_000L;

    private static double[][] line(EdgeRouter.Box from, EdgeRouter.Route route, EdgeRouter.Box to) {
        List<double[]> points = new ArrayList<double[]>();
        points.add(EdgeRouter.endOn(from, route.bends.get(0)));
        points.addAll(route.bends);
        points.add(EdgeRouter.endOn(to, route.bends.get(route.bends.size() - 1)));
        return points.toArray(new double[0][]);
    }

    private static boolean runsThrough(double[][] line, EdgeRouter.Box box) {
        for (int i = 1; i < line.length; i++) {
            double x1 = Math.min(line[i - 1][0], line[i][0]);
            double x2 = Math.max(line[i - 1][0], line[i][0]);
            double y1 = Math.min(line[i - 1][1], line[i][1]);
            double y2 = Math.max(line[i - 1][1], line[i][1]);
            if (x2 > box.x && x1 < box.right() && y2 > box.y && y1 < box.bottom()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aLineToABusyClassIsRoutedAmongTheLinesDrawnAsTheBoardWouldRouteIt() {
        EdgeRouter.Box busy = new EdgeRouter.Box("busy", 140, 220, 180, 40);
        EdgeRouter.Box property = new EdgeRouter.Box("property", 400, 600, 180, 60);
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>(Arrays.asList(
                new EdgeRouter.Box("parent", 100, 100, 180, 40),
                new EdgeRouter.Box("above", 140, 160, 180, 40),
                busy,
                new EdgeRouter.Box("below", 140, 280, 180, 40),
                new EdgeRouter.Box("a1", 600, 100, 180, 40),
                new EdgeRouter.Box("a2", 600, 400, 180, 40),
                property));
        List<EdgeRouter.Box> frames = new ArrayList<EdgeRouter.Box>();
        List<EdgeRouter.Link> drawn = new ArrayList<EdgeRouter.Link>(Arrays.asList(
                new EdgeRouter.Link("sub|above", "above", "parent", true, 0, 0),
                new EdgeRouter.Link("sub|busy", "busy", "parent", true, 0, 0),
                new EdgeRouter.Link("sub|below", "below", "parent", true, 0, 0),
                new EdgeRouter.Link("rel|a1", "a1", "busy", false, 60, 22),
                new EdgeRouter.Link("rel|a2", "a2", "busy", false, 60, 22)));
        Map<String, EdgeRouter.Route> routed = EdgeRouter.route(boxes, frames, drawn,
                System.nanoTime() + FAR);
        assertEquals(5, routed.size(), "the board's own lines, every one routed");
        EdgeRouter.Link range = new EdgeRouter.Link("range|property|busy", "property", "busy",
                false, 36, 22);
        List<EdgeRouter.Link> all = new ArrayList<EdgeRouter.Link>(drawn);
        all.add(range);
        assertNotNull(EdgeRouter.route(boxes, frames, all, System.nanoTime() + FAR).get(range.id),
                "routed with the board, the line has a way");

        Map<String, EdgeRouter.Route> among = EdgeRouter.routeAmong(boxes, frames, drawn, routed,
                Collections.singletonList(range), System.nanoTime() + FAR);

        EdgeRouter.Route route = among.get(range.id);
        assertNotNull(route, "routed among the lines drawn, the range has a way too");
        double[][] points = line(property, route, busy);
        for (EdgeRouter.Box box : boxes) {
            if (box != property && box != busy) {
                assertFalse(runsThrough(points, box), "the range runs through " + box.id + ": "
                        + Arrays.deepToString(points));
            }
        }
    }
}
