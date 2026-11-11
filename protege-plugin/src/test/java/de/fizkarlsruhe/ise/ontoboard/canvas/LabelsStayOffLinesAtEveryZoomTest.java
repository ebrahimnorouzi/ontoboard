package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A label lies on no other line, box or label at any zoom, where the router found it a clear place.
 *
 * <p>Found on the egg-boiling study board (1.135.1, Windows): after T3 the label "∃ concretizes"
 * (plan to plan specification) lay on the is-a line from egg boiling up to completely executed
 * planned process, and after T6 "∃ has specified output" (assay to data entity) on the is-a lines
 * from plate and raw egg up to object - measured, as the study measures, at the zoom Fit gave,
 * 31%. At 100% neither touched anything: the router had put each 2px clear of the line, as it puts
 * every label, and JGraphX drew it elsewhere and bigger. Elsewhere: the router gives a label's
 * place as a share of its line, which ends on the target box, and JGraphX took that share of the
 * line it draws, which stops 3px short of the box for the arrowhead's air - so every label stood
 * up to 3px nearer the source than it was placed, "∃ concretizes" 1.7px, 0.3px from the is-a
 * line. Bigger: JGraphX measures a line's label at every zoom in the font scaled to it, whose
 * glyph widths are rounded to whole pixels, so at 31% that chip was 5px wider on the board than at
 * 100%, and 26px wider at 21%. The 2px were gone, and the chip hid the line.
 *
 * <p>The boards are the live ones, from {@code fixture-egg-board-after-T3.tsv} and {@code -T6}:
 * every frame, note and box where it stood, every line drawn, routed here as they were there.
 */
class LabelsStayOffLinesAtEveryZoomTest {

    /** The air the router keeps round a label: no other line is closer to its chip. */
    private static final double AIR = 2;

    /** The zoom Fit gave the board after T3 in the study's run: where it was seen. */
    private static final double FITTED = 0.3060185185185185;

    /**
     * Measured as the study's probe measures, in board pixels at the zoom shown: a label on a line
     * when the line passes more than a pixel inside its chip, on a box or a label when the chips
     * overlap at all. At 31%, and at every zoom from 10% to 200%: none, on either board.
     */
    @Test
    void onTheEggBoardNoLabelLiesOnALineABoxOrALabelAtAnyZoom() throws Exception {
        List<String> found = new ArrayList<String>();
        for (String board : new String[] {"T3", "T6"}) {
            SchemaGraph graph = routed(board);
            for (double scale : zooms(FITTED)) {
                graph.getView().setScale(scale);
                graph.getView().validate();
                for (String wrong : wrongLabels(graph)) {
                    found.add("after " + board + " at " + Math.round(scale * 1000) / 10.0 + "%: "
                            + wrong);
                }
            }
        }
        assertEquals(new ArrayList<String>(), found);
    }

    /**
     * A label stands where the router placed it: at 100% its chip, as JGraphX draws it, keeps
     * the router's {@link #AIR} from every other line. "∃ concretizes" kept 0.3px of it.
     */
    @Test
    void aLabelIsDrawnWhereTheRouterPlacedIt() throws Exception {
        List<String> close = new ArrayList<String>();
        for (String board : new String[] {"T3", "T6"}) {
            SchemaGraph graph = routed(board);
            List<Object> edges = linesDrawn(graph);
            for (Object edge : edges) {
                double[] chip = chip(graph, edge);
                if (chip == null) {
                    continue;
                }
                for (Object other : edges) {
                    double gap = other == edge ? Double.MAX_VALUE : gap(line(graph, other), chip);
                    if (gap < AIR - 1e-6) {
                        close.add("after " + board + ": " + name(graph, edge) + " "
                                + Math.round(gap * 10) / 10.0 + "px from " + name(graph, other));
                    }
                }
            }
        }
        assertEquals(new ArrayList<String>(), close);
    }

