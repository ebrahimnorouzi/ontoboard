package de.fizkarlsruhe.ise.ontoboard.collab;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * Asks for the details needed to join a team's collaboration server.
 *
 * <p>OntoBoard runs no server. A team stands one up and each member points the plugin at it, which
 * is why this asks for an address and a token rather than offering a sign-in: there is no OntoBoard
 * account to sign in to. The dialog therefore also has to explain the alternative, because leaving
 * these fields empty is a legitimate choice - git mode - and not an error.
 *
 * <p>Swing, so untested. Everything it decides is delegated: {@link CollabSettings} validates the
 * address and derives the mode, {@link CollabSettingsStore} handles persistence and the token
 * rule. What is left here is layout and wording.
 */
public final class CollabDialog {

    /** Distinguishable at a glance and readable behind white or black text. */
    private static final String[] COLOURS = {
        "#4A90D9", "#E2725B", "#4CAF50", "#9C6ADE", "#E5A50A", "#00A3A3", "#D9548C", "#5D6D7E",
    };

    private CollabDialog() {
    }

    /**
     * The settings dialog, without an ontology to derive a board from.
     *
     * <p>Kept for callers that have none. Prefer {@link #show(Component, String)}: a board id
     * typed by hand is the one that gets mistyped, and a mistyped board id is a second empty
     * session rather than an error.
     */
    public static CollabSettings show(Component parent) {
        return show(parent, null);
    }

    /**
     * The settings dialog for a particular ontology.
     *
     * <p>The board id defaults to the one the ontology derives, so two people editing the same
     * ontology reach the same session without agreeing anything and without either of them typing
     * a name. Overriding is allowed - one board across a pair of related files is a real thing to
     * want - but a board that does not match the open ontology is said out loud, because the
     * failure it causes is otherwise invisible until somebody else's class appears in your file.
     *
     * @param ontologyIri the open ontology's own IRI, or null when there is none
     */
    public static CollabSettings show(Component parent, String ontologyIri) {
        CollabSettings existing = CollabSettingsStore.load();
        boolean tokenWasRemembered = CollabSettingsStore.isTokenRemembered();

        JTextField server = new JTextField(existing.getBridgeUrl(), 28);
        String derived = BoardId.forOntology(ontologyIri);
        // The stored board wins - somebody who set one meant it - and the derived one fills an
        // empty field so that the common case needs no typing at all.
        String startingBoard = existing.getBoard().isEmpty() ? derived : existing.getBoard();
        JTextField board = new JTextField(startingBoard, 28);
        JPasswordField token = new JPasswordField(existing.getToken(), 28);
        // No name field. The bridge takes the name from the access token's subject and never reads
        // what a client claims - presenceFor(...).set(socket, { user, ... }) is written once at
        // hello from the authenticated user, and a presence message only ever updates x, y,
        // selection and seenAt. A "Your name" box therefore changed nothing anyone could see, which
        // is worse than not offering one.
        String displayName = existing.getDisplayName();
        JComboBox<String> colour = new JComboBox<String>(COLOURS);
        colour.setSelectedItem(existing.getColour());
        // A hex code is not a colour to anyone but a developer. The renderer paints the
        // actual swatch, so picking "the green one" is a matter of looking rather than
        // of decoding.
        colour.setRenderer(new SwatchRenderer());
        JCheckBox remember = new JCheckBox(
                "Remember the token on this computer", tokenWasRemembered);
        remember.setToolTipText("Stored in " + CollabSettingsStore.tokenStorageDescription());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(4, 6, 4, 6);
        at.anchor = GridBagConstraints.WEST;
        at.gridx = 0;
        at.gridy = 0;

        addRow(form, at, "Server address", server,
                "The JSON bridge, normally port 1235 - not 1234, which browsers use");
        addRow(form, at, "Board id", board, derived.isEmpty()
                ? "The board you and your colleagues share"
                : "Derived from this ontology, so everyone editing it reaches the same board "
                        + "without being told the name. Change it only if you mean to.");
        addRow(form, at, "Access token", token,
                "From the web application; your name comes from it, not from this dialog");
        addRow(form, at, "Your colour", colour,
                "Your cursor and selection colour, fixed for the session once connected");

        at.gridx = 1;
        at.gridy++;
        form.add(remember, at);

        at.gridx = 0;
        at.gridy++;
        at.gridwidth = 2;
        form.add(storageNotice(), at);
        at.gridy++;
        form.add(gitModeNotice(), at);

        int answer = JOptionPane.showConfirmDialog(parent, form, "Collaboration",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }

        CollabSettings chosen = new CollabSettings(server.getText().trim(),
                board.getText().trim(), new String(token.getPassword()).trim(),
                displayName, String.valueOf(colour.getSelectedItem()));
        try {
            chosen.validate();
        } catch (IllegalArgumentException wrong) {
            JOptionPane.showMessageDialog(parent, wrong.getMessage(),
                    "That address will not work", JOptionPane.WARNING_MESSAGE);
            return show(parent, ontologyIri);
        }
        CollabSettingsStore.save(chosen, remember.isSelected());
        return chosen;
    }

