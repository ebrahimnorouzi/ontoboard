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

    /**
     * Space, the middle button or the right button pan. A plain left-drag selects.
     *
     * <p>This is the reverse of what it was, and the reversal is deliberate. The original comment
     * read "out of the box there is no way to move the diagram with the mouse at all - the canvas
     * felt stuck", and that was true when it was written: there was no outline panel, no fit, no
     * zoom keys and no drag-to-connect. It is not true now, and a plain drag is the one gesture a
     * board user spends most - selecting several things at once - while this canvas had it behind
     * Ctrl or Shift and said so only in a doc comment.
     *
     * <p>Held to two rules. A drag that starts on a cell still moves the cell, or nothing could ever
     * be repositioned. And the three pan triggers are the three every canvas application uses, so
     * the muscle memory this asks for is one people already have.
     *
     * <p>The keystroke list in {@code ShortcutsPanel} exists because of this method. An inversion
     * nobody is told about is indistinguishable from a bug.
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
            return spaceHeld && getCellAt(event.getX(), event.getY()) == null;
        }
        // The right button keeps panning, which is mxGraph's own convention. Since 1.66.0 the
        // context menu no longer opens at the end of one.
        return super.isPanningEvent(event);
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
            }
        };
    }
}
