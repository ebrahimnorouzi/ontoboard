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

    /** Which tooltips are kept open. Never null, so the view can pin without checking. */
    private final transient PinnedTooltips pins = new PinnedTooltips();

    public PinnedTooltips getPins() {
        return pins;
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
     *
     * <p>That last paragraph was written in 1.66.0 and was not true until now: the right button
     * fell through to {@code mxGraphComponent.isPanningEvent}, which in 4.2.2 is literally
     * {@code event.isShiftDown() && event.isControlDown()}, so a right-drag panned only with both
     * modifiers held. The context menu had already been taught to stand aside for a right-drag,
     * so the gesture it was standing aside for did nothing. Named explicitly here instead, and
     * the fall-through is kept so Shift+Ctrl still pans whatever the button.
     */
    @Override
    public boolean isPanningEvent(java.awt.event.MouseEvent event) {
        if (event == null) {
            return false;
        }
        if (javax.swing.SwingUtilities.isMiddleMouseButton(event)
                || javax.swing.SwingUtilities.isRightMouseButton(event)) {
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

    /**
     * The panning handler, taught not to throw the selection away.
     *
     * <p>The library's own handler consumes a release only when the press and the release are
     * significantly apart <em>in the graph control's coordinates</em>, and a pan ends with the
     * cursor over the control pixel it started on, because the board moved underneath it. So the
     * release was never consumed, {@code mxGraphHandler.mouseReleased} ran next - it is guarded
     * only by {@code !isConsumed} - and cleared the selection. Measured: one cell selected before
     * a 100px pan, none after; and because the view pushes the canvas selection into Protege's
     * {@code OWLSelectionModel}, panning also reset the selected entity, which is the opposite of
     * what the other half of this canvas is for.
     *
     * <p>The fix is to measure the gesture in a frame that does not move while it happens. This
     * component does not move; the control inside it does. {@link CanvasPan#movedFarEnough} is the
     * comparison, so it can be tested - none of this class can be, because
     * {@code mxGraphHandler} builds a {@code java.awt.dnd.DragSource} and that throws
     * {@code HeadlessException}.
     *
     * <p>Called from the superclass constructor, like {@link #createGraphHandler()}, so the
     * handler must not read this class's fields. It reads none: {@code getTolerance()} is the
     * superclass's and {@link CanvasPan} is static.
     */
    @Override
    protected com.mxgraph.swing.handler.mxPanningHandler createPanningHandler() {
        return new com.mxgraph.swing.handler.mxPanningHandler(this) {
            private static final long serialVersionUID = 1L;

            private transient java.awt.Point pressedAt;

            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                pressedAt = inThisComponent(event);
                super.mousePressed(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                java.awt.Point now = inThisComponent(event);
                if (pressedAt != null && now != null && CanvasPan.movedFarEnough(
                        pressedAt.x, pressedAt.y, now.x, now.y, getTolerance())) {
                    // Before super, which is where the library would have decided the same thing
                    // and got it wrong, and before the drag handler, which is registered after
                    // this one and reads isConsumed.
                    event.consume();
                }
                pressedAt = null;
                super.mouseReleased(event);
            }
        };
    }

    /**
     * A mouse event's point in this component's coordinates, or null if it cannot be converted.
     *
     * <p>Not {@code getLocationOnScreen}: that throws {@code IllegalComponentStateException} for a
     * component that is not showing, which is every synthetic event in a test and every event
     * during construction.
     */
    private java.awt.Point inThisComponent(java.awt.event.MouseEvent event) {
        if (event == null || event.getComponent() == null) {
            return null;
        }
        return javax.swing.SwingUtilities.convertPoint(
                event.getComponent(), event.getPoint(), this);
    }

    /** The guides the drag handler currently wants drawn, or none. */
    /**
     * Draws the pinned cards, if the graph can say what they should contain.
     *
     * <p>Only a {@link SchemaGraph} holds the tooltips, and the component is constructed against
     * a plain {@code mxGraph} in tests, so this asks rather than assumes.
     */
    private void paintPins(Graphics2D graphics) {
        if (pins.isEmpty() || !(getGraph() instanceof SchemaGraph)) {
            return;
        }
        PinnedTooltipLayer.paint(graphics, getGraph(),
                pins.cards(((SchemaGraph) getGraph()).tooltips()), getViewport().getViewRect());
    }

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
                // Above the badges and cursors: a pinned card is something the reader opened and
                // is reading, so nothing on the board should cover it.
                paintPins((Graphics2D) graphics);
                // Last, so a guide is never hidden behind a node, a badge or a cursor. It is
                // chrome for the duration of a drag and has to be readable over whatever it
                // crosses.
                GuideLayer.paint((Graphics2D) graphics, activeGuides());
            }
        };
    }
}
