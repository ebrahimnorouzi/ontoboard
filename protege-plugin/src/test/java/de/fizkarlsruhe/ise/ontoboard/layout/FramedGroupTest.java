package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An imported pattern arriving as a named group rather than as a row of loose nodes.
 *
 * <p>Pure, which is the point of {@link FramedGroup} being its own class: the two decisions worth
 * guarding - where the group lands and what happens to terms the user has already placed - are
 * checkable without a canvas, a Protege or a running reasoner.
 */
class FramedGroupTest {

    private static List<String> iris(int count) {
        List<String> list = new ArrayList<String>();
        for (int at = 0; at < count; at++) {
            list.add("http://example.org/pattern#T" + at);
        }
        return list;
    }

    private static CanvasLayout boardWith(double x, double y, double w, double h) {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = w;
        node.h = h;
        board.nodes.put("http://example.org/existing#A", node);
        return board;
    }

    // ---------- the group ----------

    /** Every term gets a position, and the frame carries the pattern's name. */
    @Test
    void thePatternArrivesAsOneNamedGroup() {
        FramedGroup.Placement placement = FramedGroup.plan("Agent Role", iris(6), new CanvasLayout());

        assertEquals(6, placement.getNodes().size());
        assertNotNull(placement.getFrame());
        assertEquals("Agent Role", placement.getFrame().label);
        assertTrue(placement.getAlreadyOnTheBoard().isEmpty());
    }

