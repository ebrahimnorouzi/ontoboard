package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.util.List;

/**
 * Draws the alignment guides while a node is being dragged.
 *
 * <p>An overlay, like {@link NoteBadgeLayer} and {@link PeerCursorLayer}, and for the same reason
 * stated there: {@code CanvasExport} renders from the graph model and never calls the component's
 * paint. That is correct here rather than a trade-off - a guide exists for the duration of a drag
 * and has no business in an exported diagram.
 *
 * <p>Magenta, 1px, with a short dash. The colour is the one no ontology namespace is assigned and
 * the one every design tool uses for the same purpose, so it reads as chrome rather than as part
 * of the diagram; the dash says the same thing a second way, for anyone who cannot rely on
 * colour. A line that could be mistaken for an edge would be a bad guide.
 */
public final class GuideLayer {

    /** The guide colour: deliberately not a namespace colour and not the unsatisfiable red. */
    static final Color GUIDE = new Color(0xE0, 0x2F, 0xA8);

    /** Extends each line a little past the nodes, so it reads as a guide and not as a join. */
    static final int OVERHANG = 12;

    private static final Stroke DASHED = new BasicStroke(1f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f);

    private GuideLayer() {
    }

    /** Draws every guide. Does nothing, quickly, when there are none - which is almost always. */
    public static void paint(Graphics2D graphics, List<AlignmentGuides.Guide> guides) {
        if (graphics == null || guides == null || guides.isEmpty()) {
            return;
        }
        Color savedColour = graphics.getColor();
        Stroke savedStroke = graphics.getStroke();
        Object savedHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        // Off, not on. A 1px line on an exact pixel is crisper without it, and a blurred guide
        // is one you cannot tell is exactly on the edge.
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setColor(GUIDE);
        graphics.setStroke(DASHED);
        try {
            for (AlignmentGuides.Guide guide : guides) {
                if (guide == null) {
                    continue;
                }
                if (guide.isVertical()) {
                    graphics.drawLine(guide.getPosition(), guide.getStart() - OVERHANG,
                            guide.getPosition(), guide.getEnd() + OVERHANG);
                } else {
                    graphics.drawLine(guide.getStart() - OVERHANG, guide.getPosition(),
                            guide.getEnd() + OVERHANG, guide.getPosition());
                }
            }
        } finally {
            graphics.setColor(savedColour);
            graphics.setStroke(savedStroke);
            if (savedHint != null) {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, savedHint);
            }
        }
    }

    /**
     * The area the guides occupy, so a repaint can be a strip rather than the whole board.
     *
     * <p>A guide spans the nodes it joins, which on a tall board is most of the viewport, and the
     * drag repaints it on every mouse event. Repainting the union of the old and the new strips
     * keeps that to two thin bands.
     *
     * @return the union, or null when there is nothing to paint
     */
    public static Rectangle areaOf(List<AlignmentGuides.Guide> guides) {
        Rectangle area = null;
        if (guides == null) {
            return null;
        }
        for (AlignmentGuides.Guide guide : guides) {
            if (guide == null) {
                continue;
            }
            // Two pixels either side of the line, so an antialiased or rounded edge is included.
            Rectangle one = guide.isVertical()
                    ? new Rectangle(guide.getPosition() - 2, guide.getStart() - OVERHANG, 5,
                            guide.getEnd() - guide.getStart() + 2 * OVERHANG)
                    : new Rectangle(guide.getStart() - OVERHANG, guide.getPosition() - 2,
                            guide.getEnd() - guide.getStart() + 2 * OVERHANG, 5);
            area = area == null ? one : area.union(one);
        }
        return area;
    }
}
