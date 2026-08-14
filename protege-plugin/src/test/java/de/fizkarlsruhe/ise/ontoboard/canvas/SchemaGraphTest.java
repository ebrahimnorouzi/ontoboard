package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mxgraph.model.mxCell;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class SchemaGraphTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private static Projection twoClassesWithSubClassEdge() {
        return new Projection(
                Arrays.asList(
                        new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                        new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS)));
    }

    @Test
    void rendersOneCellPerNodeAndEdge() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        assertNotNull(graph.getCellForId(PERSON));
        assertNotNull(graph.getCellForId(AGENT));
        assertNotNull(graph.getCellForId("sub|1"));
        assertEquals("Person", ((mxCell) graph.getCellForId(PERSON)).getValue());
    }

    @Test
    void placesNodesAtTheirStoredLayoutPosition() {
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(310, 190));

        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), layout);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        assertEquals(310.0, person.getGeometry().getX());
        assertEquals(190.0, person.getGeometry().getY());
    }

    @Test
    void mapsCellsBackToTheirEntityIri() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        assertEquals(PERSON, graph.getIdForCell(graph.getCellForId(PERSON)));
        assertNull(graph.getIdForCell(null));
    }

    @Test
    void reRenderingReplacesRatherThanAccumulatesCells() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        Object[] children = com.mxgraph.model.mxGraphModel
                .getChildren(graph.getModel(), graph.getDefaultParent());
        assertEquals(3, children.length, "expected 2 vertices + 1 edge, not duplicates");
    }
}
