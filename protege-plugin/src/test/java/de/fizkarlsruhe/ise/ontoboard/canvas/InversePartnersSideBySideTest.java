package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Two properties that are each other's inverse stand side by side in the Outline, and the line
 * between them stays in their frame.
 *
 * <p>Found on the egg-boiling study board (1.135.1): the property trees were laid out in label
 * order with no regard to inverse pairs, so "has quality", in the has characteristic tree, and
 * "quality of", a tree of its own, stood three columns apart. Their dashed line left the Object
 * properties frame over its top border, ran above its title and came back in; "concretizes" and
 * "is concretized as" were joined by a loop along the frame's bottom.
 */
class InversePartnersSideBySideTest {

    private static final String OBO = "http://purl.obolibrary.org/obo/";
    private static final String PMD = "https://w3id.org/pmd/co/";

    /** The study board's object properties: IRI, label, prefixed name or null. */
    private static final String[][] OBJECT_PROPERTIES = {
        {OBO + "OBI_0000417", "achieves_planned_objective", "obo:OBI_0000417"},
        {OBO + "RO_0009006", "assay measures characteristic", "obo:RO_0009006"},
        {OBO + "RO_0000059", "concretizes", "obo:RO_0000059"},
        {OBO + "IAO_0000235", "denoted by", "obo:IAO_0000235"},
        {OBO + "RO_0000053", "has characteristic", "obo:RO_0000053"},
        {OBO + "RO_0000086", "has quality", "obo:RO_0000086"},
        {OBO + "RO_0000087", "has role", "obo:RO_0000087"},
        {OBO + "BFO_0000051", "has part", "obo:BFO_0000051"},
        {OBO + "IAO_0000039", "has measurement unit label", "obo:IAO_0000039"},
        {OBO + "OBI_0000293", "has specified input", "obo:OBI_0000293"},
        {OBO + "OBI_0000299", "has specified output", "obo:OBI_0000299"},
        {OBO + "OBI_0001938", "has value specification", "obo:OBI_0001938"},
        {OBO + "IAO_0000136", "is about", "obo:IAO_0000136"},
        {OBO + "IAO_0000221", "is quality measurement of", "obo:IAO_0000221"},
        {OBO + "OBI_0001927", "specifies value of", "obo:OBI_0001927"},
        {OBO + "RO_0000058", "is concretized as", "obo:RO_0000058"},
        {PMD + "PMD_0000004", "is subject of", null},
        {OBO + "RO_0000056", "participates in", "obo:RO_0000056"},
        {OBO + "IAO_0000419", "quality is specified as", "obo:IAO_0000419"},
        {OBO + "RO_0000080", "quality of", "obo:RO_0000080"},
        {OBO + "BFO_0000055", "realizes", "obo:BFO_0000055"},
        {PMD + "PMD_0000077", "specified by value", null},
    };

    private static final String[][] DATA_PROPERTIES = {
        {OBO + "OBI_0001937", "has specified numeric value", "obo:OBI_0001937"},
        {"https://nfdi.fiz-karlsruhe.de/ontology/NFDI_0001007", "has value", null},
    };

    /** Sub-property, super-property. */
    private static final String[][] SUB_PROPERTIES = {
        {OBO + "RO_0000086", OBO + "RO_0000053"}, {OBO + "RO_0000087", OBO + "RO_0000053"},
        {OBO + "IAO_0000039", OBO + "BFO_0000051"}, {OBO + "IAO_0000221", OBO + "IAO_0000136"},
        {OBO + "OBI_0001927", OBO + "IAO_0000136"},
    };

    /** The three inverse pairs, the first IRI the lesser, as the projection draws them. */
    private static final String[][] INVERSES = {
        {OBO + "RO_0000080", OBO + "RO_0000086"},
        {OBO + "IAO_0000136", PMD + "PMD_0000004"},
        {OBO + "RO_0000058", OBO + "RO_0000059"},
    };

