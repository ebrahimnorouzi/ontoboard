package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.awt.Dimension;

/**
 * How much room a board needs before a drag can move it, and when a drag counts as a drag.
 *
 * <p><b>Why a board that fits the window could not be dragged at all.</b> The gesture was never
 * the problem: {@link CanvasGesture#leftDragPans} returns true for a plain left-drag on empty
 * board, {@code CollaborativeGraphComponent.isPanningEvent} agrees, and JGraphX's panning handler
 * accepts the press and consumes every drag event. But its {@code mouseDragged} is one statement -
 * {@code graphControl.scrollRectToVisible(new Rectangle(x, y, 0, 0))} - so a pan is a scroll of
 * the {@code JViewport}, and {@code mxGraphControl} is only ever as large as the content's far
 * edge ({@code getScaledPreferredSizeForGraph} is {@code round(bounds.x + bounds.width) + border
 * + 1}). A board whose content fits the window therefore gives the viewport a scroll range of
 * zero, and the drag moves nothing. Measured: one node at (40,40,120,40), control 161x81 against
 * a viewport extent of 782x582, the board moved <b>0px for 100px of cursor travel</b>. On a board
 * larger than the window the same handler tracks the cursor exactly 1:1 in all four directions,
 * and the round trip is exact - so there is nothing to fix in the handler and nothing to fix in
 * the gesture. What was missing is somewhere to pan <em>to</em>.
 *
 * <p><b>Why the room is set rather than the view translated.</b> Translating
 * {@code mxGraphView} would also work and was measured to work, but it costs three more files and
 * breaks an invariant this view relies on: {@code zoomTo} calls
 * {@code scaleAndTranslate(newScale, 0, 0)} whenever page view is off, so the translate is
 * permanently (0,0) and {@code SchemaCanvasView.zoomAt} may treat a graph point's pixel as its
 * coordinate times the scale. Measured: with a translate of (-250,-175) in place, one
 * {@code zoomTo} reset it to (0,0) - so a translating pan has to re-apply it around every zoom,
 * and the cursor layer and the minimap have to learn about it too. {@code setMinimumGraphSize}
 * keeps all of that untouched: read from the bytecode of {@code mxGraphControl
 * .updatePreferredSize}, it is applied as {@code max(size, round(min.getWidth() * scale))} and
 * nowhere else, so cell geometry, {@code getGraphBounds}, the PNG/SVG export and the overview are
 * blind to it.
 *
 * <p><b>Units.</b> {@code setMinimumGraphSize} wants board units and is multiplied by the view
 * scale, while a viewport extent is pixels. {@link #room} is the one place that conversion
 * happens, which is why it takes the scale rather than leaving each caller to remember.
 *
 * <p>No Swing here on purpose, and no mxGraph either: the arithmetic is what is wrong in ways a
 * unit test catches, and {@code CollaborativeGraphComponent} cannot be built in a headless test
 * at all - {@code mxGraphHandler} constructs a {@code java.awt.dnd.DragSource}, which throws
 * {@code HeadlessException}. The same reasoning as {@link CanvasGesture}.
 */
public final class CanvasPan {

    /**
     * The room is rounded up to a multiple of this.
     *
     * <p>Not tidiness: giving the control a new preferred size can make a scrollbar appear, which
     * shrinks the viewport extent by the scrollbar's thickness, which would compute a different
     * room, which could take the scrollbar away again. Quantising absorbs a change of that size
     * so the second pass agrees with the first and the layout settles.
     */
    public static final int QUANTUM = 256;

    /** Clear space left between the topmost or leftmost node and the edge of the board. */
    public static final int MARGIN = 40;

    private CanvasPan() {
    }

