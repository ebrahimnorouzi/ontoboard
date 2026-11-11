package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;

/**
 * One chip above the chat (spec D3): something the next call will tell the model about.
 *
 * <p>A recipe, not a snapshot - except for a reasoner problem, whose text was computed when the
 * person asked for it. A term is rendered again at every send, so an edit made between two
 * questions is what the second question is about.
 */
public final class ContextItem {

    /** What the chip stands for. */
    public enum Kind {
        /** One term and its neighbourhood. */
        TERM,
        /** The asserted class hierarchy as labels. */
        HIERARCHY,
        /** Every axiom of the ontology, one per line. */
        WHOLE_ONTOLOGY,
        /** A reasoner problem and its justifications, computed when asked for. */
        PROBLEM
    }

    private final Kind kind;
    private final IRI iri;
    private final boolean followsSelection;
    private final String title;
    private final String text;
    private final IRI subject;

    private ContextItem(Kind kind, IRI iri, boolean followsSelection, String title, String text,
            IRI subject) {
        this.kind = kind;
        this.iri = iri;
        this.followsSelection = followsSelection;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.subject = subject;
    }

    private ContextItem(Kind kind, IRI iri, boolean followsSelection, String title, String text) {
        this(kind, iri, followsSelection, title, text, null);
    }

    /** @param followsSelection true for the chip that changes with the selection */
    public static ContextItem term(IRI iri, boolean followsSelection) {
        return new ContextItem(Kind.TERM, Objects.requireNonNull(iri, "iri"), followsSelection,
                "", "");
    }

    public static ContextItem hierarchy() {
        return new ContextItem(Kind.HIERARCHY, null, false, "", "");
    }

    public static ContextItem wholeOntology() {
        return new ContextItem(Kind.WHOLE_ONTOLOGY, null, false, "", "");
    }

    public static ContextItem problem(String title, String text) {
        return new ContextItem(Kind.PROBLEM, null, false, title, text);
    }

    /**
     * A reasoner problem about one class - why it is unsatisfiable - which the reasoner can be
     * asked about again once the ontology has changed: see {@code Problems.now}.
     */
    public static ContextItem problem(String title, String text, IRI subject) {
        return new ContextItem(Kind.PROBLEM, null, false, title, text, subject);
    }

    public Kind getKind() {
        return kind;
    }

    /** The term's IRI, or null for the other kinds. */
    public IRI getIri() {
        return iri;
    }

    public boolean isFollowingSelection() {
        return followsSelection;
    }

    /** A problem's title; empty for the other kinds, whose title depends on the ontology. */
    public String getTitle() {
        return title;
    }

    /** A problem's text; empty for the other kinds. */
    public String getText() {
        return text;
    }

    /**
     * The class a problem is about - the one that is unsatisfiable - or null: for the
     * inconsistency, and for every other kind.
     */
    public IRI getSubject() {
        return subject;
    }

    /**
     * Equal when they would tell the model the same thing, whether or not they follow. Two
     * classes' problems are two, though their labels are alike.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ContextItem)) {
            return false;
        }
        ContextItem that = (ContextItem) other;
        return kind == that.kind && Objects.equals(iri, that.iri) && title.equals(that.title)
                && Objects.equals(subject, that.subject);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, iri, title, subject);
    }

    @Override
    public String toString() {
        return kind + (iri == null ? "" : " " + iri) + (title.isEmpty() ? "" : " " + title);
    }
}
