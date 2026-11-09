package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
import java.util.ArrayList;
import java.util.List;

/**
 * What a reader would hold against the lines of a drawn board, measured on what JGraphX draws: the
 * points of every visible line and the box of every label, after routing, at 100%.
 *
 * <p>Written for 1.135.1, after a tester measured on the study board what the tests here had not:
 * subclass lines drawn on top of each other for 600px, a relation arrow running 4px beside a
 * subclass line for 357px, and labels sitting on other lines. The tests had compared arrows with
 * arrows only, so all of that passed.
 */
final class BoardMeasures {

    /** Closer than this, two lines side by side read as one double line. */
    static final double APART = 12;

    /** A close run shorter than this is two lines arriving at neighbouring points, not a double line. */
    static final double SHORT = 24;

    final String board;
    int boxes;
    int lines;
    int labels;
    double length;
    double shared;
    double close;
    /** The longest two lines run on top of each other, and closer than {@link #APART} beside. */
    double longestShared;
    double longestClose;
    /** How many labels touch something they should not. */
    int labelsWrong;
    final List<String> sharing = new ArrayList<String>();
    final List<String> beside = new ArrayList<String>();
    final List<String> onBoxes = new ArrayList<String>();
    final List<String> onLabels = new ArrayList<String>();
    final List<String> onLines = new ArrayList<String>();
    final List<String> offTheirLine = new ArrayList<String>();
    final List<String> through = new ArrayList<String>();
    /** A line through the arrowhead of another class's subclass line, which reads as a wrong arrow. */
    final List<String> throughArrowheads = new ArrayList<String>();

    private final SchemaGraph graph;
    private final List<Object> boxCells = new ArrayList<Object>();
    private final List<Object> lineCells = new ArrayList<Object>();

