package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A line between two properties that stand apart - one beside its class, the other beside another
 * class or in its section - waits for a selection, as a range does; between two that stand
 * together, beside the same class or both in their section, it is drawn as before.
 *
 * <p>Found on the egg-boiling board (1.135.1): has role stood beside independent continuant in a
 * pattern's frame at the top and has characteristic, its super-property, with no domain class on
 * the board, in the Object properties frame at the bottom; their is-a line ran 1,000 to 1,900 px
 * down the board, the parent below its child. Keeping has role in the section with its
 * super-property instead sent pizza's has base and has topping back there as well, under has
 * component, which has no domain - what the maintainer had found on the pizza ontologies.
 */
class LinesBetweenPropertiesStandingApartWaitTest {

    private static final String NS = "http://example.org/apart#";
    private static final String WHOLE = NS + "whole";
    private static final String PART = NS + "part";
    private static final String HAS_PART = NS + "hasPart";
    private static final String DIRECT = NS + "hasDirectPart";
    private static final String PART_OF = NS + "partOf";
    private static final String RELATED = NS + "relatedTo";
    private static final String DOMAIN = "domain|" + HAS_PART + "|" + WHOLE;
    private static final String SUB = "subprop|" + HAS_PART + "|" + RELATED;

    private static void place(CanvasLayout layout, String id, double x, double y) {
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = 200;
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

    /** The Object properties frame, room for two boxes side by side at y 700. */
    private static CanvasLayout.FrameLayout objectProperties() {
        return frame(CanvasLayouts.SECTION_PREFIX + "object-properties", "Object properties",
                100, 640, 600, 160);
    }

    private static CanvasEdge domain(String property, String cls) {
        return new CanvasEdge("domain|" + property + "|" + cls, property, cls, "domain",
                CanvasEdge.Kind.DOMAIN_RANGE);
    }

    private static CanvasEdge subProperty(String sub, String sup) {
        return new CanvasEdge("subprop|" + sub + "|" + sup, sub, sup, "rdfs:subPropertyOf",
                CanvasEdge.Kind.SUB_PROPERTY);
    }

    private static SchemaGraph drawn(List<CanvasNode> nodes, List<CanvasEdge> edges,
            CanvasLayout layout) {
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    private static boolean drawnNow(SchemaGraph graph, String id) {
        return graph.isCellVisible(graph.getCellForId(id));
    }

    private static void select(SchemaGraph graph, String id) {
        graph.setFocus(Collections.singleton(id));
        graph.getView().validate();
    }

    /**
     * The egg board's case, and pizza's: has part beside whole, its domain line drawn; related to,
     * its super-property, with no domain, in the Object properties frame below.
     */
    @Test
    void anIsALineFromAPropertyBesideItsClassToOneInItsSectionWaitsForASelection() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, RELATED, 140, 700);
        layout.frames.add(objectProperties());
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(subProperty(HAS_PART, RELATED));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertTrue(drawnNow(graph, DOMAIN), "has part's domain line drawn beside whole");
        assertFalse(drawnNow(graph, SUB), "its is-a line to related to waits for a selection");
        assertTrue(graph.isFullyRouted(), "every line drawn has its route");

        select(graph, HAS_PART);
        assertTrue(drawnNow(graph, SUB), "has part selected: its is-a line shown");
        assertNotNull(graph.routeOf(graph.getCellForId(SUB)), "routed round the boxes");

        select(graph, RELATED);
        assertTrue(drawnNow(graph, SUB), "related to selected: shown too");

        graph.setFocus(Collections.<String>emptySet());
        graph.getView().validate();
        assertFalse(drawnNow(graph, SUB), "nothing selected: waiting again");
    }

