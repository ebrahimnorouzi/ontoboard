package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxCell;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanvasLayoutsTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private static final String NOTE_ID = SchemaGraph.NOTE_ID_PREFIX + "1";
    private static final String FRAME_ID = SchemaGraph.FRAME_ID_PREFIX + "1";

    /** Two overlapping terms plus one note and one frame, all at known positions. */
    private static SchemaGraph graphWithAnnotations() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(AGENT, new CanvasLayout.NodeLayout(0, 0));

        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = NOTE_ID;
        note.text = "check this with Bob";
        note.x = 900;
        note.y = 40;
        layout.notes.add(note);

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = FRAME_ID;
        frame.label = "Toppings";
        frame.x = 500;
        frame.y = 500;
        frame.w = 340;
        frame.h = 240;
        layout.frames.add(frame);

        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS))),
                layout);
        return graph;
    }

    private static SchemaGraph stackedGraph() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        // Deliberately overlap both nodes so any layout must move at least one.
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(AGENT, new CanvasLayout.NodeLayout(0, 0));
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS))),
                layout);
        return graph;
    }

    /**
     * A layout arranges terms and leaves annotations alone.
     *
     * <p>A frame is a rectangle drawn round a group of classes. Laid out as if it were a term it
     * lands in a cell of its own and encloses nothing, which destroys the only thing it is for; a
     * note pinned beside the class it comments on ends up in a row among the classes. Both happened
     * for every algorithm, because the library layouts are handed the default parent and
     * {@code applyGrid} walked all of its children.
     */
    @Test
    void noAlgorithmMovesAStickyNoteOrAFrame() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph graph = graphWithAnnotations();
            mxCell note = (mxCell) graph.getCellForId(NOTE_ID);
            mxCell frame = (mxCell) graph.getCellForId(FRAME_ID);
            double noteX = note.getGeometry().getX();
            double noteY = note.getGeometry().getY();
            double frameW = frame.getGeometry().getWidth();

            CanvasLayouts.apply(graph, algorithm);

            assertEquals(noteX, note.getGeometry().getX(), 0.001,
                    algorithm + " moved a sticky note");
            assertEquals(noteY, note.getGeometry().getY(), 0.001,
                    algorithm + " moved a sticky note");
            assertEquals(frameW, frame.getGeometry().getWidth(), 0.001,
                    algorithm + " resized a frame");
        }
    }

    /** And it still arranges the terms, or the exclusion has thrown out the point. */
    @Test
    void theTermsAreStillArrangedWhenAnnotationsArePresent() {
        SchemaGraph graph = graphWithAnnotations();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        mxCell agent = (mxCell) graph.getCellForId(AGENT);
        assertNotEquals(person.getGeometry().getY(), agent.getGeometry().getY(),
                "the terms must still be laid out with a note and a frame on the board");
    }

    @Test
    void everyAlgorithmHasAHumanReadableName() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            assertTrue(algorithm.getDisplayName().length() > 0);
        }
    }

    @Test
    void hierarchicalLayoutSeparatesOverlappingNodes() {
        SchemaGraph graph = stackedGraph();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        mxCell agent = (mxCell) graph.getCellForId(AGENT);
        assertNotEquals(person.getGeometry().getY(), agent.getGeometry().getY(),
                "a hierarchical layout must place a subclass below its superclass");
    }

    @Test
    void gridLayoutKeepsEveryNodeAtNonNegativeCoordinates() {
        SchemaGraph graph = stackedGraph();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.GRID);

        for (String id : new String[] {PERSON, AGENT}) {
            mxCell cell = (mxCell) graph.getCellForId(id);
            assertTrue(cell.getGeometry().getX() >= 0);
            assertTrue(cell.getGeometry().getY() >= 0);
        }
    }

    /**
     * {@code stackedGraph()} only has 2 nodes, which with {@code GRID_COLUMNS = 4} never
     * wraps and already sits at non-negative coordinates before the layout even runs - so
     * {@link #gridLayoutKeepsEveryNodeAtNonNegativeCoordinates} alone cannot tell a real
     * grid arrangement from a no-op. This test uses more nodes than a single row holds so
     * wrapping is unavoidable, and checks the two properties a hand-rolled wrapping
     * layout can actually get wrong: that a second row is produced at all, that positions
     * within one row are distinct and increase left-to-right, and that re-running the
     * same layout on the same graph is deterministic rather than reshuffling on every
     * click.
     */
    private static SchemaGraph sixNodeGraph() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        List<CanvasNode> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = "http://example.org/tiny#N" + i;
            layout.nodes.put(id, new CanvasLayout.NodeLayout(0, 0));
            nodes.add(new CanvasNode(id, NodeKind.CLASS, "N" + i));
        }
        graph.render(new Projection(nodes, Collections.<CanvasEdge>emptyList()), layout);
        return graph;
    }

    @Test
    void gridLayoutWrapsToASecondRowAndIsDeterministic() {
        SchemaGraph graph = sixNodeGraph();

        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.GRID);

        double[] xs = new double[6];
        double[] ys = new double[6];
        for (int i = 0; i < 6; i++) {
            mxCell cell = (mxCell) graph.getCellForId("http://example.org/tiny#N" + i);
            xs[i] = cell.getGeometry().getX();
            ys[i] = cell.getGeometry().getY();
        }

        boolean anyRowBeyondTheFirst = false;
        for (double y : ys) {
            if (y > 0) {
                anyRowBeyondTheFirst = true;
                break;
            }
        }
        assertTrue(anyRowBeyondTheFirst,
                "6 nodes must wrap to a second row under a fixed, smaller column count");

        for (int i = 1; i < xs.length; i++) {
            if (ys[i] == ys[i - 1]) {
                assertTrue(xs[i] > xs[i - 1],
                        "nodes within the same row must have distinct, increasing x");
            }
        }

        // Determinism: applying GRID again to the same graph must reproduce the same
        // coordinates, not shuffle them - a real concern for a hand-written layout.
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.GRID);
        for (int i = 0; i < 6; i++) {
            mxCell cell = (mxCell) graph.getCellForId("http://example.org/tiny#N" + i);
            assertEquals(xs[i], cell.getGeometry().getX(), 0.0001,
                    "GRID must be deterministic across repeat application");
            assertEquals(ys[i], cell.getGeometry().getY(), 0.0001,
                    "GRID must be deterministic across repeat application");
        }
    }

    @Test
    void everyAlgorithmRunsWithoutThrowingOnAnEmptyGraph() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            CanvasLayouts.apply(new SchemaGraph(), algorithm);
        }
        assertEquals(4, CanvasLayouts.Algorithm.values().length);
    }
}
