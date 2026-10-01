package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/**
 * The toolbar's icons, drawn in code.
 *
 * <p>There is no icon set to reach for. Adding one would mean a new dependency, which this plugin does
 * not take, or bundling image files, which then have to exist at two or three densities and be redrawn
 * for a dark theme. The remaining option people reach for is a unicode glyph - and that is the one to
 * avoid: coverage varies by platform and by installed font, so a missing glyph is a tofu box in a
 * toolbar, on somebody else's machine, that nobody here would ever see.
 *
 * <p>So they are painted. Sixteen pixels square, from the palette the rest of the canvas already uses,
 * in the same shape {@code LegendPanel}'s own swatches are written: a {@code Graphics2D} copy,
 * antialiasing on, disposed in a {@code finally}. None of this exports - an icon is chrome, and
 * {@code CanvasExport} renders from the graph model - which is correct.
 */
public final class CanvasIcons {

    /** The accent: the same blue a class is stroked with. */
    static final Color ACCENT = new Color(0x25, 0x63, 0xEB);

    /** Full-strength ink, as on a node label. */
    static final Color INK = new Color(0x1A, 0x1D, 0x21);

    /** Secondary ink, for an outline that should not shout. */
    static final Color MUTED = new Color(0x5A, 0x64, 0x70);

    /** The hairline used on chips, cards and cluster borders. */
    static final Color BORDER = new Color(0xD8, 0xDD, 0xE3);

    private CanvasIcons() {
    }

