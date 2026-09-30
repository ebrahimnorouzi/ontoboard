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

    /**
     * The same two terms, started apart, optionally with a note and a frame.
     *
     * <p>Apart on purpose. {@code mxFastOrganicLayout} is deterministic - three runs of the same
     * separated board give identical geometry - <em>except</em> when two nodes occupy exactly the
     * same point, where it breaks the tie with a random displacement: three runs of a board with
     * both terms at (0,0) gave (-1,-1), (139,-1) and (-1,139). Measured, not assumed. Any test that
     * compares one ORGANIC result against another has to start from a board where that tie cannot
     * arise, or it is a coin toss wearing an assertion.
     */
    private static SchemaGraph separatedTerms(boolean withAnnotations) {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(AGENT, new CanvasLayout.NodeLayout(300, 200));

        if (withAnnotations) {
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
        }

        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS))),
                layout);
        return graph;
    }

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
     * A layout arranges terms and never lays out an annotation.
     *
     * <p>A frame is a rectangle drawn round a group of classes. Laid out as if it were a term it
     * lands in a cell of its own and encloses nothing, which destroys the only thing it is for; a
     * note pinned beside the class it comments on ends up in a row among the classes. Both happened
     * for every algorithm, because the library layouts are handed the default parent and
     * {@code applyGrid} walked all of its children.
     *
     * <p>This asserted absolute positions until 1.64.0, when {@code normalise} started moving the
     * whole board - terms, notes and frames together - so that a hierarchy running upward from zero
     * is on screen rather than above it. An absolute assertion would now be asserting that the board
     * is never brought into view.
     *
     * <p>So it asserts what is actually true and actually matters: annotations are moved as one rigid
     * piece with everything else, never resized, and never given a slot of their own. Their mutual
     * offsets are the observable form of "rigid". Deliberately <em>not</em> asserted: that a note
     * keeps its offset from a particular term. A layout rearranges the terms, so no term-relative
     * offset can survive one, and a test claiming otherwise would be asserting something false.
     */
    @Test
    void annotationsAreMovedAsOnePieceAndNeverLaidOut() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph graph = graphWithAnnotations();
            mxCell note = (mxCell) graph.getCellForId(NOTE_ID);
            mxCell frame = (mxCell) graph.getCellForId(FRAME_ID);
            double offsetX = note.getGeometry().getX() - frame.getGeometry().getX();
            double offsetY = note.getGeometry().getY() - frame.getGeometry().getY();
            double frameW = frame.getGeometry().getWidth();
            double frameH = frame.getGeometry().getHeight();
            double noteW = note.getGeometry().getWidth();

            CanvasLayouts.apply(graph, algorithm);

            assertEquals(offsetX, note.getGeometry().getX() - frame.getGeometry().getX(), 0.001,
                    algorithm + " moved a note and a frame apart from each other");
            assertEquals(offsetY, note.getGeometry().getY() - frame.getGeometry().getY(), 0.001,
                    algorithm + " moved a note and a frame apart from each other");
            assertEquals(frameW, frame.getGeometry().getWidth(), 0.001,
                    algorithm + " resized a frame");
            assertEquals(frameH, frame.getGeometry().getHeight(), 0.001,
                    algorithm + " resized a frame");
            assertEquals(noteW, note.getGeometry().getWidth(), 0.001,
                    algorithm + " resized a sticky note");
        }
    }

    /**
     * And annotations do not influence where the terms go.
     *
     * <p>The other half of "not laid out": a note must not take a slot, push a rank wider, or change
     * a force-directed result by being one more body in the simulation. Laying the same two terms out
     * twice - once on a board carrying a note and a frame at awkward coordinates, once on a board
     * with neither - has to put the terms in the same places.
     */
    @Test
    void annotationsDoNotInfluenceWhereTheTermsGo() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph withAnnotations = separatedTerms(true);
            SchemaGraph without = separatedTerms(false);

            CanvasLayouts.apply(withAnnotations, algorithm);
            CanvasLayouts.apply(without, algorithm);

            // The offset BETWEEN the terms, not their absolute coordinates. The two boards are
            // normalised against different contents - a note at (900,40) legitimately shifts the
            // whole board, annotations included - so comparing absolute positions would be
            // comparing the normalisation rather than the arrangement.
            mxCell personHere = (mxCell) withAnnotations.getCellForId(PERSON);
            mxCell agentHere = (mxCell) withAnnotations.getCellForId(AGENT);
            mxCell personThere = (mxCell) without.getCellForId(PERSON);
            mxCell agentThere = (mxCell) without.getCellForId(AGENT);

            assertEquals(personThere.getGeometry().getX() - agentThere.getGeometry().getX(),
                    personHere.getGeometry().getX() - agentHere.getGeometry().getX(), 0.001,
                    algorithm + " arranged the terms differently because a note was on the board");
            assertEquals(personThere.getGeometry().getY() - agentThere.getGeometry().getY(),
                    personHere.getGeometry().getY() - agentHere.getGeometry().getY(), 0.001,
                    algorithm + " arranged the terms differently because a note was on the board");
        }
    }

    /**
     * Every algorithm leaves the board on screen.
     *
     * <p>The hierarchy runs upward from zero, so before 1.64.0 a corrected orientation would have put
     * every term at a negative y - and {@code mxGraphComponent}'s viewport starts at the origin, so
     * the diagram would simply have been above the canvas with nothing to scroll to. This pins the
     * normalise step that prevents it.
     */
    @Test
    void everyAlgorithmLeavesTheBoardOnScreen() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph graph = graphWithAnnotations();

            CanvasLayouts.apply(graph, algorithm);

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
                com.mxgraph.model.mxGeometry geometry = graph.getModel().getGeometry(vertex);
                minX = Math.min(minX, geometry.getX());
                minY = Math.min(minY, geometry.getY());
            }
            assertEquals(40, minX, 0.001, algorithm + " left the board off the left edge");
            assertEquals(40, minY, 0.001, algorithm + " left the board above the top edge");
        }
    }

    /**
     * A term with no axioms goes under the diagram, not off to its right.
     *
     * <p>{@code mxHierarchicalLayout.findRoots} only accepts a vertex with {@code fanIn == 0} and
     * {@code fanOut > 0}, so an isolated term is not a root and is not reachable from one: it became
     * a hierarchy of its own, laid out to the right of everything else. On the representative board
     * the diagram ended at x=824 and four loose terms sat between x=1465 and x=2288 with nothing in
     * between - which reads as an empty canvas with some debris at the edge.
     */
    @Test
    void aTermWithNoAxiomsIsParkedUnderTheDiagram() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        String loose = "http://example.org/tiny#Loose";
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(AGENT, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(loose, new CanvasLayout.NodeLayout(0, 0));
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent"),
                new CanvasNode(loose, NodeKind.CLASS, "Loose")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS))),
                layout);

        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        mxCell agent = (mxCell) graph.getCellForId(AGENT);
        mxCell orphan = (mxCell) graph.getCellForId(loose);
        double lowest = Math.max(person.getGeometry().getY() + person.getGeometry().getHeight(),
                agent.getGeometry().getY() + agent.getGeometry().getHeight());
        double rightmost = Math.max(person.getGeometry().getX() + person.getGeometry().getWidth(),
                agent.getGeometry().getX() + agent.getGeometry().getWidth());

        assertTrue(orphan.getGeometry().getY() >= lowest,
                "a loose term belongs under the diagram, not inside it: " + orphan.getGeometry());
        assertTrue(orphan.getGeometry().getX() < rightmost + 200,
                "a loose term was exiled to the right again: " + orphan.getGeometry());
    }

    /** A board of nothing but loose terms is still a board, and still on screen. */
    @Test
    void aBoardOfNothingButLooseTermsIsLaidOutToo() {
        SchemaGraph graph = sixNodeGraph();

        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        for (int i = 0; i < 6; i++) {
            mxCell cell = (mxCell) graph.getCellForId("http://example.org/tiny#N" + i);
            assertTrue(cell.getGeometry().getX() >= 40 && cell.getGeometry().getY() >= 40,
                    "N" + i + " is off screen at " + cell.getGeometry());
        }
    }

    // ---------- the arithmetic, tested at the sizes that matter ----------

    /**
     * How wide the block of loose terms gets.
     *
     * <p>Two bounds and the tighter wins: no wider than the diagram it sits under, and no narrower
     * than a roughly 16:9 block. Tested rather than eyeballed because being wrong by one puts a term
     * on top of another, and because the sizes that matter are not the size the fixture happens to
     * use.
     */
    @Test
    void theLooseBlockIsNeverWiderThanTheDiagramNorATallColumn() {
        // A narrow diagram caps the block at its own width.
        assertEquals(2, CanvasLayouts.looseTermsPerRow(40, 400));
        // A wide diagram lets the block choose its own shape: ceil(sqrt(40 * 1.78)) = 9.
        assertEquals(9, CanvasLayouts.looseTermsPerRow(40, 4000));
        // One loose term needs one column, whatever the diagram is.
        assertEquals(1, CanvasLayouts.looseTermsPerRow(1, 4000));
        // Never zero, whatever it is asked: a zero here divides by zero in the caller.
        assertTrue(CanvasLayouts.looseTermsPerRow(0, 0) >= 1);
        assertTrue(CanvasLayouts.looseTermsPerRow(10, 0) >= 1);
        assertTrue(CanvasLayouts.looseTermsPerRow(10, -500) >= 1);
    }

    /**
     * How many columns a grid uses.
     *
     * <p>It was a fixed four, which is defensible for a dozen terms and wrong for anything bigger:
     * 120 terms in four columns is a 3000px strip, which is the hierarchy's own failure rotated
     * ninety degrees.
     */
    @Test
    void theGridGetsWiderAsTheBoardGrows() {
        // The default pitch: 200 across, 100 down.
        assertEquals(3, CanvasLayouts.gridColumns(6, 200, 100));
        assertEquals(6, CanvasLayouts.gridColumns(31, 200, 100));
        assertEquals(11, CanvasLayouts.gridColumns(120, 200, 100));
        assertTrue(CanvasLayouts.gridColumns(120, 200, 100)
                > CanvasLayouts.gridColumns(31, 200, 100),
                "more terms must mean more columns, or the grid becomes a strip");
        // Never more columns than there are terms to put in them, and never zero.
        assertEquals(1, CanvasLayouts.gridColumns(1, 200, 100));
        assertEquals(1, CanvasLayouts.gridColumns(0, 200, 100));
        assertEquals(1, CanvasLayouts.gridColumns(10, 0, 0));
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
     * {@code stackedGraph()} only has 2 nodes, which never wraps - {@link CanvasLayouts#gridColumns}
     * gives 2 columns for 2 terms - and already sits at non-negative coordinates before the layout
     * even runs, so
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

        // Two distinct rows, not "some y is positive": since 1.64.0 normalise puts the first row
        // at y=40, so the old test passed without a second row existing at all.
        boolean anyRowBeyondTheFirst = false;
        for (double y : ys) {
            if (y != ys[0]) {
                anyRowBeyondTheFirst = true;
                break;
            }
        }
        assertTrue(anyRowBeyondTheFirst,
                "6 nodes must wrap to a second row: " + java.util.Arrays.toString(ys));

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