    BoardMeasures(String board, SchemaGraph graph) {
        this.board = board;
        this.graph = graph;
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            String style = graph.getModel().getStyle(cell);
            if (graph.getModel().isVertex(cell)) {
                if (style == null || !style.startsWith(SchemaStyles.FRAME)) {
                    boxCells.add(cell);
                }
            } else if (graph.getModel().isEdge(cell) && graph.isCellVisible(cell)
                    && graph.getView().getState(cell) != null
                    && graph.getView().getState(cell).getAbsolutePoints() != null) {
                lineCells.add(cell);
            }
        }
        boxes = boxCells.size();
        lines = lineCells.size();
        for (Object edge : lineCells) {
            if (graph.routeOf(edge) != null) {
                routed++;
            } else {
                unrouted.add(lineName(edge));
            }
        }
        measure();
    }

    /** How many of the lines have a route of their own rather than the plain connector. */
    int routed;

    /** The lines drawn by the plain connector. */
    final List<String> unrouted = new ArrayList<String>();

    /** Everything wrong in a line or two, for a failure message or a printed table. */
    String summary() {
        return board + ": boxes=" + boxes + " lines=" + lines + " routed=" + routed
                + " labels=" + labels
                + " shared=" + Math.round(shared) + "px in " + sharing.size() + " pairs"
                + " closer-than-" + Math.round(APART) + "px=" + Math.round(close) + "px in "
                + beside.size() + " runs" + " labels-on-boxes=" + onBoxes.size()
                + " labels-on-labels=" + onLabels.size() + " labels-on-lines=" + onLines.size()
                + " labels-off-their-line=" + offTheirLine.size()
                + " lines-through-boxes=" + through.size()
                + " lines-through-arrowheads=" + throughArrowheads.size();
    }

    boolean isClean() {
        return sharing.isEmpty() && beside.isEmpty() && onBoxes.isEmpty() && onLabels.isEmpty()
                && onLines.isEmpty() && offTheirLine.isEmpty() && through.isEmpty();
    }

    private double[][] points(Object edge) {
        List<mxPoint> drawn = graph.getView().getState(edge).getAbsolutePoints();
        double[][] line = new double[drawn.size()][];
        for (int i = 0; i < line.length; i++) {
            line[i] = new double[] {drawn.get(i).getX(), drawn.get(i).getY()};
        }
        return line;
    }

    private double[] rect(Object cell) {
        mxCellState state = graph.getView().getState(cell);
        return new double[] {state.getX(), state.getY(), state.getWidth(), state.getHeight()};
    }

    private String name(Object cell) {
        return String.valueOf(graph.getModel().getValue(cell)).split("\n")[0];
    }

    private String lineName(Object edge) {
        String label = graph.getLabel(edge);
        return (isHierarchy(edge) ? "is-a" : label == null ? "" : label.replace('\n', ' ')) + " "
                + name(graph.getModel().getTerminal(edge, true)) + " -> "
                + name(graph.getModel().getTerminal(edge, false));
    }

    private boolean isHierarchy(Object edge) {
        String style = graph.getModel().getStyle(edge);
        return style != null && (style.startsWith(SchemaStyles.SUBCLASS)
                || style.startsWith(SchemaStyles.SUB_PROPERTY) || style.startsWith(SchemaStyles.TYPE));
    }

    /** Subclasses of one class meet on the line down from it: that stretch is theirs to share. */
    private boolean siblings(Object one, Object other) {
        return isHierarchy(one) && isHierarchy(other)
                && graph.getModel().getTerminal(one, false) == graph.getModel().getTerminal(other, false);
    }

    private void measure() {
        List<double[][]> drawn = new ArrayList<double[][]>();
        for (Object edge : lineCells) {
            double[][] line = points(edge);
            drawn.add(line);
            for (int i = 1; i < line.length; i++) {
                length += Math.abs(line[i][0] - line[i - 1][0]) + Math.abs(line[i][1] - line[i - 1][1]);
            }
        }
        for (int a = 0; a < lineCells.size(); a++) {
            for (int b = a + 1; b < lineCells.size(); b++) {
                if (siblings(lineCells.get(a), lineCells.get(b))) {
                    continue;
                }
                double[] runs = alongside(drawn.get(a), drawn.get(b));
                if (runs[0] > 2) {
                    shared += runs[0];
                    longestShared = Math.max(longestShared, runs[0]);
                    sharing.add(Math.round(runs[0]) + "px: [" + lineName(lineCells.get(a)) + "] / ["
                            + lineName(lineCells.get(b)) + "]");
                }
                if (runs[1] > SHORT) {
                    close += runs[1];
                    longestClose = Math.max(longestClose, runs[1]);
                    beside.add(Math.round(runs[1]) + "px at " + Math.round(runs[2]) + "px: ["
                            + lineName(lineCells.get(a)) + "] / [" + lineName(lineCells.get(b)) + "]");
                }
            }
        }
        List<double[]> chips = new ArrayList<double[]>();
        List<Object> chipLines = new ArrayList<Object>();
        for (int a = 0; a < lineCells.size(); a++) {
            Object edge = lineCells.get(a);
            String text = graph.getLabel(edge);
            mxRectangle bounds = graph.getView().getState(edge).getLabelBounds();
            if (text == null || text.trim().isEmpty() || bounds == null) {
                continue;
            }
            labels++;
            double[] chip = {bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight()};
            String what = text.replace('\n', ' ') + " (" + name(graph.getModel().getTerminal(edge, true))
                    + " -> " + name(graph.getModel().getTerminal(edge, false)) + ")";
            int before = onBoxes.size() + onLabels.size() + onLines.size() + offTheirLine.size();
            for (Object box : boxCells) {
                if (overlaps(chip, rect(box))) {
                    onBoxes.add(what + " on " + name(box));
                }
            }
            for (int c = 0; c < chips.size(); c++) {
                if (overlaps(chip, chips.get(c))) {
                    onLabels.add(what + " on " + graph.getLabel(chipLines.get(c)).replace('\n', ' '));
                }
            }
            for (int b = 0; b < lineCells.size(); b++) {
                if (b != a && crosses(drawn.get(b), chip)) {
                    onLines.add(what + " on [" + lineName(lineCells.get(b)) + "]");
                }
            }
            if (distance(chip[0] + chip[2] / 2, chip[1] + chip[3] / 2, drawn.get(a)) > 3) {
                offTheirLine.add(what);
            }
            if (onBoxes.size() + onLabels.size() + onLines.size() + offTheirLine.size() > before) {
                labelsWrong++;
            }
            chips.add(chip);
            chipLines.add(edge);
        }
        for (int a = 0; a < lineCells.size(); a++) {
            Object edge = lineCells.get(a);
            Object from = graph.getModel().getTerminal(edge, true);
            Object to = graph.getModel().getTerminal(edge, false);
            for (Object box : boxCells) {
                if (box != from && box != to && crosses(drawn.get(a), rect(box))) {
                    through.add(lineName(edge) + " passes " + name(box));
                }
            }
        }
        // The arrowhead of a subclass line: its last 13px, 13px wide, as SchemaStyles draws it.
        for (int a = 0; a < lineCells.size(); a++) {
            if (!isHierarchy(lineCells.get(a)) || drawn.get(a).length < 2) {
                continue;
            }
            double[][] line = drawn.get(a);
            double[] end = line[line.length - 1];
            double[] before = line[line.length - 2];
            boolean upright = Math.abs(end[0] - before[0]) < 0.5;
            double sign = upright ? Math.signum(before[1] - end[1]) : Math.signum(before[0] - end[0]);
            double[] head = upright
                    ? new double[] {end[0] - 5, Math.min(end[1], end[1] + 12 * sign), 10, 12}
                    : new double[] {Math.min(end[0], end[0] + 12 * sign), end[1] - 5, 12, 10};
            for (int b = 0; b < lineCells.size(); b++) {
                if (b != a && !siblings(lineCells.get(a), lineCells.get(b))
                        && crosses(drawn.get(b), head)) {
                    throughArrowheads.add("[" + lineName(lineCells.get(b)) + "] through the arrowhead of ["
                            + lineName(lineCells.get(a)) + "]");
                }
            }
        }
    }

    /**
     * How far two lines run along each other: on top of each other, and closer than {@link #APART}
     * side by side, with the nearest distance of the second.
     */
    static double[] alongside(double[][] one, double[][] other) {
        double shared = 0;
        double close = 0;
        double nearest = Double.MAX_VALUE;
        for (int i = 1; i < one.length; i++) {
            for (int j = 1; j < other.length; j++) {
                boolean flat = Math.abs(one[i][1] - one[i - 1][1]) < 0.5
                        && Math.abs(other[j][1] - other[j - 1][1]) < 0.5;
                boolean upright = Math.abs(one[i][0] - one[i - 1][0]) < 0.5
                        && Math.abs(other[j][0] - other[j - 1][0]) < 0.5;
                if (flat == upright) {
                    continue;
                }
                int along = flat ? 0 : 1;
                int across = flat ? 1 : 0;
                double gap = Math.abs(one[i][across] - other[j][across]);
                double from = Math.max(Math.min(one[i][along], one[i - 1][along]),
                        Math.min(other[j][along], other[j - 1][along]));
                double to = Math.min(Math.max(one[i][along], one[i - 1][along]),
                        Math.max(other[j][along], other[j - 1][along]));
                double run = Math.max(0, to - from);
                if (gap < 1.5) {
                    shared += run;
                } else if (gap < APART && run > 0) {
                    close += run;
                    nearest = Math.min(nearest, gap);
                }
            }
        }
        return new double[] {shared, close, nearest == Double.MAX_VALUE ? 0 : nearest};
    }

    /** Whether two rectangles - x, y, w, h - share more than a pixel's edge. */
    static boolean overlaps(double[] one, double[] other) {
        return one[0] + 1 < other[0] + other[2] && other[0] + 1 < one[0] + one[2]
                && one[1] + 1 < other[1] + other[3] && other[1] + 1 < one[1] + one[3];
    }

    /** Whether any stretch of the line runs through the inside of the rectangle. */
    static boolean crosses(double[][] line, double[] r) {
        for (int i = 1; i < line.length; i++) {
            double x1 = Math.min(line[i - 1][0], line[i][0]);
            double x2 = Math.max(line[i - 1][0], line[i][0]);
            double y1 = Math.min(line[i - 1][1], line[i][1]);
            double y2 = Math.max(line[i - 1][1], line[i][1]);
            if (x2 > r[0] + 1 && x1 < r[0] + r[2] - 1 && y2 > r[1] + 1 && y1 < r[1] + r[3] - 1) {
                return true;
            }
        }
        return false;
    }

    private static double distance(double x, double y, double[][] line) {
        double nearest = Double.MAX_VALUE;
        for (int i = 1; i < line.length; i++) {
            double dx = line[i][0] - line[i - 1][0];
            double dy = line[i][1] - line[i - 1][1];
            double squared = dx * dx + dy * dy;
            double t = squared == 0 ? 0 : Math.max(0, Math.min(1,
                    ((x - line[i - 1][0]) * dx + (y - line[i - 1][1]) * dy) / squared));
            nearest = Math.min(nearest, Math.hypot(x - line[i - 1][0] - t * dx,
                    y - line[i - 1][1] - t * dy));
        }
        return nearest;
    }
}
