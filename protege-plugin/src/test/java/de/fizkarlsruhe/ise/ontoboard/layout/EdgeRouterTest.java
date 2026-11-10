package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** "Arrows for properties should not have overlaps." */
class EdgeRouterTest {

    private static EdgeRouter.Box box(String id, double x, double y) {
        return new EdgeRouter.Box(id, x, y, 160, 40);
    }

    private static EdgeRouter.Link arrow(String id, String from, String to) {
        return new EdgeRouter.Link(id, from, to, false, 80, 16);
    }

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

    private static boolean orthogonal(double[][] line) {
        for (int i = 1; i < line.length; i++) {
            if (line[i - 1][0] != line[i][0] && line[i - 1][1] != line[i][1]) {
                return false;
            }
        }
        return true;
    }

    /** How long two lines run along each other, in pixels. */
    private static double sharedLength(double[][] one, double[][] other) {
        double shared = 0;
        for (int i = 1; i < one.length; i++) {
            for (int j = 1; j < other.length; j++) {
                boolean bothAcross = one[i][1] == one[i - 1][1] && other[j][1] == other[j - 1][1]
                        && one[i][1] == other[j][1];
                boolean bothDown = one[i][0] == one[i - 1][0] && other[j][0] == other[j - 1][0]
                        && one[i][0] == other[j][0];
                int axis = bothAcross ? 0 : bothDown ? 1 : -1;
                if (axis >= 0) {
                    double from = Math.max(Math.min(one[i][axis], one[i - 1][axis]),
                            Math.min(other[j][axis], other[j - 1][axis]));
                    double to = Math.min(Math.max(one[i][axis], one[i - 1][axis]),
                            Math.max(other[j][axis], other[j - 1][axis]));
                    shared += Math.max(0, to - from);
                }
            }
        }
        return shared;
    }

