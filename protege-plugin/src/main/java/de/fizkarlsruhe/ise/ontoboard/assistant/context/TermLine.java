package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import org.semanticweb.owlapi.model.OWLAxiom;

/**
 * One line of what a term says - a label, a definition, one axiom - as the assistant column
 * lists it, each with its own ✦ menu.
 */
public final class TermLine {

    /** Annotations first, then the logic: the order the column shows them in. */
    public enum Group {
        ANNOTATION,
        SEMANTICS
    }

    private final Group group;
    private final String key;
    private final String text;
    private final String rendered;
    private final OWLAxiom axiom;

    TermLine(Group group, String key, String text, String rendered, OWLAxiom axiom) {
        this.group = group;
        this.key = key;
        this.text = text;
        this.rendered = rendered;
        this.axiom = axiom;
    }

    public Group getGroup() {
        return group;
    }

    /** "label", "definition", "SubClassOf", "Domain", "Types"... */
    public String getKey() {
        return key;
    }

    /** The line's own part: the value of an annotation, or what the axiom says of the term. */
    public String getText() {
        return text;
    }

    /** The whole line with its subject named, as sent when a person asks about it. */
    public String getRendered() {
        return rendered;
    }

    /** The axiom behind the line, for the terms it mentions. */
    OWLAxiom getAxiom() {
        return axiom;
    }

    /** An annotation's literal, or null for a line that is not one. */
    private org.semanticweb.owlapi.model.OWLLiteral literal() {
        if (axiom instanceof org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom) {
            com.google.common.base.Optional<org.semanticweb.owlapi.model.OWLLiteral> value =
                    ((org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom) axiom).getValue()
                            .asLiteral();
            return value.isPresent() ? value.get() : null;
        }
        return null;
    }

    /**
     * The annotation's language tag as the literal has it, or null - never read from the text,
     * where "(SEM)" ends an untagged value as often as "(en)" ends a tagged one (1.138.0 R3).
     */
    public String getLanguage() {
        org.semanticweb.owlapi.model.OWLLiteral literal = literal();
        return literal == null || !literal.hasLang() ? null : literal.getLang();
    }

    /** The annotation's value without the language the line shows after it; else the text. */
    public String getValue() {
        org.semanticweb.owlapi.model.OWLLiteral literal = literal();
        return literal == null ? text : TermLines.oneLine(literal.getLiteral());
    }

    @Override
    public String toString() {
        return key + ": " + text;
    }
}
