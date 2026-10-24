package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where to put a node that has no saved position, so it does not land on one that has.
 *
 * <p><b>Why terms were stacking.</b> {@code SchemaGraph.render} laid every unpositioned node in
 * one row from (40,40), stepping right, and that row was computed without looking at the nodes
 * that <em>did</em> have a stored position. On a board anybody had arranged, the first saved node
 * is usually near the top left - so a newly added term, and every derived node the projection
 * mints (a datatype, a class expression, a property chain), was placed exactly on top of it. The
 * row also ran off to the right for ever, so adding thirty terms put them in a line a reader had
 * to pan along.
 *
 * <p>So a slot is searched for instead of counted out: the first one on a lattice that touches
 * nothing already placed. Deterministic - the same board gives the same arrangement every time,
 * which matters because a render happens after every edit anywhere in Prot&eacute;g&eacute; and a
 * board that reshuffled itself each time would be unusable.
 *
 * <p>No Swing and no mxGraph here, for the same reason as {@link CanvasGesture} and
 * {@link CanvasPan}: this is arithmetic, it is wrong in ways a unit test catches, and the class
 * that calls it cannot be built in a headless test.
 */
public final class CanvasPlacement {

    /** Clear space left between two boxes, so they read as separate rather than merely not equal. */
    public static final int GAP = 40;

    /** Where the first slot sits, matching the margin the board has always used. */
    public static final int ORIGIN = 40;

    /**
     * How many slots a row holds before the search moves down.
     *
     * <p>A row rather than a square because a diagram is read left to right, and ten because a
     * board of a hundred new terms then forms a block that fits a screen at a readable zoom
     * instead of a line four thousand pixels long.
     */
    public static final int COLUMNS = 10;

    private CanvasPlacement() {
    }

    /**
     * The first lattice slot of this size that touches nothing in {@code taken}.
     *
     * <p>The caller adds each returned slot to {@code taken} before asking again, so a run of
     * unpositioned nodes fills the board instead of stacking in one place.
     *
     * @param taken  every box already on the board, saved or just placed; may be empty
     * @param width  the width of the box to place
     * @param height its height
     * @return the top-left corner of a free slot
     */
    public static java.awt.Point freeSlot(List<Rectangle> taken, int width, int height) {
        int stepX = Math.max(1, width + GAP);
        int stepY = Math.max(1, height + GAP);
        for (int row = 0; row < 10000; row++) {
            for (int column = 0; column < COLUMNS; column++) {
                int x = ORIGIN + column * stepX;
                int y = ORIGIN + row * stepY;
                if (isFree(taken, new Rectangle(x, y, width, height))) {
                    return new java.awt.Point(x, y);
                }
            }
        }
        // Ten thousand rows is a hundred thousand slots. Reaching the end means something is
        // very wrong upstream, and a position is still better than an exception.
        return new java.awt.Point(ORIGIN, ORIGIN);
    }

    /**
     * Whether a box can go here without touching anything.
     *
     * <p>Grown by the gap on every side before the test, so "free" means visibly clear rather
     * than one pixel apart - two boxes sharing an edge read as one wide box.
     */
    public static boolean isFree(List<Rectangle> taken, Rectangle slot) {
        Rectangle padded = new Rectangle(slot.x - GAP / 2, slot.y - GAP / 2,
                slot.width + GAP, slot.height + GAP);
        for (Rectangle already : taken) {
            if (already.intersects(padded)) {
                return false;
            }
        }
        return true;
    }
}