    /**
     * How far the drawing must be pushed right and down so that nothing sits above the origin.
     *
     * <p><b>Why anything sits above the origin at all.</b> Dragging a node up or left puts it
     * there, and the auto-arrange can too. A real board in this repository had four of its nine
     * nodes at a negative y, the topmost at -400.
     *
     * <p><b>Why that made the top of the board unreachable.</b> A pan is a scroll, and a
     * {@code JViewport} clamps its position at zero - but the deeper problem is that the negative
     * region is not part of the scrollable area in the first place.
     * {@code getScaledPreferredSizeForGraph} is {@code round(bounds.x + bounds.width)}, so a
     * negative origin is <em>subtracted</em> from the size. Measured: content spanning 880x762
     * from (-120,-400) gave a control of 761x363. The missing 400 pixels are the ones the reader
     * cannot reach, and dragging cannot help, because there is nothing there to scroll to.
     *
     * <p>{@code mxGraphControl} carries a {@code translate} for exactly this, and it survives a
     * pan - measured: still (160,440) after a {@code scrollRectToVisible}. Pushing the drawing
     * into positive space is therefore the whole fix, and it moves no cell: geometry, the saved
     * layout, the export and the overview all still see the coordinates the ontology was drawn
     * with.
     *
     * @return the translate for the graph control, in pixels; {@code (0,0)} when the content is
     *         already wholly positive, so an ordinary board is left exactly as it was
     */
    public static java.awt.Point shiftFor(double contentLeftPx, double contentTopPx) {
        int x = contentLeftPx < 0 ? (int) Math.ceil(-contentLeftPx) + MARGIN : 0;
        int y = contentTopPx < 0 ? (int) Math.ceil(-contentTopPx) + MARGIN : 0;
        return new java.awt.Point(x, y);
    }

    /**
     * The minimum graph size that leaves a window of slack beyond the content, in board units.
     *
     * <p>A window of slack rather than a fixed four thousand pixels, because the room is what the
     * scrollbars describe: a constant would put a permanent multi-thousand-pixel range on a board
     * with three terms on it, and a range proportional to the window keeps the thumb meaningful.
     * The floor of two windows is for the case that prompted all of this - a board whose content
     * is smaller than the viewport, where the content term alone would leave nothing to scroll.
     *
     * <p>Slack lies to the right and below the content, which is what a scroll pane can express:
     * the viewport clamps its position at zero, and the minimum's x and y are ignored (read from
     * the bytecode), so the board cannot be dragged past its own top-left corner. That is how
     * every scrolling canvas behaves and it is not worth a translating pan to change.
     *
     * @param contentRightPx  the right edge of the content in pixels, scaled as
     *                        {@code mxGraphView.getGraphBounds()} reports it
     * @param contentBottomPx the bottom edge of the content, likewise
     * @param viewportWidth   the visible width in pixels
     * @param viewportHeight  the visible height in pixels
     * @param scale           the view scale, because the result is in board units
     * @return the size to hand {@code mxGraph.setMinimumGraphSize}, or {@code 0x0} when the
     *         viewport has no size yet, which means "not laid out - ask again later"
     */
    public static Dimension room(double contentRightPx, double contentBottomPx,
            int viewportWidth, int viewportHeight, double scale) {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !(scale > 0)) {
            return new Dimension(0, 0);
        }
        double wide = Math.max(contentRightPx + viewportWidth, 2.0 * viewportWidth) / scale;
        double tall = Math.max(contentBottomPx + viewportHeight, 2.0 * viewportHeight) / scale;
        return new Dimension(upToQuantum(wide), upToQuantum(tall));
    }

    /**
     * Whether a press and a release are far enough apart to have been a drag.
     *
     * <p><b>What this is for.</b> A pan used to clear the board selection, and with it Protege's.
     * JGraphX's panning handler consumes the release only when the press and the release are
     * significantly apart <em>in the graph control's own coordinates</em> - and a successful pan
     * ends with the cursor over the same control pixel it started on, because the board moved
     * underneath it. The measured distance is therefore 0 against a tolerance of 4, the release
     * is not consumed, and {@code mxGraphHandler.mouseReleased} - which is guarded only by
     * {@code !isConsumed} - calls {@code clearSelection()}. Measured: one cell selected before a
     * 100px pan, none after. Since {@code SchemaCanvasView} pushes the canvas selection into
     * {@code OWLSelectionModel}, panning also reset Protege's selected entity.
     *
     * <p>So the distance has to be measured in a frame that does not move during the gesture -
     * the graph component's - and that is the caller's job; this is the comparison, matching
     * {@code mxGraphComponent.isSignificant}: strictly greater than the tolerance, on either
     * axis independently.
     */
    public static boolean movedFarEnough(int fromX, int fromY, int toX, int toY, int tolerance) {
        return Math.abs(toX - fromX) > tolerance || Math.abs(toY - fromY) > tolerance;
    }

    private static int upToQuantum(double value) {
        if (value <= 0) {
            return 0;
        }
        long quanta = (long) Math.ceil(value / QUANTUM);
        return (int) Math.min(quanta * QUANTUM, Integer.MAX_VALUE);
    }
}