    private static void addRow(JPanel form, GridBagConstraints at, String label,
            Component field, String hint) {
        at.gridx = 0;
        form.add(new JLabel(label), at);
        at.gridx = 1;
        form.add(field, at);
        at.gridy++;
        at.gridx = 1;
        form.add(hintLabel(hint), at);
        at.gridy++;
    }

    private static JLabel hintLabel(String text) {
        JLabel hint = new JLabel(text);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, hint.getFont().getSize() - 1f));
        hint.setForeground(new Color(0x66, 0x6E, 0x7A));
        return hint;
    }

    /**
     * Names where a saved token goes.
     *
     * <p>Spelled out rather than hidden in a tooltip: a checkbox that stores a bearer credential
     * somewhere the user cannot name is not informed consent.
     */
    private static Component storageNotice() {
        JPanel notice = new JPanel(new BorderLayout());
        notice.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(0xE5, 0xA5, 0x0A)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        notice.add(wrapped("The token is a credential. Left unticked it is kept only for this "
                + "Protege session; ticked, it is written to "
                + CollabSettingsStore.tokenStorageDescription() + " as plain text."));
        return notice;
    }

    /** Empty fields are a choice, not a mistake, and the dialog should say so. */
    private static Component gitModeNotice() {
        JPanel notice = new JPanel(new BorderLayout());
        notice.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, new Color(0x4A, 0x90, 0xD9)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        notice.add(wrapped("No server? Leave these empty and work through git instead - commit "
                + "and push as usual. You will not see other people's cursors or their edits as "
                + "they happen, but nothing else changes."));
        return notice;
    }

    /**
     * Paints each palette entry as a swatch with a readable name beside it.
     *
     * <p>The names matter as much as the swatches: "#9C6ADE" is nothing a user can repeat to a
     * colleague, whereas "Purple" is.
     */
    private static final class SwatchRenderer extends javax.swing.DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            String hex = String.valueOf(value);
            setText(nameOf(hex));
            setIcon(new SwatchIcon(hex));
            setIconTextGap(8);
            return this;
        }
    }

    /** A filled rounded square in the given colour. */
    private static final class SwatchIcon implements javax.swing.Icon {
        private final Color colour;

        SwatchIcon(String hex) {
            Color parsed;
            try {
                parsed = Color.decode(hex);
            } catch (NumberFormatException notAColour) {
                // The palette is ours, so this cannot happen today - but a grey square is a
                // better outcome than an exception inside a list renderer.
                parsed = Color.GRAY;
            }
            this.colour = parsed;
        }

        @Override
        public void paintIcon(Component host, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(colour);
                g.fillRoundRect(x, y, getIconWidth(), getIconHeight(), 4, 4);
                g.setColor(new Color(0, 0, 0, 40));
                g.drawRoundRect(x, y, getIconWidth() - 1, getIconHeight() - 1, 4, 4);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    /** A name for each palette entry, in the same order as {@link #COLOURS}. */
    static String nameOf(String hex) {
        String[] names = {"Blue", "Terracotta", "Green", "Purple", "Amber", "Teal", "Pink",
            "Slate"};
        for (int i = 0; i < COLOURS.length && i < names.length; i++) {
            if (COLOURS[i].equalsIgnoreCase(hex)) {
                return names[i];
            }
        }
        return hex;
    }

    private static JLabel wrapped(String text) {
        JLabel label = new JLabel("<html><body style='width:340px'>" + text + "</body></html>");
        label.setPreferredSize(new Dimension(360, 52));
        return label;
    }
}
