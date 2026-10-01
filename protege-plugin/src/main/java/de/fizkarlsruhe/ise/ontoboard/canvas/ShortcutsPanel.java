package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * What this canvas responds to, written down.
 *
 * <p>Until 1.68.0 none of these interactions was advertised anywhere except the Find field's
 * tooltip - which you can only read once you have already found the Find field.
 *
 * <p>What a plain drag does has now been settled in both directions: it panned until 1.68.0,
 * selected until 1.73.0, and pans again, because that is the gesture people reached for. So it is
 * a mode with a button on the toolbar and a key, and both halves are listed here. A canvas whose
 * primary gesture can change has to say somewhere what it is currently doing, or the change is
 * indistinguishable from a bug.
 *
 * <p>Every row here was checked against the line that implements it rather than against intent. A
 * shortcut list that is wrong is worse than none, because it is the thing somebody believes when the
 * canvas appears not to work.
 */
public final class ShortcutsPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** {key, what it does}. Order is by how early somebody needs it, not alphabetical. */
    private static final String[][] ROWS = {
        {"Ctrl+F", "Find a term on the board"},
        {"Enter", "Go to the next match; Shift+Enter for the previous"},
        {"Ctrl+Enter", "Add a match that is in the ontology but not yet on the board"},
        {"Double-click", "On empty board: a new class. On a term: expand its neighbours."},
        {"Drag", "On empty board: move the board. From a node's handle: draw an axiom."},
        {"Shift-drag", "Select a region instead. Ctrl-drag does the same."},
        {"Space-drag", "Pan the board whichever mode it is in. The middle and right buttons too."},
        {"H / V", "Hand or pointer: whether a plain drag pans or selects. The toolbar shows it."},
        {"Wheel", "Zoom, at the pointer"},
        {"Ctrl+0", "Actual size"},
        {"Ctrl+1", "Fit the whole board"},
        {"Ctrl+2", "Frame whatever is selected"},
        {"Ctrl+A", "Select every term on the board"},
        {"Ctrl+D", "Duplicate a sticky note or a frame"},
        {"Ctrl+Z", "Undo the board. Ctrl+Shift+Z redoes it."},
        {"Delete", "Take the selection off the board. The axioms stay."},
        {"Escape", "Clear the selection"},
        {"Alt-drag", "Place a node off the grid"},
    };

    private static Color uiColour(String key, int r, int g, int b) {
        Color themed = UIManager.getColor(key);
        return themed != null ? themed : new Color(r, g, b);
    }

    public ShortcutsPanel() {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(16, 18, 18, 18));

        Color ink = uiColour("Label.foreground", 0x1A, 0x1D, 0x21);
        Color muted = uiColour("Label.disabledForeground", 0x5A, 0x64, 0x70);

        GridBagConstraints at = new GridBagConstraints();
        at.gridx = 0;
        at.gridy = 0;
        at.gridwidth = 2;
        at.anchor = GridBagConstraints.WEST;
        at.insets = new Insets(0, 0, 12, 0);
        JLabel heading = new JLabel("Canvas shortcuts");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize() + 2f));
        heading.setForeground(ink);
        add(heading, at);

        at.gridwidth = 1;
        for (String[] row : ROWS) {
            at.gridy++;
            at.gridx = 0;
            at.insets = new Insets(3, 0, 3, 14);
            JLabel cap = new JLabel(new CanvasIcons.KeyCap(row[0]), SwingConstants.LEFT);
            add(cap, at);

            at.gridx = 1;
            at.insets = new Insets(3, 0, 3, 0);
            JLabel meaning = new JLabel(row[1]);
            meaning.setForeground(ink);
            add(meaning, at);
        }

        at.gridy++;
        at.gridx = 0;
        at.gridwidth = 2;
        at.insets = new Insets(14, 0, 0, 0);
        JLabel footer = new JLabel("Undo here is the board's own - which terms are shown, where they "
                + "sit, the notes and frames. Axioms are undone with Protégé's Edit › Undo.");
        footer.setForeground(muted);
        footer.setFont(footer.getFont().deriveFont(Font.PLAIN, footer.getFont().getSize() - 1f));
        add(footer, at);
    }

    /** How many interactions this panel documents, so a test can hold it to the real number. */
    public static int documentedCount() {
        return ROWS.length;
    }

    /** The key column, for a test that wants to check one is present. */
    public static String[][] rows() {
        String[][] copy = new String[ROWS.length][];
        for (int i = 0; i < ROWS.length; i++) {
            copy[i] = new String[] {ROWS[i][0], ROWS[i][1]};
        }
        return copy;
    }
}
