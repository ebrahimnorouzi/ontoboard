package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The arrangement as a setting (1.155.0): which one a board uses, which way a tree runs, how far
 * apart its boxes stand, and whether properties are rows or boxes - kept in the look.
 */
class ArrangementSettingsTest {

    private static final String U = "http://example.org/u#";

    /** Agent above Person, Robot and Company, each a subclass of it. */
    private static SchemaGraph family() {
        SchemaGraph graph = new SchemaGraph();
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        nodes.add(new CanvasNode(U + "Agent", NodeKind.CLASS, "Agent"));
        for (String child : new String[] {"Person", "Robot", "Company"}) {
            nodes.add(new CanvasNode(U + child, NodeKind.CLASS, child));
            edges.add(new CanvasEdge("sub|" + child, U + child, U + "Agent", "", CanvasEdge.Kind.SUBCLASS));
        }
        graph.render(new Projection(nodes, edges), new CanvasLayout());
        return graph;
    }

    private static mxGeometry at(SchemaGraph graph, String name) {
        return graph.getModel().getGeometry(graph.getCellForId(U + name));
    }

    @Test
    void aTreeRunsDownOrRightAsTheLookSays() {
        SchemaGraph down = family();
        SchemaGraph right = family();

        CanvasLayouts.apply(down, CanvasLayouts.Algorithm.HIERARCHICAL, BoardLook.defaults());
        CanvasLayouts.apply(right, CanvasLayouts.Algorithm.HIERARCHICAL,
                BoardLook.defaults().with(BoardLook.DIRECTION, "right"));

        assertTrue(at(down, "Agent").getY() + at(down, "Agent").getHeight() <= at(down, "Person").getY(),
                "down: the superclass above - " + at(down, "Agent") + " " + at(down, "Person"));
        assertTrue(at(right, "Agent").getX() + at(right, "Agent").getWidth() <= at(right, "Person").getX(),
                "right: the superclass on the left - " + at(right, "Agent") + " " + at(right, "Person"));
    }

    @Test
    void roomySpacingSpreadsTheBoxesAndCompactDrawsThemIn() {
        SchemaGraph compact = family();
        SchemaGraph normal = family();
        SchemaGraph roomy = family();

        CanvasLayouts.apply(compact, CanvasLayouts.Algorithm.HIERARCHICAL,
                BoardLook.defaults().with(BoardLook.SPACING, "compact"));
        CanvasLayouts.apply(normal, CanvasLayouts.Algorithm.HIERARCHICAL, BoardLook.defaults());
        CanvasLayouts.apply(roomy, CanvasLayouts.Algorithm.HIERARCHICAL,
                BoardLook.defaults().with(BoardLook.SPACING, "roomy"));

        assertTrue(rankGap(compact) < rankGap(normal) && rankGap(normal) < rankGap(roomy),
                rankGap(compact) + " " + rankGap(normal) + " " + rankGap(roomy));
    }

    /** From the bottom of the superclass to the top of its subclasses. */
    private static double rankGap(SchemaGraph graph) {
        return at(graph, "Person").getY() - (at(graph, "Agent").getY() + at(graph, "Agent").getHeight());
    }

    @Test
    void theDefaultArrangementIsTheBoardOf1154() {
        SchemaGraph before = family();
        SchemaGraph now = family();

        CanvasLayouts.apply(before, CanvasLayouts.Algorithm.HIERARCHICAL);
        CanvasLayouts.apply(now, CanvasLayouts.Algorithm.HIERARCHICAL, BoardLook.defaults());

        for (String name : new String[] {"Agent", "Person", "Robot", "Company"}) {
            assertEquals(at(before, name).toString(), at(now, name).toString(), name);
        }
    }

    @Test
    void theBoardKeysTakeOnlyWhatABoardKnows() {
        BoardLook look = BoardLook.defaults();

        assertEquals("SECTIONS", look.get(BoardLook.ARRANGEMENT));
        assertEquals("down", look.get(BoardLook.DIRECTION));
        assertEquals("normal", look.get(BoardLook.SPACING));
        assertEquals("rows", look.get(BoardLook.PROPERTIES));
        assertEquals("HIERARCHICAL", look.with(BoardLook.ARRANGEMENT, "HIERARCHICAL").get(BoardLook.ARRANGEMENT));
        assertEquals("boxes", look.with(BoardLook.PROPERTIES, "boxes").get(BoardLook.PROPERTIES));
        assertEquals("down", look.with(BoardLook.DIRECTION, "sideways").get(BoardLook.DIRECTION));
        assertEquals("SECTIONS", look.with(BoardLook.ARRANGEMENT, "SPIRAL").get(BoardLook.ARRANGEMENT));
        assertEquals("normal", look.with(BoardLook.SPACING, "vast").get(BoardLook.SPACING));
    }
}
