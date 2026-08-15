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
