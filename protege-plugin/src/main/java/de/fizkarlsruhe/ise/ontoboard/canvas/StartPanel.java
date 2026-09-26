package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * What the canvas shows before anything is on it.
 *
 * <p>An empty grid is a dead end: it looks broken, and it gives no clue that the canvas is
 * empty <em>by design</em> because Protégé opens ontologies far too large to draw whole. This
 * replaces that with the three things someone actually wants first — start a project, open one
 * they already have, or put an existing entity on the board — and explains the opt-in
 * behaviour rather than leaving it to be discovered.
 */
public final class StartPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /**
     * Taken from the look and feel, with the old hexes as the fallback.
     *
     * <p>Prot&eacute;g&eacute; 5.6 ships a dark theme, and this is the first screen anybody sees. It
     * hardcoded four light colours and painted itself on the canvas background, so on a dark IDE it
     * was a bright slab with dark-on-light text - the one panel in the workbench that had not been
     * told. The canvas itself stays light deliberately (see {@code SchemaStyles.CANVAS_BACKGROUND});
     * this panel is not the canvas, it is the thing standing in front of it.
     */
    private static final Color INK = uiColour("Label.foreground", 0x1A, 0x1D, 0x21);
    private static final Color MUTED = uiColour("Label.disabledForeground", 0x5A, 0x64, 0x70);
    private static final Color CARD = cardColour();
    private static final Color EDGE = uiColour("controlShadow", 0xD8, 0xDD, 0xE3);

    /** A look-and-feel colour, or the given fallback when the theme does not define it. */
    private static Color uiColour(String key, int r, int g, int b) {
        Color themed = javax.swing.UIManager.getColor(key);
        return themed != null ? themed : new Color(r, g, b);
    }

    /**
     * A surface that sits <em>above</em> the panel behind it, whichever way the theme runs.
     *
     * <p>Swing has no "card" or "surface" token, and the obvious substitute is wrong: taking
     * {@code Panel.background} makes the card exactly the colour of what it is supposed to be raised
     * off, and on the default light theme it came out darker than its own surround - a card pressed
     * into the page rather than lifted off it. So: white on a light theme, and a step lighter than
     * the panel on a dark one.
     */
    private static Color cardColour() {
        Color panel = javax.swing.UIManager.getColor("Panel.background");
        if (panel == null) {
            return Color.WHITE;
        }
        double luminance = (0.2126 * panel.getRed() + 0.7152 * panel.getGreen()
                + 0.0722 * panel.getBlue()) / 255.0;
        if (luminance >= 0.4) {
            return Color.WHITE;
        }
        return new Color(Math.min(255, panel.getRed() + 14),
                Math.min(255, panel.getGreen() + 14), Math.min(255, panel.getBlue() + 16));
    }

    /**
     * @param onNewProject     create a new ODK project
     * @param onOpenProject    open an existing ODK repository
     * @param onAddSelected    put the entity selected in Protégé onto the canvas
     */
    public StartPanel(Runnable onNewProject, Runnable onOpenProject, Runnable onAddSelected,
            Runnable onAddAll) {
        super(new GridBagLayout());
        // The IDE's panel colour, not the canvas colour. This card stands in FRONT of the board -
        // showAppropriateCard swaps the whole component out - so painting it the canvas's near-white
        // made the first screen anybody sees a bright slab in a dark workbench, and pretended to be
        // a board that is not there.
        setBackground(uiColour("Panel.background", 0xF7, 0xF8, 0xFA));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(EDGE),
                BorderFactory.createEmptyBorder(24, 28, 24, 28)));

        card.add(heading("Nothing on the board yet"));
        card.add(Box.createVerticalStrut(6));
        card.add(body("The canvas starts empty on purpose — ontologies often hold"));
        card.add(body("tens of thousands of classes, so you choose what to draw."));
        card.add(Box.createVerticalStrut(18));

        card.add(action("New ODK project…",
                "Scaffold a repository and start a new ontology", onNewProject));
        card.add(Box.createVerticalStrut(8));
        card.add(action("Open existing ODK project…",
                "Point at a repository you already have", onOpenProject));
        card.add(Box.createVerticalStrut(8));
        card.add(action("Add the selected entity",
                "Put whatever is selected in the class hierarchy on the board", onAddSelected));

        card.add(Box.createVerticalStrut(16));
        card.add(action("Add every term",
                "Puts all the classes, properties and individuals on the board",
                onAddAll));
        card.add(javax.swing.Box.createVerticalStrut(14));
        // Two corrections. This used to say "double-click the board", which cannot be done while
        // this card is covering it, and "the palette on the left", which is not the name of
        // anything: the left column of the OntoBoard tab is Protege's own entity views, the first
        // labelled Classes.
        card.add(body("Or pick a term in the Classes tab on the left and press Add selected."));
        card.add(javax.swing.Box.createVerticalStrut(10));
        // Said here because it is the single most consequential thing about this canvas, and
        // nothing anywhere else says it until somebody presses Delete and reads the status bar.
        card.add(body("Taking something off the board never deletes it from the ontology."));

        GridBagConstraints centre = new GridBagConstraints();
        centre.gridx = 0;
        centre.gridy = 0;
        centre.insets = new Insets(24, 24, 24, 24);
        add(card, centre);
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(INK);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JLabel body(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 12f));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /** A primary action plus the one line that says what it will do. */
    private static JPanel action(String label, String explanation, Runnable onClick) {
        JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setPreferredSize(new Dimension(300, 30));
        button.setMaximumSize(new Dimension(300, 30));
        if (onClick != null) {
            button.addActionListener(e -> onClick.run());
        } else {
            button.setEnabled(false);
        }

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(button);
        JLabel hint = body("      " + explanation);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        row.add(hint);
        return row;
    }
}
