package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void graphIsConfiguredForDirectManipulation() {
        SchemaGraph graph = new SchemaGraph();
        assertTrue(graph.isCellsMovable(), "nodes must be draggable");
        assertFalse(graph.isAllowDanglingEdges(), "an edge with one end is not an axiom");
        assertFalse(graph.isCellsDisconnectable(),
                "detaching an edge endpoint would silently orphan an axiom");
        assertFalse(graph.isCellsEditable(),
                "label editing arrives in Task 3; enabling it now would let a user rename "
                        + "a cell without touching the ontology");
        assertFalse(graph.isDropEnabled(), "drag-and-drop cell splitting is not part of this task");
        assertFalse(graph.isSplitEnabled(), "edge splitting is not part of this task");
    }

    /**
     * A tooltip still carries the full IRI, and now carries more.
     *
     * <p>This asserted equality with the IRI until 1.53.0, when the tooltip became a description -
     * label, kind, markers, IRI - because returning the id meant an edge hovered as
     * {@code rest|some|http://…} and a sticky note as {@code ontoboard-note-3f2a1b9c}. The
     * requirement the old assertion existed to protect is unchanged and still checked: the short
     * visible label must not be the only thing a user can get at. {@code CanvasTooltipsTest} covers
     * the wording itself.
     */
    @Test
    void tooltipCarriesTheFullIriAsWellAsTheShortLabel() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        String tooltip = graph.getToolTipForCell(graph.getCellForId(PERSON));

        assertTrue(tooltip.contains(PERSON),
                "tooltip must show the full IRI even though the visible label is short: " + tooltip);
        assertTrue(tooltip.contains("Class"), tooltip);
    }

    /** An edge hovers as the axiom it stands for, not as the internal id it is keyed by. */
    @Test
    void anEdgeTooltipIsNotItsInternalId() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        for (Object cell : graph.getChildCells(graph.getDefaultParent(), false, true)) {
            String id = graph.getIdForCell(cell);
            String tooltip = graph.getToolTipForCell(cell);
            assertFalse(tooltip.equals(id),
                    "an edge still hovers as its own id: " + tooltip);
            assertTrue(tooltip.contains("subClassOf"), tooltip);
        }
    }
    // ---------- markers ----------

    /**
     * An unsatisfiable class takes the namespace colour's channel. Which vocabulary a term came
     * from stops mattering the moment a reasoner says it can have no instances, and an error
     * visible only to somebody who knows which shade of blue to look for is not visible.
     */
    @Test
    void anUnsatisfiableClassIsDrawnInRedRatherThanItsNamespaceColour() {
        CanvasNode broken = new CanvasNode("http://example.org/o#Impossible", NodeKind.CLASS,
                "Impossible", false, true);
        CanvasNode ordinary = new CanvasNode("http://example.org/o#Fine", NodeKind.CLASS, "Fine");

        String brokenStyle = SchemaGraph.styleForTesting(broken);
        String ordinaryStyle = SchemaGraph.styleForTesting(ordinary);

        assertTrue(brokenStyle.contains(SchemaStyles.UNSATISFIABLE_STROKE), brokenStyle);
        assertFalse(ordinaryStyle.contains(SchemaStyles.UNSATISFIABLE_STROKE), ordinaryStyle);
    }

    @Test
    void aNotedTermGetsTheHeavierBorderAndKeepsItsNamespaceColour() {
        CanvasNode noted = new CanvasNode("http://example.org/o#Noted", NodeKind.CLASS,
                "Noted", true);

        String style = SchemaGraph.styleForTesting(noted);

        assertTrue(style.contains("strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH), style);
        assertFalse(style.contains(SchemaStyles.UNSATISFIABLE_STROKE), style);
    }

    /** Both at once is an ordinary state, and the error has to win the colour. */
    @Test
    void anUnsatisfiableTermThatAlsoHasANoteIsStillDrawnAsAnError() {
        CanvasNode both = new CanvasNode("http://example.org/o#Both", NodeKind.CLASS,
                "Both", true, true);

        String style = SchemaGraph.styleForTesting(both);

        assertTrue(style.contains(SchemaStyles.UNSATISFIABLE_STROKE), style);
        assertTrue(style.contains("strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH), style);
    }

    @Test
    void anOrdinaryTermGetsNeitherMarker() {
        String style = SchemaGraph.styleForTesting(
                new CanvasNode("http://example.org/o#Plain", NodeKind.CLASS, "Plain"));

        assertFalse(style.contains("strokeWidth="), style);
        assertFalse(style.contains(SchemaStyles.UNSATISFIABLE_STROKE), style);
    }

    // ---------- sticky notes and frames ----------

    /**
     * The canvas keys everything by id, and a note's id is generated rather than an IRI. Anything
     * that treats a cell as a term - selection, axiom removal, expanding neighbours - has to be
     * able to tell them apart, and asking the ontology would answer "not found" both for a note
     * and for a term somebody has just deleted.
     */
    @Test
    void notesAndFramesAreRecognisableByTheirIdAlone() {
        assertTrue(SchemaGraph.isAnnotationId(SchemaGraph.NOTE_ID_PREFIX + "abc123"));
        assertTrue(SchemaGraph.isAnnotationId(SchemaGraph.FRAME_ID_PREFIX + "abc123"));
    }

    @Test
    void anIriIsNotAnAnnotation() {
        assertFalse(SchemaGraph.isAnnotationId("http://example.org/o#Person"));
        assertFalse(SchemaGraph.isAnnotationId("http://purl.obolibrary.org/obo/IAO_0000116"));
        assertFalse(SchemaGraph.isAnnotationId(null));
        assertFalse(SchemaGraph.isAnnotationId(""));
    }

    /** The two prefixes must not be confusable with each other or with anything else. */
    @Test
    void theTwoPrefixesAreDistinct() {
        assertFalse(SchemaGraph.NOTE_ID_PREFIX.equals(SchemaGraph.FRAME_ID_PREFIX));
        assertFalse(SchemaGraph.NOTE_ID_PREFIX.startsWith(SchemaGraph.FRAME_ID_PREFIX));
        assertFalse(SchemaGraph.FRAME_ID_PREFIX.startsWith(SchemaGraph.NOTE_ID_PREFIX));
    }

}
