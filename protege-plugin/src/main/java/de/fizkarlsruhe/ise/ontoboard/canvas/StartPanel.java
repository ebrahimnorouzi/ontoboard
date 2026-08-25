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

    private static final Color INK = new Color(0x1A, 0x1D, 0x21);
    private static final Color MUTED = new Color(0x5A, 0x64, 0x70);
    private static final Color CARD = Color.WHITE;
    private static final Color EDGE = new Color(0xD8, 0xDD, 0xE3);

    /**
     * @param onNewProject     create a new ODK project
     * @param onOpenProject    open an existing ODK repository
     * @param onAddSelected    put the entity selected in Protégé onto the canvas
     */
    public StartPanel(Runnable onNewProject, Runnable onOpenProject, Runnable onAddSelected) {
        super(new GridBagLayout());
        setBackground(Color.decode(SchemaStyles.CANVAS_BACKGROUND));

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
        card.add(body("Already have an ontology open? Double-click the board to add a class,"));
        card.add(body("or drag one in from the palette on the left."));

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
