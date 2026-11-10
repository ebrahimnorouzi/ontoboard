package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The domain line of a property beside its class is drawn whatever is selected, routed with the
 * board's own lines; its range still waits for a selection, and so does every line of a property
 * standing in its section.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): "the properties that are not
 * connected, and they just stray in the canvas". Domains and ranges have waited for a selection on
 * every board since 1.135.1, so nothing joined a property to anything until it was clicked.
 */
class DomainLineBesideItsClassIsDrawnTest {

    private static final String NS = "http://example.org/lines#";
    private static final String DOMAIN = "domain|" + NS + "hasPart|" + NS + "whole";
    private static final String RANGE = "range|" + NS + "hasPart|" + NS + "part";

    private static void place(CanvasLayout layout, String id, double x, double y, double w) {
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = w;
        node.h = 40;
        layout.nodes.put(id, node);
    }

    private static CanvasLayout.FrameLayout frame(String id, String label, double x, double y,
            double w, double h) {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = id;
        frame.label = label;
        frame.x = x;
        frame.y = y;
        frame.w = w;
        frame.h = h;
        return frame;
    }

    /** whole, has part beside it - 200 + 80 right of its box - and part well below. */
    private static void besideItsClass(List<CanvasNode> nodes, List<CanvasEdge> edges,
            CanvasLayout layout) {
        nodes.add(new CanvasNode(NS + "whole", NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(NS + "part", NodeKind.CLASS, "part"));
        nodes.add(new CanvasNode(NS + "hasPart", NodeKind.OBJECT_PROPERTY, "has part"));
        place(layout, NS + "whole", 100, 100, 200);
        place(layout, NS + "hasPart", 100 + 200 + PropertiesBeside.GAP, 100, 200);
        place(layout, NS + "part", 100, 400, 200);
        edges.add(new CanvasEdge(DOMAIN, NS + "hasPart", NS + "whole", "domain",
                CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge(RANGE, NS + "hasPart", NS + "part", "range",
                CanvasEdge.Kind.DOMAIN_RANGE));
    }

    @Test
    void theDomainLineOfAPropertyBesideItsClassIsDrawnAndRoutedWithNothingSelected() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        besideItsClass(nodes, edges, layout);
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);

        graph.routeEdges();
        graph.getView().validate();

        Object domain = graph.getCellForId(DOMAIN);
        assertTrue(graph.isCellVisible(domain), "the domain line drawn with nothing selected");
        assertNotNull(graph.routeOf(domain), "routed with the board's own lines");
        assertTrue(graph.isFullyRouted(), "every line drawn has its route");
        assertFalse(graph.isCellVisible(graph.getCellForId(RANGE)), "the range still waits");
        assertEquals(new ArrayList<String>(), new BoardMeasures("beside", graph).through,
                "lines through boxes");
    }

    /**
     * A board saved with the property in its section keeps it there, both its lines waiting, until
     * it is arranged: then the property stands beside its class, in the class's frame, and its
     * domain line is drawn.
     */
    @Test
    void aPropertyInItsSectionWaitsUntilTheBoardIsArrangedThenItsDomainIsDrawn() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        besideItsClass(nodes, edges, layout);
        place(layout, NS + "hasPart", 140, 700, 200);
        layout.frames.add(frame(SchemaGraph.FRAME_ID_PREFIX + "pattern", "Parts", 60, 60, 1000,
                420));
        layout.frames.add(frame(CanvasLayouts.SECTION_PREFIX + "object-properties",
                "Object properties", 100, 640, 300, 160));
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        graph.routeEdges();
        graph.getView().validate();
        assertFalse(graph.isCellVisible(graph.getCellForId(DOMAIN)),
                "in its section, the domain waits for a selection");

        CanvasLayouts.applySections(graph, null, false);
        graph.routeEdges();
        graph.getView().validate();

        mxGeometry whole = graph.getModel().getGeometry(graph.getCellForId(NS + "whole"));
        mxGeometry hasPart = graph.getModel().getGeometry(graph.getCellForId(NS + "hasPart"));
        assertEquals((whole.getX() + whole.getWidth() + PropertiesBeside.GAP) + ","
                + whole.getY(), hasPart.getX() + "," + hasPart.getY(), "has part beside whole");
        assertTrue(graph.isCellVisible(graph.getCellForId(DOMAIN)), "its domain line drawn");
        assertFalse(graph.isCellVisible(graph.getCellForId(RANGE)), "its range still waits");
    }

    /** whole with has part beside it, and twenty classes {@code relations} relations join. */
    private static SchemaGraph withRelations(int relations) {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        besideItsClass(nodes, edges, layout);
        for (int c = 0; c < 20; c++) {
            nodes.add(new CanvasNode(NS + "c" + c, NodeKind.CLASS, "class " + c));
            place(layout, NS + "c" + c, 100 + (c % 5) * 300, 600 + (c / 5) * 120, 200);
        }
        for (int r = 0; r < relations; r++) {
            int from = r % 20;
            int to = (from + 1 + r / 20) % 20;
            edges.add(new CanvasEdge("rel|" + r, NS + "c" + from, NS + "c" + to, "∃ relates " + r,
                    CanvasEdge.Kind.OBJECT_PROPERTY));
        }
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    /**
     * On a board of more than 150 lines a domain line drawn always is a relation like the others:
     * the plain connector, drawn faintly until one of its ends is selected.
     */
    @Test
    void onADenseBoardADomainLineDrawnAlwaysIsARelation() {
        SchemaGraph graph = withRelations(151);

        assertTrue(graph.isDense(), "151 relations: dense");
        Object domain = graph.getCellForId(DOMAIN);
        assertTrue(graph.isCellVisible(domain), "the domain line drawn");
        assertNull(graph.routeOf(graph.getCellForId("rel|0")), "a relation keeps the connector");
        assertNull(graph.routeOf(domain), "and so does the domain line");
        assertEquals(SchemaGraph.QUIET, ((Number) graph.getCellStyle(domain)
                .get(com.mxgraph.util.mxConstants.STYLE_OPACITY)).intValue(),
                "drawn faintly, as the relations are");
    }

    /**
     * The board's count of lines is what it was: 150 relations and a domain line drawn always
     * beside its class are not a dense board. Counted, they tipped pizza-bfo - 145 lines and 18
     * properties beside their class - over 150, and every relation was drawn straight.
     */
    @Test
    void domainLinesDrawnAlwaysDoNotMakeABoardDense() {
        SchemaGraph graph = withRelations(150);

        assertFalse(graph.isDense(), "150 relations and a domain line beside its class");
        assertNotNull(graph.routeOf(graph.getCellForId(DOMAIN)), "the domain line routed");
    }
}
