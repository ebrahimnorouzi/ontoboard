package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JOptionPane;
import org.junit.jupiter.api.Test;

/**
 * "Save first?", without showing it: the order the save and the answer come in, and what each
 * answer means.
 */
class SaveFirstTest {

    private static AbstractButton button(JOptionPane pane, String text) {
        for (Object option : pane.getOptions()) {
            if (option instanceof AbstractButton && text.equals(((AbstractButton) option)
                    .getText())) {
                return (AbstractButton) option;
            }
        }
        throw new AssertionError("no button " + text);
    }

    /**
     * The save runs while the question is still unanswered - still open.
     *
     * <p>Answering closes the dialog, closing it activates Protege's window, and an activated
     * window is when Protege looks for files changed behind its back. Saving after the answer
     * put the save in the middle of that look: a false "changed outside of Protege ... you will
     * lose local changes" in a reopened project, every time.
     */
    @Test
    void theSaveHappensBeforeTheAnswerClosesTheQuestion() {
        final List<Object> seen = new ArrayList<Object>();
        final JOptionPane[] pane = new JOptionPane[1];
        pane[0] = SaveFirst.question("Unsaved.", "Save and build", "Build the saved file",
                () -> seen.add(pane[0].getValue()));

        button(pane[0], "Save and build").doClick(0);

        assertEquals(1, seen.size(), "saved once");
        assertEquals(JOptionPane.UNINITIALIZED_VALUE, seen.get(0),
                "the question was still open when it saved");
        assertEquals(SaveFirst.Answer.SAVED, SaveFirst.answerOf(pane[0]));
    }

    /** The other answers save nothing. */
    @Test
    void theOtherAnswersSaveNothing() {
        final List<String> saved = new ArrayList<String>();
        JOptionPane build = SaveFirst.question("Unsaved.", "Save and build",
                "Build the saved file", () -> saved.add("saved"));
        button(build, "Build the saved file").doClick(0);
        assertEquals(SaveFirst.Answer.NOT_SAVED, SaveFirst.answerOf(build));

        JOptionPane cancelled = SaveFirst.question("Unsaved.", "Save and build",
                "Build the saved file", () -> saved.add("saved"));
        button(cancelled, "Cancel").doClick(0);
        assertEquals(SaveFirst.Answer.CANCELLED, SaveFirst.answerOf(cancelled));

        // Escape and the close box, as the option pane answers them.
        JOptionPane escaped = SaveFirst.question("Unsaved.", "Save and build", null,
                () -> saved.add("saved"));
        escaped.setValue(JOptionPane.CLOSED_OPTION);
        assertEquals(SaveFirst.Answer.CANCELLED, SaveFirst.answerOf(escaped));
        escaped.setValue(null);
        assertEquals(SaveFirst.Answer.CANCELLED, SaveFirst.answerOf(escaped));

        assertTrue(saved.isEmpty(), String.valueOf(saved));
    }

    /** Release offers no "without saving": a release the edit file cannot make again is none. */
    @Test
    void withoutASecondChoiceThereAreTwoButtons() {
        JOptionPane release = SaveFirst.question("Unsaved.", "Save and release", null, () -> { });
        assertEquals(2, release.getOptions().length);
        assertEquals(release.getOptions()[0], release.getInitialValue(), "saving is the default");
        button(release, "Cancel").doClick(0);
        assertEquals(SaveFirst.Answer.CANCELLED, SaveFirst.answerOf(release));
    }
}
