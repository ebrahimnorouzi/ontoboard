package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** An edge as the canvas sees it. Identity is the id alone. */
public final class CanvasEdge {

    public enum Kind {
        SUBCLASS, OBJECT_PROPERTY, DATA_PROPERTY, TYPE, SUB_PROPERTY, INFERRED_SUBCLASS
    }

    private final String id;
    private final String sourceId;
    private final String targetId;
    private final String label;
    private final Kind kind;

    public CanvasEdge(String id, String sourceId, String targetId, String label, Kind kind) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.label = Objects.requireNonNull(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getId() {
        return id;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getLabel() {
        return label;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanvasEdge && id.equals(((CanvasEdge) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
