package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Draws {@link CanvasLegend} as a panel.
 *
 * <p>Thin by design: every decision about what the legend contains, and every colour and dash
 * pattern, lives in {@link CanvasLegend}, which is unit-tested for agreement with
 * {@link SchemaStyles} and for covering every kind of node and edge the canvas can draw. What is
 * left here is painting, which no test can check.
 */
public final class LegendPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int SWATCH_WIDTH = 46;
    private static final int SWATCH_HEIGHT = 22;

    /** The legend with no board to describe - every row except the namespaces. */
    public LegendPanel() {
        this(java.util.Collections.<String, String>emptyMap());
    }

    /**
     * @param prefixColours this board's namespace colours, from {@code CanvasLayout.prefixColors}.
     *     Passed in rather than read from a static, because the colours are per board and a key
     *     showing another board's is worse than one showing none.
     */
    public LegendPanel(java.util.Map<String, String> prefixColours) {
        super(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));

        GridBagConstraints at = new GridBagConstraints();
        at.insets = new Insets(3, 6, 3, 6);
        at.anchor = GridBagConstraints.WEST;
        at.gridy = 0;

        CanvasLegend.Form section = null;
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            if (entry.getForm() != section) {
                section = entry.getForm();
                at.gridx = 0;
                at.gridwidth = 2;
                // Three kinds, not two. MODIFIER rows - a term carrying an editorial note, an
                // unsatisfiable class - fell into the "Relationships" branch of a two-way
                // conditional and were headed as relationships and drawn as arrows, which is the
                // opposite of what they are: a change to how an existing shape is drawn.
                add(heading(headingFor(section)), at);
                at.gridy++;
                at.gridwidth = 1;
            }
            at.gridx = 0;
            add(new JLabel(new SwatchIcon(entry)), at);
            at.gridx = 1;
            add(describe(entry), at);
            at.gridy++;
        }

        // The channel that was missing. Every node is outlined in a colour derived from its
        // namespace, and until this section existed the key never said so - so a purple outline
        // next to the purple "Individual" swatch read as "individual".
        java.util.List<CanvasLegend.Namespace> namespaces =
                CanvasLegend.namespaces(prefixColours);
        if (!namespaces.isEmpty()) {
            at.gridx = 0;
            at.gridwidth = 2;
            add(heading("Namespaces on this board"), at);
            at.gridy++;
            at.gridwidth = 1;
            for (CanvasLegend.Namespace namespace : namespaces) {
                at.gridx = 0;
                add(new JLabel(new NamespaceIcon(namespace.getColour())), at);
                at.gridx = 1;
                JLabel label = new JLabel(namespace.getPrefix());
                label.setToolTipText("<html><body style='width:280px'>Every term in this namespace "
                        + "is outlined in this colour. The outline says where a term comes from; "
                        + "the shape says what kind of thing it is.</body></html>");
                add(label, at);
                at.gridy++;
            }
        }
    }

    /**
     * A square outlined in a namespace's colour.
     *
     * <p>An outline rather than a filled block, because that is what the canvas does with the
     * colour - a filled swatch would suggest the fill carries the namespace, which is the confusion
     * this section exists to end.
     */
    private static final class NamespaceIcon implements Icon {
        private final String colour;

        NamespaceIcon(String colour) {
            this.colour = colour;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE);
                g.fillRect(x + 12, y + 3, SWATCH_HEIGHT - 6, SWATCH_HEIGHT - 6);
                g.setColor(decode(colour, Color.DARK_GRAY));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(x + 12, y + 3, SWATCH_HEIGHT - 6, SWATCH_HEIGHT - 6);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SWATCH_WIDTH;
        }

        @Override
        public int getIconHeight() {
            return SWATCH_HEIGHT;
        }
    }

    /** The section heading for a form. Three kinds, so not a conditional. */
    private static String headingFor(CanvasLegend.Form form) {
        switch (form) {
            case NODE:
                return "Things";
            case EDGE:
                return "Relationships";
            case MODIFIER:
            default:
                return "Markers on a thing";
        }
    }

    private static Component heading(String text) {
        JLabel heading = new JLabel(text);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        return heading;
    }

    /**
     * The label, with the meaning as its tooltip.
     *
     * <p>The meaning is a sentence, and eight sentences stacked in a dialog is a wall of text; the
     * tooltip keeps it one hover away instead of making the key harder to read than the diagram.
     */
    private static Component describe(CanvasLegend.Entry entry) {
        JLabel label = new JLabel(entry.getLabel());
        label.setToolTipText("<html><body style='width:280px'>" + entry.getMeaning()
                + "</body></html>");
        return label;
    }

    /**
     * A colour from the stylesheet or the sidecar, or a fallback.
     *
     * <p>At panel level rather than inside one icon because both icons need it. It lived in
     * {@code SwatchIcon} until the namespace swatch was added and could not see it - a private
     * member of a sibling nested class is reachable in Java, but writing
     * {@code SwatchIcon.decode(...)} from the namespace icon would say the colour logic belongs to
     * the shape swatch, and it does not.
     */
    private static Color decode(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        try {
            return Color.decode(hex);
        } catch (NumberFormatException notAColour) {
            return fallback;
        }
    }

    /** A miniature of the node shape or the edge line. */
    private static final class SwatchIcon implements Icon {
        private final CanvasLegend.Entry entry;

        SwatchIcon(CanvasLegend.Entry entry) {
            this.entry = entry;
        }

        @Override
        public void paintIcon(Component host, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (entry.getForm() == CanvasLegend.Form.EDGE) {
                    paintEdge(g, x, y);
                } else {
                    // NODE and MODIFIER both illustrate a box. A modifier's whole point is that it
                    // is a box drawn differently - a heavier border, a red outline - so drawing it
                    // as a line said nothing about what the user would actually see.
                    paintNode(g, x, y);
                }
            } finally {
                g.dispose();
            }
        }

        private void paintNode(Graphics2D g, int x, int y) {
            Color fill = decode(entry.getFill(), Color.WHITE);
            Color stroke = decode(entry.getStroke(), Color.DARK_GRAY);
            // A row whose only distinction is opacity has to be drawn with it, or the swatch shows
            // the reader something other than what the canvas will.
            if (entry.getOpacity() < 100) {
                g.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, entry.getOpacity() / 100f));
            }
            int w = SWATCH_WIDTH - 4;
            int h = SWATCH_HEIGHT - 4;

            g.setStroke(new BasicStroke(1.6f));
            if (SchemaStyles.OBJECT_PROPERTY_NODE.equals(entry.getStyleName())
                    || SchemaStyles.DATA_PROPERTY_NODE.equals(entry.getStyleName())) {
                // A hexagon, as the canvas draws a property node. Drawn as a rectangle here until
                // 1.65.0, which is the same fault the individual's rhombus had in reverse: a key
                // that shows a shape the board does not draw has to be unlearned the first time
                // somebody compares the two.
                int[] xs = {x + w / 4, x + 3 * w / 4, x + w, x + 3 * w / 4, x + w / 4, x};
                int[] ys = {y, y, y + h / 2, y + h, y + h, y + h / 2};
                g.setColor(fill);
                g.fillPolygon(xs, ys, 6);
                g.setColor(stroke);
                g.drawPolygon(xs, ys, 6);
                return;
            }
            if (SchemaStyles.DATATYPE.equals(entry.getStyleName())) {
                g.setColor(fill);
                g.fillOval(x, y, w, h);
                g.setColor(stroke);
                g.drawOval(x, y, w, h);
                return;
            }
            // Both are rounded cards on the canvas now: the individual stopped being a rhombus
            // when it turned out a rhombus cannot hold an ontology label.
            boolean rounded = SchemaStyles.CLASS.equals(entry.getStyleName())
                    || SchemaStyles.INDIVIDUAL.equals(entry.getStyleName());
            int arc = rounded ? 8 : 0;
            g.setColor(fill);
            g.fillRoundRect(x, y, w, h, arc, arc);
            g.setColor(stroke);
            g.drawRoundRect(x, y, w, h, arc, arc);
        }

        private void paintEdge(Graphics2D g, int x, int y) {
            Color stroke = decode(entry.getStroke(), Color.DARK_GRAY);
            int middle = y + SWATCH_HEIGHT / 2;
            int end = x + SWATCH_WIDTH - 8;

            g.setColor(stroke);
            g.setStroke(strokeFor(entry.getDashPattern()));
            g.drawLine(x, middle, end, middle);

            // A solid arrowhead regardless of the line's dash, since a dashed head reads as noise.
            // Wider than it was, because a hollow triangle needs room to read as one at swatch size.
            g.setStroke(new BasicStroke(1f));
            int[] xs = {end + 9, end, end};
            int[] ys = {middle, middle - 5, middle + 5};
            // Hollow for the three is-a relations, filled for the rest - the same distinction the
            // canvas draws. A key that fills a head the board leaves open is a key that has to be
            // unlearned the first time somebody compares it with the diagram.
            if (SchemaStyles.SUBCLASS.equals(entry.getStyleName())
                    || SchemaStyles.SUB_PROPERTY.equals(entry.getStyleName())
                    || SchemaStyles.INFERRED_SUBCLASS.equals(entry.getStyleName())) {
                g.drawPolygon(xs, ys, 3);
            } else {
                g.fillPolygon(xs, ys, 3);
            }
        }

        private static BasicStroke strokeFor(String dashPattern) {
            if (dashPattern == null) {
                return new BasicStroke(1.8f);
            }
            String[] parts = dashPattern.trim().split("\\s+");
            float[] dashes = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    dashes[i] = Float.parseFloat(parts[i]);
                } catch (NumberFormatException notANumber) {
                    return new BasicStroke(1.8f);
                }
            }
            return new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    dashes, 0f);
        }

        @Override
        public int getIconWidth() {
            return SWATCH_WIDTH;
        }

        @Override
        public int getIconHeight() {
            return SWATCH_HEIGHT;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension natural = super.getPreferredSize();
        return new Dimension(Math.max(natural.width, 320), natural.height);
    }
}
