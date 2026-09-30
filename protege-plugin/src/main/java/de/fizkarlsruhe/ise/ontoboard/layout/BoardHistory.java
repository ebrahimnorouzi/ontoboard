package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Undo and redo for the things the board owns.
 *
 * <p>Prot&eacute;g&eacute;'s own undo covers the ontology and nothing else, which leaves everything this
 * canvas is for outside it: an arrangement of forty classes, which terms are on the board, a sticky
 * note's text, a frame's size. A misdirected <em>Arrange</em> replaced a layout somebody had spent an
 * afternoon on, and the only way back was to do it again by hand. <em>Add all</em> on a large ontology
 * was the same in reverse - one click, and the only way out was to remove the terms one at a time.
 *
 * <p>Snapshots rather than commands. A board's whole state is one small DTO - a list of identifiers,
 * a map of rectangles, two lists of annotations - so copying it costs less than the bookkeeping of an
 * inverse operation per action, and it cannot drift: a new board action gets undo for free the moment it
 * records, and there is no second implementation of "the opposite of expanding" to get wrong. The cost
 * is that undo is coarse - it restores the whole board, not one node - which for board state is what a
 * user means anyway.
 *
 * <p><b>What this deliberately does not undo: the ontology.</b> Removing a term from the board and
 * deleting a class are different actions in this plugin and the status line has always said which
 * happened; undo keeps that line. Restoring a term to the board does not restore an axiom somebody
 * retracted in between, and the caller says so in words, because a partial undo that looked total would
 * be worse than none.
 */
public final class BoardHistory {

    /**
     * How many steps back it goes.
     *
     * <p>Enough to cover a session's worth of arranging and a mistake found a few actions later. Each
     * entry is a whole board, so the bound is what keeps a long session from holding hundreds of copies
     * of a large layout in memory.
     */
    public static final int LIMIT = 25;

    private final Deque<Step> undoable = new ArrayDeque<Step>();
    private final Deque<Step> redoable = new ArrayDeque<Step>();

    /** A board as it was, and the name of the action that was about to change it. */
    public static final class Step {
        private final String action;
        private final CanvasLayout board;

        Step(String action, CanvasLayout board) {
            this.action = action;
            this.board = board;
        }

        /** What the user did, phrased to complete "Undid: ..." - for instance "adding 7 terms". */
        public String getAction() {
            return action;
        }

        /** The board as it was before that action. */
        public CanvasLayout getBoard() {
            return board;
        }
    }

    /**
     * Remembers the board as it is now, before an action changes it.
     *
     * <p>Called before the change rather than after, so the snapshot is the state to go back to. A
     * copy is taken here rather than trusted from the caller: the board the canvas holds is mutated in
     * place by everything that touches it, so keeping the same instance would give a history whose
     * every entry was the present.
     *
     * <p>Recording clears the redo stack, which is what every editor does: once you have taken a new
     * path, the abandoned one is no longer reachable, and offering it would redo something the current
     * board knows nothing about.
     */
    public void record(String action, CanvasLayout board) {
        if (board == null) {
            return;
        }
        undoable.push(new Step(action == null ? "that" : action, board.copy()));
        while (undoable.size() > LIMIT) {
            undoable.removeLast();
        }
        redoable.clear();
    }

    /** Whether there is anything to go back to. */
    public boolean canUndo() {
        return !undoable.isEmpty();
    }

    /** Whether an undone action can be put back. */
    public boolean canRedo() {
        return !redoable.isEmpty();
    }

    /** What the next undo would undo, for a menu label, or null. */
    public String nextUndoAction() {
        return undoable.isEmpty() ? null : undoable.peek().getAction();
    }

    /** What the next redo would put back, for a menu label, or null. */
    public String nextRedoAction() {
        return redoable.isEmpty() ? null : redoable.peek().getAction();
    }

    /**
     * Steps back, given the board as it is now.
     *
     * <p>The current board is pushed onto the redo stack under the same action name, so a redo puts
     * back exactly what the undo took away.
     *
     * @return the step to restore, or null when there is nothing to undo
     */
    public Step undo(CanvasLayout current) {
        if (undoable.isEmpty() || current == null) {
            return null;
        }
        Step going = undoable.pop();
        redoable.push(new Step(going.getAction(), current.copy()));
        return going;
    }

    /**
     * Steps forward again.
     *
     * @return the step to restore, or null when there is nothing to redo
     */
    public Step redo(CanvasLayout current) {
        if (redoable.isEmpty() || current == null) {
            return null;
        }
        Step going = redoable.pop();
        undoable.push(new Step(going.getAction(), current.copy()));
        return going;
    }

    /**
     * Forgets everything.
     *
     * <p>Called when the open ontology changes. A history from another ontology would offer to restore
     * a board of identifiers that do not exist in this one, which the sidecar loader would then prune
     * to nothing - an undo that empties the board is the worst possible answer to "put it back".
     */
    public void clear() {
        undoable.clear();
        redoable.clear();
    }

    /** How many steps back are available, for tests and for a status line. */
    public int depth() {
        return undoable.size();
    }
}
