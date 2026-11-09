package de.fizkarlsruhe.ise.ontoboard.menu;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;

/**
 * Escape, for the dialogs OntoBoard builds out of a plain {@code JDialog}.
 *
 * <p>Swing gives an option pane Escape for nothing and a {@code JDialog} no keys at all, so the
 * four dialogs built that way - Pattern library, Find terms, Imported terms and History - stayed
 * open on Escape while every form, file chooser and question beside them closed. Found by pressing
 * Escape in each dialog the menu opens, during a rehearsal of the usability study.
 */
final class DialogKeys {

    /** What Escape is bound to, in the root pane's action map. */
    static final String CLOSE = "ontoboard.close";

    private DialogKeys() {
    }

    /**
     * Escape presses {@code close}: the dialog's own Close button, so whatever it does before the
     * dialog goes - stopping a load that is still running - happens on Escape too.
     *
     * <p>Bound for the whole window and so asked last: a list, a table cell being edited or an open
     * drop-down that has a use for Escape still gets it first.
     */
    static void escapePresses(JRootPane root, final AbstractButton close) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE);
        root.getActionMap().put(CLOSE, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                close.doClick(0);
            }
        });
    }
}
