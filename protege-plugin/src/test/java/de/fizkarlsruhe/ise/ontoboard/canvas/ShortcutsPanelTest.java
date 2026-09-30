package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The shortcut list, which is the thing that makes an inverted gesture defensible.
 *
 * <p>A list of keystrokes is only worth having if it is right. It is the document somebody believes
 * when the canvas appears not to work, so a stale row is worse than a missing one - it sends a person
 * looking for a fault in the plugin rather than in their own memory.
 *
 * <p>What can be checked here is narrow but real: that the two inverted gestures are present and
 * described, that no key is listed twice with different meanings, and that nothing describes itself in
 * one word. Whether each row matches the code that implements it cannot be asserted from here, and is
 * not pretended.
 */
class ShortcutsPanelTest {

    /**
     * The pan inversion is documented.
     *
     * <p>1.68.0 took the plain drag away from panning and gave it to region selection. That is the
     * reverse of what this canvas did for its whole life before, and of what {@code mxGraph} does out
     * of the box. If this row ever disappears while the behaviour stays, the canvas has a gesture
     * nobody can discover.
     */
    @Test
    void theTwoInvertedGesturesAreExplained() {
        String drag = null;
        String pan = null;
        for (String[] row : ShortcutsPanel.rows()) {
            if ("Drag".equals(row[0])) {
                drag = row[1];
            }
            if ("Space-drag".equals(row[0])) {
                pan = row[1];
            }
        }
        assertTrue(drag != null && drag.toLowerCase().contains("select"),
                "a plain drag selects a region now, and the list has to say so: " + drag);
        assertTrue(pan != null && pan.toLowerCase().contains("pan"),
                "panning moved to space, the middle button and the right: " + pan);
        assertTrue(pan.contains("middle") || pan.contains("right"),
                "the other two pan triggers belong in the same row: " + pan);
    }

    /** No key means two different things, which is the easiest way for this list to go wrong. */
    @Test
    void noKeyIsListedTwice() {
        Set<String> seen = new HashSet<String>();
        for (String[] row : ShortcutsPanel.rows()) {
            assertTrue(seen.add(row[0]), row[0] + " is listed more than once");
        }
    }

    /**
     * Every row says something, and nothing says it in one word.
     *
     * <p>Counted in words rather than characters. The first version of this used a character floor
     * and rejected "Actual size" for Ctrl+0 - which is eleven characters and exactly the right
     * phrasing. A threshold that pushes somebody to pad a clear line is worse than none.
     */
    @Test
    void everyRowExplainsItself() {
        for (String[] row : ShortcutsPanel.rows()) {
            assertFalse(row[0].trim().isEmpty(), "a row with no key");
            assertTrue(row[1].trim().split("\\s+").length >= 2,
                    row[0] + " explains itself in one word: " + row[1]);
        }
    }

    /**
     * Undo's scope is stated.
     *
     * <p>The one thing about this canvas that is most easily misread: Ctrl+Z here undoes the board,
     * not the ontology. A user who believes otherwise will press it after retracting an axiom and
     * conclude the plugin has lost their work.
     */
    @Test
    void undoSaysWhatItUndoes() {
        String undo = null;
        for (String[] row : ShortcutsPanel.rows()) {
            if (row[0].startsWith("Ctrl+Z")) {
                undo = row[1];
            }
        }
        assertTrue(undo != null && undo.toLowerCase().contains("board"),
                "Ctrl+Z undoes the board, and the row has to say which: " + undo);
    }

    /** Delete says what survives it, which is the sentence the whole plugin keeps repeating. */
    @Test
    void deleteSaysTheAxiomsStay() {
        String delete = null;
        for (String[] row : ShortcutsPanel.rows()) {
            if ("Delete".equals(row[0])) {
                delete = row[1];
            }
        }
        assertTrue(delete != null && delete.toLowerCase().contains("axiom"),
                "Delete takes things off the board and leaves the axioms: " + delete);
    }

    /** The panel builds without a display, which is where the in-host self-test would find a null. */
    @Test
    void thePanelBuilds() {
        ShortcutsPanel panel = new ShortcutsPanel();

        assertTrue(panel.getComponentCount() >= ShortcutsPanel.documentedCount(),
                "every row should have reached the panel");
    }
}
