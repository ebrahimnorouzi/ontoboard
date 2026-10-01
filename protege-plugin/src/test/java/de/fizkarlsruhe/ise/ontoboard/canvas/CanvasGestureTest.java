package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a plain left-drag does.
 *
 * <p>This is here because the decision is made in two places that cannot be tested - the graph
 * component's {@code isPanningEvent} and the view's rubberband - and because the failure mode is
 * silent. If both claim a drag, the board pans while a selection rectangle is drawn across it; if
 * neither does, the drag does nothing at all and the canvas reads as frozen. Both have happened
 * on this canvas.
 */
class CanvasGestureTest {

    private static final boolean[] BOTH = {false, true};

    /**
     * On empty board, exactly one of the two gestures claims the drag. Every combination.
     *
     * <p>Sixteen cases, because reading the two methods side by side is how a gap gets missed -
     * and a gap is invisible until somebody drags on the one combination nobody tried.
     */
    @Test
    void exactlyOneGestureClaimsEveryDragOnEmptyBoard() {
        for (CanvasGesture.Mode mode : CanvasGesture.Mode.values()) {
            for (boolean space : BOTH) {
                for (boolean additive : BOTH) {
                    boolean pans = CanvasGesture.leftDragPans(mode, space, additive, false);
                    boolean selects = CanvasGesture.leftDragSelects(mode, space, additive, false);

                    assertTrue(pans ^ selects,
                            "mode=" + mode + " space=" + space + " additive=" + additive
                                    + " pans=" + pans + " selects=" + selects
                                    + " - one must claim the drag, and only one");
                }
            }
        }
    }

    /**
     * A drag that starts on a cell moves the cell, whatever is held.
     *
     * <p>No modifier may change this. A gesture that sometimes moved a node and sometimes moved
     * the board depending on a key would make every drag a gamble, and the node would be the
     * thing that moved when you did not mean it to.
     */
    @Test
    void aDragOnACellIsNeverPanOrMarquee() {
        for (CanvasGesture.Mode mode : CanvasGesture.Mode.values()) {
            for (boolean space : BOTH) {
                for (boolean additive : BOTH) {
                    assertFalse(CanvasGesture.leftDragPans(mode, space, additive, true));
                    assertFalse(CanvasGesture.leftDragSelects(mode, space, additive, true));
                }
            }
        }
    }

    /** The default is panning, which is what the request asked for and what 1.67.0 did. */
    @Test
    void theDefaultIsPan() {
        assertEquals(CanvasGesture.Mode.PAN, CanvasGesture.DEFAULT);
        assertTrue(CanvasGesture.leftDragPans(CanvasGesture.DEFAULT, false, false, false),
                "a plain drag on empty board moves the board");
    }

    /**
     * Space pans in both modes.
     *
     * <p>The one gesture that has never changed meaning on this canvas, and the one the first-run
     * hint has always named. Someone who learned it in 1.68.0 must not have it taken away by a
     * mode they did not know existed.
     */
    @Test
    void spaceAlwaysPans() {
        for (CanvasGesture.Mode mode : CanvasGesture.Mode.values()) {
            assertTrue(CanvasGesture.leftDragPans(mode, true, false, false), mode.toString());
            assertTrue(CanvasGesture.leftDragPans(mode, true, true, false),
                    mode + " with a modifier held");
        }
    }

    /** A modifier reaches the other gesture, whichever mode you are in. */
    @Test
    void aModifierReachesTheOtherGesture() {
        assertTrue(CanvasGesture.leftDragSelects(CanvasGesture.Mode.PAN, false, true, false),
                "Shift or Ctrl selects a region while panning is the default");
        assertTrue(CanvasGesture.leftDragSelects(CanvasGesture.Mode.SELECT, false, false, false),
                "and in select mode a plain drag already does it");
    }

    /** A stored preference comes back, and anything else falls back rather than throwing. */
    @Test
    void theStoredChoiceRoundTrips() {
        for (CanvasGesture.Mode mode : CanvasGesture.Mode.values()) {
            assertEquals(mode, CanvasGesture.byName(mode.name()));
            assertEquals(mode, CanvasGesture.byName(mode.name().toLowerCase()));
        }
        assertEquals(CanvasGesture.DEFAULT, CanvasGesture.byName(null));
        assertEquals(CanvasGesture.DEFAULT, CanvasGesture.byName(""));
        assertEquals(CanvasGesture.DEFAULT, CanvasGesture.byName("lasso"));
    }

    /** A null mode behaves as the default rather than throwing from a mouse event. */
    @Test
    void aMissingModeIsTheDefault() {
        assertEquals(CanvasGesture.leftDragPans(CanvasGesture.DEFAULT, false, false, false),
                CanvasGesture.leftDragPans(null, false, false, false));
        assertEquals(CanvasGesture.leftDragSelects(CanvasGesture.DEFAULT, false, false, false),
                CanvasGesture.leftDragSelects(null, false, false, false));
    }

    /**
     * Each mode's help names both gestures.
     *
     * <p>It is the tooltip on the button and the sentence on the status line when the mode
     * changes, and a mode that only says what it does leaves the other half undiscoverable - the
     * exact complaint that produced this change.
     */
    @Test
    void eachModeExplainsBothGestures() {
        for (CanvasGesture.Mode mode : CanvasGesture.Mode.values()) {
            String help = mode.getHelp().toLowerCase();
            assertTrue(help.contains("drag"), mode + ": " + help);
            assertTrue(help.contains("select"), mode + " must name selecting: " + help);
            assertTrue(help.contains("board"), mode + " must name moving the board: " + help);
            assertEquals(mode.other().other(), mode);
        }
    }
}
