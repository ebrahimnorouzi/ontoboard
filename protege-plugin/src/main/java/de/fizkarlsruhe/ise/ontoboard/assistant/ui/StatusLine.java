package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * What the assistant is doing now, above the input: three dots that pulse while something is
 * under way, the words the session reports, and Stop beside them while an answer is coming.
 */
final class StatusLine extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Three dots, the brightest one moving along. */
    private static final class Dots extends JComponent {
        private static final long serialVersionUID = 1L;
        private int phase;

        Dots() {
            setPreferredSize(new Dimension(26, 12));
        }

        void step() {
            phase = (phase + 1) % 3;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color accent = UIManager.getColor("List.selectionBackground");
                if (accent == null) {
                    accent = new Color(0x2f, 0x6f, 0xd0);
                }
                int y = (getHeight() - 6) / 2;
                for (int i = 0; i < 3; i++) {
                    g.setColor(i == phase ? accent
                            : Bubble.blend(accent, Bubble.background(), 0.35));
                    g.fillOval(2 + i * 8, y, 6, 6);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private final Dots dots = new Dots();
    private final JLabel words = new JLabel(" ");
    private final Timer timer = new Timer(260, event -> dots.step());
    private boolean working;

    StatusLine(JComponent right) {
        super(new BorderLayout(6, 0));
        setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        words.setForeground(Bubble.muted());
        dots.setVisible(false);
        add(dots, BorderLayout.WEST);
        add(words, BorderLayout.CENTER);
        if (right != null) {
            add(right, BorderLayout.EAST);
        }
    }

    void setStatus(String text, boolean busy) {
        words.setText(text == null || text.isEmpty() ? " " : text);
        words.setToolTipText(text);
        working = busy;
        dots.setVisible(busy);
        if (busy && isShowing()) {
            timer.start();
        } else {
            timer.stop();
        }
    }

    String getText() {
        return words.getText();
    }

    boolean isWorking() {
        return working;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (working) {
            timer.start();
        }
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }
}
