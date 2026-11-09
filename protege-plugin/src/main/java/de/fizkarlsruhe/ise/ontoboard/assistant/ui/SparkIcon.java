package de.fizkarlsruhe.ise.ontoboard.assistant.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;

/**
 * The assistant's mark: a four-pointed star, drawn rather than typed. Protégé 5.5.0's Java may
 * have no font with U+2726, and a missing glyph is an empty box on every line of the column.
 */
public final class SparkIcon implements Icon {

    private final int size;

    public SparkIcon() {
        this(12);
    }

    public SparkIcon(int size) {
        this.size = size;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color colour = component == null ? Color.DARK_GRAY : component.getForeground();
            if (component != null && !component.isEnabled()) {
                colour = Color.GRAY;
            }
            g.setColor(colour);
            double centreX = x + size / 2.0;
            double centreY = y + size / 2.0;
            double outer = size / 2.0;
            double inner = size / 6.0;
            Path2D star = new Path2D.Double();
            for (int point = 0; point < 8; point++) {
                double radius = point % 2 == 0 ? outer : inner;
                double angle = Math.PI / 4 * point - Math.PI / 2;
                double px = centreX + radius * Math.cos(angle);
                double py = centreY + radius * Math.sin(angle);
                if (point == 0) {
                    star.moveTo(px, py);
                } else {
                    star.lineTo(px, py);
                }
            }
            star.closePath();
            g.fill(star);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }
}