    /** has direct part under has part, both beside whole: their line is short, and drawn. */
    @Test
    void anIsALineBetweenTwoPropertiesBesideTheSameClassIsDrawn() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(DIRECT, NodeKind.OBJECT_PROPERTY, "has direct part"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, DIRECT, 100 + 200 + PropertiesBeside.GAP, 100 + 40 + 16);
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(domain(DIRECT, WHOLE));
        edges.add(subProperty(DIRECT, HAS_PART));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertTrue(drawnNow(graph, "subprop|" + DIRECT + "|" + HAS_PART),
                "two properties beside the same class: their is-a line drawn");
        assertTrue(graph.isFullyRouted(), "every line drawn has its route");
    }

    /** Two properties with no domain, in their section as before: their line is drawn. */
    @Test
    void anIsALineBetweenTwoPropertiesInTheirSectionIsDrawn() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        place(layout, RELATED, 140, 700);
        place(layout, HAS_PART, 440, 700);
        layout.frames.add(objectProperties());
        edges.add(subProperty(HAS_PART, RELATED));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertTrue(drawnNow(graph, SUB), "both in their section: their is-a line drawn");
    }

    /**
     * has part beside whole, part of, its inverse, beside part: their line would cross from one
     * class to the other, as a range does, and waits as a range does.
     */
    @Test
    void anInverseLineBetweenPropertiesBesideDifferentClassesWaits() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(PART, NodeKind.CLASS, "part"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(PART_OF, NodeKind.OBJECT_PROPERTY, "part of"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, PART, 100, 400);
        place(layout, PART_OF, 100 + 200 + PropertiesBeside.GAP, 400);
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(domain(PART_OF, PART));
        String inverse = "inv|" + HAS_PART + "|" + PART_OF;
        edges.add(new CanvasEdge(inverse, HAS_PART, PART_OF, "", CanvasEdge.Kind.INVERSE));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertTrue(drawnNow(graph, DOMAIN), "has part's domain line drawn");
        assertTrue(drawnNow(graph, "domain|" + PART_OF + "|" + PART), "part of's drawn");
        assertFalse(drawnNow(graph, inverse), "their inverse line waits for a selection");

        select(graph, PART_OF);
        assertTrue(drawnNow(graph, inverse), "part of selected: the inverse line shown");
    }

    /**
     * A board saved by an earlier build, both properties in the Object properties frame: there
     * they stand together, and their line is drawn, until the board is arranged - then has part
     * stands beside whole, related to stays in its section, and the line between them waits.
     */
    @Test
    void onABoardSavedWithBothInTheirSectionTheLineIsDrawnUntilTheBoardIsArranged() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        place(layout, WHOLE, 100, 100);
        place(layout, RELATED, 140, 700);
        place(layout, HAS_PART, 440, 700);
        layout.frames.add(frame(SchemaGraph.FRAME_ID_PREFIX + "pattern", "Parts", 60, 60, 1000,
                420));
        layout.frames.add(objectProperties());
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(subProperty(HAS_PART, RELATED));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertFalse(drawnNow(graph, DOMAIN), "in its section, the domain waits");
        assertTrue(drawnNow(graph, SUB), "both in their section: their is-a line drawn");

        CanvasLayouts.applySections(graph, null, false);
        graph.routeEdges();
        graph.getView().validate();

        mxGeometry whole = graph.getModel().getGeometry(graph.getCellForId(WHOLE));
        mxGeometry hasPart = graph.getModel().getGeometry(graph.getCellForId(HAS_PART));
        assertEquals((whole.getX() + whole.getWidth() + PropertiesBeside.GAP) + ","
                + whole.getY(), hasPart.getX() + "," + hasPart.getY(), "has part beside whole");
        assertTrue(drawnNow(graph, DOMAIN), "its domain line drawn");
        assertFalse(drawnNow(graph, SUB), "apart now: their is-a line waits");
        assertTrue(graph.isFullyRouted(), "every line drawn has its route");
    }

    /**
     * A board with no frames - arranged by Hierarchical, Organic, Circle or Grid, which take the
     * sections away, or saved before there were sections - where has part stands far from whole:
     * it does not stand beside whole, so its domain line waits as any domain does, and its is-a
     * line is drawn as it always was. Found by a review of 1.135.1: with no frame on either end,
     * every property whose domain names one class counted as beside it, wherever it stood.
     */
    @Test
    void onABoardWithNoFramesAPropertyFarFromItsClassIsNotBesideIt() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100, 700);
        place(layout, RELATED, 400, 700);
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(subProperty(HAS_PART, RELATED));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertFalse(drawnNow(graph, DOMAIN), "far from whole, its domain line waits");
        assertTrue(drawnNow(graph, SUB), "its is-a line to related to drawn, as before");
    }

    /** has part beside whole, then moved away by hand: it no longer stands beside whole. */
    @Test
    void aPropertyMovedAwayFromItsClassNoLongerStandsBesideIt() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, RELATED, 140, 700);
        layout.frames.add(objectProperties());
        edges.add(domain(HAS_PART, WHOLE));
        edges.add(subProperty(HAS_PART, RELATED));
        SchemaGraph graph = drawn(nodes, edges, layout);
        assertTrue(drawnNow(graph, DOMAIN), "beside whole, its domain line drawn");

        Object hasPart = graph.getCellForId(HAS_PART);
        graph.getModel().beginUpdate();
        try {
            mxGeometry moved = (mxGeometry) graph.getModel().getGeometry(hasPart).clone();
            moved.setX(900);
            moved.setY(400);
            graph.getModel().setGeometry(hasPart, moved);
        } finally {
            graph.getModel().endUpdate();
        }
        graph.routeEdges();
        graph.getView().validate();

        assertFalse(drawnNow(graph, DOMAIN), "moved away, its domain line waits");
        assertTrue(drawnNow(graph, SUB), "and its is-a line is drawn, as for any two not beside");
    }

    /** Data properties follow the same rule: has weight beside whole, has measure in a section. */
    @Test
    void aDataPropertyBesideItsClassAndItsSuperPropertyInTheSectionFollowTheSameRule() {
        String weight = NS + "hasWeight";
        String measure = NS + "hasMeasure";
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(weight, NodeKind.DATA_PROPERTY, "has weight"));
        nodes.add(new CanvasNode(measure, NodeKind.DATA_PROPERTY, "has measure"));
        place(layout, WHOLE, 100, 100);
        place(layout, weight, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, measure, 140, 700);
        layout.frames.add(frame(CanvasLayouts.SECTION_PREFIX + "data-properties",
                "Data properties", 100, 640, 600, 160));
        edges.add(domain(weight, WHOLE));
        edges.add(subProperty(weight, measure));
        SchemaGraph graph = drawn(nodes, edges, layout);
        String line = "subprop|" + weight + "|" + measure;

        assertTrue(drawnNow(graph, "domain|" + weight + "|" + WHOLE), "its domain line drawn");
        assertFalse(drawnNow(graph, line), "its is-a line to has measure waits");
        select(graph, weight);
        assertTrue(drawnNow(graph, line), "has weight selected: shown");
    }

    /**
     * A chain's lines end at the chain's node, not at a property: drawn as they always were,
     * though the property its chain implies stands beside its class.
     */
    @Test
    void aChainsLinesAreDrawnAsBefore() {
        String chain = "chain-node";
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(WHOLE, NodeKind.CLASS, "whole"));
        nodes.add(new CanvasNode(HAS_PART, NodeKind.OBJECT_PROPERTY, "has part"));
        nodes.add(new CanvasNode(RELATED, NodeKind.OBJECT_PROPERTY, "related to"));
        nodes.add(new CanvasNode(chain, NodeKind.EXPRESSION, "related to o related to"));
        place(layout, WHOLE, 100, 100);
        place(layout, HAS_PART, 100 + 200 + PropertiesBeside.GAP, 100);
        place(layout, RELATED, 140, 700);
        place(layout, chain, 440, 700);
        layout.frames.add(objectProperties());
        edges.add(domain(HAS_PART, WHOLE));
        String toSuper = "chain|" + chain + "|" + HAS_PART;
        String fromLink = "chain|" + chain + "|0|" + RELATED;
        edges.add(new CanvasEdge(toSuper, chain, HAS_PART, "", CanvasEdge.Kind.SUB_PROPERTY));
        edges.add(new CanvasEdge(fromLink, RELATED, chain, "1", CanvasEdge.Kind.SUB_PROPERTY));
        SchemaGraph graph = drawn(nodes, edges, layout);

        assertTrue(drawnNow(graph, DOMAIN), "has part beside whole");
        assertTrue(drawnNow(graph, toSuper), "the chain's line to has part drawn");
        assertTrue(drawnNow(graph, fromLink), "and its line from related to");
    }
}
