package de.fizkarlsruhe.ise.ontoboard.axiom;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * Turns "the user drew an arrow from A to B labelled R" into an OWL axiom.
 *
 * <p>An arrow on a diagram is ambiguous - it can mean at least six different things in OWL -
 * so this offers the candidate set from OWLAx (Sarker, Krisnadhi &amp; Hitzler,
 * arXiv:1808.10105 section 2) and lets the user choose, rather than silently picking one.
 *
 * <p>The retired web application wrote {@code rdfs:domain} + {@code rdfs:range} for every
 * edge, which is why {@link #GLOBAL_DOMAIN} and {@link #GLOBAL_RANGE} are offered at all.
 * That encoding is a trap: domains are global, so drawing {@code Person -worksFor-> Org} and
 * then {@code Robot -worksFor-> Factory} silently means {@code worksFor} has domain
 * {@code Person and Robot}. {@link #EXISTENTIAL} is the default because it is what an
 * ontologist almost always means by an arrow.
 *
 * <p>Pure functions over {@link OWLDataFactory} - no Protege types, no mutation - so every
 * form is unit-testable. Callers apply the result through {@code OWLModelManager}.
 */
public final class EdgeAxioms {

    /** The candidate readings of an edge {@code A --R--> B}. */
    public enum Candidate {

        /** {@code SubClassOf(A ObjectSomeValuesFrom(R B))} - every A relates to some B. */
        EXISTENTIAL("Existential", "A ⊑ ∃R.B",
                "Every A is related to some B. Usually what an arrow means."),

        /** {@code SubClassOf(ObjectSomeValuesFrom(R B) A)} - only A's relate to B via R. */
        SCOPED_DOMAIN("Scoped domain", "∃R.B ⊑ A",
                "Anything that relates to a B via R must be an A."),

        /** {@code SubClassOf(A ObjectAllValuesFrom(R B))} - A relates only to B's. */
        SCOPED_RANGE("Scoped range", "A ⊑ ∀R.B",
                "When an A relates via R, it relates only to B's."),

        /** {@code ObjectPropertyDomain(R A)} - global, and intersects across edges. */
        GLOBAL_DOMAIN("Global domain", "∃R.⊤ ⊑ A",
                "Anything with an R at all is an A. Global: a second edge with the same "
                        + "property narrows the domain to the intersection."),

        /** {@code ObjectPropertyRange(R B)} - global, and intersects across edges. */
        GLOBAL_RANGE("Global range", "⊤ ⊑ ∀R.B",
                "Every R points at a B. Global: a second edge with the same property "
                        + "narrows the range to the intersection."),

        /** {@code SubClassOf(A ObjectMaxCardinality(1 R B))} - at most one B per A. */
        FUNCTIONALITY("Functionality", "A ⊑ ≤1 R.B",
                "An A relates to at most one B via R.");

        private final String displayName;
        private final String dlNotation;
        private final String explanation;

        Candidate(String displayName, String dlNotation, String explanation) {
            this.displayName = displayName;
            this.dlNotation = dlNotation;
            this.explanation = explanation;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** Description-logic rendering, for showing beside the choice. */
        public String getDlNotation() {
            return dlNotation;
        }

        /** Plain-language meaning, so the choice is not guesswork for a domain expert. */
        public String getExplanation() {
            return explanation;
        }

        @Override
        public String toString() {
            return displayName + "  (" + dlNotation + ")";
        }
    }

    /** The candidate offered when the user does not choose. */
    public static final Candidate DEFAULT = Candidate.EXISTENTIAL;

    private EdgeAxioms() {
    }

    /**
     * The axiom for reading {@code subject --property--> filler} as {@code candidate}.
     */
    public static OWLAxiom build(OWLDataFactory factory, Candidate candidate,
            OWLClass subject, OWLObjectProperty property, OWLClass filler) {
        switch (candidate) {
            case SCOPED_DOMAIN:
                return factory.getOWLSubClassOfAxiom(
                        factory.getOWLObjectSomeValuesFrom(property, filler), subject);
            case SCOPED_RANGE:
                return factory.getOWLSubClassOfAxiom(subject,
                        factory.getOWLObjectAllValuesFrom(property, filler));
            case GLOBAL_DOMAIN:
                return factory.getOWLObjectPropertyDomainAxiom(property, subject);
            case GLOBAL_RANGE:
                return factory.getOWLObjectPropertyRangeAxiom(property, filler);
            case FUNCTIONALITY:
                return factory.getOWLSubClassOfAxiom(subject,
                        factory.getOWLObjectMaxCardinality(1, property, filler));
            case EXISTENTIAL:
            default:
                return factory.getOWLSubClassOfAxiom(subject,
                        factory.getOWLObjectSomeValuesFrom(property, filler));
        }
    }

    /** {@code SubClassOf(subClass superClass)}, for an edge drawn as a hierarchy link. */
    public static OWLAxiom subClassOf(OWLDataFactory factory, OWLClass subClass,
            OWLClass superClass) {
        return factory.getOWLSubClassOfAxiom(subClass, superClass);
    }
}