    /**
     * A label's chip is the same on the board at every zoom, as a box is: the one the router made
     * room for at 100%, in the same place. JGraphX's own measure at 31% was 5px wider.
     */
    @Test
    void aLabelsChipIsTheSameOnTheBoardAtEveryZoom() throws Exception {
        SchemaGraph graph = routed("T6");
        List<Object> edges = linesDrawn(graph);
        Map<Object, double[]> at100 = new LinkedHashMap<Object, double[]>();
        for (Object edge : edges) {
            double[] chip = chip(graph, edge);
            if (chip != null) {
                at100.put(edge, chip);
            }
        }
        List<String> grown = new ArrayList<String>();
        for (double scale : zooms(FITTED)) {
            graph.getView().setScale(scale);
            graph.getView().validate();
            for (Map.Entry<Object, double[]> one : at100.entrySet()) {
                double[] chip = chip(graph, one.getKey());
                double[] was = one.getValue();
                for (int k = 0; k < 4; k++) {
                    if (Math.abs(chip[k] - was[k]) > 0.01) {
                        grown.add(Math.round(scale * 1000) / 10.0 + "%: "
                                + name(graph, one.getKey()) + " " + text(chip) + " where 100% has "
                                + text(was));
                        break;
                    }
                }
            }
        }
        assertEquals(new ArrayList<String>(), grown);
    }

    /** 10% to 100% a percent at a time, on to 200% ten at a time, and the zoom Fit gave. */
    private static List<Double> zooms(double fitted) {
        List<Double> zooms = new ArrayList<Double>();
        for (int z = 10; z <= 200; z += z < 100 ? 1 : 10) {
            zooms.add(z / 100.0);
        }
        zooms.add(fitted);
        return zooms;
    }