    /**
     * Every node is inside its frame.
     *
     * <p>The claim the whole feature rests on. A frame carries its contents when dragged, by
     * centre-point containment - so a node whose centre falls outside is a node that silently
     * stops belonging to the group the moment somebody moves it.
     */
    @Test
    void everyTermIsInsideTheFrame() {
        for (int count : new int[] {1, 2, 5, 8, 13, 26}) {
            FramedGroup.Placement placement =
                    FramedGroup.plan("P", iris(count), new CanvasLayout());
            CanvasLayout.FrameLayout frame = placement.getFrame();
            for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
                double cx = node.x + node.w / 2;
                double cy = node.y + node.h / 2;
                assertTrue(cx > frame.x && cx < frame.x + frame.w,
                        count + " terms: a centre at x=" + cx + " is outside " + frame.x + ".."
                                + (frame.x + frame.w));
                assertTrue(cy > frame.y && cy < frame.y + frame.h,
                        count + " terms: a centre at y=" + cy + " is outside the frame");
                // The inset, not just containment. Asserting only "inside" is too loose to be
                // useful: dropping the label band from the frame's height leaves every node
                // technically inside, because the bottom padding is wider than the band and
                // silently absorbs it - the frame is then 4px from its last row instead of 34
                // and looks wrong, with every containment assertion still green. Measured, by
                // making exactly that change and watching this test pass.
                assertTrue(node.x - frame.x >= FramedGroup.PADDING - 0.001,
                        count + " terms: left inset is " + (node.x - frame.x));
                assertTrue(frame.x + frame.w - (node.x + node.w) >= FramedGroup.PADDING - 0.001,
                        count + " terms: right inset is "
                                + (frame.x + frame.w - (node.x + node.w)));
                assertTrue(node.y - frame.y >= FramedGroup.PADDING + FramedGroup.LABEL_BAND - 0.001,
                        count + " terms: top inset is " + (node.y - frame.y)
                                + ", which is less than the padding plus the label band");
                assertTrue(frame.y + frame.h - (node.y + node.h) >= FramedGroup.PADDING - 0.001,
                        count + " terms: bottom inset is "
                                + (frame.y + frame.h - (node.y + node.h)));
            }
        }
    }

    /** Nothing overlaps anything else. */
    @Test
    void theTermsDoNotSitOnTopOfEachOther() {
        FramedGroup.Placement placement = FramedGroup.plan("P", iris(9), new CanvasLayout());
        List<CanvasLayout.NodeLayout> nodes =
                new ArrayList<CanvasLayout.NodeLayout>(placement.getNodes().values());

        for (int a = 0; a < nodes.size(); a++) {
            for (int b = a + 1; b < nodes.size(); b++) {
                CanvasLayout.NodeLayout one = nodes.get(a);
                CanvasLayout.NodeLayout two = nodes.get(b);
                boolean apart = one.x + one.w <= two.x || two.x + two.w <= one.x
                        || one.y + one.h <= two.y || two.y + two.h <= one.y;
                assertTrue(apart, "two terms overlap");
            }
        }
    }

    /** The first row clears the frame's label band. */
    @Test
    void theFirstRowDoesNotSitUnderTheLabel() {
        FramedGroup.Placement placement = FramedGroup.plan("P", iris(3), new CanvasLayout());

        for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
            assertTrue(node.y >= placement.getFrame().y + FramedGroup.LABEL_BAND,
                    "a node is drawn over the frame's own label");
        }
    }

    /**
     * A wide pattern wraps instead of becoming a frame nobody can see the end of.
     *
     * <p>26 terms in one row is four thousand pixels. Capped at five columns, which keeps the
     * widest frame under a thousand and so inside a laptop canvas.
     */
    @Test
    void aBigPatternWrapsRatherThanRunningOffTheBoard() {
        assertEquals(1, FramedGroup.columnsFor(1));
        assertEquals(2, FramedGroup.columnsFor(2));
        assertEquals(3, FramedGroup.columnsFor(9));
        assertEquals(5, FramedGroup.columnsFor(26));
        assertEquals(5, FramedGroup.columnsFor(200));

        assertTrue(FramedGroup.plan("P", iris(26), new CanvasLayout()).getFrame().w < 1100,
                "a 26-term frame has to fit on a screen");
    }

    // ---------- not overruling the user ----------

    /**
     * A term the user has already placed stays where they put it.
     *
     * <p>Importing a pattern that happens to mention a class somebody has already arranged is not
     * a reason to drag that class into a box. It is reported instead, so the frame holding less
     * than the whole pattern has an explanation.
     */
    @Test
    void termsAlreadyOnTheBoardAreLeftAlone() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.NodeLayout placed = new CanvasLayout.NodeLayout(1234, 5678);
        board.nodes.put("http://example.org/pattern#T1", placed);

        FramedGroup.Placement placement = FramedGroup.plan("P", iris(4), board);

        assertEquals(3, placement.getNodes().size());
        assertFalse(placement.getNodes().containsKey("http://example.org/pattern#T1"));
        assertEquals(Arrays.asList("http://example.org/pattern#T1"),
                placement.getAlreadyOnTheBoard());
        assertEquals(1234, board.nodes.get("http://example.org/pattern#T1").x, 0.001,
                "planning must not move anything");
    }

    /** A pattern entirely on the board already makes no frame at all. */
    @Test
    void aPatternAlreadyDrawnMakesNoEmptyFrame() {
        CanvasLayout board = new CanvasLayout();
        for (String iri : iris(3)) {
            board.nodes.put(iri, new CanvasLayout.NodeLayout(10, 10));
        }

        FramedGroup.Placement placement = FramedGroup.plan("P", iris(3), board);

        assertTrue(placement.isEmpty());
        assertNull(placement.getFrame(), "a frame with nothing in it is a labelled empty box");
        assertEquals(3, placement.getAlreadyOnTheBoard().size());
    }

    /** The plan never modifies the board it was shown. */
    @Test
    void theExistingBoardIsNotTouched() {
        CanvasLayout board = boardWith(0, 0, 160, 60);
        int nodesBefore = board.nodes.size();
        int framesBefore = board.frames.size();

        FramedGroup.plan("P", iris(5), board);

        assertEquals(nodesBefore, board.nodes.size());
        assertEquals(framesBefore, board.frames.size());
    }

    // ---------- where it lands ----------

    /** On an empty board it lands clear of the corner, not flush against it. */
    @Test
    void anEmptyBoardPutsItClearOfTheCorner() {
        FramedGroup.Placement placement = FramedGroup.plan("P", iris(4), new CanvasLayout());

        assertTrue(placement.getFrame().x >= FramedGroup.MARGIN);
        assertTrue(placement.getFrame().y >= FramedGroup.MARGIN);
    }

    /** It lands beside what is there, never on top of it. */
    @Test
    void itLandsClearOfWhatIsAlreadyDrawn() {
        CanvasLayout board = boardWith(0, 0, 400, 300);

        CanvasLayout.FrameLayout frame = FramedGroup.plan("P", iris(4), board).getFrame();

        assertTrue(frame.x >= 400, "it must start to the right of the existing node, not over it");
        for (CanvasLayout.NodeLayout node : board.nodes.values()) {
            boolean clear = frame.x >= node.x + node.w || node.x >= frame.x + frame.w
                    || frame.y >= node.y + node.h || node.y >= frame.y + frame.h;
            assertTrue(clear, "the frame overlaps an existing node");
        }
    }

    /** Existing frames are avoided too, not just nodes. */
    @Test
    void itClearsExistingFramesAsWell() {
        CanvasLayout board = new CanvasLayout();
        CanvasLayout.FrameLayout existing = new CanvasLayout.FrameLayout();
        existing.x = 0;
        existing.y = 0;
        existing.w = 900;
        existing.h = 400;
        board.frames.add(existing);

        CanvasLayout.FrameLayout frame = FramedGroup.plan("P", iris(3), board).getFrame();

        assertTrue(frame.x >= 900, "a new frame must not land inside an existing one");
    }

    // ---------- the awkward inputs ----------

    /** No terms, no frame. */
    @Test
    void nothingToPlaceIsNotAFrame() {
        assertTrue(FramedGroup.plan("P", Collections.<String>emptyList(),
                new CanvasLayout()).isEmpty());
        assertTrue(FramedGroup.plan("P", null, new CanvasLayout()).isEmpty());
    }

    /** Blanks and repeats do not become empty boxes or doubled nodes. */
    @Test
    void blanksAndRepeatsAreIgnored() {
        FramedGroup.Placement placement = FramedGroup.plan("P",
                Arrays.asList("a", "a", "", "   ", null, "b"), new CanvasLayout());

        assertEquals(2, placement.getNodes().size());
        assertTrue(placement.getNodes().containsKey("a"));
        assertTrue(placement.getNodes().containsKey("b"));
    }

    /** A pattern with no name still gets a frame that says something. */
    @Test
    void anUnnamedPatternStillGetsALabel() {
        for (String name : new String[] {null, "", "   "}) {
            CanvasLayout.FrameLayout frame =
                    FramedGroup.plan(name, iris(2), new CanvasLayout()).getFrame();
            assertNotNull(frame.label);
            assertFalse(frame.label.trim().isEmpty(), "an unlabelled frame is an anonymous box");
        }
    }

    /** A null board is treated as an empty one rather than throwing. */
    @Test
    void noBoardYetIsNotACrash() {
        FramedGroup.Placement placement = FramedGroup.plan("P", iris(3), null);

        assertEquals(3, placement.getNodes().size());
        assertNotNull(placement.getFrame());
    }

    /**
     * The frame uses the same stride as every other placement on the board.
     *
     * <p>Reported as "so compact and lots of concepts are packed". This was the only multi-node
     * placement that was tighter than the rest: {@code CanvasLayouts} puts nodes on a 200x100
     * stride everywhere - {@code GRID_GAP = 40}, {@code LOOSE_PITCH_X/Y = 200/100},
     * {@code NODE_PITCH = 200}, {@code RING_GAP = 200} - and this used 188x88.
     *
     * <p>Asserted as the stride rather than as {@code GAP == 40}, because the stride is the thing
     * a user sees and the thing that has to agree with the other layouts. A test on the constant
     * would pass if somebody changed the node size and left the gap alone.
     */
    @Test
    void theStrideMatchesTheRestOfTheBoard() {
        List<CanvasLayout.NodeLayout> row = new ArrayList<CanvasLayout.NodeLayout>(
                FramedGroup.plan("P", iris(10), new CanvasLayout()).getNodes().values());
        Collections.sort(row, new java.util.Comparator<CanvasLayout.NodeLayout>() {
            @Override
            public int compare(CanvasLayout.NodeLayout a, CanvasLayout.NodeLayout b) {
                int byRow = Double.compare(a.y, b.y);
                return byRow != 0 ? byRow : Double.compare(a.x, b.x);
            }
        });

        assertEquals(200.0, row.get(1).x - row.get(0).x, 0.001,
                "the horizontal stride every other layout uses is 200");
        double firstRowY = row.get(0).y;
        for (CanvasLayout.NodeLayout node : row) {
            if (node.y > firstRowY + 0.001) {
                assertEquals(100.0, node.y - firstRowY, 0.001,
                        "the vertical stride every other layout uses is 100");
                break;
            }
        }
    }

    /**
     * Everything lands on the canvas's 20px snap grid.
     *
     * <p>So a pattern's nodes line up with whatever else is on the board, and do not jump the
     * first time one is dragged - dragging snaps to 20, and the old insets of 34 and 64 did not.
     */
    @Test
    void everyNodeLandsOnTheSnapGrid() {
        FramedGroup.Placement placement = FramedGroup.plan("P", iris(7), new CanvasLayout());
        CanvasLayout.FrameLayout frame = placement.getFrame();

        for (CanvasLayout.NodeLayout node : placement.getNodes().values()) {
            assertEquals(0.0, (node.x - frame.x) % 20, 0.001,
                    "x inset off the 20px grid: " + (node.x - frame.x));
            assertEquals(0.0, (node.y - frame.y) % 20, 0.001,
                    "y inset off the 20px grid: " + (node.y - frame.y));
        }
    }
}
