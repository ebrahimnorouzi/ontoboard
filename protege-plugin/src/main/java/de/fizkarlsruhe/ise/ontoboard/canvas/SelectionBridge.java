package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventObject;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import java.util.function.Consumer;

/**
 * Keeps the canvas selection and Protege's selection in step.
 *
 * <p>The {@code applyingInbound} guard is essential: without it, pushing Protege's
 * selection onto the canvas fires the canvas listener, which pushes back to Protege,
 * which fires again.
 */
public class SelectionBridge {

    private final SchemaGraph graph;
    private final Consumer<String> onCanvasSelection;
    private final mxIEventListener listener;

    private boolean applyingInbound;

    public SelectionBridge(SchemaGraph graph, Consumer<String> onCanvasSelection) {
        this.graph = graph;
        this.onCanvasSelection = onCanvasSelection;
        this.listener = new mxIEventListener() {
            @Override
            public void invoke(Object sender, mxEventObject event) {
                handleCanvasSelectionChanged();
            }
        };
    }

    public void install() {
        graph.getSelectionModel().addListener(mxEvent.CHANGE, listener);
    }

    public void uninstall() {
        graph.getSelectionModel().removeListener(listener, mxEvent.CHANGE);
    }

    /** Highlights {@code iri} on the canvas. Clears the selection if it is not shown. */
    public void selectOnCanvas(String iri) {
        Object cell = graph.getCellForId(iri);
        applyingInbound = true;
        try {
            if (cell == null) {
                graph.clearSelection();
            } else {
                graph.setSelectionCell(cell);
            }
        } finally {
            applyingInbound = false;
        }
    }

    public String currentCanvasSelection() {
        return graph.getIdForCell(graph.getSelectionCell());
    }

    /**
     * Re-syncs the canvas selection with {@code previousIri} after a call to
     * {@link SchemaGraph#render}, which clears and recreates every cell and so silently
     * drops any live selection - the old cell object simply no longer exists, even if the
     * same IRI is still shown afterwards.
     *
     * <p>If {@code previousIri} still has a cell post-render, it is re-selected through the
     * same suppressed, inbound path as {@link #selectOnCanvas}: nothing conceptually
     * changed from Protege's point of view, so it must not be notified again.
     *
     * <p>If {@code previousIri} no longer has a cell - the node was removed from the
     * canvas, or vanished from the ontology - the loss is real: the canvas and Protege
     * would otherwise permanently disagree about what is selected. So this clears the
     * canvas selection (a no-op if {@link SchemaGraph#render} already cleared it) and,
     * unlike an ordinary unknown-IRI {@link #selectOnCanvas} call, reports the loss outward
     * with {@code null} so the caller can clear Protege's selection too.
     */
    public void resyncAfterRender(String previousIri) {
        if (previousIri == null) {
            return;
        }
        if (graph.getCellForId(previousIri) != null) {
            selectOnCanvas(previousIri);
        } else {
            graph.clearSelection();
            onCanvasSelection.accept(null);
        }
    }

    private void handleCanvasSelectionChanged() {
        if (applyingInbound) {
            return;
        }
        String iri = currentCanvasSelection();
        if (iri != null) {
            onCanvasSelection.accept(iri);
        }
    }
}
