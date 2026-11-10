package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What to show after a relation is drawn: the new arrow and both its boxes, as large as the
 * window allows (1.135.1, Windows study rehearsal).
 *
 * <p>A tester relating egg boiling to raw egg saw nothing happen: raw egg stood 1,160 px away, off
 * the window, and the arrow with it; after Fit at 26% both ends were on screen and neither label
 * could be read. Pure decisions, so they can be tested without a window; the view moves the
 * camera only, never a box.
 */
public final class RelationInView {

    /** Arrange's floor for a readable board, which framing aims at when it can. */
    static final double LEGIBLE = 0.5;

    private RelationInView() {
    }

    /**
     * The arrows between the two terms, in either direction, and both ends; empty when an end is
     * not on the board or no arrow joins them - a refused change, a global reading, a hidden layer
     * - so nothing moves when nothing was drawn.
     */
    public static Object[] cellsOf(SchemaGraph graph, String sourceId, String targetId) {
        Object source = graph.getCellForId(sourceId);
        Object target = graph.getCellForId(targetId);
        if (source == null || target == null) {
            return new Object[0];
        }
        Object[] edges = graph.getEdgesBetween(source, target, false);
        if (edges == null || edges.length == 0) {
            return new Object[0];
        }
        List<Object> cells = new ArrayList<Object>(Arrays.asList(edges));
        cells.add(source);
        cells.add(target);
        return cells.toArray();
    }

    /**
     * The zoom to show content of this size whole, or NaN to leave the view as it is: when it is
     * all in view already at a zoom framing would not improve. All sizes are unscaled pixels.
     */
    public static double scaleToShow(double contentW, double contentH, double viewW, double viewH,
            double current, boolean allInView) {
        if (viewW <= 0 || viewH <= 0) {
            return Double.NaN;
        }
        double fit = CanvasZoom.scaleToFit(contentW, contentH, viewW, viewH);
        if (allInView && current >= Math.min(fit, LEGIBLE) - 1e-9) {
            return Double.NaN;
        }
        return fit;
    }
}