    /** What the probe counts against each label: the lines it lies on, the boxes and labels. */
    private static List<String> wrongLabels(SchemaGraph graph) {
        List<String> wrong = new ArrayList<String>();
        List<Object> edges = linesDrawn(graph);
        List<double[]> boxes = new ArrayList<double[]>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            String style = graph.getModel().getStyle(cell);
            if (graph.getModel().isVertex(cell) && graph.isCellVisible(cell)
                    && (style == null || !style.startsWith(SchemaStyles.FRAME))) {
                boxes.add(onTheBoard(graph, graph.getView().getState(cell)));
            }
        }
        List<double[]> chips = new ArrayList<double[]>();
        for (Object edge : edges) {
            double[] chip = chip(graph, edge);
            if (chip == null) {
                continue;
            }
            for (Object other : edges) {
                if (other != edge && BoardMeasures.crosses(line(graph, other), size(chip))) {
                    wrong.add(name(graph, edge) + " on [" + name(graph, other) + "]");
                }
            }
            for (double[] box : boxes) {
                if (overlap(size(chip), box)) {
                    wrong.add(name(graph, edge) + " on a box");
                }
            }
            for (double[] label : chips) {
                if (overlap(size(chip), size(label))) {
                    wrong.add(name(graph, edge) + " on a label");
                }
            }
            chips.add(chip);
        }
        return wrong;
    }

    /** The board after that task, every line routed. */
    private static SchemaGraph routed(String task) throws Exception {
        SchemaGraph graph = board("/fixture-egg-board-after-" + task + ".tsv");
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    /**
     * Reads a board: frames, notes and boxes where they stood, in the order the projection had
     * them - routes break ties by it - and the lines drawn.
     */
    private static SchemaGraph board(String resource) throws Exception {
        Map<String, String> prefixes = new LinkedHashMap<String, String>();
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        InputStream in = LabelsStayOffLinesAtEveryZoomTest.class.getResourceAsStream(resource);
        assertNotNull(in, resource);
        BufferedReader rows = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            for (String row = rows.readLine(); row != null; row = rows.readLine()) {
                String[] f = row.split("\t", -1);
                if (f[0].equals("prefix")) {
                    prefixes.put(f[1], f[2]);
                } else if (f[0].equals("frame")) {
                    CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
                    frame.id = f[1];
                    frame.x = Double.parseDouble(f[2]);
                    frame.y = Double.parseDouble(f[3]);
                    frame.w = Double.parseDouble(f[4]);
                    frame.h = Double.parseDouble(f[5]);
                    frame.label = f[6];
                    frame.pattern = f[7].isEmpty() ? null : f[7];
                    layout.frames.add(frame);
                } else if (f[0].equals("note")) {
                    CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
                    note.id = f[1];
                    note.x = Double.parseDouble(f[2]);
                    note.y = Double.parseDouble(f[3]);
                    note.w = Double.parseDouble(f[4]);
                    note.h = Double.parseDouble(f[5]);
                    note.text = f[6].replace("\\n", "\n");
                    layout.notes.add(note);
                } else if (f[0].equals("box")) {
                    String id = full(f[1], prefixes);
                    CanvasNode node = new CanvasNode(id, NodeKind.valueOf(f[2]),
                            f[8].replace("\\n", "\n"));
                    node = f[9].isEmpty() ? node : node.withCurie(f[9]);
                    nodes.add(f[3].equals("imported") ? node.asImported() : node);
                    CanvasLayout.NodeLayout at = new CanvasLayout.NodeLayout(
                            Double.parseDouble(f[4]), Double.parseDouble(f[5]));
                    at.w = Double.parseDouble(f[6]);
                    at.h = Double.parseDouble(f[7]);
                    layout.nodes.put(id, at);
                    layout.onCanvas.add(id);
                } else if (f[0].equals("line")) {
                    edges.add(new CanvasEdge(full(f[1], prefixes), full(f[3], prefixes),
                            full(f[4], prefixes), f[5].replace("\\n", "\n"),
                            CanvasEdge.Kind.valueOf(f[2])));
                }
            }
        } finally {
            rows.close();
        }
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    private static String full(String text, Map<String, String> prefixes) {
        String full = text;
        for (Map.Entry<String, String> prefix : prefixes.entrySet()) {
            full = full.replace(prefix.getKey(), prefix.getValue());
        }
        return full;
    }

    private static List<Object> linesDrawn(SchemaGraph graph) {
        List<Object> lines = new ArrayList<Object>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            if (graph.getModel().isEdge(cell) && graph.isCellVisible(cell)
                    && graph.getView().getState(cell) != null) {
                lines.add(cell);
            }
        }
        return lines;
    }

    /** The line as drawn, in board pixels. */
    private static double[][] line(SchemaGraph graph, Object edge) {
        double scale = graph.getView().getScale();
        mxPoint translate = graph.getView().getTranslate();
        List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
        double[][] line = new double[points.size()][];
        for (int i = 0; i < line.length; i++) {
            line[i] = new double[] {points.get(i).getX() / scale - translate.getX(),
                points.get(i).getY() / scale - translate.getY()};
        }
        return line;
    }

    /** The label's chip as drawn, x1, y1, x2, y2 in board pixels; null for a line without one. */
    private static double[] chip(SchemaGraph graph, Object edge) {
        String text = graph.getLabel(edge);
        mxRectangle bounds = graph.getView().getState(edge).getLabelBounds();
        if (text == null || text.trim().isEmpty() || bounds == null) {
            return null;
        }
        double[] r = onTheBoard(graph, bounds);
        return new double[] {r[0], r[1], r[0] + r[2], r[1] + r[3]};
    }

    private static double[] onTheBoard(SchemaGraph graph, mxRectangle bounds) {
        double scale = graph.getView().getScale();
        mxPoint translate = graph.getView().getTranslate();
        return new double[] {bounds.getX() / scale - translate.getX(),
            bounds.getY() / scale - translate.getY(), bounds.getWidth() / scale,
            bounds.getHeight() / scale};
    }

    private static double[] size(double[] chip) {
        return new double[] {chip[0], chip[1], chip[2] - chip[0], chip[3] - chip[1]};
    }

    private static boolean overlap(double[] a, double[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3]
                && b[1] < a[1] + a[3];
    }

    /** How far the line stays from the chip, x1, y1, x2, y2; 0 or less where it runs into it. */
    private static double gap(double[][] line, double[] chip) {
        double nearest = Double.MAX_VALUE;
        for (int i = 1; i < line.length; i++) {
            double x1 = Math.min(line[i - 1][0], line[i][0]);
            double x2 = Math.max(line[i - 1][0], line[i][0]);
            double y1 = Math.min(line[i - 1][1], line[i][1]);
            double y2 = Math.max(line[i - 1][1], line[i][1]);
            double dx = Math.max(chip[0] - x2, x1 - chip[2]);
            double dy = Math.max(chip[1] - y2, y1 - chip[3]);
            nearest = Math.min(nearest, dx > 0 && dy > 0 ? Math.hypot(dx, dy) : Math.max(dx, dy));
        }
        return nearest;
    }

    private static String name(SchemaGraph graph, Object edge) {
        String label = graph.getLabel(edge);
        String style = graph.getModel().getStyle(edge);
        boolean isA = style != null && style.startsWith(SchemaStyles.SUBCLASS);
        return (isA ? "is-a" : String.valueOf(label).replace('\n', ' ')) + " "
                + box(graph, graph.getModel().getTerminal(edge, true)) + " -> "
                + box(graph, graph.getModel().getTerminal(edge, false));
    }

    private static String box(SchemaGraph graph, Object cell) {
        return String.valueOf(graph.getModel().getValue(cell)).split("\n")[0];
    }

    private static String text(double[] chip) {
        return String.format("%.1f,%.1f-%.1f,%.1f", chip[0], chip[1], chip[2], chip[3]);
    }
}
