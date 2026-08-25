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

    @Override
    protected mxGraphComponent.mxGraphControl createGraphControl() {
        // An inner class of mxGraphComponent, so the enclosing instance is implicit - passing
        // `this` explicitly does not compile.
        return new mxGraphControl() {
            private static final long serialVersionUID = 1L;

            @Override
            public void paint(Graphics graphics) {
                super.paint(graphics);
                // Read here rather than in the enclosing constructor: this runs later, once the
                // field exists.
                PeerCursorLayer.paint((Graphics2D) graphics, getGraph(), cursors,
                        System.currentTimeMillis());
            }
        };
    }
}
