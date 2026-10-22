package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.junit.jupiter.api.Test;

/**
 * Whether a drag has anywhere to move the board to, and whether it counts as a drag.
 *
 * <p>Here rather than on the component because the component cannot be constructed in a headless
 * test at all: {@code mxGraphHandler} builds a {@code java.awt.dnd.DragSource} and that throws
 * {@code HeadlessException}. So the two numbers that decide whether a board can be dragged are
 * pinned here, and the wiring is covered only by the host smoke.
 *
 * <p>The case that matters is the first one: a board smaller than the window. That is the state
 * every board is in after adding a handful of terms, it is when somebody first tries to drag, and
 * it is where the library gives a scroll range of zero.
 */
class CanvasPanTest {

    /** A window, in pixels. */
    private static final int WIDE = 800;
    private static final int TALL = 600;

    /**
     * A board smaller than the window still gets room to move - the whole point of this class.
     *
     * <p>Measured before the fix: content 161x81 against a viewport of 782x582 moved the board 0px
     * for 100px of cursor travel. The room has to exceed the viewport for the viewport to have any
     * scroll range at all, so that is what is asserted rather than a particular size.
     */
    @Test
    void aBoardSmallerThanTheWindowStillHasRoomToMove() {
        Dimension room = CanvasPan.room(161, 81, WIDE, TALL, 1.0);

        assertTrue(room.width > WIDE, "no horizontal scroll range: " + room);
        assertTrue(room.height > TALL, "no vertical scroll range: " + room);
    }

    /** An empty board too, since the content bounds are then zero. */
    @Test
    void anEmptyBoardHasRoomToMove() {
        Dimension room = CanvasPan.room(0, 0, WIDE, TALL, 1.0);

        assertTrue(room.width > WIDE && room.height > TALL, "" + room);
    }

    /**
     * The slack beyond the content is about a windowful, not a constant.
     *
     * <p>A fixed four thousand pixels would put a permanent multi-thousand-pixel scrollbar range
     * on a board with three terms on it. Proportional to the window keeps the thumb meaningful,
     * which is the only thing a scrollbar is for once the drag works.
     */
    @Test
    void theSlackIsAWindowfulBeyondTheContent() {
        Dimension room = CanvasPan.room(3000, 2000, WIDE, TALL, 1.0);

        assertTrue(room.width >= 3000 + WIDE, "not a windowful wider: " + room);
        assertTrue(room.width < 3000 + WIDE + CanvasPan.QUANTUM, "more than a windowful: " + room);
        assertTrue(room.height >= 2000 + TALL && room.height < 2000 + TALL + CanvasPan.QUANTUM,
                "" + room);
    }

    /**
     * The room is in board units, so it is divided by the scale.
     *
     * <p>Read from the bytecode of {@code mxGraphControl.updatePreferredSize}: the minimum is
     * applied as {@code max(size, round(min.getWidth() * scale))}. A room computed in pixels and
     * handed over unconverted would be four times too large at 400% zoom and a quarter of what is
     * needed at 25%, and the content bounds this takes are already scaled - that is what
     * {@code mxGraphView.getGraphBounds()} returns.
     */
    @Test
    void theRoomIsInBoardUnitsSoItShrinksWithTheScale() {
        Dimension plain = CanvasPan.room(1600, 1200, WIDE, TALL, 1.0);
        Dimension zoomedIn = CanvasPan.room(1600, 1200, WIDE, TALL, 2.0);

        assertEquals(plain.width / 2, zoomedIn.width, "at 200% the same pixels are half the board");
        assertEquals(plain.height / 2, zoomedIn.height, "" + zoomedIn);
    }

