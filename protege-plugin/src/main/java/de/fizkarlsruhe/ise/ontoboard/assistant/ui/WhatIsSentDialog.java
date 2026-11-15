package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * "What is sent" (spec D3): the exact text of the next request - destination, model, sign-in
 * (with the key shown as "(your key)"), size, role, fixed rules, context and conversation - before
 * anything is sent.
 */
public final class WhatIsSentDialog {

    private WhatIsSentDialog() {
    }

    public static void show(Component parent, String text) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, "What is sent");
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 520));

        JButton copy = new JButton("Copy");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        buttons.add(close);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}
