package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** A node as the canvas sees it. Identity is the id alone. */
public final class CanvasNode {

    private final String id;
    private final NodeKind kind;
    private final String label;
    private final List<String> notes;
    private final boolean unsatisfiable;
    private final boolean imported;

    public CanvasNode(String id, NodeKind kind, String label) {
        this(id, kind, label, null, false);
    }

    public CanvasNode(String id, NodeKind kind, String label, List<String> notes) {
        this(id, kind, label, notes, false);
    }

    public CanvasNode(String id, NodeKind kind, String label, List<String> notes,
            boolean unsatisfiable) {
        this(id, kind, label, notes, unsatisfiable, false);
    }

    private CanvasNode(String id, NodeKind kind, String label, List<String> notes,
            boolean unsatisfiable, boolean imported) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
        this.notes = withoutBlanks(notes);
        this.unsatisfiable = unsatisfiable;
        this.imported = imported;
    }

    /** Blank notes dropped, so {@link #hasNote} cannot be true with nothing to show. */
    private static List<String> withoutBlanks(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> kept = new ArrayList<String>(candidates.size());
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                kept.add(candidate);
            }
        }
        return Collections.unmodifiableList(kept);
    }

    /** The same node, marked as one the reasoner found unsatisfiable. */
    public CanvasNode asUnsatisfiable() {
        return new CanvasNode(id, kind, label, notes, true, imported);
    }

    /** The same node, marked as defined in an imported ontology rather than in the edit file. */
    public CanvasNode asImported() {
        return new CanvasNode(id, kind, label, notes, unsatisfiable, true);
    }

    /**
     * Whether this term is defined in an import rather than in the file being edited.
     *
     * <p>The distinction a curator must not get wrong. An imported term belongs to somebody else's
     * ontology: asserting about it locally is normal - that is what an import is for - but editing it
     * as though it were yours produces a change that the next {@code make all_imports} silently
     * discards, or worse, a duplicate definition that survives into a release.
     *
     * <p>Like {@link #hasNote} and {@link #isUnsatisfiable}, not part of {@link #equals}: it is a fact
     * about where the term came from at the moment of drawing, not about which node this is.
     */
    public boolean isImported() {
        return imported;
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
        return !notes.isEmpty();
    }

    /**
     * The editorial notes on this term, in the order the ontology gave them, never null.
     *
     * <p>The words, not a flag. This was a boolean until 1.59.0, and the boolean was the reason the
     * canvas could draw a term as noted and then refuse to say what the note said: tooltips are built
     * from the projection, and the projection knew only that a note existed. Hovering gave "Has an
     * editorial note", which raises a question and withholds its answer.
     *
     * <p>A list rather than one string because a term can carry several - OBO ontologies routinely do,
     * one per editor - and collapsing them to "the note" would hide everyone's but the first.
     *
     * <p>Kinds are not distinguished here. {@code IAO:0000116} and {@code IAO:0000232} mean different
     * things and the Notes dialog says which is which; on a hover the words are what is wanted, and a
     * tooltip that spent a line on the annotation property before quoting the note would be answering
     * a question nobody hovered to ask.
     */
    public List<String> getNotes() {
        return notes;
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
