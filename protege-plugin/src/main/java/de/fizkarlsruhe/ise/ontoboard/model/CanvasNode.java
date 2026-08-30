package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** A node as the canvas sees it. Identity is the id alone. */
public final class CanvasNode {

    private final String id;
    private final NodeKind kind;
    private final String label;
    private final boolean noted;

    public CanvasNode(String id, NodeKind kind, String label) {
        this(id, kind, label, false);
    }

    public CanvasNode(String id, NodeKind kind, String label, boolean noted) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
        this.noted = noted;
    }

    public String getId() {
        return id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Whether this term carries an editorial note.
     *
     * <p>Read from the ontology every time the diagram is built, never stored in the sidecar: the
     * note is the fact, and a cached flag would go stale the moment somebody removed one.
     *
     * <p>Deliberately not part of {@link #equals}. Two nodes are the same node when they are the
     * same term drawn the same way; whether a note happens to hang off it is a property of the
     * ontology at the moment of drawing, and folding it into identity would make a node stop
     * equalling itself across an annotation edit.
     */
    public boolean hasNote() {
        return noted;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanvasNode && id.equals(((CanvasNode) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return kind + "(" + id + ")";
    }
}