    /** Sets up a graphics copy the same way for every icon. The caller disposes it. */
    private static Graphics2D prepare(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    /** Every icon here is 16x16 unless it says otherwise. */
    abstract static class Square implements Icon {

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    /** Add one thing: a plain cross in the accent colour. */
    public static final class Plus extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x + 8, y + 3, x + 8, y + 13);
                g.drawLine(x + 3, y + 8, x + 13, y + 8);
            } finally {
                g.dispose();
            }
        }
    }

    /** Add everything: a stack of bars with a small cross on the corner. */
    public static final class PlusStack extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(BORDER);
                g.fillRoundRect(x + 2, y + 4, 9, 3, 2, 2);
                g.fillRoundRect(x + 2, y + 9, 9, 3, 2, 2);
                g.fillRoundRect(x + 2, y + 14, 9, 2, 2, 2);
                g.setColor(ACCENT);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x + 12, y + 1, x + 12, y + 7);
                g.drawLine(x + 9, y + 4, x + 15, y + 4);
            } finally {
                g.dispose();
            }
        }
    }

    /** Find: a magnifier. */
    public static final class Magnifier extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawOval(x + 2, y + 2, 9, 9);
                g.drawLine(x + 10, y + 10, x + 14, y + 14);
            } finally {
                g.dispose();
            }
        }
    }

    /** Arrange: a parent above two children, which is what the layout now produces. */
    public static final class Tree extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1f));
                g.drawRect(x + 5, y + 2, 6, 3);
                g.drawRect(x + 1, y + 11, 5, 3);
                g.drawRect(x + 10, y + 11, 5, 3);
                g.drawLine(x + 8, y + 5, x + 8, y + 8);
                g.drawLine(x + 3, y + 8, x + 12, y + 8);
                g.drawLine(x + 3, y + 8, x + 3, y + 11);
                g.drawLine(x + 12, y + 8, x + 12, y + 11);
            } finally {
                g.dispose();
            }
        }
    }

    /** Inferences: a dashed outline, quoting the dash an inferred edge is drawn with. */
    public static final class Dashed extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[] {3f, 3f}, 0f));
                g.drawRoundRect(x + 2, y + 2, 12, 12, 4, 4);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * Pan: an open hand, which is what every canvas application draws for this.
     *
     * <p>Outline rather than filled, to sit beside Tree and Dashed at the same weight. A filled
     * glyph next to two hairline ones reads as the selected one even when it is not.
     */
    public static final class Hand extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                // Palm.
                g.drawRoundRect(x + 4, y + 7, 9, 7, 4, 4);
                // Three fingers, the middle one longest, as a hand is drawn.
                g.drawLine(x + 6, y + 7, x + 6, y + 4);
                g.drawLine(x + 9, y + 7, x + 9, y + 2);
                g.drawLine(x + 12, y + 7, x + 12, y + 4);
                // Thumb.
                g.drawLine(x + 4, y + 9, x + 2, y + 11);
            } finally {
                g.dispose();
            }
        }
    }

    /** Select: a dashed marquee with a pointer in it, so it is not confused with Dashed. */
    public static final class Marquee extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[] {2f, 2f}, 0f));
                g.drawRect(x + 1, y + 1, 11, 11);
                // The pointer, solid, breaking out of the marquee's corner.
                g.setStroke(new BasicStroke(1f));
                java.awt.Polygon arrow = new java.awt.Polygon();
                arrow.addPoint(x + 8, y + 6);
                arrow.addPoint(x + 15, y + 11);
                arrow.addPoint(x + 11, y + 11);
                arrow.addPoint(x + 12, y + 15);
                arrow.addPoint(x + 10, y + 15);
                arrow.addPoint(x + 9, y + 12);
                arrow.addPoint(x + 7, y + 14);
                g.setColor(INK);
                g.fillPolygon(arrow);
            } finally {
                g.dispose();
            }
        }
    }

    /** Legend: three swatches. */
    public static final class Key extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                Color[] swatches = {ACCENT, new Color(0x2F, 0x7A, 0x4C), new Color(0xB3, 0x5C, 0x00)};
                for (int i = 0; i < swatches.length; i++) {
                    g.setColor(swatches[i]);
                    g.fillRoundRect(x + 2, y + 2 + i * 5, 4, 4, 1, 1);
                    g.setColor(BORDER);
                    g.fillRoundRect(x + 8, y + 3 + i * 5, 6, 2, 1, 1);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Export: something leaving a frame. */
    public static final class Download extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x + 2, y + 11, x + 2, y + 14);
                g.drawLine(x + 2, y + 14, x + 14, y + 14);
                g.drawLine(x + 14, y + 11, x + 14, y + 14);
                g.setColor(ACCENT);
                g.drawLine(x + 8, y + 2, x + 8, y + 10);
                g.drawLine(x + 5, y + 7, x + 8, y + 10);
                g.drawLine(x + 11, y + 7, x + 8, y + 10);
            } finally {
                g.dispose();
            }
        }
    }

    /** More: the three dots every overflow menu uses. */
    public static final class Kebab extends Square {

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                for (int i = 0; i < 3; i++) {
                    g.fillOval(x + 7, y + 3 + i * 5, 3, 3);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** A small downward triangle, for a button that opens a menu. */
    public static final class Caret implements Icon {

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 5;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(MUTED);
                g.fillPolygon(new int[] {x, x + 7, x + 3}, new int[] {y, y, y + 4}, 3);
            } finally {
                g.dispose();
            }
        }
    }

    /** A status light: one filled circle in whatever the state is. */
    public static final class Dot implements Icon {

        private final Color colour;

        public Dot(Color colour) {
            this.colour = colour;
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                g.setColor(colour);
                g.fillOval(x, y + 1, 7, 7);
            } finally {
                g.dispose();
            }
        }
    }

    /** A keyboard key, for the shortcuts panel: a rounded cap with a label in it. */
    public static final class KeyCap implements Icon {

        private final String label;

        public KeyCap(String label) {
            this.label = label == null ? "" : label;
        }

        @Override
        public int getIconWidth() {
            return Math.max(22, 9 * label.length() + 12);
        }

        @Override
        public int getIconHeight() {
            return 20;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = prepare(graphics);
            try {
                int w = getIconWidth() - 1;
                int h = getIconHeight() - 1;
                g.setColor(Color.WHITE);
                g.fillRoundRect(x, y, w, h, 6, 6);
                g.setColor(BORDER);
                g.drawRoundRect(x, y, w, h, 6, 6);
                g.setColor(INK);
                g.setFont(host == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 11)
                        : host.getFont().deriveFont(Font.PLAIN, 11f));
                java.awt.FontMetrics metrics = g.getFontMetrics();
                g.drawString(label, x + (w - metrics.stringWidth(label)) / 2 + 1,
                        y + (h + metrics.getAscent() - metrics.getDescent()) / 2);
            } finally {
                g.dispose();
            }
        }
    }
}
