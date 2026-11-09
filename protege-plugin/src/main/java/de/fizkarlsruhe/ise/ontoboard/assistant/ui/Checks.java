package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * A check box whose words wrap at the column's width (1.144.0, review 1.143.0 I-4): a check box
 * draws its text on one line, so in a narrow column, or with a larger font, "This server is on my
 * network; send without encryption" was cut before "without encryption".
 */
final class Checks {

    private Checks() {
    }

    /**
     * The box, and its words beside it in a {@link Note} that wraps. Clicking the words ticks the
     * box, and they show its tooltip and whether it is enabled. Tests and code keep using the box.
     */
    static JComponent labelled(final JCheckBox box, String text) {
        box.setText("");
        box.getAccessibleContext().setAccessibleName(text);
        // No border on the words: a text area asked for its height while it is 1 px tall
        // ignores its width when that is less than its insets, and wraps at an old width.
        final Note words = new Note(text, foreground(true));
        words.setToolTipText(box.getToolTipText());
        words.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (box.isEnabled()) {
                    box.doClick();
                }
            }
        });
        box.addPropertyChangeListener("enabled",
                event -> words.setForeground(foreground(box.isEnabled())));
        box.addPropertyChangeListener(JComponent.TOOL_TIP_TEXT_KEY,
                event -> words.setToolTipText(box.getToolTipText()));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(box, BorderLayout.NORTH);
        final JPanel row = new JPanel(new BorderLayout(2, 0)) {
            private static final long serialVersionUID = 1L;

            /**
             * The look and feel draws a check box's focus around its text, which is beside it
             * now, so a keyboard user saw no focus at all: the words show it (1.145.0, review
             * 1.144.0). Painted, not a border - see above.
             */
            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                Color focus = UIManager.getColor("CheckBox.focus");
                if (focus != null && box.isFocusOwner() && box.isFocusPainted()) {
                    Rectangle around = words.getBounds();
                    g.setColor(focus);
                    g.drawRect(around.x - 1, around.y, around.width, around.height - 1);
                }
            }
        };
        box.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                row.repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                row.repaint();
            }
        });
        row.add(holder, BorderLayout.WEST);
        row.add(words, BorderLayout.CENTER);
        return row;
    }

    private static Color foreground(boolean enabled) {
        Color color = UIManager.getColor(enabled ? "Label.foreground" : "Label.disabledForeground");
        return color != null ? color : enabled ? Color.BLACK : Color.GRAY;
    }
}
