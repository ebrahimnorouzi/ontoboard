package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Draws a {@link LegendLayout.Key}, into a raster image or into an SVG document.
 *
 * <p>Two renderers, one layout. The alternative - laying the key out separately for each output
 * format - is how a PNG and an SVG of the same diagram come to disagree about where the key is,
 * and the disagreement is invisible until somebody puts them side by side in a paper.
 */
public final class LegendRender {

    private LegendRender() {
    }

    /** Paints the key at {@code (dx, dy)}, scaled. */
    public static void paint(Graphics2D graphics, LegendLayout.Key key, int dx, int dy,
            double scale) {
        if (graphics == null || key == null || key.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(dx, dy);
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            for (LegendLayout.Shape shape : key.getShapes()) {
                switch (shape.getKind()) {
                    case RECT:
                        paintRect(g, shape);
                        break;
                    case LINE:
                        paintLine(g, shape);
                        break;
                    case TEXT:
                    default:
                        paintText(g, shape);
                        break;
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static void paintRect(Graphics2D g, LegendLayout.Shape shape) {
        int arc = shape.isRounded() ? 6 : 0;
        if (shape.getFill() != null) {
            g.setColor(withOpacity(shape.getFill(), shape.getOpacity()));
            g.fillRoundRect(shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight(),
                    arc, arc);
        }
        if (shape.getStroke() != null) {
            g.setColor(withOpacity(shape.getStroke(), shape.getOpacity()));
            g.setStroke(strokeFor(shape));
            g.drawRoundRect(shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight(),
                    arc, arc);
        }
    }

    private static void paintLine(Graphics2D g, LegendLayout.Shape shape) {
        g.setColor(withOpacity(shape.getStroke() == null ? LegendLayout.INK : shape.getStroke(),
                shape.getOpacity()));
        g.setStroke(strokeFor(shape));
        g.drawLine(shape.getX(), shape.getY(), shape.getX() + shape.getWidth(), shape.getY());
    }

    private static void paintText(Graphics2D g, LegendLayout.Shape shape) {
        g.setColor(withOpacity(shape.getFill() == null ? LegendLayout.INK : shape.getFill(),
                shape.getOpacity()));
        g.setFont(new Font(Font.SANS_SERIF, shape.isBold() ? Font.BOLD : Font.PLAIN,
                shape.getFontSize()));
        g.drawString(shape.getText() == null ? "" : shape.getText(), shape.getX(), shape.getY());
    }

    private static java.awt.Stroke strokeFor(LegendLayout.Shape shape) {
        if (!shape.isDashed()) {
            return new BasicStroke(1.4f);
        }
        return new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[] {4f, 3f}, 0f);
    }

    /** {@code #RRGGBB} plus a percentage, as a colour. Unparseable text falls back to ink. */
    static Color withOpacity(String hex, int opacity) {
        Color base;
        try {
            base = Color.decode(hex);
        } catch (RuntimeException notAColour) {
            base = Color.decode(LegendLayout.INK);
        }
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255f / 100f)));
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    /**
     * Appends the key to an SVG document as elements, at {@code (dx, dy)}.
     *
     * <p>Elements rather than an embedded bitmap. Half the reason to export an SVG is that it
     * does not blur when somebody enlarges it for a poster, and a key pasted in as a PNG would
     * be the one part that did.
     */
    public static void appendTo(Document document, Element parent, LegendLayout.Key key,
            int dx, int dy) {
        if (document == null || parent == null || key == null || key.isEmpty()) {
            return;
        }
        Element group = document.createElement("g");
        group.setAttribute("transform", "translate(" + dx + "," + dy + ")");
        parent.appendChild(group);

        for (LegendLayout.Shape shape : key.getShapes()) {
            Element element;
            switch (shape.getKind()) {
                case RECT:
                    element = document.createElement("rect");
                    element.setAttribute("x", String.valueOf(shape.getX()));
                    element.setAttribute("y", String.valueOf(shape.getY()));
                    element.setAttribute("width", String.valueOf(shape.getWidth()));
                    element.setAttribute("height", String.valueOf(shape.getHeight()));
                    if (shape.isRounded()) {
                        element.setAttribute("rx", "3");
                    }
                    element.setAttribute("fill",
                            shape.getFill() == null ? "none" : shape.getFill());
                    if (shape.getStroke() != null) {
                        element.setAttribute("stroke", shape.getStroke());
                        element.setAttribute("stroke-width", "1.4");
                        if (shape.isDashed()) {
                            element.setAttribute("stroke-dasharray", "4 3");
                        }
                    }
                    break;
                case LINE:
                    element = document.createElement("line");
                    element.setAttribute("x1", String.valueOf(shape.getX()));
                    element.setAttribute("y1", String.valueOf(shape.getY()));
                    element.setAttribute("x2", String.valueOf(shape.getX() + shape.getWidth()));
                    element.setAttribute("y2", String.valueOf(shape.getY()));
                    element.setAttribute("stroke",
                            shape.getStroke() == null ? LegendLayout.INK : shape.getStroke());
                    element.setAttribute("stroke-width", "1.4");
                    if (shape.isDashed()) {
                        element.setAttribute("stroke-dasharray", "4 3");
                    }
                    break;
                case TEXT:
                default:
                    element = document.createElement("text");
                    element.setAttribute("x", String.valueOf(shape.getX()));
                    element.setAttribute("y", String.valueOf(shape.getY()));
                    element.setAttribute("font-family", "sans-serif");
                    element.setAttribute("font-size", String.valueOf(shape.getFontSize()));
                    if (shape.isBold()) {
                        element.setAttribute("font-weight", "bold");
                    }
                    element.setAttribute("fill",
                            shape.getFill() == null ? LegendLayout.INK : shape.getFill());
                    element.setTextContent(shape.getText() == null ? "" : shape.getText());
                    break;
            }
            if (shape.getOpacity() < 100) {
                element.setAttribute("opacity",
                        String.valueOf(shape.getOpacity() / 100.0));
            }
            group.appendChild(element);
        }
    }
}
