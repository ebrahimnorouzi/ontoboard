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

    /**
     * Highlights {@code iri} on the canvas. Clears the selection if it is not shown.
     *
     * <p>Every cell that stands for the entity is selected, not one of them: a property is
     * usually several arrows, and picking one would jump the board to whichever the projection
     * emitted first. {@code installSelection} already calls {@code setSingleSelection(false)}, so
     * this is legal.
     */
    public void selectOnCanvas(String iri) {
        select(iri, reveal);
    }

    /**
     * What the canvas currently has selected, as the entity it stands for.
     *
     * <p>{@code getSelectionCell} is {@code cells.get(0)} - the cell selected <em>first</em>, not
     * the one last clicked - so this is only the whole answer while one cell is selected. What to
     * tell Prot&eacute;g&eacute; after a Ctrl-click is {@link #theOneJustAdded}.
     */
    public String currentCanvasSelection() {
        return graph.getIdForCell(graph.getSelectionCell());
    }

    /**
     * Called with the cells to bring into view, when a selection arrives from outside.
     *
     * <p>Hung off the inbound path only. {@link #resyncAfterRender} re-selects through the same
     * code and must not scroll: it runs after every {@link SchemaGraph#render}, and render runs on
     * every ontology edit anywhere in Prot&eacute;g&eacute; - measured, five unrelated edits, five
     * calls. A board that scrolled itself on each of them would be unusable.
     */
    public void setReveal(Consumer<java.util.List<Object>> reveal) {
        this.reveal = reveal;
    }

    private Consumer<java.util.List<Object>> reveal;

    private void select(String iri, Consumer<java.util.List<Object>> bringIntoView) {
        // ALREADY SELECTED IS NOT A CHANGE. Clicking an arrow tells Protege the property, Protege
        // tells the canvas the property, and the canvas used to look for a cell whose id IS that
        // IRI - an arrow's id is pe|...|property|..., so it found none, cleared the selection, and
        // the arrow the reader had just clicked went dark. Nothing further is needed for that
        // case, and it has to be tested before the inbound guard so that a resync cannot scroll.
        String current = graph.getIdForCell(graph.getSelectionCell());
        if (iri != null && (iri.equals(current)
                || iri.equals(de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId
                        .propertyIn(current)))) {
            return;
        }
        java.util.List<Object> cells = graph.getCellsForEntity(iri);
        applyingInbound = true;
        try {
            if (cells.isEmpty()) {
                graph.clearSelection();
            } else {
                graph.setSelectionCells(cells.toArray());
            }
        } finally {
            applyingInbound = false;
        }
        if (!cells.isEmpty() && bringIntoView != null) {
            bringIntoView.accept(cells);
        }
    }

    /**
     * The entity to tell Prot&eacute;g&eacute; about, given what was selected before and now.
     *
     * <p><b>Why not simply the selection.</b> {@code mxGraphSelectionModel.getSelectionCell}
     * returns {@code cells.get(0)}, and {@code installSelection} turns single selection off, so
     * after Ctrl-clicking a second term the canvas reported the <em>first</em> one. Measured:
     * click Pizza, Ctrl-click Topping, and Prot&eacute;g&eacute; was told Pizza - which is the
     * reported defect, "clicking a class on the canvas does not take me to that class", on a
     * gesture nobody thought to try.
     *
     * <p>Computed from the ids rather than read off the change event: mxGraph's {@code added} and
     * {@code removed} have been reported the wrong way round in more than one of its ports, and
     * this bookkeeping needs no payload at all.
     *
     * <p>A marquee that selects several terms at once answers null - Prot&eacute;g&eacute;'s model
     * holds one entity, and an arbitrary member of five is worse than leaving the panel where the
     * reader put it.
     */
    static String theOneJustAdded(java.util.Collection<String> before,
            java.util.List<String> now) {
        java.util.List<String> fresh = new java.util.ArrayList<String>();
        for (String id : now) {
            if (id != null && !before.contains(id)) {
                fresh.add(id);
            }
        }
        if (fresh.size() == 1) {
            return fresh.get(0);
        }
        if (fresh.isEmpty() && now.size() == 1) {
            // Deselecting back down to one: that one is the answer.
            return now.get(0);
        }
        return null;
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
            // Without the reveal: see setReveal. A render is not a reader asking to be taken
            // somewhere, and it happens on every edit anywhere in Protege.
            select(previousIri, null);
        } else {
            graph.clearSelection();
            onCanvasSelection.accept(null);
        }
    }

    private void handleCanvasSelectionChanged() {
        if (applyingInbound) {
            return;
        }
        java.util.List<String> now = selectedIds();
        String iri = theOneJustAdded(lastSeen, now);
        lastSeen = new java.util.LinkedHashSet<String>(now);
        if (iri != null) {
            onCanvasSelection.accept(iri);
        }
    }

    /** What the canvas has selected, in the model's own order, ids only. */
    private java.util.List<String> selectedIds() {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (Object cell : graph.getSelectionCells()) {
            String id = graph.getIdForCell(cell);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** What was selected the last time this listener ran, so the new cell can be told apart. */
    private java.util.Set<String> lastSeen = new java.util.LinkedHashSet<String>();
}
