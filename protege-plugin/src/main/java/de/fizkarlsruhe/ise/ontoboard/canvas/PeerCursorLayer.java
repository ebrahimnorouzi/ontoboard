package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.collab.PeerCursors;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;

/**
 * Draws other people's cursors and selections over the canvas.
 *
 * <p>Deliberately thin: which cursors exist, where they are and what colour they take are all
 * decided in {@link PeerCursors}, which is unit-tested. What is left here is drawing, and drawing
 * can only be checked by looking at it.
 *
 * <p>Coordinates are graph space, as {@code PeerPresence} documents, so a cursor lands on the same
 * entity for everyone regardless of window size, scroll position or zoom. This paints on the graph
 * <em>control</em> - the scrolled content inside mxGraphComponent's viewport - so the scroll offset
 * is already applied by the scroll pane and only the zoom has to be, which is the mirror image of
 * {@code SchemaCanvasView.toGraphPoint}.
 */
public final class PeerCursorLayer {

    /** Height of the drawn pointer in screen pixels, so it does not shrink when zoomed out. */
    private static final int POINTER_HEIGHT = 16;
    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    private static final Stroke SELECTION_STROKE =
            new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private PeerCursorLayer() {
    }

    /**
     * Paints every visible peer.
     *
     * @param graphics the graph control's graphics; not disposed here
     * @param graph the graph, for locating a peer's selected cell
     * @param cursors the peers to draw, or null when not collaborating
     * @param now the clock used for staleness, passed in so the caller owns the time source
     */
    public static void paint(Graphics2D graphics, mxGraph graph, PeerCursors cursors, long now) {
        if (cursors == null || graphics == null || graph == null) {
            return;
        }
        List<PeerCursors.Marker> markers = cursors.visibleAt(now);
        if (markers.isEmpty()) {
            return;
        }
        double scale = graph.getView().getScale();
        if (scale <= 0) {
            scale = 1;
        }
        Object antialiasing = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Font previousFont = graphics.getFont();
        Stroke previousStroke = graphics.getStroke();
        Color previousColour = graphics.getColor();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            for (PeerCursors.Marker marker : markers) {
                drawSelection(graphics, graph, marker);
                drawCursor(graphics, marker, scale);
            }
        } finally {
            // Restored rather than left behind: the same Graphics goes on to paint the rest of
            // the canvas, and a stroke or font left set here would change how cells look.
            graphics.setFont(previousFont);
            graphics.setStroke(previousStroke);
            graphics.setColor(previousColour);
            if (antialiasing != null) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialiasing);
            }
        }
    }

    /** A ring round the node a peer has selected, so you can see what they are working on. */
    private static void drawSelection(Graphics2D graphics, mxGraph graph,
            PeerCursors.Marker marker) {
        if (marker.getSelection() == null || !(graph instanceof SchemaGraph)) {
            return;
        }
        Object cell = ((SchemaGraph) graph).getCellForId(marker.getSelection());
        if (cell == null) {
            // They are on something this canvas does not show. Nothing to ring, and their
            // cursor still says where they are.
            return;
        }
        mxCellState state = graph.getView().getState(cell);
        if (state == null) {
            return;
        }
        graphics.setStroke(SELECTION_STROKE);
        graphics.setColor(marker.getColour());
        graphics.drawRoundRect((int) state.getX() - 4, (int) state.getY() - 4,
                (int) state.getWidth() + 8, (int) state.getHeight() + 8, 12, 12);
    }

    private static void drawCursor(Graphics2D graphics, PeerCursors.Marker marker, double scale) {
        int x = (int) Math.round(marker.getX() * scale);
        int y = (int) Math.round(marker.getY() * scale);

        Polygon pointer = new Polygon();
        pointer.addPoint(x, y);
        pointer.addPoint(x, y + POINTER_HEIGHT);
        pointer.addPoint(x + POINTER_HEIGHT / 3, y + POINTER_HEIGHT - POINTER_HEIGHT / 4);
        pointer.addPoint(x + POINTER_HEIGHT / 2, y + POINTER_HEIGHT + 1);
        pointer.addPoint(x + POINTER_HEIGHT * 2 / 3, y + POINTER_HEIGHT - POINTER_HEIGHT / 3);
        pointer.addPoint(x + POINTER_HEIGHT * 3 / 4, y + POINTER_HEIGHT * 3 / 4);

        // Outlined in white first, so a dark cursor stays visible against a dark node.
        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(Color.WHITE);
        graphics.drawPolygon(pointer);
        graphics.setColor(marker.getColour());
        graphics.fillPolygon(pointer);

        drawLabel(graphics, marker, x + POINTER_HEIGHT * 3 / 4, y + POINTER_HEIGHT);
    }

    private static void drawLabel(Graphics2D graphics, PeerCursors.Marker marker, int x, int y) {
        graphics.setFont(LABEL_FONT);
        FontMetrics metrics = graphics.getFontMetrics();
        String name = marker.getUser();
        int width = metrics.stringWidth(name) + 10;
        int height = metrics.getHeight() + 2;

        graphics.setColor(marker.getColour());
        graphics.fillRoundRect(x, y, width, height, 6, 6);
        graphics.setColor(readableOn(marker.getColour()));
        graphics.drawString(name, x + 5, y + metrics.getAscent() + 1);
    }

    /**
     * Black or white, whichever is readable on {@code background}.
     *
     * <p>Peer colours come from other clients and can be anything, so a fixed text colour would
     * leave some names unreadable. ITU-R BT.601 luma is the usual rule of thumb.
     */
    static Color readableOn(Color background) {
        double luma = 0.299 * background.getRed() + 0.587 * background.getGreen()
                + 0.114 * background.getBlue();
        return luma > 140 ? Color.BLACK : Color.WHITE;
    }
}