    @Test
    void anArrowGoesRoundABoxInItsWay() {
        EdgeRouter.Box a = box("a", 0, 100);
        EdgeRouter.Box wall = box("wall", 280, 100);
        EdgeRouter.Box b = box("b", 560, 100);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(a, wall, b),
                Collections.<EdgeRouter.Box>emptyList(), Arrays.asList(arrow("r", "a", "b")));
        double[][] line = line(a, routes.get("r"), b);
        assertFalse(runsThrough(line, wall), Arrays.deepToString(line));
        assertTrue(orthogonal(line), Arrays.deepToString(line));
    }

    @Test
    void arrowsBetweenTheSameColumnsTakeLanesOfTheirOwn() {
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>();
        List<EdgeRouter.Link> links = new ArrayList<EdgeRouter.Link>();
        for (int i = 0; i < 5; i++) {
            boxes.add(box("l" + i, 0, i * 60));
            boxes.add(box("r" + i, 400, i * 60));
            links.add(arrow("a" + i, "l" + i, "r" + (4 - i)));
        }
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links);
        assertEquals(5, routes.size());
        List<double[][]> lines = new ArrayList<double[][]>();
        for (int i = 0; i < 5; i++) {
            lines.add(line(boxes.get(i * 2), routes.get("a" + i), boxes.get((4 - i) * 2 + 1)));
        }
        for (int i = 0; i < 5; i++) {
            for (int j = i + 1; j < 5; j++) {
                assertEquals(0, sharedLength(lines.get(i), lines.get(j)),
                        "arrows " + i + " and " + j + " share a stretch");
            }
        }
    }

    @Test
    void arrowsAtOneBoxArriveAtPointsOfTheirOwn() {
        EdgeRouter.Box hub = box("hub", 400, 100);
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>(Arrays.asList(hub));
        List<EdgeRouter.Link> links = new ArrayList<EdgeRouter.Link>();
        for (int i = 0; i < 4; i++) {
            boxes.add(box("s" + i, 0, i * 60));
            links.add(arrow("a" + i, "s" + i, "hub"));
        }
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links);
        List<String> ends = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            List<double[]> bends = routes.get("a" + i).bends;
            double[] end = EdgeRouter.endOn(hub, bends.get(bends.size() - 1));
            String key = Math.round(end[0]) + "," + Math.round(end[1]);
            assertFalse(ends.contains(key), "two arrows end at " + key);
            ends.add(key);
        }
    }

    @Test
    void aSubclassLineRunsDownTheLaneToThePointUnderItsClass() {
        EdgeRouter.Box parent = box("p", 0, 0);
        EdgeRouter.Box child = box("c", HierarchyColumns.INDENT, 80);
        List<double[]> lane = EdgeRouter.treeLane(child, parent, Arrays.asList(parent, child));
        assertNotNull(lane);
        double spine = HierarchyColumns.INDENT / 2;
        assertEquals(spine, lane.get(0)[0]);
        assertEquals(child.centreY(), lane.get(0)[1]);
        assertEquals(spine, lane.get(lane.size() - 1)[0]);
    }

    @Test
    void aSubclassInASecondColumnJoinsTheSamePoint() {
        EdgeRouter.Box parent = box("p", 0, 0);
        EdgeRouter.Box child = box("c", 400, 80);
        List<double[]> lane = EdgeRouter.treeLane(child, parent, Arrays.asList(parent, child));
        assertNotNull(lane);
        assertEquals(HierarchyColumns.INDENT / 2, lane.get(lane.size() - 1)[0]);
    }

    @Test
    void aSubclassThatDoesNotStandBelowItsClassIsRoutedLikeAnyOtherLine() {
        EdgeRouter.Box parent = box("p", 300, 300);
        EdgeRouter.Box child = box("c", 0, 0);
        assertNull(EdgeRouter.treeLane(child, parent, Arrays.asList(parent, child)));
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(parent, child),
                Collections.<EdgeRouter.Box>emptyList(),
                Arrays.asList(new EdgeRouter.Link("s", "c", "p", true, 0, 0)));
        assertTrue(routes.containsKey("s"));
    }

    /**
     * An arrow from a subclass to a box on the left crosses its own subclass line clear of the
     * arrowhead under the class. Found on the cookbook board: {@code ∃ has part} from recipe dataset
     * ran through the arrowhead under data collection, then just under its base.
     */
    @Test
    void anArrowCrossesASubclassLineClearOfItsArrowhead() {
        EdgeRouter.Box parent = box("p", 600, 0);
        EdgeRouter.Box child = box("c", 600 + HierarchyColumns.INDENT, 40 + HierarchyColumns.UNDER);
        EdgeRouter.Box target = box("t", 0, 80);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(parent, child, target),
                Collections.<EdgeRouter.Box>emptyList(),
                Arrays.asList(new EdgeRouter.Link("is-a", "c", "p", true, 0, 0),
                        arrow("has-part", "c", "t")));
        double[][] lane = line(child, routes.get("is-a"), parent);
        double stem = lane[lane.length - 1][0];
        double head = parent.y + 40;
        double[][] arrow = line(child, routes.get("has-part"), target);
        for (int i = 1; i < arrow.length; i++) {
            boolean across = Math.abs(arrow[i][1] - arrow[i - 1][1]) < 0.5;
            double y = arrow[i][1];
            boolean passesStem = across && Math.min(arrow[i][0], arrow[i - 1][0]) < stem
                    && Math.max(arrow[i][0], arrow[i - 1][0]) > stem;
            assertTrue(!passesStem || y > head + 13 + 8 || y < parent.y,
                    "crosses the stem at y=" + y + ", the arrowhead ends at y=" + (head + 13) + ": "
                            + Arrays.deepToString(arrow));
        }
    }

    @Test
    void aBoxInTheLaneSendsTheLineRoundIt() {
        EdgeRouter.Box parent = box("p", 0, 0);
        EdgeRouter.Box child = box("c", HierarchyColumns.INDENT, 300);
        EdgeRouter.Box inTheWay = new EdgeRouter.Box("x", 0, 150, 120, 40);
        assertNull(EdgeRouter.treeLane(child, parent, Arrays.asList(parent, child, inTheWay)));
    }

    @Test
    void aFrameNeitherEndIsInIsGoneAroundWhenThereIsAWay() {
        EdgeRouter.Box a = box("a", 0, 200);
        EdgeRouter.Box b = box("b", 800, 200);
        EdgeRouter.Box other = box("other", 400, 200);
        EdgeRouter.Box frame = new EdgeRouter.Box("frame", 340, 160, 280, 120);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(a, b, other),
                Arrays.asList(frame), Arrays.asList(arrow("r", "a", "b")));
        double[][] line = line(a, routes.get("r"), b);
        for (int i = 1; i < line.length; i++) {
            double midX = (line[i][0] + line[i - 1][0]) / 2;
            double midY = (line[i][1] + line[i - 1][1]) / 2;
            assertFalse(frame.contains(midX, midY), Arrays.deepToString(line));
        }
    }

    @Test
    void theSameBoardRoutesTheSameWay() {
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>();
        List<EdgeRouter.Link> links = new ArrayList<EdgeRouter.Link>();
        for (int i = 0; i < 8; i++) {
            boxes.add(box("b" + i, (i % 3) * 300, (i / 3) * 80));
        }
        for (int i = 0; i < 7; i++) {
            links.add(arrow("a" + i, "b" + i, "b" + ((i * 3 + 1) % 8)));
        }
        Map<String, EdgeRouter.Route> once = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links);
        Map<String, EdgeRouter.Route> again = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links);
        assertEquals(once.keySet(), again.keySet());
        for (String id : once.keySet()) {
            assertEquals(Arrays.deepToString(once.get(id).bends.toArray()),
                    Arrays.deepToString(again.get(id).bends.toArray()));
            assertEquals(once.get(id).labelAt, again.get(id).labelAt);
        }
    }

    @Test
    void aLabelSitsWhereItCoversNoBox() {
        EdgeRouter.Box a = box("a", 0, 0);
        EdgeRouter.Box b = box("b", 500, 0);
        EdgeRouter.Link link = new EdgeRouter.Link("r", "a", "b", false, 100, 16);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(a, b),
                Collections.<EdgeRouter.Box>emptyList(), Arrays.asList(link));
        double at = routes.get("r").labelAt;
        assertTrue(at > 0.1 && at < 0.9, "on the open stretch between them: " + at);
    }

    @Test
    void anEndNotOnTheBoardIsNotRouted() {
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(box("a", 0, 0)),
                Collections.<EdgeRouter.Box>emptyList(), Arrays.asList(arrow("r", "a", "gone")));
        assertTrue(routes.isEmpty());
    }

    /** How long two lines run side by side closer than APART without being on top of each other. */
    private static double closeLength(double[][] one, double[][] other) {
        double close = 0;
        for (int i = 1; i < one.length; i++) {
            for (int j = 1; j < other.length; j++) {
                boolean bothAcross = one[i][1] == one[i - 1][1] && other[j][1] == other[j - 1][1];
                boolean bothDown = one[i][0] == one[i - 1][0] && other[j][0] == other[j - 1][0];
                int axis = bothAcross ? 0 : bothDown ? 1 : -1;
                if (axis < 0 || bothAcross && bothDown) {
                    continue;
                }
                double gap = Math.abs(one[i][1 - axis] - other[j][1 - axis]);
                if (gap >= 1.5 && gap < EdgeRouter.APART) {
                    double from = Math.max(Math.min(one[i][axis], one[i - 1][axis]),
                            Math.min(other[j][axis], other[j - 1][axis]));
                    double to = Math.min(Math.max(one[i][axis], one[i - 1][axis]),
                            Math.max(other[j][axis], other[j - 1][axis]));
                    close += Math.max(0, to - from);
                }
            }
        }
        return close;
    }

    /** The label's chip where JGraphX draws it: centred on the line, at labelAt of its length. */
    private static double[] chip(double[][] line, double labelAt, EdgeRouter.Link link) {
        double total = 0;
        for (int i = 1; i < line.length; i++) {
            total += Math.abs(line[i][0] - line[i - 1][0]) + Math.abs(line[i][1] - line[i - 1][1]);
        }
        double left = labelAt * total;
        for (int i = 1; i < line.length; i++) {
            double length = Math.abs(line[i][0] - line[i - 1][0])
                    + Math.abs(line[i][1] - line[i - 1][1]);
            if (left <= length || i == line.length - 1) {
                double share = length == 0 ? 0 : Math.min(1, left / length);
                double x = line[i - 1][0] + (line[i][0] - line[i - 1][0]) * share;
                double y = line[i - 1][1] + (line[i][1] - line[i - 1][1]) * share;
                return new double[] {x - link.labelW / 2, y - link.labelH / 2, link.labelW,
                    link.labelH};
            }
            left -= length;
        }
        return null;
    }

    private static boolean covers(double[] chip, EdgeRouter.Box box) {
        return chip[0] < box.right() && chip[0] + chip[2] > box.x && chip[1] < box.bottom()
                && chip[1] + chip[3] > box.y;
    }

    private static boolean crosses(double[][] line, double[] chip) {
        return runsThrough(line, new EdgeRouter.Box("chip", chip[0], chip[1], chip[2], chip[3]));
    }

    /**
     * Two subclass lines to different classes share no stretch, since 1.135.1: on the study board a
     * tester found two of them on one x for 600px, and a class seemed to hang off the other's
     * parent. Both lanes here run up one column; the second was taken as it was, 260px of it on the
     * first.
     */
    @Test
    void subclassLinesToDifferentClassesShareNoLane() {
        EdgeRouter.Box high = box("high", 1560, 100);
        EdgeRouter.Box low = box("low", 1300, 300);
        EdgeRouter.Box first = box("first", 1600, 600);
        EdgeRouter.Box second = box("second", 1600, 700);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(
                Arrays.asList(high, low, first, second), Collections.<EdgeRouter.Box>emptyList(),
                Arrays.asList(new EdgeRouter.Link("one", "first", "high", true, 0, 0),
                        new EdgeRouter.Link("two", "second", "low", true, 0, 0)));
        double[][] one = line(first, routes.get("one"), high);
        double[][] two = line(second, routes.get("two"), low);
        assertEquals(0, sharedLength(one, two), Arrays.deepToString(one) + " / "
                + Arrays.deepToString(two));
        assertEquals(0, closeLength(one, two), Arrays.deepToString(one) + " / "
                + Arrays.deepToString(two));
    }

    /**
     * A relation arrow keeps {@link EdgeRouter#APART} from a subclass line beside it: a tester
     * measured one 4px beside a subclass line for 357px, which reads as one double line. The lanes
     * of the hierarchy were not known to the routing at all, and here, in the gap between a box and
     * five subclasses, the arrow ran up their lane itself.
     */
    @Test
    void anArrowKeepsItsDistanceFromTheLaneOfASubclassLine() {
        EdgeRouter.Box parent = box("parent", 0, 0);
        EdgeRouter.Box south = box("south", -60, 480);
        EdgeRouter.Box west = box("west", -200, 0);
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>(Arrays.asList(parent, south,
                west, new EdgeRouter.Box("wall", -160, 80, 160, 300)));
        List<EdgeRouter.Link> links = new ArrayList<EdgeRouter.Link>();
        for (int i = 0; i < 5; i++) {
            boxes.add(box("child" + i, 40, 80 + 60 * i));
            links.add(new EdgeRouter.Link("child" + i, "child" + i, "parent", true, 0, 0));
        }
        links.add(arrow("up", "south", "west"));
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links);
        double[][] up = line(south, routes.get("up"), west);
        for (int i = 0; i < 5; i++) {
            double[][] lane = line(boxes.get(4 + i), routes.get("child" + i), parent);
            assertEquals(0, sharedLength(up, lane), Arrays.deepToString(up));
            assertEquals(0, closeLength(up, lane), Arrays.deepToString(up));
        }
    }

    /**
     * A label sits on no other line: on the study board seven did, one of them covering both
     * arrows out of one box. Here an arrow crosses the other's line in its middle, where the label
     * used to go, placed before the second line had a route.
     */
    @Test
    void aLabelSitsOnNoOtherLine() {
        EdgeRouter.Box a = box("a", 0, 100);
        EdgeRouter.Box b = box("b", 600, 100);
        EdgeRouter.Box c = box("c", 300, -200);
        EdgeRouter.Box d = box("d", 300, 400);
        EdgeRouter.Link across = new EdgeRouter.Link("across", "a", "b", false, 80, 20);
        EdgeRouter.Link down = new EdgeRouter.Link("down", "c", "d", false, 80, 20);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(a, b, c, d),
                Collections.<EdgeRouter.Box>emptyList(), Arrays.asList(across, down));
        double[][] first = line(a, routes.get("across"), b);
        double[][] second = line(c, routes.get("down"), d);
        double[] firstChip = chip(first, routes.get("across").labelAt, across);
        double[] secondChip = chip(second, routes.get("down").labelAt, down);
        assertFalse(crosses(second, firstChip), Arrays.toString(firstChip));
        assertFalse(crosses(first, secondChip), Arrays.toString(secondChip));
    }

    /**
     * A line too short for its label between two boxes is given a route that has room for it:
     * after a second pattern was imported, a tester found a label on a box, the short arrow under it
     * hidden. Two boxes 40px apart, and a label 100px wide.
     */
    @Test
    void aLineTooShortForItsLabelIsGivenARouteWithRoomForIt() {
        EdgeRouter.Box a = box("a", 0, 0);
        EdgeRouter.Box b = box("b", 200, 0);
        EdgeRouter.Link link = new EdgeRouter.Link("r", "a", "b", false, 100, 20);
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(Arrays.asList(a, b),
                Collections.<EdgeRouter.Box>emptyList(), Arrays.asList(link));
        double[][] line = line(a, routes.get("r"), b);
        double[] chip = chip(line, routes.get("r").labelAt, link);
        assertFalse(covers(chip, a) || covers(chip, b), Arrays.deepToString(line) + " label "
                + Arrays.toString(chip));
    }

    /**
     * Six frames of twenty classes, each a hierarchy, and relations within them and between: about
     * 120 boxes and 100 lines, where 1.135.0 gave up. On a tester's board of 119 boxes 71 of 98 lines
     * were routed and the rest drawn through boxes, because the routing grid had more lines than its
     * cap, not for want of time.
     */
    @Test
    void aBoardOfAHundredAndTwentyBoxesIsRoutedInFull() {
        List<EdgeRouter.Box> boxes = new ArrayList<EdgeRouter.Box>();
        List<EdgeRouter.Box> frames = new ArrayList<EdgeRouter.Box>();
        List<EdgeRouter.Link> links = new ArrayList<EdgeRouter.Link>();
        madeUp(boxes, frames, links, 6, 20, 30, 1351L);
        assertTrue(boxes.size() >= 120 && links.size() >= 95, boxes.size() + " / " + links.size());
        long started = System.nanoTime();
        Map<String, EdgeRouter.Route> routes = EdgeRouter.route(boxes, frames, links);
        long millis = (System.nanoTime() - started) / 1_000_000;
        List<String> missing = new ArrayList<String>();
        for (EdgeRouter.Link link : links) {
            if (!routes.containsKey(link.id)) {
                missing.add(link.id);
            }
        }
        assertEquals(new ArrayList<String>(), missing, "routed in " + millis + "ms");
        System.out.println("EdgeRouter: " + boxes.size() + " boxes, " + links.size()
                + " lines, routed in " + millis + "ms");
    }

    /**
     * One time for all the routing one action does, since 1.135.1: a moment already past leaves
     * every line that needs a search to the plain connector, and keeps the lanes of the hierarchy,
     * which need none. In 1.135.0 each call had two seconds of its own, and an arrangement routed up
     * to three times.
     */
    @Test
    void aMomentAlreadyPastLeavesTheArrowsToThePlainConnector() {
        List<EdgeRouter.Box> boxes = Arrays.asList(box("parent", 0, 0), box("child", 40, 80),
                box("a", 400, 0), box("b", 400, 300));
        List<EdgeRouter.Link> links = Arrays.asList(
                new EdgeRouter.Link("is-a", "child", "parent", true, 0, 0), arrow("r", "a", "b"));
        Map<String, EdgeRouter.Route> late = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links, System.nanoTime() - 1);
        assertTrue(late.containsKey("is-a"));
        assertFalse(late.containsKey("r"));
        Map<String, EdgeRouter.Route> inTime = EdgeRouter.route(boxes,
                Collections.<EdgeRouter.Box>emptyList(), links,
                System.nanoTime() + EdgeRouter.MOST_TIME);
        assertTrue(inTime.containsKey("r"));
    }

    /**
     * Frames of {@link HierarchyColumns} trees in rows, as patterns are framed on a board: the first
     * class of each frame under a class of an earlier one, and {@code relations} arrows, most of
     * them inside a frame. The same board for the same seed.
     */
    static void madeUp(List<EdgeRouter.Box> boxes, List<EdgeRouter.Box> frames,
            List<EdgeRouter.Link> links, int count, int each, int relations, long seed) {
        java.util.Random random = new java.util.Random(seed);
        double x = 60;
        double y = 60;
        double rowHeight = 0;
        for (int f = 0; f < count; f++) {
            List<HierarchyColumns.Term> terms = new ArrayList<HierarchyColumns.Term>();
            Map<String, String> parents = new java.util.HashMap<String, String>();
            Map<String, double[]> sizes = new java.util.HashMap<String, double[]>();
            for (int t = 0; t < each; t++) {
                String id = "f" + f + "t" + t;
                double w = 160 + 20 * random.nextInt(5);
                sizes.put(id, new double[] {w, 40});
                terms.add(new HierarchyColumns.Term(id, "term " + t, w, 40));
                if (t > 0 && random.nextInt(5) < 3) {
                    parents.put(id, "f" + f + "t" + random.nextInt(t));
                    links.add(new EdgeRouter.Link("sub" + id, id, parents.get(id), true, 0, 0));
                }
            }
            HierarchyColumns.Placed placed = HierarchyColumns.place(terms, parents, 0, 0);
            if (x > 60 && x + placed.right + 80 > 3000) {
                x = 60;
                y += rowHeight + 60;
                rowHeight = 0;
            }
            frames.add(new EdgeRouter.Box("frame" + f, x, y, placed.right + 80,
                    placed.bottom + 120));
            for (Map.Entry<String, double[]> at : placed.positions.entrySet()) {
                double[] size = sizes.get(at.getKey());
                boxes.add(new EdgeRouter.Box(at.getKey(), x + 40 + at.getValue()[0],
                        y + 80 + at.getValue()[1], size[0], size[1]));
            }
            if (f > 0) {
                links.add(new EdgeRouter.Link("sub" + f, "f" + f + "t0",
                        "f" + random.nextInt(f) + "t" + random.nextInt(each), true, 0, 0));
            }
            x += placed.right + 80 + 60;
            rowHeight = Math.max(rowHeight, placed.bottom + 120);
        }
        for (int r = 0; r < relations; r++) {
            int from = random.nextInt(count);
            int to = random.nextInt(10) < 7 ? from : random.nextInt(count);
            String source = "f" + from + "t" + random.nextInt(each);
            String target = "f" + to + "t" + random.nextInt(each);
            if (!source.equals(target)) {
                links.add(new EdgeRouter.Link("rel" + r, source, target, false,
                        40 + random.nextInt(80), 20));
            }
        }
    }
}
