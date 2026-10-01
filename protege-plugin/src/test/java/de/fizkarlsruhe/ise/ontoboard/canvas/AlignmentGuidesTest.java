package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guides shown while a node is dragged.
 *
 * <p>Geometry, so it is tested rather than looked at. The thing worth guarding is not that a line
 * appears - that is obvious the first time anyone drags a node - but that it appears for the
 * right reason and in the right place. A guide that lights up when two nodes are four pixels
 * apart, or that draws through the dragged node instead of the one it lined up with, is worse
 * than none: it is a tool telling you something is aligned when it is not.
 */
class AlignmentGuidesTest {

    private static final Rectangle NODE = new Rectangle(100, 100, 160, 44);

    private static List<AlignmentGuides.Guide> guides(Rectangle moving, Rectangle... others) {
        return AlignmentGuides.forMove(moving, Arrays.asList(others));
    }

    private static AlignmentGuides.Guide onlyVertical(List<AlignmentGuides.Guide> guides) {
        AlignmentGuides.Guide found = null;
        for (AlignmentGuides.Guide guide : guides) {
            if (guide.isVertical()) {
                found = guide;
            }
        }
        return found;
    }

    private static AlignmentGuides.Guide onlyHorizontal(List<AlignmentGuides.Guide> guides) {
        AlignmentGuides.Guide found = null;
        for (AlignmentGuides.Guide guide : guides) {
            if (!guide.isVertical()) {
                found = guide;
            }
        }
        return found;
    }

    // ---------- when a guide appears ----------

    /** Two left edges on the same x produce a vertical guide there. */
    @Test
    void equalLeftEdgesAlign() {
        // Different widths, so the centres are 20 apart and cannot be what matched.
        List<AlignmentGuides.Guide> found =
                guides(new Rectangle(100, 400, 120, 44), NODE);

        AlignmentGuides.Guide vertical = onlyVertical(found);
        assertNotNull(vertical, "the left edges are identical");
        assertEquals(AlignmentGuides.Match.LEFT, vertical.getMatch());
        assertEquals(100, vertical.getPosition());
    }

    /** Centres count as much as edges, and are tried first. */
    @Test
    void equalCentresAlign() {
        // Centre 180 for both; lefts are 100 and 120, which is well outside the tolerance.
        List<AlignmentGuides.Guide> found =
                guides(new Rectangle(120, 400, 120, 44), NODE);

        AlignmentGuides.Guide vertical = onlyVertical(found);
        assertNotNull(vertical);
        assertEquals(AlignmentGuides.Match.CENTRE_X, vertical.getMatch());
        assertEquals(180, vertical.getPosition());
    }

    /** Tops line up the same way, on the other axis. */
    @Test
    void equalTopsAlign() {
        List<AlignmentGuides.Guide> found =
                guides(new Rectangle(400, 100, 120, 60), NODE);

        AlignmentGuides.Guide horizontal = onlyHorizontal(found);
        assertNotNull(horizontal);
        assertEquals(AlignmentGuides.Match.TOP, horizontal.getMatch());
        assertEquals(100, horizontal.getPosition());
    }

    /** A dragged node touching another's right edge is an alignment people use. */
    @Test
    void anEdgeMeetingAnotherEdgeAligns() {
        // NODE runs from x=100 to x=260. This one starts exactly where that ends.
        List<AlignmentGuides.Guide> found =
                guides(new Rectangle(260, 400, 120, 44), NODE);

        AlignmentGuides.Guide vertical = onlyVertical(found);
        assertNotNull(vertical);
        assertEquals(AlignmentGuides.Match.EDGE_X, vertical.getMatch());
        assertEquals(260, vertical.getPosition());
    }

    // ---------- when no guide appears ----------

    /** Beyond the tolerance there is nothing, which is the usual state of a drag. */
    @Test
    void nothingAlignsWhenNothingIsNear() {
        assertTrue(guides(new Rectangle(500, 500, 160, 44), NODE).isEmpty());
    }

    /**
     * The grid makes the tolerance exact.
     *
     * <p>The grid step is 20 and the tolerance is 4, so two snapped nodes are either on the same
     * line or at least 20 apart. One grid step away must not light a guide up, or every node on
     * the board would be "aligned" with its neighbour.
     */
    @Test
    void oneGridStepAwayIsNotAligned() {
        assertNull(onlyVertical(guides(new Rectangle(120, 400, 160, 44), NODE)),
                "20px apart is a grid step, not an alignment");
    }

