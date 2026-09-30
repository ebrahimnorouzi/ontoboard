package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Where an expansion puts the neighbours it adds.
 *
 * <p>Worth arithmetic tests rather than a look, because the thing being replaced was invisible in the
 * code and obvious on screen: with no geometry, `SchemaGraph.render` laid new nodes in a row along the
 * top of the board, overlapping whatever was up there and nowhere near the term they neighbour. The
 * property that matters is the one a glance cannot verify on a crowded board - that no two of them are
 * put in the same place.
 */
class CanvasRingTest {

    /** The closest two offsets in a set, for the no-overlap property. */
    private static double closestPair(List<double[]> offsets) {
        double closest = Double.MAX_VALUE;
        for (int i = 0; i < offsets.size(); i++) {
            for (int j = i + 1; j < offsets.size(); j++) {
                double dx = offsets.get(i)[0] - offsets.get(j)[0];
                double dy = offsets.get(i)[1] - offsets.get(j)[1];
                closest = Math.min(closest, Math.sqrt(dx * dx + dy * dy));
            }
        }
        return closest;
    }

    private static double radiusOf(double[] offset) {
        return Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]);
    }

    @Test
    void nothingToPlaceProducesNothing() {
        assertTrue(CanvasLayouts.ringOffsets(0).isEmpty());
        assertTrue(CanvasLayouts.ringOffsets(-3).isEmpty());
    }

    @Test
    void oneNeighbourGoesAboveTheTerm() {
        List<double[]> offsets = CanvasLayouts.ringOffsets(1);

        assertEquals(1, offsets.size());
        assertEquals(0, offsets.get(0)[0], 1e-6, "directly above, not to one side");
        assertTrue(offsets.get(0)[1] < 0, "negative y is up on a Swing canvas");
    }

    @Test
    void everyNeighbourIsPlacedSomewhere() {
        // The loop that fills rings is the kind that quietly drops the last item.
        for (int count : new int[] { 1, 2, 3, 7, 8, 12, 25, 60 }) {
            assertEquals(count, CanvasLayouts.ringOffsets(count).size(), "for " + count);
        }
    }

    @Test
    void noTwoNeighboursLandOnTheSameSpot() {
        for (int count : new int[] { 2, 3, 8, 25, 60 }) {
            List<double[]> offsets = CanvasLayouts.ringOffsets(count);

            // A default node is 160 wide, so anything closer than that overlaps visibly. This is the
            // property the old behaviour failed outright: it put every new node in one row with no
            // regard for how many there were.
            assertTrue(closestPair(offsets) > 100,
                    "for " + count + " the closest pair was " + closestPair(offsets));
        }
    }

    @Test
    void neighboursSitFarEnoughOutToLeaveRoomForAnEdge() {
        for (double[] offset : CanvasLayouts.ringOffsets(9)) {
            assertTrue(radiusOf(offset) >= 200,
                    "a neighbour drawn on top of its term hides the arrow between them");
        }
    }

    @Test
    void moreNeighboursThanOneRingHoldsGoOntoFurtherRings() {
        List<double[]> many = CanvasLayouts.ringOffsets(40);

        double innermost = Double.MAX_VALUE;
        double outermost = 0;
        for (double[] offset : many) {
            innermost = Math.min(innermost, radiusOf(offset));
            outermost = Math.max(outermost, radiusOf(offset));
        }
        // Forty neighbours cannot fit on one ring without touching, so the answer must use more than
        // one - the alternative is a ring so crowded that it is the row it replaced, bent.
        assertTrue(outermost > innermost + 100,
                "innermost " + innermost + ", outermost " + outermost);
    }

    @Test
    void theSameCountAlwaysGivesTheSamePlaces() {
        // Expanding, collapsing and expanding again should not shuffle the board.
        List<double[]> first = CanvasLayouts.ringOffsets(11);
        List<double[]> second = CanvasLayouts.ringOffsets(11);

        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i)[0], second.get(i)[0], 1e-9);
            assertEquals(first.get(i)[1], second.get(i)[1], 1e-9);
        }
    }
}
