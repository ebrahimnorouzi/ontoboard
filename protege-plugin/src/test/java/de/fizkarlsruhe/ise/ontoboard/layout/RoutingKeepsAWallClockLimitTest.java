package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Routing counts its budgets in its own processor time, and stops on the wall clock too, at three
 * times the budget (1.135.2 review).
 *
 * <p>Counted in processor time alone, nothing bounded how long routing held the board in wall
 * time: with the machine busy, or the thread descheduled or held up by the garbage collector at
 * a small heap, a routing given two seconds of processor time could hold the board for many more.
 * On a quiet machine the wall clock never decides; with the thread given less than a third of a
 * core it does, and what is left keeps the straight connector, as it did before 1.135.2.
 */
class RoutingKeepsAWallClockLimitTest {

    private static EdgeRouter.Box box(String id, double x, double y) {
        return new EdgeRouter.Box(id, x, y, 120, 40);
    }

    @Test
    void aMomentPastOnTheWallClockStopsTheSearchesThoughProcessorTimeIsLeft() {
        List<EdgeRouter.Box> boxes = Arrays.asList(box("parent", 0, 0), box("child", 40, 80),
                box("a", 400, 0), box("b", 400, 300));
        List<EdgeRouter.Link> links = Arrays.asList(
                new EdgeRouter.Link("is-a", "child", "parent", true, 0, 0),
                new EdgeRouter.Link("r", "a", "b", false, 0, 0));

        Map<String, EdgeRouter.Route> late = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links,
                EdgeRouter.now() + EdgeRouter.MOST_TIME, System.nanoTime() - 1);

        assertTrue(late.containsKey("is-a"), "a lane of the hierarchy needs no search");
        assertFalse(late.containsKey("r"), "a line that needs one keeps the plain connector");
    }

    /**
     * The lines a selection shows: the first is begun whatever the time, so a click makes headway,
     * and with the wall clock past the call's limit nothing after it is - left neither routed nor
     * given up, for a later slice.
     */
    @Test
    void aSelectionsLinesPastTheWallClockLeaveAllButTheFirstForLater() {
        List<EdgeRouter.Box> boxes = Arrays.asList(box("a", 0, 0), box("b", 0, 300),
                box("c", 400, 0), box("d", 400, 300));
        List<EdgeRouter.Link> shown = Arrays.asList(
                new EdgeRouter.Link("first", "a", "b", false, 0, 0),
                new EdgeRouter.Link("second", "c", "d", false, 0, 0));
        List<String> noWay = new java.util.ArrayList<String>();

        Map<String, EdgeRouter.Route> late = EdgeRouter.routeAmong(boxes,
                Collections.<EdgeRouter.Box>emptyList(), Collections.<EdgeRouter.Box>emptyList(),
                Collections.<EdgeRouter.Link>emptyList(),
                Collections.<String, EdgeRouter.Route>emptyMap(), shown,
                EdgeRouter.now() + EdgeRouter.MOST_TIME, System.nanoTime() - 1, noWay);

        assertEquals(1, late.size(), late.keySet().toString());
        assertTrue(noWay.isEmpty(), "nothing given up: " + noWay);
    }

    @Test
    void theWallClockGivesThreeTimesTheBudget() {
        assertEquals(3 * 150_000_000L, EdgeRouter.wallTimeFor(150_000_000L));
        assertEquals(3 * EdgeRouter.MOST_TIME, EdgeRouter.wallTimeFor(EdgeRouter.MOST_TIME));
        assertEquals(0L, EdgeRouter.wallTimeFor(-5));
    }

    /**
     * A deadline of another clock - a wall-clock reading where a processor-time one belongs -
     * reads as a budget of hours; the wall clock still stops it after the board's own budget.
     */
    @Test
    void noBudgetGetsMoreWallTimeThanAWholeBoardsRouting() {
        assertEquals(3 * EdgeRouter.MOST_TIME, EdgeRouter.wallTimeFor(Long.MAX_VALUE / 4));
    }
}