    @Test
    void inversePartnersStandSideBySideAndTheirLineStaysInTheirFrame() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        for (String[] property : OBJECT_PROPERTIES) {
            nodes.add(node(property, NodeKind.OBJECT_PROPERTY));
        }
        for (String[] property : DATA_PROPERTIES) {
            nodes.add(node(property, NodeKind.DATA_PROPERTY));
        }
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        for (String[] sub : SUB_PROPERTIES) {
            edges.add(new CanvasEdge("subprop|" + sub[0] + "|" + sub[1], sub[0], sub[1],
                    "rdfs:subPropertyOf", CanvasEdge.Kind.SUB_PROPERTY));
        }
        for (String[] pair : INVERSES) {
            edges.add(new CanvasEdge("inv|" + pair[0] + "|" + pair[1], pair[0], pair[1], "",
                    CanvasEdge.Kind.INVERSE));
        }
        Projection projection = new Projection(nodes, edges);
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection, new CanvasLayout());

        // The Outline, its sections framed as the board frames them, and the lines routed.
        List<BoardSections.Section> sections = CanvasLayouts.applySections(graph, null, false);
        CanvasLayout framed = new CanvasLayout();
        for (CanvasNode node : nodes) {
            com.mxgraph.model.mxGeometry at = graph.getModel().getGeometry(
                    graph.getCellForId(node.getId()));
            CanvasLayout.NodeLayout placed = new CanvasLayout.NodeLayout(at.getX(), at.getY());
            placed.w = at.getWidth();
            placed.h = at.getHeight();
            framed.nodes.put(node.getId(), placed);
        }
        for (BoardSections.Section section : sections) {
            CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
            frame.id = CanvasLayouts.SECTION_PREFIX
                    + section.label.toLowerCase(Locale.ROOT).replace(' ', '-');
            frame.label = section.label;
            frame.x = section.x;
            frame.y = section.y;
            frame.w = section.w;
            frame.h = section.h;
            frame.stroke = "#B35C00";
            framed.frames.add(frame);
        }
        graph.render(projection, framed);
        graph.routeEdges();
        graph.getView().validate();
        BoardSections.Section objects = sections.get(0);
        assertEquals("Object properties", objects.label);

        List<String> wrong = new ArrayList<String>();
        for (String[] pair : INVERSES) {
            Object one = graph.getCellForId(pair[0]);
            Object other = graph.getCellForId(pair[1]);
            String names = name(graph, one) + " / " + name(graph, other);
            double[] a = rect(graph, one);
            double[] b = rect(graph, other);
            boolean oneRow = a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
            boolean oneColumn = a[0] < b[0] + b[2] && b[0] < a[0] + a[2];
            if (!oneRow && !oneColumn) {
                wrong.add(names + ": neither in one row nor in one column");
            }
            double[] between = between(a, b);
            for (Object box : graph.getChildVertices(graph.getDefaultParent())) {
                String style = graph.getModel().getStyle(box);
                if (box != one && box != other
                        && (style == null || !style.startsWith(SchemaStyles.FRAME))
                        && BoardMeasures.overlaps(between, rect(graph, box))) {
                    wrong.add(names + ": " + name(graph, box) + " stands between them");
                }
            }
            for (double[] point : points(graph, graph.getCellForId(
                    "inv|" + pair[0] + "|" + pair[1]))) {
                if (point[0] < objects.x - 1 || point[0] > objects.x + objects.w + 1
                        || point[1] < objects.y + FramedGroup.LABEL_BAND - 1
                        || point[1] > objects.y + objects.h + 1) {
                    wrong.add(names + ": their line runs out of the frame's inside at "
                            + Math.round(point[0]) + "," + Math.round(point[1]));
                    break;
                }
            }
        }
        assertEquals(new ArrayList<String>(), wrong);
    }

    private static CanvasNode node(String[] property, NodeKind kind) {
        CanvasNode node = new CanvasNode(property[0], kind, property[1]);
        return property[2] == null ? node : node.withCurie(property[2]);
    }

    /**
     * The space between two boxes - x, y, w, h: on each axis the stretch they share, or the gap
     * between them where they share none.
     */
    private static double[] between(double[] one, double[] other) {
        double[] gap = new double[4];
        for (int axis = 0; axis < 2; axis++) {
            double from = Math.max(one[axis], other[axis]);
            double to = Math.min(one[axis] + one[axis + 2], other[axis] + other[axis + 2]);
            gap[axis] = Math.min(from, to);
            gap[axis + 2] = Math.abs(to - from);
        }
        return gap;
    }

    private static double[] rect(SchemaGraph graph, Object cell) {
        mxCellState state = graph.getView().getState(cell);
        return new double[] {state.getX(), state.getY(), state.getWidth(), state.getHeight()};
    }

    private static String name(SchemaGraph graph, Object cell) {
        return String.valueOf(graph.getModel().getValue(cell)).split("\n")[0];
    }

    private static double[][] points(SchemaGraph graph, Object edge) {
        List<mxPoint> drawn = graph.getView().getState(edge).getAbsolutePoints();
        double[][] line = new double[drawn.size()][];
        for (int i = 0; i < line.length; i++) {
            line[i] = new double[] {drawn.get(i).getX(), drawn.get(i).getY()};
        }
        return line;
    }
}
