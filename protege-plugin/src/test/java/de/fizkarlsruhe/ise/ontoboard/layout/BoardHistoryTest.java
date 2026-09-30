package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Undo for the board.
 *
 * <p>The failure this prevents is the one nobody could recover from: an <em>Arrange</em> replaced every
 * position on the board at once, and until 1.62.0 there was no way back to the arrangement somebody had
 * spent an afternoon on. Prot&eacute;g&eacute;'s undo covers the ontology and none of this.
 *
 * <p>Two properties matter more than the stack mechanics, and both are ways a snapshot history quietly
 * fails: a snapshot that shares structure with the live board becomes the present the moment anything is
 * dragged, and a restore that replaces the board object instead of its contents leaves everything that
 * held the old reference editing a board nobody draws.
 */
class BoardHistoryTest {

    private static CanvasLayout boardWith(String... iris) {
        CanvasLayout board = new CanvasLayout();
        for (String iri : iris) {
            board.onCanvas.add(iri);
            board.nodes.put(iri, new CanvasLayout.NodeLayout(10, 20));
        }
        return board;
    }

    @Test
    void anEmptyHistoryHasNothingToUndo() {
        BoardHistory history = new BoardHistory();

        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertNull(history.nextUndoAction());
        assertNull(history.undo(new CanvasLayout()));
    }

    @Test
    void undoReturnsTheBoardAsItWasBeforeTheAction() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a", "b");

        history.record("adding c", board);
        board.onCanvas.add("c");

        BoardHistory.Step step = history.undo(board);

