package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Nodes that arrive without a position have to land somewhere free.
 *
 * <p>The reported symptom was terms on top of each other. The cause: the placement was a counter
 * stepping right from (40,40) that never looked at the saved positions, so a new term went
 * exactly where an arranged one already was - and every derived node (a datatype, a class
 * expression, a property chain) arrives unpositioned on every render, not just once.
 */
class CanvasPlacementTest {

    private static final int W = 160;
    private static final int H = 60;

    /** The first node on an empty board goes to the origin. */
    @Test
    void theFirstSlotIsTheOrigin() {
        Point where = CanvasPlacement.freeSlot(new ArrayList<Rectangle>(), W, H);

        assertEquals(CanvasPlacement.ORIGIN, where.x);
        assertEquals(CanvasPlacement.ORIGIN, where.y);
    }

    /** A node never lands on a node that already has a saved position. */
    @Test
    void anUnpositionedNodeAvoidsASavedOne() {
        List<Rectangle> taken = new ArrayList<Rectangle>(Collections.singletonList(
                new Rectangle(CanvasPlacement.ORIGIN, CanvasPlacement.ORIGIN, W, H)));

        Point where = CanvasPlacement.freeSlot(taken, W, H);

        assertFalse(new Rectangle(where.x, where.y, W, H).intersects(taken.get(0)),
                "placed on top of the saved node at the origin: " + where);
    }

    /** Several in a row each get their own slot, so they do not all take the same one. */
    @Test
    void severalUnpositionedNodesEachGetTheirOwnSlot() {
        List<Rectangle> taken = new ArrayList<Rectangle>();
        List<Rectangle> placed = new ArrayList<Rectangle>();
        for (int at = 0; at < 25; at++) {
            Point where = CanvasPlacement.freeSlot(taken, W, H);
            Rectangle box = new Rectangle(where.x, where.y, W, H);
            placed.add(box);
            taken.add(box);
        }

        for (int first = 0; first < placed.size(); first++) {
            for (int second = first + 1; second < placed.size(); second++) {
                assertFalse(placed.get(first).intersects(placed.get(second)),
                        "two nodes overlap: " + placed.get(first) + " and " + placed.get(second));
            }
        }
    }

    /**
     * They form a block rather than one long line.
     *
     * <p>A row that never wrapped put thirty terms four thousand pixels apart, which a reader has
     * to pan along to see at all.
     */
    @Test
    void theyWrapIntoABlock() {
        List<Rectangle> taken = new ArrayList<Rectangle>();
        int lowest = 0;
        for (int at = 0; at < CanvasPlacement.COLUMNS + 3; at++) {
            Point where = CanvasPlacement.freeSlot(taken, W, H);
            taken.add(new Rectangle(where.x, where.y, W, H));
            lowest = Math.max(lowest, where.y);
        }

        assertTrue(lowest > CanvasPlacement.ORIGIN,
                "more than one row's worth of nodes stayed on one row");
    }

    /** A slot touching another box edge to edge is not free: two boxes would read as one. */
    @Test
    void touchingIsNotFree() {
        List<Rectangle> taken = Arrays.asList(new Rectangle(100, 100, W, H));

        assertFalse(CanvasPlacement.isFree(taken, new Rectangle(100 + W, 100, W, H)),
                "flush against the right edge of an existing box");
        assertTrue(CanvasPlacement.isFree(taken,
                        new Rectangle(100 + W + CanvasPlacement.GAP, 100, W, H)),
                "a gap away is free");
    }

    /**
     * The same board gives the same answer twice.
     *
     * <p>A render happens after every edit anywhere in Prot&eacute;g&eacute;. A placement that
     * varied would reshuffle the board under the reader on an edit they made elsewhere.
     */
    @Test
    void placementIsDeterministic() {
        List<Rectangle> taken = Arrays.asList(new Rectangle(40, 40, W, H),
                new Rectangle(500, 300, W, H));

        assertEquals(CanvasPlacement.freeSlot(taken, W, H),
                CanvasPlacement.freeSlot(taken, W, H));
    }
}
