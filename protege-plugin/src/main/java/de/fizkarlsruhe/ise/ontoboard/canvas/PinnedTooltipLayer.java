package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxGraph;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the pinned tooltip cards, each anchored beside the cell it belongs to.
 *
 * <p>An overlay, like {@link NoteBadgeLayer} and {@link PeerCursorLayer}, and for the same
 * reason it carries the same caveat: {@code CanvasExport} renders from the graph model and never
 * calls the component's {@code paint}, so nothing here appears in an exported PNG or SVG. That
 * is the right behaviour rather than a limitation - a pinned card is something you opened while
 * reading, not part of the diagram you are publishing.
 *
 * <p>Anchored to the cell's current state, so a card follows its term through a pan, a zoom and
 * a drag. A card at a remembered pixel position would be beside the right term until the first
 * scroll and beside the wrong one afterwards, which is worse than not having it.
 */
public final class PinnedTooltipLayer {

    static final Color BACKGROUND = new Color(0xFF, 0xFD, 0xE8);
    static final Color BORDER = new Color(0x8A, 0x7A, 0x2E);
    static final Color TEXT = new Color(0x22, 0x22, 0x22);
    static final Color SHADOW = new Color(0, 0, 0, 40);

    static final int PADDING = 8;
    static final int LINE_HEIGHT = 15;
    static final int FONT_SIZE = 11;

    /** How far right of the cell a card sits, before being pushed back on screen. */
    static final int OFFSET_X = 12;

    private PinnedTooltipLayer() {
    }

    /** Paints every card. Does nothing when there are none. */
    public static void paint(Graphics2D graphics, mxGraph graph, List<PinnedTooltips.Card> cards,
            Rectangle visible) {
        if (graphics == null || graph == null || cards == null || cards.isEmpty()) {
            return;
        }
        Graphics2D scratch = (Graphics2D) graphics.create();
        try {
            scratch.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            scratch.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
            scratch.setFont(font);
            FontMetrics metrics = scratch.getFontMetrics(font);
            for (PinnedTooltips.Card card : cards) {
                mxCellState state = stateOf(graph, card.getCellId());
                if (state == null) {
                    continue;
                }
                Rectangle box = boxFor(card, metrics, state, visible);
                drawCard(scratch, card, box, metrics);
            }
        } finally {
            scratch.dispose();
        }
    }

    /**
     * Where a card goes: beside its cell, nudged back inside the viewport if it would fall out.
     *
     * <p>Nudged rather than clipped. A card half off the right edge is a card whose IRI cannot
     * be read, and the IRI is usually the reason it was pinned.
     */
    static Rectangle boxFor(PinnedTooltips.Card card, FontMetrics metrics, mxCellState state,
            Rectangle visible) {
        int width = 0;
        for (String line : card.getLines()) {
            width = Math.max(width, metrics.stringWidth(line));
        }
        width += PADDING * 2;
        int height = card.getLines().size() * LINE_HEIGHT + PADDING * 2;

        int x = (int) Math.round(state.getX() + state.getWidth()) + OFFSET_X;
        int y = (int) Math.round(state.getY());
        if (visible != null) {
            if (x + width > visible.x + visible.width) {
                // Flip to the cell's left rather than overhanging.
                x = (int) Math.round(state.getX()) - width - OFFSET_X;
            }
            x = Math.max(visible.x + 2, Math.min(x, visible.x + visible.width - width - 2));
            y = Math.max(visible.y + 2, Math.min(y, visible.y + visible.height - height - 2));
        }
        return new Rectangle(x, y, width, height);
    }

    private static void drawCard(Graphics2D graphics, PinnedTooltips.Card card, Rectangle box,
            FontMetrics metrics) {
        graphics.setColor(SHADOW);
        graphics.fillRoundRect(box.x + 2, box.y + 2, box.width, box.height, 8, 8);
        graphics.setColor(BACKGROUND);
        graphics.fillRoundRect(box.x, box.y, box.width, box.height, 8, 8);
        graphics.setColor(BORDER);
        graphics.setStroke(new BasicStroke(1f));
        graphics.drawRoundRect(box.x, box.y, box.width, box.height, 8, 8);

        graphics.setColor(TEXT);
        int baseline = box.y + PADDING + metrics.getAscent();
        for (String line : card.getLines()) {
            graphics.drawString(line, box.x + PADDING, baseline);
            baseline += LINE_HEIGHT;
        }
    }

    /** The drawn state of a cell by its id, or null when it is not on the board. */
    static mxCellState stateOf(mxGraph graph, String cellId) {
        Object parent = graph.getDefaultParent();
        List<Object> queue = new ArrayList<Object>();
        queue.add(parent);
        while (!queue.isEmpty()) {
            Object cell = queue.remove(0);
            if (cell != parent && cellId.equals(idOf(graph, cell))) {
                return graph.getView().getState(cell);
            }
            int children = graph.getModel().getChildCount(cell);
            for (int at = 0; at < children; at++) {
                queue.add(graph.getModel().getChildAt(cell, at));
            }
        }
        return null;
    }

    private static String idOf(mxGraph graph, Object cell) {
        if (cell instanceof com.mxgraph.model.mxCell) {
            return ((com.mxgraph.model.mxCell) cell).getId();
        }
        return null;
    }
}
