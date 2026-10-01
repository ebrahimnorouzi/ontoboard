package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>What can be checked here is narrow but real: that both halves of the gesture mode are present
 * and described, that no key is listed twice with different meanings, and that nothing describes
 * itself in one word. Whether each row matches the code that implements it cannot be asserted from here, and is
 * not pretended.
 */
class ShortcutsPanelTest {

    /**
     * Both halves of the gesture mode are documented, and so is the switch between them.
     *
     * <p>What a plain drag does has been decided in both directions: it panned until 1.68.0,
     * selected until 1.73.0, and pans again. A canvas whose primary gesture can change has to say
     * somewhere what it is currently doing and how to get the other one, or the change is
     * indistinguishable from a fault. This list is that somewhere.
     *
     * <p>Checked against {@link CanvasGesture#DEFAULT} rather than against the word "pan", so
     * that flipping the default without rewriting this row fails here rather than in the field.
     */
    @Test
    void bothHalvesOfTheGestureModeAreExplained() {
        String drag = null;
        String marquee = null;
        String space = null;
        String mode = null;
        for (String[] row : ShortcutsPanel.rows()) {
            if ("Drag".equals(row[0])) {
                drag = row[1];
            }
            if ("Shift-drag".equals(row[0])) {
                marquee = row[1];
            }
            if ("Space-drag".equals(row[0])) {
                space = row[1];
            }
            if (row[0].contains("H") && row[0].contains("V")) {
                mode = row[1];
            }
        }
        assertEquals(CanvasGesture.Mode.PAN, CanvasGesture.DEFAULT,
                "this row describes the default, so the two have to be changed together");
        assertTrue(drag != null && drag.toLowerCase().contains("move the board"),
                "a plain drag moves the board, and the list has to say so: " + drag);
        assertTrue(marquee != null && marquee.toLowerCase().contains("select"),
                "the other gesture is a modifier away, and that is the half people lose: "
                        + marquee);
        assertTrue(space != null && space.toLowerCase().contains("pan"),
                "space pans in both modes: " + space);
        assertTrue(space.contains("middle") || space.contains("right"),
                "the other two pan triggers belong in the same row: " + space);
        assertTrue(mode != null && mode.toLowerCase().contains("drag"),
                "the keys that switch the mode have to say what the mode changes: " + mode);
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
