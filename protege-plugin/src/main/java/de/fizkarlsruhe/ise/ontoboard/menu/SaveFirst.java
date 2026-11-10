package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.Component;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import org.protege.editor.owl.model.OWLModelManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * "Save first?" - asked by Build and Release when the open ontology has changes that are not
 * saved.
 *
 * <p><b>The save happens while the question is still open.</b> It used to happen after it had
 * closed, and in a project opened with Open existing ODK project... that raised Protege's own
 * "An ontology has changed outside of Protege ... you will lose local changes" warning, with Yes
 * as the default, over a file Protege had just saved itself. Protege watches the files it has open
 * and looks at them whenever its window is activated; closing the question activated the window
 * while the save was running, after the file was written and before Protege had noted the new
 * time - its log said "Saving" at 11:33:15 and "Saved ontology" only at 11:37:46, after the
 * warning had been answered. With the question open, the window behind it cannot be activated
 * until the question closes, and by then the save is done and noted. Build was where this was
 * found, reproduced four times in two sessions.
 */
final class SaveFirst {

    /** What came of the question. */
    enum Answer {
        /** Saved; go on. */
        SAVED,
        /** Not saved, and go on all the same - the second choice, where there is one. */
        NOT_SAVED,
        /** Stop: cancelled, closed, or the save failed and that was said. */
        CANCELLED
    }

    private SaveFirst() {
    }

    /** Whether the open ontology has changes that are not saved. */
    static boolean isNeeded(OWLModelManager modelManager) {
        OWLOntology open = modelManager.getActiveOntology();
        return open != null && modelManager.getDirtyOntologies().contains(open);
    }

    /**
     * Asks, and saves when that is the answer.
     *
     * @param saveLabel the first choice, which saves: "Save and build"
     * @param withoutLabel the choice that goes on without saving, or null to offer none
     * @param cannotTitle the title of the message when the save fails: "Cannot build"
     */
    static Answer ask(Component parent, final OWLModelManager modelManager, String message,
            String saveLabel, String withoutLabel, String cannotTitle) {
        final OWLOntology open = modelManager.getActiveOntology();
        final String[] failure = new String[1];
        JOptionPane pane = question(message, saveLabel, withoutLabel, () -> {
            try {
                modelManager.save(open);
            } catch (Exception cannotSave) {
                failure[0] = cannotSave.getMessage() == null
                        ? cannotSave.getClass().getSimpleName() : cannotSave.getMessage();
            }
        });
        JDialog dialog = pane.createDialog(parent, "Save first?");
        dialog.setVisible(true);
        dialog.dispose();
        Answer answer = answerOf(pane);
        if (answer == Answer.SAVED && failure[0] != null) {
            JOptionPane.showMessageDialog(parent, "Could not save: " + failure[0], cannotTitle,
                    JOptionPane.WARNING_MESSAGE);
            return Answer.CANCELLED;
        }
        return answer;
    }

    /**
     * The question, its first button running {@code save} before the answer is given - which is
     * what closes the dialog. Escape and the close box answer as Cancel does.
     */
    static JOptionPane question(String message, String saveLabel, String withoutLabel,
            final Runnable save) {
        final JButton saving = new JButton(saveLabel);
        final JButton without = withoutLabel == null ? null : new JButton(withoutLabel);
        final JButton cancel = new JButton("Cancel");
        Object[] options = without == null ? new Object[] {saving, cancel}
                : new Object[] {saving, without, cancel};
        final JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE,
                JOptionPane.DEFAULT_OPTION, null, options, saving) {
            private static final long serialVersionUID = 1L;

            // Release's sentence came out over 1000 px on one line; broken at words instead.
            @Override
            public int getMaxCharactersPerLineCount() {
                return 72;
            }
        };
        saving.addActionListener(event -> {
            save.run();
            pane.setValue(saving);
        });
        if (without != null) {
            without.addActionListener(event -> pane.setValue(without));
        }
        cancel.addActionListener(event -> pane.setValue(cancel));
        return pane;
    }

    /** What the answer given to {@link #question} means. */
    static Answer answerOf(JOptionPane pane) {
        Object[] options = pane.getOptions();
        Object value = pane.getValue();
        if (value == null || options == null || options.length == 0) {
            return Answer.CANCELLED;
        }
        if (value == options[0]) {
            return Answer.SAVED;
        }
        if (options.length == 3 && value == options[1]) {
            return Answer.NOT_SAVED;
        }
        return Answer.CANCELLED;
    }
}
