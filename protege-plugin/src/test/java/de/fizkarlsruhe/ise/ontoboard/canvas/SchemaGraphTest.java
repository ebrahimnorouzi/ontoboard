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
        // Editable since 1.66.0, and the refusal this used to express now lives in the predicate
        // rather than in the flag - see onlyNotesAndFramesCanBeEditedInPlace. The concern was never
        // sticky notes: it was that renaming a TERM in place would change what the board says
        // without changing the ontology.
        assertTrue(graph.isCellsEditable(), "a sticky note is retyped by double-clicking it");
        assertFalse(graph.isCellsCloneable(),
                "mxCell.clone copies the id, so a Ctrl+drag would forge a second node for one IRI");
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
                "Impossible", null, true);
        CanvasNode ordinary = new CanvasNode("http://example.org/o#Fine", NodeKind.CLASS, "Fine");

        String brokenStyle = SchemaGraph.styleForTesting(broken);
        String ordinaryStyle = SchemaGraph.styleForTesting(ordinary);

        assertTrue(brokenStyle.contains(SchemaStyles.UNSATISFIABLE_STROKE), brokenStyle);
        assertFalse(ordinaryStyle.contains(SchemaStyles.UNSATISFIABLE_STROKE), ordinaryStyle);
    }

    /**
     * An imported term is drawn faded, and keeps everything else it was saying.
     *
     * <p>Opacity is the only free channel on a node: the shape says what kind of thing it is, the
     * stroke colour says which namespace, the border weight says there is a note, and a dash would read
     * as "inferred". A fade that took one of those with it would trade one piece of information for
     * another.
     */
    @Test
    void anImportedTermIsDrawnFaded() {
        CanvasNode imported = new CanvasNode("http://purl.obolibrary.org/obo/BFO_0000002",
                NodeKind.CLASS, "continuant").asImported();

        String style = SchemaGraph.styleForTesting(imported);

        assertTrue(style.contains("opacity=" + SchemaStyles.IMPORTED_OPACITY), style);
        assertFalse(style.contains("dashed"), "a dash on a node would read as inferred: " + style);
    }

    /** Imported and noted at once is an ordinary state, and both marks have to survive. */
    @Test
    void anImportedTermWithANoteKeepsBothMarks() {
        CanvasNode both = new CanvasNode("http://purl.obolibrary.org/obo/BFO_0000002",
                NodeKind.CLASS, "continuant",
                java.util.Collections.singletonList("we use this as our root")).asImported();

        String style = SchemaGraph.styleForTesting(both);

        assertTrue(style.contains("opacity=" + SchemaStyles.IMPORTED_OPACITY), style);
        assertTrue(style.contains("strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH), style);
    }

    @Test
    void aLocalTermIsNotFaded() {
        String style = SchemaGraph.styleForTesting(
                new CanvasNode("http://example.org/o#Mine", NodeKind.CLASS, "Mine"));

        assertFalse(style.contains("opacity"), style);
    }

    /**
     * Only board furniture can be renamed by double-clicking it.
     *
     * <p>The predicate is the whole of the safety here. {@code setCellsEditable} was false outright,
     * which refused the ordinary gesture of retyping a sticky note in order to prevent a much worse
     * one: a term's label is its {@code rdfs:label}, so letting a double click rewrite it would let
     * somebody change what the board displays without changing the ontology - the canvas showing one
     * thing and the file saying another, which is the single lie this projection must never tell.
     */
    @Test
    void onlyNotesAndFramesCanBeEditedInPlace() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        String term = "http://example.org/o#Pizza";
        layout.nodes.put(term, new CanvasLayout.NodeLayout(0, 0));

        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = SchemaGraph.NOTE_ID_PREFIX + "1";
        note.text = "check this";
        layout.notes.add(note);

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = SchemaGraph.FRAME_ID_PREFIX + "1";
        frame.label = "Toppings";
        frame.w = 200;
        frame.h = 120;
        layout.frames.add(frame);

        graph.render(new Projection(
                java.util.Collections.singletonList(
                        new CanvasNode(term, NodeKind.CLASS, "Pizza")),
                java.util.Collections.<CanvasEdge>emptyList()), layout);

        assertTrue(graph.isCellEditable(graph.getCellForId(note.id)), "a sticky note");
        assertTrue(graph.isCellEditable(graph.getCellForId(frame.id)), "a frame");
        assertFalse(graph.isCellEditable(graph.getCellForId(term)),
                "a term's label is its rdfs:label and is not editable by double click");
        assertFalse(graph.isCellEditable(null), "null");
    }

    /** An edge is an axiom too, and its label is the property it stands for. */
    @Test
    void anEdgeCannotBeEditedInPlace() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        String a = "http://example.org/o#A";
        String b = "http://example.org/o#B";
        layout.nodes.put(a, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(b, new CanvasLayout.NodeLayout(0, 200));
        graph.render(new Projection(
                java.util.Arrays.asList(new CanvasNode(a, NodeKind.CLASS, "A"),
                        new CanvasNode(b, NodeKind.CLASS, "B")),
                java.util.Collections.singletonList(
                        new CanvasEdge("sub|1", a, b, "", CanvasEdge.Kind.SUBCLASS))), layout);

        assertFalse(graph.isCellEditable(graph.getCellForId("sub|1")));
    }

    /** A Ctrl+drag must not be able to forge a second cell claiming to be the same term. */
    @Test
    void cellsCannotBeCloned() {
        assertFalse(new SchemaGraph().isCellsCloneable(),
                "mxCell.clone copies the id, so a clone would be a second node with one IRI");
    }

    @Test
    void aNotedTermGetsTheHeavierBorderAndKeepsItsNamespaceColour() {
        CanvasNode noted = new CanvasNode("http://example.org/o#Noted", NodeKind.CLASS,
                "Noted", java.util.Collections.singletonList("the parent is provisional"));

        String style = SchemaGraph.styleForTesting(noted);

        assertTrue(style.contains("strokeWidth=" + SchemaStyles.NOTED_STROKE_WIDTH), style);
        assertFalse(style.contains(SchemaStyles.UNSATISFIABLE_STROKE), style);
    }

    /** Both at once is an ordinary state, and the error has to win the colour. */
    @Test
    void anUnsatisfiableTermThatAlsoHasANoteIsStillDrawnAsAnError() {
        CanvasNode both = new CanvasNode("http://example.org/o#Both", NodeKind.CLASS,
                "Both", java.util.Collections.singletonList("needs review"), true);

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