    /** The boundary is inclusive, and one pixel past it is not. */
    @Test
    void theToleranceBoundaryIsInclusive() {
        assertNotNull(onlyVertical(
                guides(new Rectangle(100 + AlignmentGuides.TOLERANCE, 400, 120, 44), NODE)));
        assertNull(onlyVertical(
                guides(new Rectangle(100 + AlignmentGuides.TOLERANCE + 1, 400, 120, 44), NODE)));
    }

    /** An empty board has nothing to line up with, and must not throw working that out. */
    @Test
    void anEmptyBoardHasNoGuides() {
        assertTrue(AlignmentGuides.forMove(NODE, Collections.<Rectangle>emptyList()).isEmpty());
        assertTrue(AlignmentGuides.forMove(null, Arrays.asList(NODE)).isEmpty());
        assertTrue(AlignmentGuides.forMove(NODE, null).isEmpty());
    }

    /** A node does not line up with itself, however the caller assembles the list. */
    @Test
    void theDraggedNodeIsNotItsOwnPartner() {
        assertTrue(guides(new Rectangle(NODE), NODE).isEmpty(),
                "every edge matches itself exactly, so this would always produce two guides");
    }

    // ---------- how many, and where they run ----------

    /**
     * At most one line per axis.
     *
     * <p>A dense board matches a dozen edges at once. Drawing them all turns the diagram into a
     * cage and answers a question nobody asked - what is wanted is "what am I lined up with",
     * and two lines answer it.
     */
    @Test
    void atMostOneGuidePerAxis() {
        List<AlignmentGuides.Guide> found = guides(new Rectangle(100, 100, 160, 44),
                new Rectangle(100, 300, 160, 44),
                new Rectangle(100, 500, 160, 44),
                new Rectangle(400, 100, 160, 44),
                new Rectangle(700, 100, 160, 44));

        assertEquals(2, found.size(), "one vertical and one horizontal, no more");
        assertNotNull(onlyVertical(found));
        assertNotNull(onlyHorizontal(found));
    }

    /**
     * The nearest match wins, whichever kind it is.
     *
     * <p>The fixed order of the pairings is only a tie-break. If it decided outright, a centre
     * three pixels off would beat a left edge exactly on, and the line would be drawn where
     * nothing lines up.
     */
    @Test
    void theNearestMatchWinsOverTheOrderTried() {
        // Left exactly on 100; centre 3 off (177 against NODE's 180).
        List<AlignmentGuides.Guide> found = guides(new Rectangle(100, 400, 154, 44), NODE);

        AlignmentGuides.Guide vertical = onlyVertical(found);
        assertNotNull(vertical);
        assertEquals(AlignmentGuides.Match.LEFT, vertical.getMatch());
        assertEquals(100, vertical.getPosition());
    }

    /**
     * The line spans both nodes, so you can see what it joined you to.
     *
     * <p>A guide drawn only across the dragged node says "aligned with something" and leaves the
     * user to look for what.
     */
    @Test
    void theLineReachesBothNodes() {
        AlignmentGuides.Guide vertical =
                onlyVertical(guides(new Rectangle(100, 400, 120, 44), NODE));

        assertNotNull(vertical);
        assertEquals(100, vertical.getStart(), "the top of the higher node");
        assertEquals(444, vertical.getEnd(), "the bottom of the lower one");
    }

    /** Ties are broken the same way every time, whatever order the rectangles arrive in. */
    @Test
    void theAnswerDoesNotDependOnTheOrderOfTheBoard() {
        Rectangle moving = new Rectangle(100, 100, 160, 44);
        Rectangle first = new Rectangle(100, 300, 160, 44);
        Rectangle second = new Rectangle(100, 600, 160, 44);

        AlignmentGuides.Guide one = onlyVertical(guides(moving, first, second));
        AlignmentGuides.Guide other = onlyVertical(guides(moving, second, first));

        assertNotNull(one);
        assertNotNull(other);
        assertEquals(one.getMatch(), other.getMatch());
        assertEquals(one.getPosition(), other.getPosition());
    }

    // ---------- what gets repainted ----------

    /** The repaint area is a thin strip, not the board. */
    @Test
    void theRepaintAreaIsAStrip() {
        List<AlignmentGuides.Guide> found = guides(new Rectangle(100, 400, 120, 44), NODE);

        Rectangle area = GuideLayer.areaOf(found);

        assertNotNull(area);
        assertTrue(area.width <= 5 || area.height <= 5,
                "a single guide covers one thin band: " + area);
        assertTrue(area.contains(100, 200), "and it contains the line itself: " + area);
    }

    /** Nothing to draw means nothing to repaint, which is the state during almost every drag. */
    @Test
    void noGuidesMeansNoRepaint() {
        assertNull(GuideLayer.areaOf(Collections.<AlignmentGuides.Guide>emptyList()));
        assertNull(GuideLayer.areaOf(null));
    }
}
