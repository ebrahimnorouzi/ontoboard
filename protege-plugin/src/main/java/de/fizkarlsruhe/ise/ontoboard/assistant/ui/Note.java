package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.Color;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * Words under a control, wrapped at the column's width by {@link StackLayout}.
 *
 * <p>Not an HTML label: its preferred width is the whole text on one line, and that made the
 * Settings tab 1,433 px wide in a 396 px view, with "Save conversations in this project" out of
 * sight (1.142.0). Also plain text: a server's words in it are shown, never rendered.
 */
final class Note extends JTextArea {

    private static final long serialVersionUID = 1L;

    Note(String text, Color color) {
        super(text);
        setEditable(false);
        setFocusable(false);
        setLineWrap(true);
        setWrapStyleWord(true);
        setOpaque(false);
        setBorder(null);
        setForeground(color);
        setFont(UIManager.getFont("Label.font"));
    }
}
