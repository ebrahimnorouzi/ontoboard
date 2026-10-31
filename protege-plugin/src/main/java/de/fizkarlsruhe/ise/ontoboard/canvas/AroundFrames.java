package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxEdgeStyle;
import com.mxgraph.view.mxGraphView;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeSet;

/**
 * An edge's route, kept out of the frames it has nothing to do with.
 *
 * <p>JGraphX's orthogonal connector steers around an edge's own two ends and nothing else. On a
 * board whose patterns are framed in a row along the top, an edge from a term in the first frame
 * to one in the third went straight through the second, as if the second pattern were part of
 * it. This keeps the orthogonal route wherever it crosses no frame but the ones its ends are in,
 * and otherwise takes the shortest route with the fewest bends that keeps clear of the others.
 *
 * <p>Worked out every time the board is drawn, so it follows a frame or a node that is moved,
 * and nothing about it is saved.
 */
public final class AroundFrames implements mxEdgeStyle.mxEdgeStyleFunction {

    /** The name an edge's style gives it by. */
    public static final String NAME = "obAroundFrames";

    public static final AroundFrames INSTANCE = new AroundFrames();

    /** How far a route stays from a frame it goes around, at 100%. */
    static final double CLEARANCE = 14;

    /** What a bend costs, in pixels of route: a route does not zigzag to save a few. */
    static final double BEND = 40;

    private static final int NONE = 0;
    private static final int ACROSS = 1;
    private static final int DOWN = 2;

    private AroundFrames() {
    }

    @Override
    public void apply(mxCellState edge, mxCellState source, mxCellState target,
            List<mxPoint> points, List<mxPoint> result) {
        int before = result.size();
        mxEdgeStyle.OrthConnector.apply(edge, source, target, points, result);
        // Bends somebody placed by hand are theirs; a loop is not this function's to draw.
        if (source == null || target == null || source.getCell() == target.getCell()
                || (points != null && !points.isEmpty())) {
            return;
        }
        Point2D from = new Point2D.Double(source.getCenterX(), source.getCenterY());
        Point2D to = new Point2D.Double(target.getCenterX(), target.getCenterY());
        List<Rectangle2D> inTheWay = framesHoldingNeither(edge.getView(), from, to);
        if (inTheWay.isEmpty()) {
            return;
        }
        List<Point2D> orthogonal = new ArrayList<Point2D>();
        for (mxPoint point : result.subList(before, result.size())) {
            orthogonal.add(new Point2D.Double(point.getX(), point.getY()));
        }
        if (!crossesAny(from, orthogonal, to, inTheWay)) {
            return;
        }
        double scale = edge.getView().getScale();
        List<Point2D> around = route(from, to, inTheWay, CLEARANCE * scale, BEND * scale);
        if (around == null) {
            return;
        }
        result.subList(before, result.size()).clear();
        for (Point2D bend : around) {
            result.add(new mxPoint(bend.getX(), bend.getY()));
        }
    }

    /** The frames on the board, as drawn, that contain neither end. */
    private static List<Rectangle2D> framesHoldingNeither(mxGraphView view, Point2D from,
            Point2D to) {
        List<Rectangle2D> frames = new ArrayList<Rectangle2D>();
        mxIGraphModel model = view.getGraph().getModel();
        Object parent = view.getGraph().getDefaultParent();
        for (int i = 0; i < model.getChildCount(parent); i++) {
            Object cell = model.getChildAt(parent, i);
            String style = model.getStyle(cell);
            mxCellState frame = style != null && style.startsWith(SchemaStyles.FRAME)
                    ? view.getState(cell) : null;
            if (frame == null) {
                continue;
            }
            Rectangle2D box = new Rectangle2D.Double(frame.getX(), frame.getY(),
                    frame.getWidth(), frame.getHeight());
            if (!box.contains(from) && !box.contains(to)) {
                frames.add(box);
            }
        }
        return frames;
    }

