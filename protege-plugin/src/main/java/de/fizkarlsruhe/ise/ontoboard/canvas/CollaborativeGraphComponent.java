package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.collab.PeerCursors;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * An {@link mxGraphComponent} that paints peer cursors on top of the diagram.
 *
 * <p>Overriding the graph control's paint rather than adding a Swing overlay is what keeps the
 * cursors out of everything else's way: no extra component means nothing new in the hit-test
 * order, so mxGraph's drag, marquee selection and the palette's TransferHandler are untouched. A
 * transparent overlay component would have swallowed mouse events, which is how drag-and-drop on
 * this canvas broke once already.
 *
 * <p>{@link #createGraphControl()} runs inside the superclass constructor, before this class's own
 * fields are assigned, so it must not read them. The anonymous control reads {@link #cursors} at
 * paint time instead - long after construction - and tolerates null until a session starts.
 */
public final class CollaborativeGraphComponent extends mxGraphComponent {

    private static final long serialVersionUID = 1L;

    private transient PeerCursors cursors;

    public CollaborativeGraphComponent(mxGraph graph) {
        super(graph);
    }

    /** Starts drawing {@code cursors}; null stops it. Repainting is the caller's business. */
    public void setPeerCursors(PeerCursors cursors) {
        this.cursors = cursors;
    }

    /** True while the space bar is held, which turns a left-drag into a pan. */
    private transient boolean spaceHeld;

    /** Told by the view's key bindings. */
    public void setSpaceHeld(boolean held) {
        this.spaceHeld = held;
    }

    public boolean isSpaceHeld() {
        return spaceHeld;
    }

    /** What a plain left-drag on empty board does. The view sets it; see {@link CanvasGesture}. */
    private transient CanvasGesture.Mode gesture = CanvasGesture.DEFAULT;

    /** Changes what a plain left-drag does. Repainting the cursor is the caller's business. */
    public void setGestureMode(CanvasGesture.Mode mode) {
        this.gesture = mode == null ? CanvasGesture.DEFAULT : mode;
    }

    public CanvasGesture.Mode getGestureMode() {
        return gesture;
    }

    /**
     * Whether this press should pan. The decision itself lives in {@link CanvasGesture}.
     *
     * <p>Shared with the rubberband in the view rather than restated, because the two must be
     * exact complements: a combination both claimed would pan and marquee at once, and one
     * neither claimed would make the drag do nothing at all. A test covers every combination of
     * the four inputs; neither call site can be tested.
     *
     * <p>The middle and right buttons always pan, which is mxGraph's own convention and the one
     * every canvas application shares. Since 1.66.0 the context menu no longer opens at the end
     * of a right-drag.
     */
    @Override
    public boolean isPanningEvent(java.awt.event.MouseEvent event) {
        if (event == null) {
            return false;
        }
        if (javax.swing.SwingUtilities.isMiddleMouseButton(event)) {
            return true;
        }
        if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
            return CanvasGesture.leftDragPans(gesture, spaceHeld,
                    event.isShiftDown() || event.isControlDown(),
                    getCellAt(event.getX(), event.getY()) != null);
        }
        return super.isPanningEvent(event);
    }

    /**
     * The drag handler, with alignment guides.
     *
     * <p>Called from the superclass constructor, so it must not read this class's fields - the
     * same constraint {@link #createGraphControl()} documents.
     */
    @Override
    protected com.mxgraph.swing.handler.mxGraphHandler createGraphHandler() {
        return new GuideGraphHandler(this);
    }

    /** The guides the drag handler currently wants drawn, or none. */
    private java.util.List<AlignmentGuides.Guide> activeGuides() {
        com.mxgraph.swing.handler.mxGraphHandler handler = getGraphHandler();
        return handler instanceof GuideGraphHandler
                ? ((GuideGraphHandler) handler).getGuides()
                : java.util.Collections.<AlignmentGuides.Guide>emptyList();
    }

    /**
     * Alt does not force a marquee here.
     *
     * <p>Alt is the library's "ignore the grid this once" modifier - {@code isGridEnabledEvent} is
     * exactly {@code !isAltDown} - and the library's own default hands the same key to a forced
     * marquee. Both cannot be true. {@code mxGraphHandler.mousePressed} returns before it looks for
     * a cell when a marquee is forced, and this canvas's rubberband only starts on Ctrl or Shift, so
     * Alt+drag reached neither handler and did nothing at all.
     */
    @Override
    public boolean isForceMarqueeEvent(java.awt.event.MouseEvent event) {
        return false;
    }

    /**
     * Shift adds to the selection, as it does on every board.
     *
     * <p>{@code mxGraphComponent.isToggleEvent} knows only Ctrl, so Shift+click and Shift+drag threw
     * away whatever was already selected - while {@code installSelection}'s own comment says Shift
     * starts an additive rubberband. {@code selectCellsForEvent} is the single branch point for both
     * gestures, so this one override fixes both.
     */
    @Override
    public boolean isToggleEvent(java.awt.event.MouseEvent event) {
        return event != null && (event.isShiftDown() || super.isToggleEvent(event));
    }

    @Override
    protected mxGraphComponent.mxGraphControl createGraphControl() {
        // An inner class of mxGraphComponent, so the enclosing instance is implicit - passing
        // `this` explicitly does not compile.
        return new mxGraphControl() {
            private static final long serialVersionUID = 1L;

            @Override
            public void paint(Graphics graphics) {
                super.paint(graphics);
                // Badges under the cursors, so a peer's ring and label are never hidden behind one.
                NoteBadgeLayer.paint((Graphics2D) graphics, getGraph());
                // Read here rather than in the enclosing constructor: this runs later, once the
                // field exists.
                PeerCursorLayer.paint((Graphics2D) graphics, getGraph(), cursors,
                        System.currentTimeMillis());
                // Last, so a guide is never hidden behind a node, a badge or a cursor. It is
                // chrome for the duration of a drag and has to be readable over whatever it
                // crosses.
                GuideLayer.paint((Graphics2D) graphics, activeGuides());
            }
        };
    }
}