        assertEquals("adding c", step.getAction());
        assertEquals(2, step.getBoard().onCanvas.size(), step.getBoard().onCanvas.toString());
        assertFalse(step.getBoard().onCanvas.contains("c"));
    }

    /**
     * The snapshot is a copy, not a view.
     *
     * <p>The board is mutated in place by everything that touches it - positions are written straight
     * into {@code nodes} while a node is dragged - so a history that kept the same instance would have
     * every entry equal to the present, and undo would appear to do nothing at all.
     */
    @Test
    void aSnapshotDoesNotFollowLaterEdits() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a");

        history.record("moving a", board);
        board.nodes.get("a").x = 999;
        board.onCanvas.add("b");
        board.notes.add(new CanvasLayout.NoteLayout());

        CanvasLayout before = history.undo(board).getBoard();

        assertEquals(10, before.nodes.get("a").x, 1e-9);
        assertEquals(1, before.onCanvas.size());
        assertTrue(before.notes.isEmpty());
    }

    @Test
    void redoPutsBackWhatUndoTookAway() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a");
        history.record("adding b", board);
        board.onCanvas.add("b");

        board.copyFrom(history.undo(board).getBoard());
        assertEquals(1, board.onCanvas.size());

        BoardHistory.Step forward = history.redo(board);

        assertEquals("adding b", forward.getAction());
        assertEquals(2, forward.getBoard().onCanvas.size());
    }

    /** A new action after an undo abandons the redo path, as every editor does. */
    @Test
    void recordingClearsTheRedoStack() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a");
        history.record("adding b", board);
        board.onCanvas.add("b");
        board.copyFrom(history.undo(board).getBoard());
        assertTrue(history.canRedo());

        history.record("adding c", board);

        assertFalse(history.canRedo());
        assertNull(history.nextRedoAction());
    }

    @Test
    void severalStepsUndoInReverseOrder() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith();

        history.record("first", board);
        board.onCanvas.add("a");
        history.record("second", board);
        board.onCanvas.add("b");
        history.record("third", board);
        board.onCanvas.add("c");

        assertEquals("third", history.undo(board).getAction());
        assertEquals("second", history.undo(board).getAction());
        assertEquals("first", history.undo(board).getAction());
        assertFalse(history.canUndo());
    }

    /**
     * The stack is bounded.
     *
     * <p>Each entry is a whole board, and dragging a node fires movement events continuously - so
     * without a bound a long session would hold hundreds of copies of a large layout.
     */
    @Test
    void theHistoryIsBounded() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a");

        for (int i = 0; i < BoardHistory.LIMIT + 20; i++) {
            history.record("step " + i, board);
        }

        assertEquals(BoardHistory.LIMIT, history.depth());
        // And it is the OLDEST that were dropped: the most recent step is still the top.
        assertEquals("step " + (BoardHistory.LIMIT + 19), history.nextUndoAction());
    }

    @Test
    void clearingForgetsBothDirections() {
        BoardHistory history = new BoardHistory();
        CanvasLayout board = boardWith("a");
        history.record("something", board);
        board.copyFrom(history.undo(board).getBoard());

        history.clear();

        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals(0, history.depth());
    }

    @Test
    void nullsAreIgnoredRatherThanRecorded() {
        BoardHistory history = new BoardHistory();

        history.record("nothing", null);

        assertFalse(history.canUndo());
        assertNull(history.undo(null));
        assertNull(history.redo(null));
    }

    /** An unnamed action still reads as a sentence, since the status line quotes it. */
    @Test
    void anUnnamedActionGetsAWord() {
        BoardHistory history = new BoardHistory();
        history.record(null, new CanvasLayout());

        assertEquals("that", history.nextUndoAction());
    }

    // ---------- the copy the history depends on ----------

    @Test
    void copyingABoardSharesNothingWithIt() {
        CanvasLayout board = boardWith("a");
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "ontoboard-note-1";
        note.text = "check this";
        board.notes.add(note);
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = "ontoboard-frame-1";
        frame.label = "toppings";
        board.frames.add(frame);
        board.prefixColors.put("http://example.org/", "#123456");

        CanvasLayout copy = board.copy();

        assertNotSame(board.onCanvas, copy.onCanvas);
        assertNotSame(board.nodes, copy.nodes);
        assertNotSame(board.notes, copy.notes);
        assertNotSame(board.frames, copy.frames);
        assertNotSame(board.prefixColors, copy.prefixColors);
        // And the nested objects, which is the half a shallow copy gets wrong.
        assertNotSame(board.nodes.get("a"), copy.nodes.get("a"));
        assertNotSame(board.notes.get(0), copy.notes.get(0));
        assertNotSame(board.frames.get(0), copy.frames.get(0));

        board.notes.get(0).text = "changed";
        board.nodes.get("a").x = 500;
        assertEquals("check this", copy.notes.get(0).text);
        assertEquals(10, copy.nodes.get("a").x, 1e-9);
    }

    @Test
    void copyKeepsEverythingWorthKeeping() {
        CanvasLayout board = boardWith("a", "b");
        board.ontologyIri = "http://example.org/o";
        board.version = 1;
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = "n1";
        note.text = "words";
        note.x = 7;
        note.fontSize = 18;
        board.notes.add(note);

        CanvasLayout copy = board.copy();

        assertEquals("http://example.org/o", copy.ontologyIri);
        assertEquals(1, copy.version);
        assertEquals(2, copy.onCanvas.size());
        assertEquals("words", copy.notes.get(0).text);
        assertEquals(7, copy.notes.get(0).x, 1e-9);
        assertEquals(18, copy.notes.get(0).fontSize);
    }

    /**
     * Restoring keeps the same board object.
     *
     * <p>{@code CanvasMembership} and the position capture are handed this instance and keep the
     * reference. A restore that replaced the field would leave membership editing a board nobody draws -
     * an undo that looks correct until the next action puts everything back.
     */
    @Test
    void copyFromReplacesContentsAndNotTheObject() {
        CanvasLayout live = boardWith("a", "b");
        java.util.List<String> sameListAsBefore = live.onCanvas;
        CanvasLayout remembered = boardWith("a");

        live.copyFrom(remembered);

        assertSameList(sameListAsBefore, live.onCanvas);
        assertEquals(1, live.onCanvas.size());
        assertFalse(live.nodes.containsKey("b"));
        // And it took a copy on the way in, so the remembered board is still available unchanged.
        live.onCanvas.add("c");
        assertEquals(1, remembered.onCanvas.size());
    }

    private static void assertSameList(java.util.List<String> expected, java.util.List<String> actual) {
        assertTrue(expected == actual, "copyFrom must not replace the collections the view holds");
    }

    @Test
    void copyFromNullChangesNothing() {
        CanvasLayout live = boardWith("a");

        live.copyFrom(null);

        assertEquals(1, live.onCanvas.size());
    }
}
