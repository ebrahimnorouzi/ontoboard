package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The note badge's geometry and size.
 *
 * <p>The two things worth pinning are the two that fail silently. A badge whose size is not clamped
 * disappears at 10% zoom - which is the zoom somebody uses when looking for which of a hundred terms
 * carries a note, so the marker vanishes exactly when it is needed. And a badge placed inside the node
 * sits on the label of any term with a long name, which is most of them in an OBO ontology.
 *
 * <p>Nothing here paints. The drawing is four calls to {@code Graphics2D} and the arithmetic is the
 * part that can be wrong.
 */
class NoteBadgeLayerTest {

    @Test
    void theBadgeStraddlesTheTopRightCorner() {
        int size = 14;
        int[] box = NoteBadgeLayer.badgeBounds(100, 200, 160, size);

        // Half in, half out: the centre of the badge is the corner of the node.
        assertEquals(100 + 160 - size / 2, box[0]);
        assertEquals(200 - size / 2, box[1]);
        assertTrue(box[0] < 100 + 160, "a badge entirely outside the node reads as a separate object");
        assertTrue(box[0] + size > 100 + 160, "a badge entirely inside the node sits on the label");
    }

    @Test
    void theBadgeGrowsWithTheZoom() {
        assertTrue(NoteBadgeLayer.badgeSize(2.0) > NoteBadgeLayer.badgeSize(1.0));
        assertEquals(14, NoteBadgeLayer.badgeSize(1.0));
    }

    @Test
    void theBadgeStaysVisibleWhenTheBoardIsZoomedOut() {
        // Ten percent: the zoom at which a curator scans a large board for the noted terms. An
        // unclamped badge here is one pixel across.
        assertEquals(NoteBadgeLayer.SMALLEST, NoteBadgeLayer.badgeSize(0.1));
        assertTrue(NoteBadgeLayer.badgeSize(0.1) >= 7);
    }

    @Test
    void theBadgeStopsGrowingBeforeItTakesOverTheNode() {
        assertEquals(NoteBadgeLayer.LARGEST, NoteBadgeLayer.badgeSize(4.0));
        assertEquals(NoteBadgeLayer.LARGEST, NoteBadgeLayer.badgeSize(40.0));
    }

    @Test
    void anImpossibleScaleIsTreatedAsOneToOne() {
        assertEquals(14, NoteBadgeLayer.badgeSize(0));
        assertEquals(14, NoteBadgeLayer.badgeSize(-1));
        assertEquals(14, NoteBadgeLayer.badgeSize(Double.NaN));
    }

    /**
     * The badge is not the error colour.
     *
     * <p>Unsatisfiable is red and is a modelling error; a note is a colleague's remark. Two markers in
     * the same colour on the same node would say the same thing about two different situations.
     */
    @Test
    void theBadgeIsNotTheColourOfAnError() {
        assertTrue(NoteBadgeLayer.BADGE.getGreen() > 100,
                "the badge should read as amber, not as the red used for unsatisfiable classes");
        assertTrue(NoteBadgeLayer.BADGE.getRed() > NoteBadgeLayer.BADGE.getBlue());
    }

    /**
     * The guards hold, because this runs on the paint path.
     *
     * <p>A paint method that throws in Swing does not fail loudly - it leaves a half-drawn component
     * and floods the log on every repaint, and the diagram looks corrupted rather than broken. Both
     * arguments can legitimately be absent: the graph is not a {@code SchemaGraph} in an mxGraph
     * outline panel, and the whole layer is reached before anything has been rendered.
     */
    @Test
    void theGuardsHoldRatherThanThrowingOnThePaintPath() {
        NoteBadgeLayer.paint(null, null);
        NoteBadgeLayer.paint(null, new SchemaGraph());
        // Not a SchemaGraph: nothing to ask about notes, so it returns before using the Graphics.
        NoteBadgeLayer.paint(null, new com.mxgraph.view.mxGraph());
        // An empty board has no noted terms, which is the third early return.
        assertTrue(new SchemaGraph().getNotedIds().isEmpty());
    }
}
