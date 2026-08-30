package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** A node as the canvas sees it. Identity is the id alone. */
public final class CanvasNode {

    private final String id;
    private final NodeKind kind;
    private final String label;
    private final boolean noted;
    private final boolean unsatisfiable;

    public CanvasNode(String id, NodeKind kind, String label) {
        this(id, kind, label, false, false);
    }

    public CanvasNode(String id, NodeKind kind, String label, boolean noted) {
        this(id, kind, label, noted, false);
    }

    public CanvasNode(String id, NodeKind kind, String label, boolean noted,
            boolean unsatisfiable) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
        this.noted = noted;
        this.unsatisfiable = unsatisfiable;
    }

    /** The same node, marked as one the reasoner found unsatisfiable. */
    public CanvasNode asUnsatisfiable() {
        return new CanvasNode(id, kind, label, noted, true);
    }

    /**
     * Whether a reasoner found this class cannot have any instances.
     *
     * <p>The single most useful thing a reasoner has to say, and until now it was computed on
     * every refresh and thrown away. An unsatisfiable class is a modelling error - two axioms that
     * cannot both hold - and it is invisible on a diagram that draws it like everything else.
     *
     * <p>Like {@link #hasNote}, not part of {@link #equals}: it is a fact about what a reasoner
     * concluded at the moment of drawing, not about which node this is.
     */
    public boolean isUnsatisfiable() {
        return unsatisfiable;
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