    /** Whether the line from {@code from} through {@code via} to {@code to} enters any box. */
    static boolean crossesAny(Point2D from, List<Point2D> via, Point2D to,
            List<Rectangle2D> boxes) {
        List<Point2D> line = new ArrayList<Point2D>();
        line.add(from);
        line.addAll(via);
        line.add(to);
        for (int i = 1; i < line.size(); i++) {
            for (Rectangle2D box : boxes) {
                if (box.intersectsLine(line.get(i - 1).getX(), line.get(i - 1).getY(),
                        line.get(i).getX(), line.get(i).getY())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The bends of the shortest orthogonal route from {@code from} to {@code to} that keeps
     * {@code clearance} from every frame, a bend counted as {@code bend} of length; null when the
     * frames leave no way through.
     *
     * <p>A route only ever needs to turn on a line through one of its ends or along a frame's
     * edge at the clearance, so those lines are the whole search space: a few dozen points for a
     * board of patterns, searched for the cheapest path.
     */
    static List<Point2D> route(Point2D from, Point2D to, List<Rectangle2D> frames,
            double clearance, double bend) {
        List<Rectangle2D> blocks = new ArrayList<Rectangle2D>();
        TreeSet<Double> xs = new TreeSet<Double>(Arrays.asList(from.getX(), to.getX()));
        TreeSet<Double> ys = new TreeSet<Double>(Arrays.asList(from.getY(), to.getY()));
        for (Rectangle2D frame : frames) {
            // An end closer to a frame than the clearance still has to get out, so that frame
            // keeps half the end's distance instead: never on its border, never cut off.
            double room = Math.min(clearance,
                    Math.min(distance(frame, from), distance(frame, to)) / 2);
            Rectangle2D block = new Rectangle2D.Double(frame.getX() - room,
                    frame.getY() - room, frame.getWidth() + 2 * room,
                    frame.getHeight() + 2 * room);
            blocks.add(block);
            xs.add(block.getMinX());
            xs.add(block.getMaxX());
            ys.add(block.getMinY());
            ys.add(block.getMaxY());
        }
        double[] x = toArray(xs);
        double[] y = toArray(ys);
        int columns = x.length;
        int start = (Arrays.binarySearch(x, from.getX()) * y.length
                + Arrays.binarySearch(y, from.getY())) * 3 + NONE;
        int goal = Arrays.binarySearch(x, to.getX()) * y.length + Arrays.binarySearch(y, to.getY());

        double[] cost = new double[columns * y.length * 3];
        int[] came = new int[cost.length];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        Arrays.fill(came, -1);
        cost[start] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<double[]>(16,
                (one, other) -> one[0] != other[0] ? Double.compare(one[0], other[0])
                        : Double.compare(one[1], other[1]));
        open.add(new double[] {0, start});
        int reached = -1;
        while (!open.isEmpty()) {
            double[] next = open.poll();
            int state = (int) next[1];
            if (next[0] > cost[state]) {
                continue;
            }
            int point = state / 3;
            if (point == goal) {
                reached = state;
                break;
            }
            int i = point / y.length;
            int j = point % y.length;
            int heading = state % 3;
            int[][] steps = {{i - 1, j, ACROSS}, {i + 1, j, ACROSS}, {i, j - 1, DOWN},
                {i, j + 1, DOWN}};
            for (int[] step : steps) {
                if (step[0] < 0 || step[0] >= columns || step[1] < 0 || step[1] >= y.length
                        || blocked(blocks, (x[i] + x[step[0]]) / 2, (y[j] + y[step[1]]) / 2)) {
                    continue;
                }
                int onto = (step[0] * y.length + step[1]) * 3 + step[2];
                double through = cost[state] + Math.abs(x[step[0]] - x[i])
                        + Math.abs(y[step[1]] - y[j])
                        + (heading != NONE && heading != step[2] ? bend : 0);
                if (through < cost[onto]) {
                    cost[onto] = through;
                    came[onto] = state;
                    open.add(new double[] {through, onto});
                }
            }
        }
        if (reached < 0) {
            return null;
        }
        List<Point2D> path = new ArrayList<Point2D>();
        for (int state = reached; state >= 0; state = came[state]) {
            int point = state / 3;
            path.add(new Point2D.Double(x[point / y.length], y[point % y.length]));
        }
        Collections.reverse(path);
        List<Point2D> bends = new ArrayList<Point2D>();
        for (int k = 1; k < path.size() - 1; k++) {
            Point2D a = path.get(k - 1);
            Point2D b = path.get(k);
            Point2D c = path.get(k + 1);
            boolean straight = (a.getX() == b.getX() && b.getX() == c.getX())
                    || (a.getY() == b.getY() && b.getY() == c.getY());
            if (!straight) {
                bends.add(b);
            }
        }
        return bends;
    }

    private static boolean blocked(List<Rectangle2D> blocks, double x, double y) {
        for (Rectangle2D block : blocks) {
            if (strictlyInside(block, new Point2D.Double(x, y))) {
                return true;
            }
        }
        return false;
    }

    /** How far {@code point} is from {@code box} along whichever axis it is furthest out. */
    private static double distance(Rectangle2D box, Point2D point) {
        return Math.max(Math.max(box.getMinX() - point.getX(), point.getX() - box.getMaxX()),
                Math.max(box.getMinY() - point.getY(), point.getY() - box.getMaxY()));
    }

    private static boolean strictlyInside(Rectangle2D box, Point2D point) {
        return point.getX() > box.getMinX() && point.getX() < box.getMaxX()
                && point.getY() > box.getMinY() && point.getY() < box.getMaxY();
    }

    private static double[] toArray(TreeSet<Double> values) {
        double[] array = new double[values.size()];
        int k = 0;
        for (Double value : values) {
            array[k++] = value;
        }
        return array;
    }
}
