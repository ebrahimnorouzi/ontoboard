package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
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
 * A line a selection shows keeps off the names written at the top of the frames.
 *
 * <p>Found by a review of 1.135.1 on the egg-boiling study board: "denoted by" stands in the first
 * column of the Object properties section, and its dashed domain and range left it to the left and
 * ran straight up through the words "Object properties" - in 4 of the 79 single-term selections
 * there, through that title or Data properties'. The whole band at the top of a frame cost a line
 * little to cross, the same wherever the name was written.
 */
class RevealedLinesKeepOffTitlesTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final String ENTITY = OBO + "BFO_0000001";
    private static final String ICE = OBO + "IAO_0000030";
    private static final String DENOTED_BY = OBO + "IAO_0000235";

    /**
     * The study board's left side: a pattern's frame with entity and information content entity,
     * the dataset frame under it, and under that the section of object properties, "denoted by"
     * fourth in its first column, 55 px in from the section's side.
     */
    private static SchemaGraph board() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        Object[][] classes = {
            {ENTITY, "entity", 100, 140, 180, 60},
            {OBO + "BFO_0000002", "continuant", 140, 240, 180, 60},
            {OBO + "BFO_0000031", "generically dependent continuant", 180, 340, 240, 60},
            {ICE, "information content entity", 220, 440, 240, 60},
            {OBO + "IAO_0000027", "data item", 260, 540, 180, 60},
            {OBO + "BFO_0000003", "occurrent", 600, 140, 180, 60},
            {OBO + "BFO_0000015", "process", 640, 240, 180, 60},
            {"urn:egg#dataset", "dataset", 100, 1144, 180, 60},
            {"urn:egg#data_collection", "data collection", 140, 1244, 180, 60},
            {"urn:egg#data_entity", "data entity", 500, 1144, 180, 60}};
        for (Object[] cls : classes) {
            nodes.add(new CanvasNode((String) cls[0], NodeKind.CLASS, (String) cls[1]));
            place(layout, (String) cls[0], (Integer) cls[2], (Integer) cls[3], (Integer) cls[4],
                    (Integer) cls[5]);
        }
        String[][] subclasses = {{OBO + "BFO_0000002", ENTITY}, {OBO + "BFO_0000031", OBO + "BFO_0000002"},
            {ICE, OBO + "BFO_0000031"}, {OBO + "IAO_0000027", ICE}, {OBO + "BFO_0000003", ENTITY},
            {OBO + "BFO_0000015", OBO + "BFO_0000003"},
            {"urn:egg#data_collection", "urn:egg#dataset"}};
        for (String[] sub : subclasses) {
            edges.add(new CanvasEdge("sub|" + sub[0], sub[0], sub[1], "", CanvasEdge.Kind.SUBCLASS));
        }
        frame(layout, SchemaGraph.FRAME_ID_PREFIX + "measurement", "Measurement and setpoint", 60,
                60, 1000, 620);
        frame(layout, SchemaGraph.FRAME_ID_PREFIX + "dataset", "dataset", 60, 1080, 920, 260);
        frame(layout, CanvasLayouts.SECTION_PREFIX + "object-properties", "Object properties", 40,
                1400, 1200, 400);
        Object[][] properties = {
            {OBO + "BFO_0000051", "has part", 95, 1444}, {OBO + "IAO_0000136", "is about", 95, 1524},
            {OBO + "IAO_0000219", "denotes", 95, 1604}, {DENOTED_BY, "denoted by", 95, 1684},
            {OBO + "RO_0000053", "has characteristic", 375, 1444},
            {OBO + "OBI_0000293", "has specified input", 375, 1524}};
        for (Object[] property : properties) {
            nodes.add(new CanvasNode((String) property[0], NodeKind.OBJECT_PROPERTY,
                    (String) property[1]));
            place(layout, (String) property[0], (Integer) property[2], (Integer) property[3], 200,
                    60);
        }
        edges.add(new CanvasEdge("domain|" + DENOTED_BY + "|" + ENTITY, DENOTED_BY, ENTITY, "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("range|" + DENOTED_BY + "|" + ICE, DENOTED_BY, ICE, "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("domain|" + OBO + "IAO_0000136|" + ICE, OBO + "IAO_0000136", ICE,
                "domain", CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("range|" + OBO + "IAO_0000219|" + ENTITY, OBO + "IAO_0000219",
                ENTITY, "range", CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("domain|" + OBO + "OBI_0000293|" + OBO + "BFO_0000015",
                OBO + "OBI_0000293", OBO + "BFO_0000015", "domain", CanvasEdge.Kind.DOMAIN_RANGE));
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    private static void place(CanvasLayout layout, String id, double x, double y, double w,
            double h) {
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = w;
        node.h = h;
        layout.nodes.put(id, node);
    }

    private static void frame(CanvasLayout layout, String id, String label, double x, double y,
            double w, double h) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = id;
        frame.label = label;
        frame.x = x;
        frame.y = y;
        frame.w = w;
        frame.h = h;
        layout.frames.add(frame);
    }

    private static double[][] drawn(SchemaGraph graph, Object edge) {
        List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
        double[][] line = new double[points.size()][];
        for (int i = 0; i < line.length; i++) {
            line[i] = new double[] {points.get(i).getX(), points.get(i).getY()};
        }
        return line;
    }

    /** Every drawn line through the name of a frame, as the name is drawn. */
    private static List<String> titlesCrossed(SchemaGraph graph, boolean revealedOnly) {
        List<String> crossed = new ArrayList<String>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object frame = graph.getModel().getChildAt(parent, i);
            String style = graph.getModel().getStyle(frame);
            if (style == null || !style.startsWith(SchemaStyles.FRAME)) {
                continue;
            }
            mxRectangle title = graph.getView().getState(frame).getLabelBounds();
            double[] rect = {title.getX(), title.getY(), title.getWidth(), title.getHeight()};
            for (int k = 0; k < graph.getModel().getChildCount(parent); k++) {
                Object edge = graph.getModel().getChildAt(parent, k);
                String kind = String.valueOf(graph.getModel().getStyle(edge));
                if (!graph.getModel().isEdge(edge) || !graph.isCellVisible(edge)
                        || revealedOnly && !kind.startsWith(SchemaStyles.DOMAIN_RANGE)) {
                    continue;
                }
                mxCellState state = graph.getView().getState(edge);
                if (state != null && BoardMeasures.crosses(drawn(graph, edge), rect)) {
                    crossed.add(graph.getIdForCell(edge) + " through \""
                            + graph.getModel().getValue(frame) + "\"");
                }
            }
        }
        return crossed;
    }

    @Test
    void theLinesASelectedPropertyShowsGoRoundTheNameOfItsSection() {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.getView().validate();
        List<String> ownCrossings = titlesCrossed(graph, false);

        for (String term : new String[] {DENOTED_BY, ENTITY, ICE}) {
            graph.setFocus(Collections.singletonList(term));
            graph.getView().validate();
            for (Object edge : graph.getChildEdges(graph.getDefaultParent())) {
                if (String.valueOf(graph.getModel().getStyle(edge)).startsWith(SchemaStyles.DOMAIN_RANGE)) {
                    assertNotNull(graph.routeOf(edge), graph.getIdForCell(edge) + " routed");
                }
            }
            assertEquals(new ArrayList<String>(), titlesCrossed(graph, true),
                    "selecting " + graph.getModel().getValue(graph.getCellForId(term)));
            BoardMeasures measured = new BoardMeasures(term, graph);
            List<String> through = new ArrayList<String>();
            for (String one : measured.through) {
                if (one.startsWith("domain ") || one.startsWith("range ")) {
                    through.add(one);
                }
            }
            assertEquals(new ArrayList<String>(), through, "and round the boxes");
        }
        graph.setFocus(Collections.<String>emptyList());
        graph.getView().validate();
        assertTrue(titlesCrossed(graph, false).equals(ownCrossings),
                "the board's own lines as they were");
    }
}
