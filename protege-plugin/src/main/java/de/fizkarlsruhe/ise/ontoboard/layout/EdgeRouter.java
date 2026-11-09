package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the lines on a board run, so that they pass no box and, as far as there is room, no other
 * line.
 *
 * <p>Asked for: "arrows for properties should also not have overlaps". JGraphX's orthogonal connector
 * steers around an edge's own two ends and nothing else, so on a board of any size an arrow ran
 * behind the boxes in its way and along the line beside it, and which class an arrow ended at was a
 * matter of following it by eye.
 *
 * <p>Two kinds of line, because they do different jobs:
 *
 * <ul>
 *   <li><b>A hierarchy line</b> from a subclass to a class above it in a {@link HierarchyColumns}
 *       tree runs down the lane to the left of the subclasses. All of a class's subclasses meet at
 *       one point under it, which is how a tree is read; these lines share a lane on purpose, and
 *       only they do: a lane that would run on or beside the lane of another class's subclasses,
 *       or out of its frame, is not taken, and that line is routed like any other.
 *   <li><b>Every other line</b> takes the cheapest route over the gaps between the boxes, one line
 *       after another, shortest first. A bend costs, crossing a line or a label costs, running
 *       beside a line closer than {@link #APART} costs more, and running along a line already there
 *       costs most, so each finds a lane of its own while one exists. It leaves and arrives at a
 *       point of its own on the box, the right side first, because the left is where the hierarchy
 *       comes in.
 * </ul>
 *
 * <p>A label sits on its own line, where it covers no box, no other label and no other line: a line
 * whose route has no such place for its label is given one that has, where the board allows, and
 * the lines after it go round the labels already placed.
 *
 * <p><b>Why 1.135.1 changed how.</b> Eight testers measured the boards of a study rehearsal, and
 * found what the tests had not: two subclass lines to different classes drawn on top of each other
 * for 600px, so that a class seemed to hang off the wrong parent; a relation arrow 4px beside a
 * subclass line for 357px, which reads as one double line; labels on other lines and on boxes; and
 * on a board of 119 boxes, 27 of 98 lines left to the plain connector and drawn through boxes. The
 * lanes of the hierarchy were not known to the routing at all, so nothing kept an arrow off them;
 * nothing compared two lanes; labels were placed while lines were still to come; and the routing
 * grid had a line for every edge of every box, about ten per box on each axis, which passed its cap
 * at that size. The grid is now one line every {@link #PITCH} pixels over the board, so its size
 * follows the board's area and not the number of boxes, every line drawn is on it, lanes included,
 * and a line on the grid line next to another is the one closer than {@link #APART}.
 *
 * <p>Pure geometry: no Swing, no JGraphX, no OWL API. Coordinates are the board's, at 100%.
 */
public final class EdgeRouter {

    /** No line comes closer to a box than this, but for the stub that reaches it. */
    static final double CLEARANCE = 6;

    /**
     * How far a line leaves a box before it may turn: room for an arrowhead and the gap before it
     * on a straight piece.
     */
    static final double STUB = 20;

    /** Closer than this, two lines side by side read as one double line. */
    static final double APART = 12;

    /**
     * Between two lines a route may run on. Boxes stand on the board's 20px grid, so their sides are
     * on these lines and a route passes them 10px out; two routes on neighbouring lines are 10px
     * apart, closer than {@link #APART}, and pay for it.
     */
    static final double PITCH = 10;

    /** Between two points a box's side offers to lines, so their stubs are not closer than APART. */
    static final double PORTS_APART = 20;

    /** What a bend costs, in pixels of route. */
    static final double BEND = 40;

    /** What crossing another line costs. */
    static final double CROSSING = 30;

    /** What each pixel along a line already there costs. */
    static final double OVERLAP = 30;

    /** What each pixel closer than {@link #APART} beside a line already there costs. */
    static final double BESIDE = 8;

    /** What each pixel under another line's label costs: it would be hidden under the chip. */
    static final double UNDER_A_LABEL = 8;

    /** How many times over a stretch inside a frame neither end is in counts. */
    static final double THROUGH_A_FRAME = 3;

    /** What each pixel along the outline of a frame costs on top, where a line is lost in it. */
    private static final double ON_A_BORDER = 2;

    /** What each pixel through a frame's title band costs on top: a line there crosses its name. */
    private static final double IN_A_TITLE = 2;

    /**
     * What running into the arrowhead of another class's subclass line costs: a line through it
     * reads as an arrow to the wrong class. A tester found one on the cookbook board.
     */
    private static final double THROUGH_AN_ARROWHEAD = 500;

    /**
     * How far back from where a subclass line meets its class a line may not cross it cheaply: the
     * head is 13 px long, and at 16 px a line crossing the stem 4 px under it still read as touching
     * it, on the cookbook board. One grid step more keeps a crossing clear of the head.
     */
    private static final double ARROWHEAD = 26;

    /**
     * More points than this and the grid takes twice the pitch, its lines between the board's: about
     * 7,000 by 7,000 pixels of board. Routes still pass 10px from a box and between boxes 20px
     * apart, and are 20px apart themselves; a lane of the hierarchy is then between two of its
     * lines, and counted on the nearer. A board of 1,500 classes in 60 frames took the whole two
     * seconds at the finer pitch.
     */
    private static final int MOST_POINTS = 500_000;

    /**
     * How many states one search may take from its queue before that line is left to the plain
     * connector: a line across a board of thousands of classes is not worth the wait for all the
     * others.
     */
    private static final int MOST_STEPS = 300_000;

    /** How long routing may hold up the board, in nanoseconds; what is left keeps the plain connector. */
    public static final long MOST_TIME = 2_000_000_000L;

    /**
     * How much more than the least it could be the search takes what is left of a route to cost.
     * Above 1 a route is no longer sure to be the cheapest, and the search no longer spreads over
     * the board as it did: inside a frame a step costs three times its length, which a guess of
     * the distance alone left the search to find out everywhere. At 1.5 a route costs at most half
     * as much again as the cheapest, and in the tests none was seen to.
     */
    private static final double GREED = 1.5;

    /** How far round its two ends a route is looked for first, before the whole board is. */
    private static final double WINDOW = 300;

    /** How close to a box's corner a line may leave it. */
    private static final double CORNER = 8;

    private static final int RIGHT = 0;
    private static final int TOP = 1;
    private static final int BOTTOM = 2;
    private static final int LEFT = 3;

    private static final int ANY_SIDE = 15;
    private static final int UP_OR_DOWN = 1 << TOP | 1 << BOTTOM;
    private static final int LEFT_OR_RIGHT = 1 << LEFT | 1 << RIGHT;

    private static final int ACROSS = 0;
    private static final int DOWN = 1;

    /** The step from a stub's far end back into its box, by side: left, down, up, right. */
    private static final int[] INTO = {0, 3, 2, 1};

    /** A box on the board. */
    public static final class Box {
        public final String id;
        public final double x;
        public final double y;
        public final double w;
        public final double h;

        public Box(String id, double x, double y, double w, double h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        double right() {
            return x + w;
        }

        double bottom() {
            return y + h;
        }

        double centreY() {
            return y + h / 2;
        }

        boolean contains(double px, double py) {
            return px >= x && px <= right() && py >= y && py <= bottom();
        }
    }

    /** A line to route: from {@code source} to {@code target}, its label's size in pixels. */
    public static final class Link {
        final String id;
        final String source;
        final String target;
        final boolean hierarchy;
        final double labelW;
        final double labelH;

        public Link(String id, String source, String target, boolean hierarchy, double labelW,
                double labelH) {
            this.id = id;
            this.source = source;
            this.target = target;
            this.hierarchy = hierarchy;
            this.labelW = labelW;
            this.labelH = labelH;
        }
    }

    /** A route: the bends between the two ends, and how far along it the label sits, 0 to 1. */
    public static final class Route {
        public final List<double[]> bends;
        public final double labelAt;

        Route(List<double[]> bends, double labelAt) {
            this.bends = Collections.unmodifiableList(bends);
            this.labelAt = labelAt;
        }
    }

    /** A line given a route: its bends, the whole of it as drawn, and who it may share a lane with. */
    private static final class Drawn {
        final Link link;
        final Box source;
        final Box target;
        /** The class whose subclasses this line may run on top of, numbered from 1; -1 for none. */
        final int owner;
        List<double[]> bends;
        double[][] points;
        Port from;
        Port to;
        double labelAt = 0.5;
        /** Where its label is meant to go, x1, y1, x2, y2, for the lines routed after it; or null. */
        double[] chip;

        Drawn(Link link, Box source, Box target, int owner, List<double[]> bends) {
            this.link = link;
            this.source = source;
            this.target = target;
            this.owner = owner;
            give(bends);
        }

        void give(List<double[]> route) {
            bends = route;
            points = polyline(source, route, target);
        }
    }

    /** A route found, not yet given to its line. */
    private static final class Found {
        final List<double[]> bends;
        final Port from;
        final Port to;
        final double cost;

        Found(List<double[]> bends, Port from, Port to, double cost) {
            this.bends = bends;
            this.from = from;
            this.to = to;
            this.cost = cost;
        }
    }

    /** What a search gives when the time ran out before it found anything. */
    private static final Found OUT_OF_TIME = new Found(null, null, null, 0);

    private EdgeRouter() {
    }

    /** The same, in the time {@link #MOST_TIME} gives from now. */
    public static Map<String, Route> route(List<Box> boxes, List<Box> frames, List<Link> links) {
        return route(boxes, frames, links, System.nanoTime() + MOST_TIME);
    }

    /**
     * The route of every link it can route; a link it cannot - both ends in one box, no way through,
     * no time left - is left out, and keeps whatever the board draws by default.
     *
     * <p>The time is a moment, not a length, so that one thing somebody did that routes the board
     * more than once waits no longer than {@link #MOST_TIME} for all of it: in 1.135.0 each call had
     * two seconds of its own, and one arrangement routed up to three times.
     *
     * @param frames regions a line should go around when neither of its ends is in them
     * @param deadline {@link System#nanoTime()} past which no further line is looked for
     */
    public static Map<String, Route> route(List<Box> boxes, List<Box> frames, List<Link> links,
            long deadline) {
        Map<String, Box> byId = new HashMap<String, Box>();
        Map<String, Integer> numbers = new HashMap<String, Integer>();
        for (Box box : boxes) {
            byId.put(box.id, box);
            if (!numbers.containsKey(box.id)) {
                numbers.put(box.id, numbers.size() + 1);
            }
        }
        // A subclass has one line down the lane: with a second parent, the nearer gets it.
        Map<String, Link> nearest = new HashMap<String, Link>();
        for (Link link : links) {
            Box source = byId.get(link.source);
            Box target = byId.get(link.target);
            if (link.hierarchy && source != null && target != null && source != target) {
                Link now = nearest.get(link.source);
                if (now == null || reach(link, byId) < reach(now, byId)) {
                    nearest.put(link.source, link);
                }
            }
        }
        Grid grid = Grid.over(boxes, frames);
        List<Drawn> drawn = new ArrayList<Drawn>();
        Map<String, Integer> loops = new HashMap<String, Integer>();
        List<Link> lanes = new ArrayList<Link>();
        List<Link> others = new ArrayList<Link>();
        for (Link link : links) {
            Box source = byId.get(link.source);
            Box target = byId.get(link.target);
            if (source == null || target == null) {
                continue;
            }
            if (source == target) {
                int before = loops.getOrDefault(link.source, 0);
                loops.put(link.source, before + 1);
                Drawn loop = new Drawn(link, source, target, -1,
                        Collections.<double[]>emptyList());
                loop.give(loop(source, before, link, boxes, loop, grid, drawn));
                drawn.add(loop);
                if (grid != null) {
                    grid.occupy(loop.points, -1);
                    grid.cover(loop.chip, 1);
                }
            } else if (link.hierarchy && nearest.get(link.source) == link) {
                lanes.add(link);
            } else {
                others.add(link);
            }
        }
        final Map<String, Box> ends = byId;
        Comparator<Link> shortestFirst = new Comparator<Link>() {
            @Override
            public int compare(Link one, Link other) {
                if (one.hierarchy != other.hierarchy) {
                    // The hierarchy first: it has one place to arrive at, an arrow has many.
                    return one.hierarchy ? -1 : 1;
                }
                int order = Double.compare(reach(one, ends), reach(other, ends));
                return order != 0 ? order : one.id.compareTo(other.id);
            }
        };
        // The lanes of the hierarchy, shortest first: everything else has to get past them.
        Collections.sort(lanes, shortestFirst);
        for (Link link : lanes) {
            Box source = byId.get(link.source);
            Box target = byId.get(link.target);
            int owner = numbers.get(link.target);
            List<double[]> lane = frameOf(source, frames) == frameOf(target, frames)
                    ? treeLane(source, target, boxes) : null;
            if (lane != null && grid != null
                    && grid.clashes(polyline(source, lane, target), owner)) {
                lane = null;
            }
            if (lane == null) {
                others.add(link);
                continue;
            }
            Drawn line = new Drawn(link, source, target, owner, lane);
            drawn.add(line);
            if (grid != null) {
                grid.occupy(line.points, owner);
                grid.markHead(line.points, owner);
            }
        }
        Collections.sort(others, shortestFirst);
        if (grid != null) {
            List<Drawn> searched = new ArrayList<Drawn>();
            for (Link link : others) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                Box source = byId.get(link.source);
                Box target = byId.get(link.target);
                int owner = link.hierarchy ? numbers.get(link.target) : -1;
                Drawn line = new Drawn(link, source, target, owner,
                        Collections.<double[]>emptyList());
                if (grid.give(line, frames, deadline, drawn, boxes)) {
                    drawn.add(line);
                    searched.add(line);
                    grid.occupy(line.points, owner);
                    grid.cover(line.chip, 1);
                }
            }
            // No more search than the first pass took, so the same board comes out the same.
            grid.rerouteWhatRunsTogether(searched, drawn, frames, deadline,
                    grid.work + Math.max(grid.work, MOST_STEPS), boxes);
        }
        placeLabels(drawn, boxes, frames);
        Map<String, Route> routes = new LinkedHashMap<String, Route>();
        for (Drawn line : drawn) {
            routes.put(line.link.id, new Route(line.bends, line.labelAt));
        }
        return routes;
    }

    /**
     * A term related to itself: out of one side of its box and back, far enough out that its label
     * sits on the far stretch clear of the box, and each loop of a box on a side of its own - the
     * right, the top, the bottom, the left - where nothing else stands. Left to JGraphX every loop on
     * a box was drawn on the same spot; then they nested on the right, 4px apart, and their labels
     * sat on the box.
     */
    private static List<double[]> loop(Box box, int before, Link link, List<Box> boxes,
            Drawn line, Grid grid, List<Drawn> drawn) {
        double w = link.labelW > 0 ? link.labelW + 2 * AIR : 0;
        double h = link.labelW > 0 ? link.labelH + 2 * AIR : 0;
        double further = (before / 4) * APART;
        double cx = box.x + box.w / 2;
        double cy = box.centreY();
        for (int tried = 0; tried < 4; tried++) {
            int side = (before + tried) % 4;
            List<double[]> bends = new ArrayList<double[]>();
            double[] chip;
            if (side == RIGHT || side == LEFT) {
                double out = Math.max(side == LEFT ? HierarchyColumns.INDENT : STUB,
                        Math.ceil((w / 2 + CLEARANCE) / PITCH) * PITCH) + further;
                double x = side == RIGHT ? box.right() + out : box.x - out;
                double reach = Math.min(PORTS_APART / 2, box.h / 2 - CORNER / 2);
                bends.add(new double[] {x, cy - reach});
                bends.add(new double[] {x, cy + reach});
                chip = new double[] {x - w / 2, cy - h / 2, x + w / 2, cy + h / 2};
            } else {
                double from = side == BOTTOM ? box.x + HierarchyColumns.INDENT / 2 + APART + CORNER
                        : box.x + CORNER;
                double room = box.right() - CORNER - from;
                double span = Math.min(room, Math.max(2 * PORTS_APART, w));
                if (span < PORTS_APART) {
                    continue;
                }
                double middle = side == BOTTOM ? from + room / 2 : cx;
                double out = Math.max(STUB, Math.ceil((h / 2 + CLEARANCE) / PITCH) * PITCH)
                        + further;
                double y = side == TOP ? box.y - out : box.bottom() + out;
                double x1 = Math.round((middle - span / 2) / PITCH) * PITCH;
                double x2 = x1 + Math.floor(span / PITCH) * PITCH;
                bends.add(new double[] {x1, y});
                bends.add(new double[] {x2, y});
                chip = new double[] {middle - w / 2, y - h / 2, middle + w / 2, y + h / 2};
            }
            double[][] points = polyline(box, bends, box);
            boolean clear = grid == null || !grid.clashes(points, -1);
            for (Box other : boxes) {
                if (other != box && (crosses(points, other, CLEARANCE) || w > 0
                        && chip[0] < other.right() && chip[2] > other.x
                        && chip[1] < other.bottom() && chip[3] > other.y)) {
                    clear = false;
                    break;
                }
            }
            for (Drawn other : drawn) {
                if (w > 0 && other.chip != null && chip[0] < other.chip[2]
                        && chip[2] > other.chip[0] && chip[1] < other.chip[3]
                        && chip[3] > other.chip[1]) {
                    clear = false;
                }
            }
            if (clear) {
                line.chip = w > 0 ? chip : null;
                return bends;
            }
        }
        List<double[]> bends = new ArrayList<double[]>();
        double out = box.right() + STUB + before * APART;
        double reach = Math.min(box.h / 2 - 2, 8 + before * 4);
        bends.add(new double[] {out, cy - reach});
        bends.add(new double[] {out, cy + reach});
        return bends;
    }

    private static double reach(Link link, Map<String, Box> byId) {
        Box from = byId.get(link.source);
        Box to = byId.get(link.target);
        return Math.abs(from.x - to.x) + Math.abs(from.y - to.y);
    }

    /** The smallest frame the box stands in, or -1: a lane of the hierarchy stays in its frame. */
    private static int frameOf(Box box, List<Box> frames) {
        int found = -1;
        for (int k = 0; k < frames.size(); k++) {
            Box frame = frames.get(k);
            if (frame.contains(box.x + 1, box.y + 1) && (found < 0
                    || frame.w * frame.h < frames.get(found).w * frames.get(found).h)) {
                found = k;
            }
        }
        return found;
    }

    /**
     * The lane of a hierarchy line: from the subclass's left side to the lane beside it, up the lane
     * to under its class, and along to the one point under the class where all of them meet. Null
     * when the two do not stand that way round, or something stands in the lane - somebody moved a
     * box - and it has to be routed like any other line.
     */
    static List<double[]> treeLane(Box child, Box parent, List<Box> boxes) {
        if (child.x - parent.x < HierarchyColumns.INDENT / 2 || child.y < parent.bottom()) {
            return null;
        }
        double lane = child.x - HierarchyColumns.INDENT / 2;
        double meet = parent.x + HierarchyColumns.INDENT / 2;
        if (meet > parent.right()) {
            return null;
        }
        double across = Math.min(parent.bottom() + 20, child.y - 4);
        List<double[]> bends = new ArrayList<double[]>();
        bends.add(new double[] {lane, child.centreY()});
        if (Math.abs(lane - meet) < 1) {
            bends.add(new double[] {meet, parent.bottom() + 8});
        } else {
            bends.add(new double[] {lane, across});
            bends.add(new double[] {meet, across});
        }
        double[][] line = polyline(child, bends, parent);
        for (Box box : boxes) {
            if (box != child && box != parent && crosses(line, box, 0)) {
                return null;
            }
        }
        return bends;
    }

    /** The whole line, ends included, as JGraphX will draw it. */
    private static double[][] polyline(Box source, List<double[]> bends, Box target) {
        if (bends.isEmpty()) {
            return new double[0][];
        }
        List<double[]> line = new ArrayList<double[]>();
        line.add(endOn(source, bends.get(0)));
        line.addAll(bends);
        line.add(endOn(target, bends.get(bends.size() - 1)));
        return line.toArray(new double[0][]);
    }

    /** Where a line coming from {@code next} meets the box: straight in, as an orthogonal one does. */
    static double[] endOn(Box box, double[] next) {
        double x = Math.max(box.x, Math.min(box.right(), next[0]));
        double y = Math.max(box.y, Math.min(box.bottom(), next[1]));
        return new double[] {x, y};
    }

    private static boolean crosses(double[][] line, Box box, double margin) {
        for (int i = 1; i < line.length; i++) {
            double x1 = Math.min(line[i - 1][0], line[i][0]);
            double x2 = Math.max(line[i - 1][0], line[i][0]);
            double y1 = Math.min(line[i - 1][1], line[i][1]);
            double y2 = Math.max(line[i - 1][1], line[i][1]);
            if (x2 >= box.x - margin && x1 <= box.right() + margin && y2 >= box.y - margin
                    && y1 <= box.bottom() + margin) {
                return true;
            }
        }
        return false;
    }

    /** What a label covering a box, or another label, adds to the cost of a place for it. */
    private static final double ON_A_BOX = 1000;

    /** What a label covering another line adds: the line is hidden under it, and so is the label's. */
    private static final double ON_A_LINE = 400;

    /** Kept clear round a label, so that a line passing it does not touch it. */
    private static final double AIR = 2;

    /** The places along a stretch a label is tried at, in pixels. */
    private static final double LABEL_STEP = 5;

    /**
     * Where each label sits: on its own line, at the place where it covers least - no box, no other
     * label, no other line - and, of the places that cover nothing, on a horizontal stretch long
     * enough for it, near the middle and away from the arrowhead.
     *
     * <p>After every line has its route: in 1.135.0 a label was placed as its line was routed, so a
     * line routed later could run under it, and lines were not looked at anyway. Twice: a label
     * that found no clear place the first time looks again once all the others have one.
     */
    private static void placeLabels(List<Drawn> drawn, List<Box> boxes, List<Box> frames) {
        List<Drawn> labelled = new ArrayList<Drawn>();
        for (Drawn line : drawn) {
            if (line.link.labelW > 0 && line.points.length >= 2) {
                labelled.add(line);
            }
        }
        double[][] chips = new double[labelled.size()][];
        double[] costs = new double[labelled.size()];
        for (int round = 0; round < 2; round++) {
            for (int k = 0; k < labelled.size(); k++) {
                if (round == 1 && costs[k] < ON_A_LINE) {
                    continue;
                }
                chips[k] = null;
                double[] spot = bestSpot(labelled.get(k).points, labelled.get(k).link, drawn,
                        labelled.get(k), boxes, frames, Arrays.asList(chips));
                labelled.get(k).labelAt = spot[0];
                costs[k] = spot[1];
                chips[k] = new double[] {spot[2], spot[3], spot[4], spot[5]};
            }
        }
    }

    /**
     * The place for one label on this line: how far along, what it covers, and the chip it makes
     * there, x1, y1, x2, y2. The lines it must not cover are all of {@code drawn} but {@code self}.
     */
    private static double[] bestSpot(double[][] points, Link link, List<Drawn> drawn, Drawn self,
            List<Box> boxes, List<Box> frames, List<double[]> chips) {
        double w = link.labelW + 2 * AIR;
        double h = link.labelH + 2 * AIR;
        double total = 0;
        double[] from = new double[points.length];
        double[] near = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < points.length; i++) {
            if (i > 0) {
                from[i] = total;
                total += Math.abs(points[i][0] - points[i - 1][0])
                        + Math.abs(points[i][1] - points[i - 1][1]);
            }
            near[0] = Math.min(near[0], points[i][0] - w);
            near[1] = Math.min(near[1], points[i][1] - h);
            near[2] = Math.max(near[2], points[i][0] + w);
            near[3] = Math.max(near[3], points[i][1] + h);
        }
        double[] best = {0.5, Double.MAX_VALUE, 0, 0, 0, 0};
        if (total <= 0) {
            best[1] = 0;
            return best;
        }
        List<Box> close = new ArrayList<Box>();
        for (Box box : boxes) {
            if (box.right() > near[0] && box.x < near[2] && box.bottom() > near[1]
                    && box.y < near[3]) {
                close.add(box);
            }
        }
        List<double[][]> passing = new ArrayList<double[][]>();
        for (Drawn other : drawn) {
            if (other != self && other.points.length >= 2 && crossesChip(other.points, near)) {
                passing.add(other.points);
            }
        }
        double[] end = points[points.length - 1];
        double[] start = points[0];
        for (int i = 1; i < points.length; i++) {
            double dx = points[i][0] - points[i - 1][0];
            double dy = points[i][1] - points[i - 1][1];
            double length = Math.abs(dx) + Math.abs(dy);
            if (length <= 0) {
                continue;
            }
            boolean flat = dy == 0;
            int steps = Math.max(1, (int) (length / LABEL_STEP));
            for (int s = 0; s <= steps; s++) {
                double along = length * s / steps;
                double cx = points[i - 1][0] + dx * along / length;
                double cy = points[i - 1][1] + dy * along / length;
                double[] chip = {cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2};
                double at = (from[i] + along) / total;
                double cost = Math.abs(at - 0.5) * 20 + (flat ? 0 : 10)
                        + ((flat ? w : h) > length ? 8 : 0);
                if (inside(end, chip, 14)) {
                    cost += 200;
                }
                if (inside(start, chip, 4)) {
                    cost += 40;
                }
                for (Box box : close) {
                    if (chip[0] < box.right() && chip[2] > box.x && chip[1] < box.bottom()
                            && chip[3] > box.y) {
                        cost += ON_A_BOX;
                    }
                }
                for (double[] other : chips) {
                    if (other != null && chip[0] < other[2] && chip[2] > other[0]
                            && chip[1] < other[3] && chip[3] > other[1]) {
                        cost += ON_A_BOX;
                    }
                }
                // Astride a frame's outline, or on the name at its top: found by a tester, a
                // label half in and half out of a frame read as belonging to neither.
                for (Box frame : frames) {
                    boolean meets = chip[0] < frame.right() && chip[2] > frame.x
                            && chip[1] < frame.bottom() && chip[3] > frame.y;
                    if (!meets) {
                        continue;
                    }
                    boolean within = chip[0] >= frame.x && chip[2] <= frame.right()
                            && chip[1] >= frame.y && chip[3] <= frame.bottom();
                    if (!within) {
                        cost += ON_A_LINE;
                    } else if (chip[1] < frame.y + FramedGroup.LABEL_BAND) {
                        cost += ON_A_LINE;
                    }
                }
                if (cost >= best[1]) {
                    continue;
                }
                for (double[][] other : passing) {
                    if (crossesChip(other, chip)) {
                        cost += ON_A_LINE;
                    }
                }
                // Over a bend of its own line: it hides where its line goes.
                for (int k = 1; k < points.length; k++) {
                    if (k != i && stretchCrosses(points, k, chip)) {
                        cost += 20;
                    }
                }
                if (cost < best[1]) {
                    best = new double[] {at, cost, chip[0], chip[1], chip[2], chip[3]};
                }
            }
        }
        return best;
    }

    private static boolean inside(double[] point, double[] chip, double margin) {
        return point[0] > chip[0] - margin && point[0] < chip[2] + margin
                && point[1] > chip[1] - margin && point[1] < chip[3] + margin;
    }

    /** Whether any stretch of the line runs through the inside of the chip: x1, y1, x2, y2. */
    private static boolean crossesChip(double[][] points, double[] chip) {
        for (int i = 1; i < points.length; i++) {
            if (stretchCrosses(points, i, chip)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the stretch that ends at the line's {@code i}th point runs through the chip. */
    private static boolean stretchCrosses(double[][] points, int i, double[] chip) {
        double x1 = Math.min(points[i - 1][0], points[i][0]);
        double x2 = Math.max(points[i - 1][0], points[i][0]);
        double y1 = Math.min(points[i - 1][1], points[i][1]);
        double y2 = Math.max(points[i - 1][1], points[i][1]);
        return x2 > chip[0] && x1 < chip[2] && y2 > chip[1] && y1 < chip[3];
    }

    /** A point on a box's side a line can leave or arrive at, and the stub out from it. */
    private static final class Port {
        final int side;
        /** The grid point at the far end of the stub. */
        final int point;
        final int heading;
        /** How far along the side from the point it offers first, in points. */
        final double offset;
        /** The point under a class where its subclasses meet: theirs, and never taken. */
        final boolean meeting;
        /** Halfway between two others, for when they are taken: its stub is 10px from theirs. */
        final boolean spare;
        /**
         * A stub of one pitch, into a gap too narrow for a whole one: a line may leave by it, but
         * not arrive, as its arrowhead would reach round the bend.
         */
        final boolean brief;
        /** How many lines leave or arrive here. */
        int uses;

        Port(int side, int point, double offset, boolean meeting, boolean spare, boolean brief) {
            this.side = side;
            this.point = point;
            this.heading = side == RIGHT || side == LEFT ? ACROSS : DOWN;
            this.offset = offset;
            this.meeting = meeting;
            this.spare = spare;
            this.brief = brief;
        }
    }

    /** Which side a line leaves or arrives at first: an arrow, a subclass, its class. */
    private static final double[][] SIDE_COST = {
        {0, 20, 40, 80},
        {20, 0, 80, 20},
        {40, 80, 20, 60},
    };

    private static final int ARROW = 0;
    private static final int SUBCLASS = 1;
    private static final int SUPERCLASS = 2;

    /** Every further point along a side costs a little, so the middle ones fill first. */
    private static final double PORT_COST = 6;

    /**
     * What a spare point costs: a busy box, whose points {@link #PORTS_APART} apart are all taken,
     * still has its lines routed, two stubs 10px apart rather than one drawn through the boxes.
     */
    private static final double SPARE = 150;

    /** What leaving by a stub of one pitch costs. */
    private static final double BRIEF = 60;

    /**
     * What sharing a point another line leaves or arrives at costs: only when a box has no point
     * left free, as on a class with fifteen arrows standing between two others 20px apart, where
     * the alternative was a line drawn through the boxes.
     */
    private static final double SHARED = 400;

    /** Drops the points where a route goes straight on. */
    private static List<double[]> simplified(List<double[]> walk) {
        List<double[]> bends = new ArrayList<double[]>();
        for (int i = 0; i < walk.size(); i++) {
            double[] here = walk.get(i);
            if (i > 0 && i < walk.size() - 1) {
                double[] before = walk.get(i - 1);
                double[] after = walk.get(i + 1);
                if ((before[0] == here[0] && here[0] == after[0])
                        || (before[1] == here[1] && here[1] == after[1])) {
                    continue;
                }
            }
            bends.add(here);
        }
        return bends;
    }

    private static boolean same(List<double[]> one, List<double[]> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < one.size(); i++) {
            if (!Arrays.equals(one.get(i), other.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lines a route may run on - one every {@link Grid#pitch} pixels across the board - what
     * stands on them, and who runs along them.
     */
    private static final class Grid {
        final double x0;
        final double y0;
        final double pitch;
        final int columns;
        final int rows;
        /** Inside a box's clearance. */
        final boolean[] blocked;
        /** The smallest frame each point is in, or -1. */
        final int[] frameAt;
        /** On the outline of a frame. */
        final boolean[] border;
        /** In the band at the top of a frame where its name stands. */
        final boolean[] title;
        /** Under the arrowhead of a subclass line: the number of its class, or 0. */
        final int[] head;
        /**
         * By the way a step runs, across or down, and by the point it starts at: how many lines run
         * along it, the class whose subclass lines run there, numbered from 1, and how many of the
         * lines are those. A line of that class's subclasses may share the step when all are.
         */
        final int[][] count;
        final int[][] who;
        final int[][] own;
        /** How many labels each point is under. */
        final int[] covered;
        /** How many neighbouring lines on either side are closer than {@link #APART}. */
        final int beside;
        final Map<String, Port[]> ports = new HashMap<String, Port[]>();

        // What one search keeps: by state, a point and the way it was entered.
        final double[] cost;
        final int[] came;
        final int[] seen;
        /** What is left from a point, worked out once a search: by point, and the search's stamp. */
        final double[] aimedAt;
        final int[] aimed;
        int stamp;
        final int[] goalSeen;
        final double[] goalCost;
        final Port[] goalPort;
        int goalStamp;
        final Heap open = new Heap();
        /** How many states all searches so far have taken from their queues. */
        long work;

        private Grid(double x0, double y0, double pitch, int columns, int rows) {
            this.x0 = x0;
            this.y0 = y0;
            this.pitch = pitch;
            this.columns = columns;
            this.rows = rows;
            int points = columns * rows;
            blocked = new boolean[points];
            frameAt = new int[points];
            border = new boolean[points];
            title = new boolean[points];
            head = new int[points];
            count = new int[2][points];
            who = new int[2][points];
            own = new int[2][points];
            covered = new int[points];
            beside = (int) Math.ceil(APART / pitch) - 1;
            cost = new double[points * 2];
            came = new int[points * 2];
            seen = new int[points * 2];
            aimedAt = new double[points];
            aimed = new int[points];
            goalSeen = new int[points];
            goalCost = new double[points];
            goalPort = new Port[points];
        }

        static Grid over(List<Box> boxes, List<Box> frames) {
            if (boxes.isEmpty()) {
                return null;
            }
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (List<Box> each : Arrays.asList(boxes, frames)) {
                for (Box box : each) {
                    minX = Math.min(minX, box.x);
                    minY = Math.min(minY, box.y);
                    maxX = Math.max(maxX, box.right());
                    maxY = Math.max(maxY, box.bottom());
                }
            }
            double pitch = PITCH;
            double margin = 100;
            while (((maxX - minX + 2 * margin) / pitch + 1) * ((maxY - minY + 2 * margin) / pitch + 1)
                    > MOST_POINTS) {
                pitch *= 2;
            }
            // On the board's 20px grid; at a coarser pitch between its lines, 10px from a box.
            double offset = pitch > PITCH ? pitch / 2 : 0;
            double x0 = Math.floor((minX - margin) / 20) * 20 + offset;
            double y0 = Math.floor((minY - margin) / 20) * 20 + offset;
            int columns = (int) Math.ceil((maxX + margin - x0) / pitch) + 1;
            int rows = (int) Math.ceil((maxY + margin - y0) / pitch) + 1;
            Grid grid = new Grid(x0, y0, pitch, columns, rows);
            for (Box box : boxes) {
                int i1 = Math.max(0, grid.columnAtOrAfter(box.x - CLEARANCE));
                int i2 = Math.min(columns - 1, grid.columnAtOrBefore(box.right() + CLEARANCE));
                int j1 = Math.max(0, grid.rowAtOrAfter(box.y - CLEARANCE));
                int j2 = Math.min(rows - 1, grid.rowAtOrBefore(box.bottom() + CLEARANCE));
                for (int i = i1; i <= i2; i++) {
                    for (int j = j1; j <= j2; j++) {
                        grid.blocked[i * rows + j] = true;
                    }
                }
            }
            Arrays.fill(grid.frameAt, -1);
            double[] area = new double[columns * rows];
            for (int k = 0; k < frames.size(); k++) {
                Box frame = frames.get(k);
                int i1 = Math.max(0, grid.columnAtOrAfter(frame.x));
                int i2 = Math.min(columns - 1, grid.columnAtOrBefore(frame.right()));
                int j1 = Math.max(0, grid.rowAtOrAfter(frame.y));
                int j2 = Math.min(rows - 1, grid.rowAtOrBefore(frame.bottom()));
                for (int i = i1; i <= i2; i++) {
                    for (int j = j1; j <= j2; j++) {
                        int point = i * rows + j;
                        if (grid.frameAt[point] < 0 || frame.w * frame.h < area[point]) {
                            grid.frameAt[point] = k;
                            area[point] = frame.w * frame.h;
                        }
                        if (Math.abs(grid.x(i) - frame.x) < 1 || Math.abs(grid.x(i) - frame.right()) < 1
                                || Math.abs(grid.y(j) - frame.y) < 1
                                || Math.abs(grid.y(j) - frame.bottom()) < 1) {
                            grid.border[point] = true;
                        }
                        if (grid.y(j) < frame.y + FramedGroup.LABEL_BAND) {
                            grid.title[point] = true;
                        }
                    }
                }
            }
            return grid;
        }

        double x(int i) {
            return x0 + i * pitch;
        }

        double y(int j) {
            return y0 + j * pitch;
        }

        int columnAtOrAfter(double x) {
            return (int) Math.ceil((x - x0) / pitch - 1e-9);
        }

        int columnAtOrBefore(double x) {
            return (int) Math.floor((x - x0) / pitch + 1e-9);
        }

        int rowAtOrAfter(double y) {
            return (int) Math.ceil((y - y0) / pitch - 1e-9);
        }

        int rowAtOrBefore(double y) {
            return (int) Math.floor((y - y0) / pitch + 1e-9);
        }

        int column(double x) {
            return Math.max(0, Math.min(columns - 1, (int) Math.round((x - x0) / pitch)));
        }

        int row(double y) {
            return Math.max(0, Math.min(rows - 1, (int) Math.round((y - y0) / pitch)));
        }

        /** Counts the line on every step it runs along. */
        void occupy(double[][] points, int owner) {
            count(points, owner, 1);
        }

        /**
         * Marks the points under the arrowhead at the end of a subclass line of {@code owner}: the
         * last {@link #ARROWHEAD} pixels of it, over as many stretches as that takes - a lane's last
         * stretch, from its bend under the class to the class, is 8 px.
         */
        void markHead(double[][] points, int owner) {
            if (points.length < 2 || owner <= 0) {
                return;
            }
            double left = ARROWHEAD;
            for (int k = points.length - 1; k > 0 && left >= 0; k--) {
                double[] end = points[k];
                double[] from = points[k - 1];
                double length = Math.abs(end[0] - from[0]) + Math.abs(end[1] - from[1]);
                for (double back = 0; back <= Math.min(left, length); back += pitch / 2) {
                    double x = end[0] + (from[0] - end[0]) * back / Math.max(length, 1e-9);
                    double y = end[1] + (from[1] - end[1]) * back / Math.max(length, 1e-9);
                    int i = columnAtOrBefore(x + pitch / 2);
                    int j = rowAtOrBefore(y + pitch / 2);
                    if (i >= 0 && i < columns && j >= 0 && j < rows
                            && Math.abs(x(i) - x) < pitch / 2 + 1e-9
                            && Math.abs(y(j) - y) < pitch / 2 + 1e-9) {
                        head[i * rows + j] = owner;
                    }
                }
                left -= length;
            }
        }

        /**
         * Counts the line on every step it runs along, or with {@code by} -1 takes it off again: so
         * a line can be routed again without counting every other line afresh. Taking off the last
         * line of a class's subclasses from a step others still share leaves that step to nobody's
         * subclasses, which costs a line of theirs a lane it might have shared, and nothing else.
         */
        void count(double[][] points, int owner, int by) {
            for (int k = 1; k < points.length; k++) {
                int[] step = steps(points[k - 1], points[k]);
                if (step == null) {
                    continue;
                }
                int[] lines = count[step[0]];
                int[] whose = who[step[0]];
                int[] theirs = own[step[0]];
                for (int s = step[1]; s <= step[2]; s += step[3]) {
                    if (by > 0) {
                        if (lines[s] == 0 || theirs[s] == 0) {
                            whose[s] = Math.max(owner, 0);
                            theirs[s] = owner > 0 ? 1 : 0;
                        } else if (owner > 0 && whose[s] == owner) {
                            theirs[s]++;
                        }
                        lines[s]++;
                    } else {
                        lines[s]--;
                        if (owner > 0 && whose[s] == owner) {
                            theirs[s]--;
                        }
                        if (lines[s] == 0 || theirs[s] == 0) {
                            whose[s] = 0;
                            theirs[s] = 0;
                        }
                    }
                }
            }
        }

        /** Counts a label over the points it covers, or takes it off again. */
        void cover(double[] chip, int by) {
            if (chip == null) {
                return;
            }
            for (int i = Math.max(0, columnAtOrAfter(chip[0])); i <= Math.min(columns - 1,
                    columnAtOrBefore(chip[2])); i++) {
                for (int j = Math.max(0, rowAtOrAfter(chip[1])); j <= Math.min(rows - 1,
                        rowAtOrBefore(chip[3])); j++) {
                    covered[i * rows + j] += by;
                }
            }
        }

        /**
         * The steps from one point to the next, which must be in line: which way, the first and
         * last step's index and how far apart steps are. Null for a stretch shorter than a step.
         */
        int[] steps(double[] from, double[] to) {
            if (Math.abs(from[1] - to[1]) < 0.5) {
                int j = row(from[1]);
                int i1 = column(Math.min(from[0], to[0]));
                int i2 = column(Math.max(from[0], to[0]));
                return i2 <= i1 ? null : new int[] {ACROSS, i1 * rows + j, (i2 - 1) * rows + j, rows};
            }
            if (Math.abs(from[0] - to[0]) < 0.5) {
                int i = column(from[0]);
                int j1 = row(Math.min(from[1], to[1]));
                int j2 = row(Math.max(from[1], to[1]));
                return j2 <= j1 ? null : new int[] {DOWN, i * rows + j1, i * rows + j2 - 1, 1};
            }
            return null;
        }

        /**
         * Whether a line would run on, or closer than {@link #APART} beside, a line of another
         * class's subclasses or anything else already there; crossing is allowed. With {@code self},
         * the line is counted already, and a step of its own is one it shares only with a second.
         */
        boolean clashes(double[][] points, int owner) {
            return together(points, owner, 0);
        }

        private boolean together(double[][] points, int owner, int self) {
            for (int k = 1; k < points.length; k++) {
                int[] step = steps(points[k - 1], points[k]);
                if (step == null) {
                    continue;
                }
                int next = step[0] == ACROSS ? 1 : rows;
                for (int s = step[1]; s <= step[2]; s += step[3]) {
                    if (count[step[0]][s] > self && !shares(step[0], s, owner)) {
                        return true;
                    }
                    for (int n = 1; n <= beside; n++) {
                        if (besideOf(step[0], s, n) && theirs(step[0], s + n * next, owner)
                                || besideOf(step[0], s, -n)
                                        && theirs(step[0], s - n * next, owner)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Whether the step {@code n} lines over from this one is on the grid. */
        boolean besideOf(int heading, int step, int n) {
            if (heading == ACROSS) {
                int j = step % rows + n;
                return j >= 0 && j < rows;
            }
            int i = step / rows + n;
            return i >= 0 && i < columns;
        }

        /** Whether a line of anybody but this class's subclasses runs along the step. */
        boolean theirs(int heading, int step, int owner) {
            return count[heading][step] > 0 && !shares(heading, step, owner);
        }

        /** Whether every line along the step is one of this class's subclass lines. */
        private boolean shares(int heading, int step, int owner) {
            return owner > 0 && who[heading][step] == owner
                    && own[heading][step] == count[heading][step];
        }

        /**
         * Every point on the box a line may leave or arrive at, worked out once: along each side
         * {@link #PORTS_APART} apart and centred on it, so the stubs of two lines leaving one side
         * are never closer than {@link #APART}. Not the middle of the left side, where the box's own
         * subclass line leaves, nor the bottom's left end, where its subclasses come in - but for
         * the one point there under the box where they meet, which is theirs.
         */
        Port[] portsOf(Box box) {
            Port[] found = ports.get(box.id);
            if (found != null) {
                return found;
            }
            List<Port> list = new ArrayList<Port>();
            double middleY = box.centreY();
            int lane = column(box.x - HierarchyColumns.INDENT / 2);
            for (double[] at : along(box.y, box.bottom())) {
                double y = at[0];
                boolean spare = at[1] > 0;
                int j = row(y);
                double offset = Math.abs(y - middleY) / PORTS_APART;
                int near = columnAtOrAfter(box.right() + CLEARANCE + 1e-6);
                int out = columnAtOrAfter(box.right() + STUB);
                if (clearAcross(j, near, out) && quiet(ACROSS, j, column(box.right()), out)) {
                    list.add(new Port(RIGHT, out * rows + j, offset, false, spare, false));
                } else if (clearAcross(j, near, near) && quiet(ACROSS, j, column(box.right()), near)) {
                    list.add(new Port(RIGHT, near * rows + j, offset, false, spare, true));
                }
                // Past the lane the box's subclass line runs down, if it has one.
                boolean busy = count[DOWN][lane * rows + j] > 0
                        || j > 0 && count[DOWN][lane * rows + j - 1] > 0;
                near = columnAtOrBefore(box.x - CLEARANCE - 1e-6);
                int in = columnAtOrBefore(box.x - STUB - (busy ? HierarchyColumns.INDENT / 2 : 0));
                if (clearAcross(j, in, near) && quiet(ACROSS, j, in, column(box.x))) {
                    list.add(new Port(LEFT, in * rows + j, offset, false, spare, false));
                } else if (!busy && clearAcross(j, near, near)
                        && quiet(ACROSS, j, near, column(box.x))) {
                    list.add(new Port(LEFT, near * rows + j, offset, false, spare, true));
                }
            }
            double meet = box.x + HierarchyColumns.INDENT / 2;
            double middleX = box.x + box.w / 2;
            for (double[] at : along(box.x, box.right())) {
                double x = at[0];
                boolean spare = at[1] > 0;
                int i = column(x);
                double offset = Math.abs(x - middleX) / PORTS_APART;
                int near = rowAtOrBefore(box.y - CLEARANCE - 1e-6);
                int up = rowAtOrBefore(box.y - STUB);
                if (clearDown(i, up, near) && quiet(DOWN, i, up, row(box.y))) {
                    list.add(new Port(TOP, i * rows + up, offset, false, spare, false));
                } else if (clearDown(i, near, near) && quiet(DOWN, i, near, row(box.y))) {
                    list.add(new Port(TOP, i * rows + near, offset, false, spare, true));
                }
                if (x - meet < APART) {
                    continue;
                }
                offset = (x - meet) / PORTS_APART;
                near = rowAtOrAfter(box.bottom() + CLEARANCE + 1e-6);
                int down = rowAtOrAfter(box.bottom() + STUB);
                if (clearDown(i, near, down) && quiet(DOWN, i, row(box.bottom()), down)) {
                    list.add(new Port(BOTTOM, i * rows + down, offset, false, spare, false));
                } else if (clearDown(i, near, near) && quiet(DOWN, i, row(box.bottom()), near)) {
                    list.add(new Port(BOTTOM, i * rows + near, offset, false, spare, true));
                }
            }
            int i = column(meet);
            int down = rowAtOrAfter(box.bottom() + STUB);
            if (x(i) <= box.right()
                    && clearDown(i, rowAtOrAfter(box.bottom() + CLEARANCE + 1e-6), down)) {
                list.add(new Port(BOTTOM, i * rows + down, 0, true, false, false));
            }
            found = list.toArray(new Port[0]);
            ports.put(box.id, found);
            return found;
        }

        /**
         * Whether nothing already runs on or closer than {@link #APART} beside the stub from step
         * {@code from} to step {@code to} along line {@code fixed}: a loop's leg, or the stub of
         * the box's own subclass line.
         */
        private boolean quiet(int heading, int fixed, int from, int to) {
            int[] lines = count[heading];
            int over = heading == ACROSS ? 1 : rows;
            for (int k = Math.min(from, to); k < Math.max(from, to); k++) {
                int step = heading == ACROSS ? k * rows + fixed : fixed * rows + k;
                if (lines[step] > 0) {
                    return false;
                }
                for (int n = 1; n <= beside; n++) {
                    if (besideOf(heading, step, n) && lines[step + n * over] > 0
                            || besideOf(heading, step, -n) && lines[step - n * over] > 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Points {@link #PORTS_APART} apart between two ends of a side, clear of its corners, and
         * the spare points halfway between them: each the place along the side, and 1 for a spare.
         */
        private double[][] along(double from, double to) {
            double room = to - from - 2 * CORNER;
            if (room < 0) {
                return new double[][] {{(from + to) / 2, 0}};
            }
            int count = (int) Math.floor(room / PORTS_APART) + 1;
            double first = (from + to) / 2 - (count - 1) * PORTS_APART / 2;
            double[][] points = new double[2 * count - 1][];
            for (int k = 0; k < count; k++) {
                points[k] = new double[] {first + k * PORTS_APART, 0};
                if (k > 0) {
                    points[count + k - 1] = new double[] {first + (k - 0.5) * PORTS_APART, 1};
                }
            }
            return points;
        }

        private boolean clearAcross(int j, int i1, int i2) {
            if (j < 0 || j >= rows || i1 < 0 || i2 >= columns || i1 > i2) {
                return false;
            }
            for (int i = i1; i <= i2; i++) {
                if (blocked[i * rows + j]) {
                    return false;
                }
            }
            return true;
        }

        private boolean clearDown(int i, int j1, int j2) {
            if (i < 0 || i >= columns || j1 < 0 || j2 >= rows || j1 > j2) {
                return false;
            }
            for (int j = j1; j <= j2; j++) {
                if (blocked[i * rows + j]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * What leaving or arriving at this point costs a line of this kind; MAX_VALUE for never.
         * A point another line has is never offered but when {@code share}.
         */
        static double costOf(Port port, int role, int sides, boolean arriving, boolean share) {
            if ((sides & 1 << port.side) == 0 || port.brief && arriving) {
                return Double.MAX_VALUE;
            }
            if (port.meeting) {
                return role == SUPERCLASS ? 0 : Double.MAX_VALUE;
            }
            if (port.uses > 0 && !share) {
                return Double.MAX_VALUE;
            }
            return SIDE_COST[role][port.side] + port.offset * PORT_COST + (port.spare ? SPARE : 0)
                    + (port.brief ? BRIEF : 0) + (port.uses > 0 ? SHARED : 0);
        }

        /**
         * Gives the line its route and claims its two points; false when there is no way or no time.
         * A line with a label whose best place on that route covers a box, a label or a line is
         * given the cheaper, label and all, of that route and two more: one leaving and arriving at
         * the top or the bottom of the boxes, and one at their sides. Between two boxes side by side
         * the direct route is 40px long, and its label had nowhere to go but on one of the two.
         */
        boolean give(Drawn line, List<Box> frames, long deadline, List<Drawn> drawn,
                List<Box> boxes) {
            Found found = route(line, frames, deadline, ANY_SIDE, false);
            if (found == null) {
                found = route(line, frames, deadline, ANY_SIDE, true);
            }
            if (found == null || found == OUT_OF_TIME) {
                return false;
            }
            double[] spot = null;
            if (line.link.labelW > 0) {
                List<double[]> chips = chipsOf(drawn);
                spot = bestSpot(polyline(line.source, found.bends, line.target), line.link, drawn,
                        line, boxes, frames, chips);
                if (spot[1] >= ON_A_LINE) {
                    double least = found.cost + spot[1];
                    for (int sides : new int[] {UP_OR_DOWN, LEFT_OR_RIGHT}) {
                        Found other = route(line, frames, deadline, sides, false);
                        if (other == null || other == OUT_OF_TIME) {
                            continue;
                        }
                        double[] there = bestSpot(polyline(line.source, other.bends, line.target),
                                line.link, drawn, line, boxes, frames, chips);
                        if (other.cost + there[1] < least) {
                            least = other.cost + there[1];
                            found = other;
                            spot = there;
                        }
                    }
                }
            }
            line.give(found.bends);
            line.from = found.from;
            line.to = found.to;
            line.from.uses++;
            line.to.uses++;
            line.chip = spot == null ? null : new double[] {spot[2], spot[3], spot[4], spot[5]};
            return true;
        }

        private static List<double[]> chipsOf(List<Drawn> drawn) {
            List<double[]> chips = new ArrayList<double[]>();
            for (Drawn line : drawn) {
                if (line.chip != null) {
                    chips.add(line.chip);
                }
            }
            return chips;
        }

        /** Whether the line's label, wherever it goes on it, covers a box, a label or a line. */
        private static boolean covers(Drawn line, List<Drawn> drawn, List<Box> boxes,
                List<Box> frames) {
            List<double[]> chips = chipsOf(drawn);
            chips.remove(line.chip);
            return bestSpot(line.points, line.link, drawn, line, boxes, frames, chips)[1]
                    >= ON_A_LINE;
        }

        /**
         * The cheapest route for the line, from a free point on one box to a free point on the
         * other, first near the two and then anywhere; null when there is none.
         */
        Found route(Drawn line, List<Box> frames, long deadline, int sides, boolean share) {
            boolean[] foreign = new boolean[frames.size()];
            for (int k = 0; k < frames.size(); k++) {
                Box frame = frames.get(k);
                foreign[k] = !frame.contains(line.source.x + 1, line.source.y + 1)
                        && !frame.contains(line.target.x + 1, line.target.y + 1);
            }
            int[] window = {
                Math.max(0, column(Math.min(line.source.x, line.target.x) - WINDOW)),
                Math.max(0, row(Math.min(line.source.y, line.target.y) - WINDOW)),
                Math.min(columns - 1, column(Math.max(line.source.right(), line.target.right())
                        + WINDOW)),
                Math.min(rows - 1, row(Math.max(line.source.bottom(), line.target.bottom())
                        + WINDOW))};
            Found found = search(line, foreign, window, deadline, sides, share);
            if (found == null && (window[0] > 0 || window[1] > 0 || window[2] < columns - 1
                    || window[3] < rows - 1)) {
                found = search(line, foreign, new int[] {0, 0, columns - 1, rows - 1}, deadline,
                        sides, share);
            }
            return found;
        }

        /** The route inside the window; null when there is none, OUT_OF_TIME when time ran out. */
        private Found search(Drawn line, boolean[] foreign, int[] window, long deadline,
                int sides, boolean share) {
            int from = line.link.hierarchy ? SUBCLASS : ARROW;
            int to = line.link.hierarchy ? SUPERCLASS : ARROW;
            int owner = line.owner;
            Port[] starts = portsOf(line.source);
            Port[] goals = portsOf(line.target);
            goalStamp++;
            List<double[]> aims = new ArrayList<double[]>();
            for (Port port : goals) {
                double cost = costOf(port, to, sides, true, share);
                if (cost == Double.MAX_VALUE
                        || goalSeen[port.point] == goalStamp && goalCost[port.point] <= cost) {
                    continue;
                }
                goalSeen[port.point] = goalStamp;
                goalCost[port.point] = cost;
                goalPort[port.point] = port;
                aims.add(new double[] {port.point / rows, port.point % rows, cost});
            }
            if (aims.isEmpty()) {
                return null;
            }
            double[][] aim = aims.toArray(new double[0][]);
            stamp++;
            open.clear();
            for (int k = 0; k < starts.length; k++) {
                Port port = starts[k];
                double cost = costOf(port, from, sides, false, share);
                int i = port.point / rows;
                int j = port.point % rows;
                if (cost == Double.MAX_VALUE || i < window[0] || i > window[2] || j < window[1]
                        || j > window[3]) {
                    continue;
                }
                int state = port.point * 2 + port.heading;
                if (seen[state] != stamp || cost < this.cost[state]) {
                    seen[state] = stamp;
                    this.cost[state] = cost;
                    came[state] = -1 - k;
                    open.push(cost + GREED * remaining(port.point, aim), state);
                }
            }
            double best = Double.MAX_VALUE;
            int reached = -1;
            int pops = 0;
            while (open.size > 0) {
                double f = open.topKey();
                int state = open.pop();
                if (f >= best) {
                    break;
                }
                int point = state >> 1;
                int heading = state & 1;
                int i = point / rows;
                int j = point % rows;
                double g = cost[state];
                if (f > g + GREED * remaining(point, aim) + 1e-6) {
                    continue;
                }
                work++;
                if (++pops > MOST_STEPS || (pops & 1023) == 0 && System.nanoTime() > deadline) {
                    return OUT_OF_TIME;
                }
                if (goalSeen[point] == goalStamp
                        && (came[state] < 0 || !outwards(goalPort[point].side, came[state] >> 1,
                                point))) {
                    double arrive = g + goalCost[point]
                            + (heading == goalPort[point].heading ? 0 : BEND);
                    if (arrive < best) {
                        best = arrive;
                        reached = state;
                    }
                }
                // Out along the stub, never back along it into the box it leaves.
                int back = came[state] < 0 ? INTO[starts[-1 - came[state]].side] : -1;
                for (int dir = 0; dir < 4; dir++) {
                    int ni = i + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                    int nj = j + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                    if (dir == back || ni < window[0] || ni > window[2] || nj < window[1]
                            || nj > window[3]) {
                        continue;
                    }
                    int next = ni * rows + nj;
                    if (blocked[next]) {
                        continue;
                    }
                    int along = dir < 2 ? ACROSS : DOWN;
                    int step = dir == 0 || dir == 2 ? next : point;
                    double through = g + pitch + (along == heading ? 0 : BEND);
                    int frame = frameAt[point];
                    if (frame >= 0 && foreign[frame] && frameAt[next] == frame) {
                        through += pitch * (THROUGH_A_FRAME - 1);
                    }
                    if (border[point] && border[next]) {
                        through += pitch * ON_A_BORDER;
                    }
                    if (title[next]) {
                        through += pitch * IN_A_TITLE;
                    }
                    if (head[next] > 0 && head[next] != owner) {
                        through += THROUGH_AN_ARROWHEAD;
                    }
                    if (covered[next] > 0) {
                        through += pitch * UNDER_A_LABEL;
                    }
                    if (theirs(along, step, owner)) {
                        through += OVERLAP * pitch;
                    }
                    int over = along == ACROSS ? 1 : rows;
                    for (int n = 1; n <= beside; n++) {
                        if (besideOf(along, step, n) && theirs(along, step + n * over, owner)) {
                            through += BESIDE * pitch;
                        }
                        if (besideOf(along, step, -n) && theirs(along, step - n * over, owner)) {
                            through += BESIDE * pitch;
                        }
                    }
                    // Crossing: running into a point where a line passes the other way.
                    int other = 1 - along;
                    int behind = along == ACROSS ? 1 : rows;
                    boolean first = along == ACROSS ? nj > 0 : ni > 0;
                    boolean last = along == ACROSS ? nj < rows - 1 : ni < columns - 1;
                    if (first && theirs(other, next - behind, owner)
                            || last && theirs(other, next, owner)) {
                        through += CROSSING;
                    }
                    int onto = next * 2 + along;
                    if (seen[onto] != stamp || through < cost[onto]) {
                        seen[onto] = stamp;
                        cost[onto] = through;
                        came[onto] = state;
                        open.push(through + GREED * remaining(next, aim), onto);
                    }
                }
            }
            if (reached < 0) {
                return null;
            }
            List<double[]> walk = new ArrayList<double[]>();
            int at = reached;
            Port start;
            while (true) {
                int point = at >> 1;
                walk.add(new double[] {x(point / rows), y(point % rows)});
                if (came[at] < 0) {
                    start = starts[-1 - came[at]];
                    break;
                }
                at = came[at];
            }
            Collections.reverse(walk);
            return new Found(simplified(walk), start, goalPort[reached >> 1], best);
        }

        /**
         * Whether the step from {@code previous} to {@code point} runs out along the stub of a point
         * on this side: arriving that way, a line would go back over its last step into the box.
         */
        private boolean outwards(int side, int previous, int point) {
            int di = point / rows - previous / rows;
            int dj = point % rows - previous % rows;
            switch (side) {
                case RIGHT:
                    return di > 0;
                case LEFT:
                    return di < 0;
                case TOP:
                    return dj < 0;
                default:
                    return dj > 0;
            }
        }

        /** No less than what is left to the nearest point to arrive at, with a bend if it is off line. */
        private double remaining(int point, double[][] aim) {
            if (aimed[point] == stamp) {
                return aimedAt[point];
            }
            int i = point / rows;
            int j = point % rows;
            double least = Double.MAX_VALUE;
            for (double[] goal : aim) {
                double di = Math.abs(i - goal[0]);
                double dj = Math.abs(j - goal[1]);
                double left = (di + dj) * pitch + goal[2] + (di > 0 && dj > 0 ? BEND : 0);
                if (left < least) {
                    least = left;
                }
            }
            aimed[point] = stamp;
            aimedAt[point] = least;
            return least;
        }

        /**
         * A second look at every line that still runs on or beside another, or whose label finds
         * no place clear of the lines routed after it: taken up and routed again with all the
         * others where they are, which the first pass, one line after another, could not do for
         * the lines that came before. Twice at most, and while the search has done no more than
         * {@code most} steps in all: on a board where nearly every line meets another this pass took
         * longer than the first. Counted in steps rather than time, so that a slower machine draws
         * the same board.
         */
        void rerouteWhatRunsTogether(List<Drawn> searched, List<Drawn> drawn, List<Box> frames,
                long deadline, long most, List<Box> boxes) {
            for (int round = 0; round < 2; round++) {
                List<Drawn> again = new ArrayList<Drawn>();
                List<Drawn> hidden = new ArrayList<Drawn>();
                for (Drawn line : searched) {
                    if (together(line.points, line.owner, 1)) {
                        again.add(line);
                    } else if (line.link.labelW > 0 && covers(line, drawn, boxes, frames)) {
                        again.add(line);
                        hidden.add(line);
                    }
                }
                boolean moved = false;
                for (Drawn line : again) {
                    if (work > most || System.nanoTime() > deadline) {
                        return;
                    }
                    count(line.points, line.owner, -1);
                    cover(line.chip, -1);
                    if (hidden.contains(line) || clashes(line.points, line.owner)) {
                        List<double[]> before = line.bends;
                        Port from = line.from;
                        Port to = line.to;
                        double[] chip = line.chip;
                        from.uses--;
                        to.uses--;
                        line.chip = null;
                        if (give(line, frames, deadline, drawn, boxes)) {
                            moved |= !same(before, line.bends);
                        } else {
                            from.uses++;
                            to.uses++;
                            line.chip = chip;
                        }
                    }
                    count(line.points, line.owner, 1);
                    cover(line.chip, 1);
                }
                if (!moved) {
                    return;
                }
            }
        }
    }

    /** The cheapest state first, and of two as cheap the lower number, so routing is repeatable. */
    private static final class Heap {
        double[] keys = new double[1024];
        int[] states = new int[1024];
        int size;

        void clear() {
            size = 0;
        }

        double topKey() {
            return keys[0];
        }

        void push(double key, int state) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, size * 2);
                states = Arrays.copyOf(states, size * 2);
            }
            int at = size++;
            while (at > 0) {
                int up = (at - 1) / 2;
                if (!before(key, state, keys[up], states[up])) {
                    break;
                }
                keys[at] = keys[up];
                states[at] = states[up];
                at = up;
            }
            keys[at] = key;
            states[at] = state;
        }

        int pop() {
            int top = states[0];
            size--;
            double key = keys[size];
            int state = states[size];
            int at = 0;
            while (true) {
                int child = 2 * at + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size
                        && before(keys[child + 1], states[child + 1], keys[child], states[child])) {
                    child++;
                }
                if (!before(keys[child], states[child], key, state)) {
                    break;
                }
                keys[at] = keys[child];
                states[at] = states[child];
                at = child;
            }
            keys[at] = key;
            states[at] = state;
            return top;
        }

        private static boolean before(double key, int state, double otherKey, int otherState) {
            return key < otherKey || key == otherKey && state < otherState;
        }
    }
}
