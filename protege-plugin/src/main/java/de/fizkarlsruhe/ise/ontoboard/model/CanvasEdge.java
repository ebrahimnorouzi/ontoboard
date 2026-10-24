package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** An edge as the canvas sees it. Identity is the id alone. */
public final class CanvasEdge {

    public enum Kind {
        SUBCLASS, OBJECT_PROPERTY, DATA_PROPERTY, TYPE, SUB_PROPERTY, INFERRED_SUBCLASS,
        INFERRED_TYPE,

        /**
         * {@code DisjointClasses} - these two cannot share an instance.
         *
         * <p>Drawn with no arrowhead at either end, because the relation is symmetric and an
         * arrow would invite a reader to look for a direction that is not there.
         */
        DISJOINT,

        /**
         * {@code InverseObjectProperties} - one property read backwards is the other.
         *
         * <p>Between two PROPERTY nodes, so it appears only on a board where somebody put the
         * properties themselves. Arrowheads at both ends, for the same reason DISJOINT has
         * none: the relation really does run both ways.
         */
        INVERSE
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
