package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * What a board undo brings back includes whether the board was arranged by hand, and which pattern
 * a frame was made for.
 *
 * <p>Found by a code review: Arrange > Outline and then Undo brought a hand-made arrangement back
 * with the flag cleared, so the next new class arranged it away again with no step to undo.
 */
class ArrangedByHandTest {

    @Test
    void copyFromKeepsTheFlag() {
        CanvasLayout snapshot = new CanvasLayout();
        snapshot.arrangedByHand = Boolean.TRUE;
        CanvasLayout live = new CanvasLayout();
        live.arrangedByHand = Boolean.FALSE;
        live.copyFrom(snapshot);
        assertEquals(Boolean.TRUE, live.arrangedByHand);
    }

    @Test
    void aFramesPatternIsCopied() {
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.label = "Measurement";
        frame.pattern = "some-pattern";
        assertEquals("some-pattern", frame.copy().pattern);
        CanvasLayout board = new CanvasLayout();
        board.frames.add(frame);
        assertEquals("some-pattern", board.copy().frames.get(0).pattern);
    }
}
