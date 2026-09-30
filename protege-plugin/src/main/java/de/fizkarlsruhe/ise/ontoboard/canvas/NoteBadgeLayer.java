package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxGraph;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.Set;

/**
 * A corner badge on every term that carries an editorial note.
 *
 * <p>A note and an unsatisfiable class were both drawn on the border - one by making it thicker, the
 * other by making it red - so on a term that is both, the two markers compete for the same few pixels
 * and the reader has to know which of thickness and colour means which. The badge moves "somebody has
 * written a note about this" onto its own channel, in the corner, where it reads at a glance and
 * alongside anything else the border is saying.
 *
 * <p>Painted as an overlay, like {@link PeerCursorLayer}, rather than as a cell style. That is a real
 * trade-off and not a shortcut: {@code CanvasExport} renders through
 * {@code mxCellRenderer.createBufferedImage} and {@code createSvgDocument}, which draw from the graph
 * model and never call the component's {@code paint} - so nothing painted here appears in an exported
 * PNG or SVG. This is exactly why the heavier border stays: it is the marker that survives an export,
 * and dropping it in favour of a badge would have made notes invisible in every diagram anybody
 * publishes.
 */
public final class NoteBadgeLayer {

    /** The badge's fill: amber, which is what an annotation reads as and is not the error colour. */
    static final Color BADGE = new Color(0xF2, 0xA0, 0x0C);

    /** Drawn round the fill so the badge is visible on a node of any namespace colour. */
    static final Color OUTLINE = new Color(0x5C, 0x3D, 0x00);

    /** How big the badge would be at 1:1. */
    static final double BADGE_AT_FULL_SIZE = 14;

    /** Small enough to be a badge, large enough to see when the board is zoomed out. */
    static final int SMALLEST = 7;

    /** Beyond this it stops reading as a badge and starts reading as part of the diagram. */
    static final int LARGEST = 18;

    private static final Stroke OUTLINE_STROKE = new BasicStroke(1f);

    private NoteBadgeLayer() {
    }

    /**
     * Draws a badge on each noted term the graph currently has a state for.
     *
     * <p>Cells with no state are skipped rather than guessed at: a cell out of view, or one mid-render,
     * has no position, and a badge at a guessed position is worse than none.
     */
    public static void paint(Graphics2D graphics, mxGraph graph) {
        if (graphics == null || !(graph instanceof SchemaGraph)) {
            return;
        }
        SchemaGraph schema = (SchemaGraph) graph;
        Set<String> noted = schema.getNotedIds();
        if (noted.isEmpty()) {
            return;
        }

        Object savedHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Color savedColour = graphics.getColor();
        Stroke savedStroke = graphics.getStroke();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            int size = badgeSize(graph.getView().getScale());
            for (String id : noted) {
                Object cell = schema.getCellForId(id);
                if (cell == null) {
                    continue;
                }
                mxCellState state = graph.getView().getState(cell);
                if (state == null) {
                    continue;
                }
                int[] box = badgeBounds((int) state.getX(), (int) state.getY(),
                        (int) state.getWidth(), size);
                graphics.setColor(BADGE);
                graphics.fillOval(box[0], box[1], size, size);
                graphics.setColor(OUTLINE);
                graphics.setStroke(OUTLINE_STROKE);
                graphics.drawOval(box[0], box[1], size, size);
            }
        } finally {
            // Restored rather than left behind: the same Graphics goes on to paint the peer cursors
            // and whatever Swing paints after them.
            graphics.setColor(savedColour);
            graphics.setStroke(savedStroke);
            if (savedHint != null) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedHint);
            }
        }
    }

    /**
     * How big the badge is at this zoom.
     *
     * <p>Scaled, then clamped at both ends. Unclamped, a board at 10% draws a badge one pixel across -
     * which is the zoom level at which somebody is looking for which of a hundred terms has a note -
     * and a board at 400% draws one the size of the node's label.
     */
    static int badgeSize(double scale) {
        double zoom = Double.isNaN(scale) || scale <= 0 ? 1 : scale;
        return (int) Math.max(SMALLEST, Math.min(LARGEST, Math.round(BADGE_AT_FULL_SIZE * zoom)));
    }

    /**
     * Where the badge sits: straddling the node's top-right corner.
     *
     * <p>Half in and half out, so it never covers the label and never floats free of the node. Inside
     * the border it would sit on the text of a term with a long name; fully outside it would read as a
     * separate object, and on a dense board as belonging to the node above and right.
     *
     * @return {@code {x, y}} to pass to {@code fillOval}, in the same view coordinates as the state
     */
    static int[] badgeBounds(int cellX, int cellY, int cellWidth, int size) {
        return new int[] { cellX + cellWidth - size / 2, cellY - size / 2 };
    }
}
