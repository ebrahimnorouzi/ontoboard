package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** A node as the canvas sees it. Identity is the id alone. */
public final class CanvasNode {

    private final String id;
    private final NodeKind kind;
    private final String label;

    public CanvasNode(String id, NodeKind kind, String label) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
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
