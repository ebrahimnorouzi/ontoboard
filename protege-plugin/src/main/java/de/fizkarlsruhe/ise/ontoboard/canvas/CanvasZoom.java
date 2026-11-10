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

    /**
     * The point, in the window's pixels, that + and - keep still: the selection when it is in view,
     * else the box nearest the middle of what is in view, else that middle (1.135.1, Windows
     * study rehearsal). The middle of what is in view lay in the empty band between two frames
     * after Fit, and three presses of + slid the classes out of view around a point where nothing
     * was.
     *
     * @param view the visible part of the board, in its pixels
     * @param boxes the terms' boxes, in the same pixels
     */
    public static java.awt.Point anchor(java.awt.Rectangle view, java.awt.geom.Rectangle2D selection,
            java.awt.geom.Rectangle2D content,
            java.util.Collection<? extends java.awt.geom.Rectangle2D> boxes) {
        java.awt.Point middle = new java.awt.Point(view.width / 2, view.height / 2);
        if (selection != null && view.contains(selection.getCenterX(), selection.getCenterY())) {
            return new java.awt.Point((int) selection.getCenterX() - view.x,
                    (int) selection.getCenterY() - view.y);
        }
        if (content == null || content.isEmpty()) {
            return middle;
        }
        java.awt.geom.Rectangle2D seen = view.createIntersection(content);
        if (seen.isEmpty()) {
            return middle;
        }
        java.awt.geom.Rectangle2D at = seen;
        double nearest = Double.MAX_VALUE;
        if (boxes != null) {
            for (java.awt.geom.Rectangle2D box : boxes) {
                if (box == null || !view.contains(box.getCenterX(), box.getCenterY())) {
                    continue;
                }
                double distance = java.awt.geom.Point2D.distanceSq(box.getCenterX(),
                        box.getCenterY(), seen.getCenterX(), seen.getCenterY());
                if (distance < nearest) {
                    nearest = distance;
                    at = box;
                }
            }
        }
        return new java.awt.Point((int) at.getCenterX() - view.x, (int) at.getCenterY() - view.y);
    }

    /** What Fit says: "Fitted the board to the window at 26%." */
    public static String fitted(String what, double scale) {
        return "Fitted " + what + " to the window at " + readout(scale) + ".";
    }

    /**
     * What Arrange says, before the sentence about the terms it left in frames: "Arranged and fitted
     * to the window at 31%: Grid." - or, where fitting would leave the board too small to read,
     * "Arranged, at 50% so it can be read; Fit shows all of it: Grid."
     */
    public static String arranged(boolean fitted, double scale, CanvasLayouts.Algorithm algorithm) {
        return (fitted ? "Arranged and fitted to the window at " + readout(scale)
                : "Arranged, at " + readout(scale) + " so it can be read; Fit shows all of it")
                + ": " + algorithm.getDisplayName() + ".";
    }

    /** What a zoom says in place of one of the lines above: "Zoomed to 46%." */
    public static String zoomed(double scale) {
        return "Zoomed to " + readout(scale) + ".";
    }

    /**
     * The lines the zoom, Fit and Arrange write, each with the zoom it names as group 1 - and only
     * those. Any line naming a zoom used to count, so the next turn of the wheel replaced what
     * Relate said, "...both ends in view at 80%. File > Save keeps it.", and a save no longer said
     * "Saved."; a Find line quoting a label such as "heated to 100%" went the same way (1.135.1
     * review). The sentence after an arrangement is SchemaCanvasView.inFramesSentence's.
     */
    private static final java.util.List<java.util.regex.Pattern> ZOOMS_OWN = zoomsOwn();

    private static java.util.List<java.util.regex.Pattern> zoomsOwn() {
        StringBuilder names = new StringBuilder();
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            names.append(names.length() == 0 ? "" : "|")
                    .append(java.util.regex.Pattern.quote(algorithm.getDisplayName()));
        }
        String percent = "(\\d{1,3}%)";
        String arrangement = ": (?:" + names + ")\\.(?: The term inside a frame stays where it"
                + " is\\.| The \\d+ terms inside frames stay where they are\\.)?";
        return java.util.Arrays.asList(
                java.util.regex.Pattern.compile("Zoomed to " + percent + "\\."),
                java.util.regex.Pattern.compile("Fitted (?:the board|the selection|\\d+ selected"
                        + "|the frame .*) to the window at " + percent + "\\."),
                java.util.regex.Pattern.compile("Arranged and fitted to the window at " + percent
                        + arrangement),
                java.util.regex.Pattern.compile("Arranged, at " + percent
                        + " so it can be read; Fit shows all of it" + arrangement));
    }

    /**
     * The status line after a zoom: one the zoom, Fit or Arrange wrote names the zoom the board is
     * at now, and every other is left alone (1.135.1). "...fitted to the window at 26%" stayed on
     * with the board at 46%.
     */
    public static String afterZoom(String said, double scale) {
        if (said == null) {
            return null;
        }
        for (java.util.regex.Pattern own : ZOOMS_OWN) {
            java.util.regex.Matcher line = own.matcher(said);
            if (line.matches()) {
                return line.group(1).equals(readout(scale)) ? said : zoomed(scale);
            }
        }
        return said;
    }

    private static boolean positive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }
}
