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
     * Shows the dialog.
     *
     * @return the settings the user confirmed, already saved, or null if they cancelled
     */
    public static CollabSettings show(Component parent) {
        CollabSettings existing = CollabSettingsStore.load();
        boolean tokenWasRemembered = CollabSettingsStore.isTokenRemembered();

        JTextField server = new JTextField(existing.getBridgeUrl(), 28);
        JTextField board = new JTextField(existing.getBoard(), 28);
        JPasswordField token = new JPasswordField(existing.getToken(), 28);
        // No name field. The bridge takes the name from the access token's subject and never reads
        // what a client claims - presenceFor(...).set(socket, { user, ... }) is written once at
        // hello from the authenticated user, and a presence message only ever updates x, y,
        // selection and seenAt. A "Your name" box therefore changed nothing anyone could see, which
        // is worse than not offering one.
        String displayName = existing.getDisplayName();
        JComboBox<String> colour = new JComboBox<String>(COLOURS);
        colour.setSelectedItem(existing.getColour());
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
        addRow(form, at, "Board id", board, "The board you and your colleagues share");
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
            return show(parent);
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

    private static JLabel wrapped(String text) {
        JLabel label = new JLabel("<html><body style='width:340px'>" + text + "</body></html>");
        label.setPreferredSize(new Dimension(360, 52));
        return label;
    }
}
