package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGeometry;
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

    private static final String PATTERN_FRAME = SchemaGraph.FRAME_ID_PREFIX + "pattern-measurement";
    private static final String SECTION_FRAME = CanvasLayouts.SECTION_PREFIX + "object-properties";
    private static final String[] IN_FRAME = {
        "http://example.org/egg#process", "http://example.org/egg#boiling",
        "http://example.org/egg#object", "http://example.org/egg#rawEgg",
    };
    private static final String[] OUTSIDE = {
        "http://example.org/egg#report", "http://example.org/egg#document",
        "http://example.org/egg#dataset",
    };
    private static final String PROPERTY = "http://example.org/egg#hasPart";

    /**
     * A board as a pattern import leaves it: four classes in the pattern's frame, three outside it,
     * one relation across, and the outline's frame round a property.
     */
    private static SchemaGraph framedBoard() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(IN_FRAME[0], new CanvasLayout.NodeLayout(60, 100));
        layout.nodes.put(IN_FRAME[1], new CanvasLayout.NodeLayout(60, 240));
        layout.nodes.put(IN_FRAME[2], new CanvasLayout.NodeLayout(360, 100));
        layout.nodes.put(IN_FRAME[3], new CanvasLayout.NodeLayout(360, 240));
        layout.nodes.put(OUTSIDE[0], new CanvasLayout.NodeLayout(60, 520));
        layout.nodes.put(OUTSIDE[1], new CanvasLayout.NodeLayout(60, 660));
        layout.nodes.put(OUTSIDE[2], new CanvasLayout.NodeLayout(360, 660));
        layout.nodes.put(PROPERTY, new CanvasLayout.NodeLayout(760, 540));
        CanvasLayout.FrameLayout pattern = new CanvasLayout.FrameLayout();
        pattern.id = PATTERN_FRAME;
        pattern.label = "Measurement and setpoint";
        pattern.x = 40;
        pattern.y = 40;
        pattern.w = 560;
        pattern.h = 320;
        layout.frames.add(pattern);
        CanvasLayout.FrameLayout section = new CanvasLayout.FrameLayout();
        section.id = SECTION_FRAME;
        section.label = "Object properties";
        section.x = 740;
        section.y = 500;
        section.w = 240;
        section.h = 140;
        layout.frames.add(section);
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(IN_FRAME[0], NodeKind.CLASS, "completely executed planned process"),
                new CanvasNode(IN_FRAME[1], NodeKind.CLASS, "egg boiling"),
                new CanvasNode(IN_FRAME[2], NodeKind.CLASS, "object"),
                new CanvasNode(IN_FRAME[3], NodeKind.CLASS, "raw egg"),
                new CanvasNode(OUTSIDE[0], NodeKind.CLASS, "report"),
                new CanvasNode(OUTSIDE[1], NodeKind.CLASS, "document"),
                new CanvasNode(OUTSIDE[2], NodeKind.CLASS, "egg boiling dataset"),
                new CanvasNode(PROPERTY, NodeKind.OBJECT_PROPERTY, "has part")),
                Arrays.asList(
                        new CanvasEdge("sub|1", IN_FRAME[1], IN_FRAME[0], "", CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|2", IN_FRAME[3], IN_FRAME[2], "", CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("rel|1", IN_FRAME[1], IN_FRAME[3], "∃ has specified input",
                                CanvasEdge.Kind.OBJECT_PROPERTY),
                        new CanvasEdge("sub|3", OUTSIDE[0], OUTSIDE[1], "", CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("rel|2", OUTSIDE[0], IN_FRAME[1], "∃ is about",
                                CanvasEdge.Kind.OBJECT_PROPERTY))),
                layout);
        return graph;
    }

    private static boolean inside(mxGeometry frame, mxGeometry box) {
        return box.getX() >= frame.getX() && box.getY() >= frame.getY()
                && box.getX() + box.getWidth() <= frame.getX() + frame.getWidth()
                && box.getY() + box.getHeight() <= frame.getY() + frame.getHeight();
    }

    private static boolean overlaps(mxGeometry a, mxGeometry b) {
        return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
                && a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
    }

    /**
     * What stands in a pattern's frame stays in it, whatever the arrangement (1.135.1, Windows study
     * rehearsal). Hierarchical, Organic, Circle and Grid moved every term and put the frames back
     * where they had been, so switching the board's layout left each pattern's frame empty and its
     * classes scattered; only Outline kept them.
     */
    @Test
    void everyArrangementKeepsAFramesTermsInTheFrame() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph graph = framedBoard();
            CanvasLayouts.apply(graph, algorithm);
            mxGeometry frame = graph.getModel().getGeometry(graph.getCellForId(PATTERN_FRAME));
            for (String member : IN_FRAME) {
                assertTrue(inside(frame, graph.getModel().getGeometry(graph.getCellForId(member))),
                        algorithm + " moved " + member + " out of the pattern's frame");
            }
            for (String other : OUTSIDE) {
                assertFalse(overlaps(frame, graph.getModel().getGeometry(graph.getCellForId(other))),
                        algorithm + " put " + other + " on the pattern's frame");
            }
        }
    }

    /** And the classes keep their places in the frame: the pattern reads as it did. */
    @Test
    void aFramesTermsKeepTheirPlacesInIt() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            SchemaGraph graph = framedBoard();
            mxGeometry frameBefore = (mxGeometry) graph.getModel()
                    .getGeometry(graph.getCellForId(PATTERN_FRAME)).clone();
            double[][] before = new double[IN_FRAME.length][];
            for (int i = 0; i < IN_FRAME.length; i++) {
                mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(IN_FRAME[i]));
                before[i] = new double[] {at.getX() - frameBefore.getX(), at.getY() - frameBefore.getY()};
            }
            CanvasLayouts.apply(graph, algorithm);
            mxGeometry frame = graph.getModel().getGeometry(graph.getCellForId(PATTERN_FRAME));
            for (int i = 0; i < IN_FRAME.length; i++) {
                mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(IN_FRAME[i]));
                assertEquals(before[i][0], at.getX() - frame.getX(), 0.001,
                        algorithm + " moved " + IN_FRAME[i] + " within its frame");
                assertEquals(before[i][1], at.getY() - frame.getY(), 0.001,
                        algorithm + " moved " + IN_FRAME[i] + " within its frame");
            }
        }
    }

    /**
     * The outline's own frames go with the outline: another arrangement moves the properties out
     * of them, and a frame left behind enclosed nothing.
     */
    @Test
    void anotherArrangementTakesTheOutlinesFramesAway() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            if (algorithm == CanvasLayouts.Algorithm.SECTIONS) {
                continue;
            }
            SchemaGraph graph = framedBoard();
            CanvasLayouts.apply(graph, algorithm);
            assertEquals(null, graph.getCellForId(SECTION_FRAME),
                    algorithm + " left the outline's frame on the board");
        }
    }

    private static final String E = "http://example.org/egg#";

    /**
     * planned process and egg boiling in a pattern's frame; report and document under it, related;
     * a dataset nothing relates yet; and a class just made beside the frame, at the top of the
     * board, not related to anything either.
     */
    private static SchemaGraph framedBoardWithANewClassBesideTheFrame() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(E + "process", new CanvasLayout.NodeLayout(60, 100));
        layout.nodes.put(E + "boiling", new CanvasLayout.NodeLayout(60, 240));
        layout.nodes.put(E + "report", new CanvasLayout.NodeLayout(60, 520));
        layout.nodes.put(E + "document", new CanvasLayout.NodeLayout(60, 660));
        layout.nodes.put(E + "dataset", new CanvasLayout.NodeLayout(360, 660));
        layout.nodes.put(E + "fresh", new CanvasLayout.NodeLayout(700, 0));
        CanvasLayout.FrameLayout pattern = new CanvasLayout.FrameLayout();
        pattern.id = PATTERN_FRAME;
        pattern.label = "Measurement and setpoint";
        pattern.x = 40;
        pattern.y = 40;
        pattern.w = 560;
        pattern.h = 320;
        layout.frames.add(pattern);
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(E + "process", NodeKind.CLASS, "planned process"),
                new CanvasNode(E + "boiling", NodeKind.CLASS, "egg boiling"),
                new CanvasNode(E + "report", NodeKind.CLASS, "report"),
                new CanvasNode(E + "document", NodeKind.CLASS, "document"),
                new CanvasNode(E + "dataset", NodeKind.CLASS, "egg boiling dataset"),
                new CanvasNode(E + "fresh", NodeKind.CLASS, "new class")),
                Arrays.asList(
                        new CanvasEdge("sub|1", E + "boiling", E + "process", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|3", E + "report", E + "document", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("rel|2", E + "report", E + "boiling", "∃ is about",
                                CanvasEdge.Kind.OBJECT_PROPERTY))),
                layout);
        return graph;
    }

    /**
     * The arrangement starts right under the frames, whatever loose class stands beside them
     * (1.135.1 review). Organic and Circle leave a term nothing relates where it was, and where the
     * arrangement started was taken over those terms too: a class made beside the frame at y=0
     * put the arranged block at y=959 rather than 440, under a band of 519 px of empty board.
     */
    @Test
    void theArrangementStartsUnderTheFramesWhateverLooseClassStandsBesideThem() {
        List<String> wrong = new ArrayList<String>();
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            if (algorithm == CanvasLayouts.Algorithm.SECTIONS) {
                continue;
            }
            SchemaGraph graph = framedBoardWithANewClassBesideTheFrame();
            CanvasLayouts.apply(graph, algorithm);
            mxGeometry frame = graph.getModel().getGeometry(graph.getCellForId(PATTERN_FRAME));
            mxGeometry report = graph.getModel().getGeometry(graph.getCellForId(E + "report"));
            mxGeometry document = graph.getModel().getGeometry(graph.getCellForId(E + "document"));
            double top = Math.min(report.getY(), document.getY());
            double left = Math.min(report.getX(), document.getX());
            double under = frame.getY() + frame.getHeight() + 80;
            if (Math.abs(top - under) > 0.001 || Math.abs(left - frame.getX()) > 0.001) {
                wrong.add(algorithm + " started the arrangement at (" + left + "," + top
                        + ") rather than (" + frame.getX() + "," + under + ")");
            }
            // And the two loose terms are parked under it, as on a board with no frame.
            double bottom = Math.max(report.getY() + report.getHeight(),
                    document.getY() + document.getHeight());
            for (String loose : new String[] {E + "dataset", E + "fresh"}) {
                mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(loose));
                if (at.getY() < bottom) {
                    wrong.add(algorithm + " left " + loose + " at " + at.getY()
                            + ", above the bottom of the arrangement at " + bottom);
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
    }

    /**
     * planned process and egg boiling at the top, report and document under them, the information
     * content entity beside document: three subclass lines and one relation, report to egg boiling.
     */
    private static SchemaGraph reportAboutEggBoiling() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(E + "process", new CanvasLayout.NodeLayout(60, 100));
        layout.nodes.put(E + "boiling", new CanvasLayout.NodeLayout(60, 240));
        layout.nodes.put(E + "report", new CanvasLayout.NodeLayout(400, 520));
        layout.nodes.put(E + "document", new CanvasLayout.NodeLayout(400, 660));
        layout.nodes.put(E + "entity", new CanvasLayout.NodeLayout(700, 660));
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(E + "process", NodeKind.CLASS, "planned process"),
                new CanvasNode(E + "boiling", NodeKind.CLASS, "egg boiling"),
                new CanvasNode(E + "report", NodeKind.CLASS, "report"),
                new CanvasNode(E + "document", NodeKind.CLASS, "document"),
                new CanvasNode(E + "entity", NodeKind.CLASS, "information content entity")),
                Arrays.asList(
                        new CanvasEdge("sub|1", E + "boiling", E + "process", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|3", E + "report", E + "document", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|4", E + "document", E + "entity", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("rel|2", E + "report", E + "boiling", "∃ is about",
                                CanvasEdge.Kind.OBJECT_PROPERTY))),
                layout);
        return graph;
    }

    private static List<com.mxgraph.util.mxPoint> bendsOf(SchemaGraph graph, String edge) {
        mxGeometry geometry = graph.getModel().getGeometry(graph.getCellForId(edge));
        List<com.mxgraph.util.mxPoint> points = geometry == null ? null : geometry.getPoints();
        return points == null ? Collections.<com.mxgraph.util.mxPoint>emptyList() : points;
    }

    /**
     * A line the arrangement moved an end of keeps no bend from an arrangement before it (1.135.1
     * review). Hierarchical, a frame drawn round egg boiling and planned process, Hierarchical
     * again: report moved to (40,766) while its arrow to egg boiling kept the first run's bends,
     * and was drawn through where report had been. Hierarchical routes only the lines between the
     * terms it arranges; Circle and Grid route none, and kept every old bend.
     */
    @Test
    void aLineWithAMovedEndKeepsNoBendFromTheLastArrangement() {
        List<String> stale = new ArrayList<String>();
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            if (algorithm == CanvasLayouts.Algorithm.SECTIONS) {
                continue;
            }
            SchemaGraph graph = reportAboutEggBoiling();
            CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
            assertFalse(bendsOf(graph, "rel|2").isEmpty(), "Hierarchical bends every line");
            mxGeometry process = graph.getModel().getGeometry(graph.getCellForId(E + "process"));
            mxGeometry boiling = graph.getModel().getGeometry(graph.getCellForId(E + "boiling"));
            double left = Math.min(process.getX(), boiling.getX()) - 20;
            double top = Math.min(process.getY(), boiling.getY()) - 20;
            double right = Math.max(process.getX() + process.getWidth(),
                    boiling.getX() + boiling.getWidth()) + 20;
            double bottom = Math.max(process.getY() + process.getHeight(),
                    boiling.getY() + boiling.getHeight()) + 20;
            graph.insertVertex(graph.getDefaultParent(), SchemaGraph.FRAME_ID_PREFIX + "mine",
                    "mine", left, top, right - left, bottom - top, SchemaStyles.FRAME);

            assertEquals(2, CanvasLayouts.apply(graph, algorithm),
                    algorithm + " kept egg boiling and planned process in the frame");

            // report moved and egg boiling stayed: no arrangement routes that line.
            if (!bendsOf(graph, "rel|2").isEmpty()) {
                stale.add(algorithm + " left rel|2 (into the frame) with " + bendsOf(graph, "rel|2"));
            }
            // Both ends moved: Hierarchical routed these again; nothing else routes a line.
            if (algorithm != CanvasLayouts.Algorithm.HIERARCHICAL) {
                for (String line : new String[] {"sub|3", "sub|4"}) {
                    if (!bendsOf(graph, line).isEmpty()) {
                        stale.add(algorithm + " left " + line + " with " + bendsOf(graph, line));
                    }
                }
            }
        }
        assertTrue(stale.isEmpty(), String.join("; ", stale));
    }

    /** A frame drawn round these terms, 20 px clear of them. */
    private static void drawFrameRound(SchemaGraph graph, String... terms) {
        double left = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (String term : terms) {
            mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(term));
            left = Math.min(left, at.getX() - 20);
            top = Math.min(top, at.getY() - 20);
            right = Math.max(right, at.getX() + at.getWidth() + 20);
            bottom = Math.max(bottom, at.getY() + at.getHeight() + 20);
        }
        graph.insertVertex(graph.getDefaultParent(), SchemaGraph.FRAME_ID_PREFIX + "mine", "mine",
                left, top, right - left, bottom - top, SchemaStyles.FRAME);
    }

    /** Every box on the board, by id: x, y, w, h. */
    private static java.util.Map<String, List<Double>> boxes(SchemaGraph graph) {
        java.util.Map<String, List<Double>> boxes = new java.util.HashMap<String, List<Double>>();
        for (Object vertex : graph.getChildVertices(graph.getDefaultParent())) {
            mxGeometry at = graph.getModel().getGeometry(vertex);
            boxes.put(graph.getIdForCell(vertex),
                    Arrays.asList(at.getX(), at.getY(), at.getWidth(), at.getHeight()));
        }
        return boxes;
    }

    private static final String[] LINES = {"sub|1", "sub|3", "sub|4", "rel|2"};

    /**
     * Outline after another arrangement keeps no bend of it on a line it moved an end of (1.135.1
     * review). Outline leaves its lines to the board's router, and a line with bends of its own is
     * drawn through them instead: after Hierarchical then Outline - which the real-mouse walk does -
     * every line ran along Hierarchical's lanes from where its boxes had stood. A line whose two
     * ends stay in a frame keeps what it had: neither end moved.
     */
    @Test
    void outlineAfterHierarchicalKeepsNoBendOnALineItMovedAnEndOf() {
        List<String> stale = new ArrayList<String>();
        for (boolean framed : new boolean[] {false, true}) {
            SchemaGraph graph = reportAboutEggBoiling();
            CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
            if (framed) {
                drawFrameRound(graph, E + "process", E + "boiling");
            }
            java.util.Map<String, List<Double>> before = boxes(graph);
            CanvasLayouts.applySections(graph);
            java.util.Map<String, List<Double>> after = boxes(graph);
            for (String line : LINES) {
                Object edge = graph.getCellForId(line);
                String from = graph.getIdForCell(graph.getModel().getTerminal(edge, true));
                String to = graph.getIdForCell(graph.getModel().getTerminal(edge, false));
                boolean moved = !before.get(from).equals(after.get(from))
                        || !before.get(to).equals(after.get(to));
                if (moved && !bendsOf(graph, line).isEmpty()) {
                    stale.add((framed ? "framed: " : "") + line + " kept " + bendsOf(graph, line));
                }
            }
            if (framed) {
                assertEquals(2, bendsOf(graph, "sub|1").size(),
                        "both ends of sub|1 stayed in the frame, and so did its bends");
            }
        }
        assertTrue(stale.isEmpty(), String.join("; ", stale));
    }

    /**
     * On a board that shows everything, Outline plans the pattern frames first, and the drawing that
     * follows moves each pattern's terms into its frame before the outline places the rest. A line
     * the drawing moved keeps no bend from before either, when the boxes are taken before it.
     */
    @Test
    void outlineDropsTheBendsOfLinesTheDrawingMovedIntoAFrame() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(E + "process", new CanvasLayout.NodeLayout(60, 100));
        layout.nodes.put(E + "boiling", new CanvasLayout.NodeLayout(60, 240));
        layout.nodes.put(E + "report", new CanvasLayout.NodeLayout(400, 520));
        layout.nodes.put(E + "document", new CanvasLayout.NodeLayout(400, 660));
        layout.nodes.put(E + "entity", new CanvasLayout.NodeLayout(700, 660));
        Projection projection = new Projection(Arrays.asList(
                new CanvasNode(E + "process", NodeKind.CLASS, "planned process"),
                new CanvasNode(E + "boiling", NodeKind.CLASS, "egg boiling"),
                new CanvasNode(E + "report", NodeKind.CLASS, "report"),
                new CanvasNode(E + "document", NodeKind.CLASS, "document"),
                new CanvasNode(E + "entity", NodeKind.CLASS, "information content entity")),
                Arrays.asList(
                        new CanvasEdge("sub|1", E + "boiling", E + "process", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|3", E + "report", E + "document", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("sub|4", E + "document", E + "entity", "",
                                CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("rel|2", E + "report", E + "boiling", "∃ is about",
                                CanvasEdge.Kind.OBJECT_PROPERTY)));
        graph.render(projection, layout);
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
        assertFalse(bendsOf(graph, "sub|1").isEmpty(), "Hierarchical bends every line");

        java.util.Map<String, double[]> before = CanvasLayouts.boxesOf(graph);
        // The pattern's frame, planned with places for its two terms; nothing else keeps one.
        layout.nodes.clear();
        layout.nodes.put(E + "process", new CanvasLayout.NodeLayout(1060, 100));
        layout.nodes.put(E + "boiling", new CanvasLayout.NodeLayout(1060, 240));
        CanvasLayout.FrameLayout pattern = new CanvasLayout.FrameLayout();
        pattern.id = PATTERN_FRAME;
        pattern.label = "Measurement and setpoint";
        pattern.x = 1040;
        pattern.y = 40;
        pattern.w = 560;
        pattern.h = 320;
        layout.frames.add(pattern);
        graph.render(projection, layout);
        CanvasLayouts.applySections(graph,
                new java.util.HashSet<String>(Arrays.asList(E + "process", E + "boiling")), false);
        CanvasLayouts.dropBendsOfLinesMovedSince(graph, before);

        for (String line : LINES) {
            assertTrue(bendsOf(graph, line).isEmpty(), line + " kept " + bendsOf(graph, line));
        }
    }

    /**
     * Circle and Grid route no line, so on a board without frames too they keep none of the bends
     * Hierarchical gave them (1.135.1 review): every line ran along Hierarchical's lanes.
     */
    @Test
    void withoutFramesCircleAndGridKeepNoBendFromHierarchical() {
        List<String> stale = new ArrayList<String>();
        for (CanvasLayouts.Algorithm algorithm : new CanvasLayouts.Algorithm[] {
            CanvasLayouts.Algorithm.CIRCLE, CanvasLayouts.Algorithm.GRID}) {
            SchemaGraph graph = reportAboutEggBoiling();
            CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
            CanvasLayouts.apply(graph, algorithm);
            for (String line : LINES) {
                if (!bendsOf(graph, line).isEmpty()) {
                    stale.add(algorithm + " left " + line + " with " + bendsOf(graph, line));
                }
            }
        }
        assertTrue(stale.isEmpty(), String.join("; ", stale));
    }

    /**
     * A board only ever laid out by Outline is laid out as it was: a first Outline and a second
     * one place every box where they always did, and no line is touched - none has a bend, and each
     * keeps the geometry it was drawn with. Outline is how a board arranges itself after an import.
     */
    @Test
    void aBoardOnlyEverLaidOutByOutlineIsLaidOutAsBefore() {
        SchemaGraph graph = framedBoard();
        java.util.Map<Object, mxGeometry> drawn = new java.util.IdentityHashMap<Object, mxGeometry>();
        for (Object edge : com.mxgraph.model.mxGraphModel.getChildCells(graph.getModel(),
                graph.getDefaultParent(), false, true)) {
            drawn.put(edge, graph.getModel().getGeometry(edge));
        }
        CanvasLayouts.applySections(graph);
        java.util.Map<String, List<Double>> first = boxes(graph);
        CanvasLayouts.applySections(graph);
        assertEquals(first, boxes(graph), "a second Outline moved something");
        for (java.util.Map.Entry<Object, mxGeometry> line : drawn.entrySet()) {
            assertTrue(graph.getModel().getGeometry(line.getKey()) == line.getValue(),
                    graph.getIdForCell(line.getKey()) + " was drawn again");
            List<com.mxgraph.util.mxPoint> points = line.getValue().getPoints();
            assertTrue(points == null || points.isEmpty(), graph.getIdForCell(line.getKey()));
        }
    }

    /**
     * A board without frames is arranged as before: Hierarchical gives every line the bends of its
     * own lanes, and nothing takes them off again.
     */
    @Test
    void withoutFramesHierarchicalKeepsTheBendsItGivesEveryLine() {
        SchemaGraph graph = reportAboutEggBoiling();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
        for (String line : new String[] {"sub|1", "sub|3", "sub|4", "rel|2"}) {
            assertFalse(bendsOf(graph, line).isEmpty(), line + " lost the bends of its lane");
        }
    }

    @Test
    void everyAlgorithmRunsWithoutThrowingOnAnEmptyGraph() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            CanvasLayouts.apply(new SchemaGraph(), algorithm);
        }
        assertEquals(5, CanvasLayouts.Algorithm.values().length, "the outline was added in 1.131.0");
    }
}