    /**
     * Every size is a whole number of quanta.
     *
     * <p>Not tidiness. Giving the control a new preferred size can make a scrollbar appear, which
     * shrinks the viewport by the scrollbar's thickness, which computes a different room, which
     * could take the scrollbar away again. Quantising absorbs a change of that size so the second
     * pass agrees with the first.
     */
    @Test
    void theRoomIsQuantisedSoTheLayoutSettles() {
        int scrollbar = 17;
        Dimension full = CanvasPan.room(400, 300, WIDE, TALL, 1.0);
        Dimension narrower = CanvasPan.room(400, 300, WIDE - scrollbar, TALL - scrollbar, 1.0);

        assertEquals(0, full.width % CanvasPan.QUANTUM, "" + full);
        assertEquals(0, full.height % CanvasPan.QUANTUM, "" + full);
        assertEquals(full, narrower, "a scrollbar's width changed the answer, so the layout "
                + "could oscillate between showing and hiding one");
    }

    /**
     * A board with content above the origin gets pushed into positive space.
     *
     * <p>The reported symptom was "the upper part of the canvas is not shown, even when I
     * dragged it". A real board in this repository had four of nine nodes at a negative y, the
     * topmost at -400 - and a negative origin is SUBTRACTED from the control's preferred size
     * ({@code round(bounds.x + bounds.width)}), so that region is not in the scrollable area at
     * all. Measured: content spanning 880x762 from (-120,-400) gave a control of 761x363.
     * Dragging cannot reach what is not there to scroll to.
     */
    @Test
    void contentAboveTheOriginIsPushedBackIntoView() {
        java.awt.Point shift = CanvasPan.shiftFor(-120, -400);

        assertTrue(shift.x >= 120, "the leftmost node has to land at a positive x: " + shift);
        assertTrue(shift.y >= 400, "and the topmost at a positive y: " + shift);
        assertEquals(120 + CanvasPan.MARGIN, shift.x, "with a margin, not flush against the edge");
        assertEquals(400 + CanvasPan.MARGIN, shift.y, "" + shift);
    }

    /**
     * A board that is already wholly positive is left exactly as it was.
     *
     * <p>Shifting it would move every node on screen for no reason, and a reader who has
     * arranged a diagram would find it had slid.
     */
    @Test
    void anOrdinaryBoardIsNotMoved() {
        assertEquals(new java.awt.Point(0, 0), CanvasPan.shiftFor(40, 40));
        assertEquals(new java.awt.Point(0, 0), CanvasPan.shiftFor(0, 0));
    }

    /** One side negative shifts only that side. */
    @Test
    void onlyTheNegativeAxisIsShifted() {
        assertEquals(new java.awt.Point(0, 100 + CanvasPan.MARGIN),
                CanvasPan.shiftFor(300, -100));
        assertEquals(new java.awt.Point(50 + CanvasPan.MARGIN, 0),
                CanvasPan.shiftFor(-50, 10));
    }

    /** No viewport yet means "ask again later", not a room of zero. */
    @Test
    void anUnlaidOutViewportIsNotAnAnswer() {
        assertEquals(new Dimension(0, 0), CanvasPan.room(100, 100, 0, 0, 1.0));
        assertEquals(new Dimension(0, 0), CanvasPan.room(100, 100, WIDE, TALL, 0.0));
        assertEquals(new Dimension(0, 0), CanvasPan.room(100, 100, WIDE, TALL, -1.0));
    }

    /**
     * A pan is a drag; a click is not.
     *
     * <p>This is what stops a pan from clearing the selection. The library asked the same question
     * in the graph control's coordinates, where a successful pan ends on the pixel it started on -
     * the board moved underneath the cursor - so the answer was always "not a drag", the release
     * was never consumed, and the drag handler cleared the selection behind it. Measured: one cell
     * selected before a 100px pan, none after.
     */
    @Test
    void aPanCountsAsADragAndAClickDoesNot() {
        int tolerance = 4;

        assertFalse(CanvasPan.movedFarEnough(100, 100, 100, 100, tolerance), "a click is not a drag");
        assertFalse(CanvasPan.movedFarEnough(100, 100, 104, 104, tolerance),
                "a tremor inside the tolerance is not a drag");
        assertTrue(CanvasPan.movedFarEnough(100, 100, 105, 100, tolerance), "horizontal only");
        assertTrue(CanvasPan.movedFarEnough(100, 100, 100, 105, tolerance), "vertical only");
        assertTrue(CanvasPan.movedFarEnough(200, 200, 100, 100, tolerance), "and backwards");
    }
}
