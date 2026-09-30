package de.fizkarlsruhe.ise.ontoboard.canvas;

/**
 * How far the board is zoomed, and how to fit it in the window.
 *
 * <p>The canvas zooms on the wheel and has done since the first version, with no indication anywhere
 * of what the current scale is and no way back to a known one. The failure that produces is
 * particular and easy to reproduce: three turns of the wheel past the last node leaves a blank grey
 * grid, and from there nothing on screen distinguishes "zoomed out into empty space" from "the board
 * is empty" or "the plugin has stopped working". There was no <em>Fit</em>, no <em>100%</em>, and no
 * number - so the way back was to guess how many turns and in which direction.
 *
 * <p>Two rules here are judgements rather than arithmetic, and both are deliberate:
 *
 * <ul>
 *   <li><b>Fit never magnifies.</b> The scale is capped at 1:1 even when four nodes could fill the
 *       window at 300%. Fit exists to show everything at once, and everything is already shown; what
 *       drawing it four times bigger adds is the impression of a zoom level you did not ask for and
 *       then have to undo.
 *   <li><b>The result is clamped, not clipped to zero.</b> A window too small for the content after
 *       margins yields the minimum scale rather than 0, because a scale of 0 divides by nothing
 *       downstream and paints nothing - and "OntoBoard went blank when I made the panel narrow"
 *       would be the report.
 * </ul>
 *
 * <p>Pure arithmetic in its own class so both rules are testable without a window. The mistake this
 * guards against is a unit one: {@code mxGraphView.getGraphBounds()} returns bounds in <em>scaled</em>
 * coordinates, so a fit computed from it directly works at 100% and then drifts at every other zoom
 * level - a bug that looks like Fit "not quite fitting" and is invisible to anybody who only ever
 * tests it from 100%.
 */
public final class CanvasZoom {

    /** As far out as the canvas goes: a tenth of full size. */
    public static final double MIN_SCALE = 0.1;

    /** As far in as the canvas goes. Four times is past the point where labels help. */
    public static final double MAX_SCALE = 4.0;

    /** What Fit leaves around the content, in unscaled pixels, so nothing sits on the frame edge. */
    public static final double FIT_MARGIN = 24;

    private CanvasZoom() {
    }

    /** A scale brought inside the range the canvas supports. */
    public static double clamp(double scale) {
        if (Double.isNaN(scale) || scale <= 0) {
            return MIN_SCALE;
        }
        if (scale < MIN_SCALE) {
            return MIN_SCALE;
        }
        if (scale > MAX_SCALE) {
            return MAX_SCALE;
        }
        return scale;
    }

    /**
     * The scale at which content of this size fits a window of that size.
     *
     * <p>All four numbers are unscaled pixels: the caller divides the view's graph bounds by the
     * current scale before asking. An empty board - or a window not yet laid out, which is the state
     * during construction - answers 1:1, which is the scale somebody would want anyway once they put
     * something on it.
     */
    public static double scaleToFit(double contentWidth, double contentHeight,
            double viewWidth, double viewHeight) {
        if (!positive(contentWidth) || !positive(contentHeight)
                || !positive(viewWidth) || !positive(viewHeight)) {
            return 1.0;
        }

        double usableWidth = viewWidth - 2 * FIT_MARGIN;
        double usableHeight = viewHeight - 2 * FIT_MARGIN;
        if (usableWidth <= 0 || usableHeight <= 0) {
            return MIN_SCALE;
        }

        double fit = Math.min(usableWidth / contentWidth, usableHeight / contentHeight);
        return clamp(Math.min(fit, 1.0));
    }

    /**
     * As {@link #scaleToFit}, but allowed to magnify.
     *
     * <p>Fitting the whole board must not magnify: everything is already shown, and drawing it four
     * times bigger is a zoom level nobody asked for. Framing a selection is the opposite case -
     * putting one 160x60 node in the middle of a 1200px window is the entire point of the gesture,
     * and a version that refused to magnify would leave the node exactly the size it already was.
     */
    public static double scaleToFill(double contentWidth, double contentHeight,
            double viewWidth, double viewHeight) {
        if (!positive(contentWidth) || !positive(contentHeight)
                || !positive(viewWidth) || !positive(viewHeight)) {
            return 1.0;
        }
        double usableWidth = viewWidth - 2 * FIT_MARGIN;
        double usableHeight = viewHeight - 2 * FIT_MARGIN;
        if (usableWidth <= 0 || usableHeight <= 0) {
            return MIN_SCALE;
        }
        return clamp(Math.min(usableWidth / contentWidth, usableHeight / contentHeight));
    }

    /**
     * The scale as a percentage, for the toolbar.
     *
     * <p>Rounded to whole percent. The wheel steps by a factor, so the honest value after a few
     * turns is something like 0.7513148; a readout of "75.13148%" is a number nobody needs and a
     * toolbar that changes width as you zoom.
     */
    public static String readout(double scale) {
        double safe = Double.isNaN(scale) || scale <= 0 ? 1.0 : scale;
        return Math.round(safe * 100) + "%";
    }

    private static boolean positive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }
}
