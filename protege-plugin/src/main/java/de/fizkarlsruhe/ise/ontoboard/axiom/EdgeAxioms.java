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
                "Every {source} {property} some {target}. Usually what an arrow means.",
                "is related to"),

        /** {@code SubClassOf(ObjectSomeValuesFrom(R B) A)} - only A's relate to B via R. */
        SCOPED_DOMAIN("Scoped domain", "∃R.B ⊑ A",
                "Anything that relates to a {target} via {property} must be a {source}.", "R"),

        /** {@code SubClassOf(A ObjectAllValuesFrom(R B))} - A relates only to B's. */
        SCOPED_RANGE("Scoped range", "A ⊑ ∀R.B",
                "When a {source} relates via {property}, it relates only to {target}.", "R"),

        /** {@code ObjectPropertyDomain(R A)} - global, and intersects across edges. */
        GLOBAL_DOMAIN("Global domain", "∃R.⊤ ⊑ A",
                "Anything that relates via {property} at all is a {source}. Global: a second edge"
                        + " with the same property narrows the domain to the intersection.", "R"),

        /** {@code ObjectPropertyRange(R B)} - global, and intersects across edges. */
        GLOBAL_RANGE("Global range", "⊤ ⊑ ∀R.B",
                "Everything reached via {property} is a {target}. Global: a second edge with the"
                        + " same property narrows the range to the intersection.", "R"),

        /** {@code SubClassOf(A ObjectMaxCardinality(1 R B))} - at most one B per A. */
        // Not "Functionality": that word names FunctionalObjectProperty(R), which holds for every
        // use of R, and this is a restriction on A alone - a curator who picked it reasonably
        // believed R had become functional.
        FUNCTIONALITY("At most one", "A ⊑ ≤1 R.B",
                "A {source} relates to at most one {target} via {property}.", "R");

        /** The explanation with the two terms filled in, and no property named. */
        public String explainedFor(String source, String target) {
            return explainedFor(source, null, target);
        }

        private static final java.util.regex.Pattern PLACEHOLDER =
                java.util.regex.Pattern.compile("\\{(source|property|target)\\}");

        /**
         * The explanation with the terms and the property filled in (1.135.1, Windows study
         * rehearsal): it said "Every boiled egg is related to some egg boiling" whatever property
         * was chosen, so a participant who picked the wrong one read nothing that said so.
         *
         * <p>Here rather than in the dialog so the placeholders and the substitution cannot drift
         * apart, and done in one pass so a label inserted for one placeholder is never rescanned
         * for another.
         */
        public String explainedFor(String source, String property, String target) {
            java.util.regex.Matcher found = PLACEHOLDER.matcher(explanation);
            StringBuffer said = new StringBuffer();
            while (found.find()) {
                String name = found.group(1);
                String with = "source".equals(name) ? (source == null ? "A" : source)
                        : "target".equals(name) ? (target == null ? "B" : target)
                        : (property == null || property.trim().isEmpty() ? withoutProperty : property);
                found.appendReplacement(said, java.util.regex.Matcher.quoteReplacement(with));
            }
            found.appendTail(said);
            return said.toString();
        }

        private final String displayName;
        private final String dlNotation;
        private final String explanation;
        private final String withoutProperty;

        Candidate(String displayName, String dlNotation, String explanation,
                String withoutProperty) {
            this.displayName = displayName;
            this.dlNotation = dlNotation;
            this.explanation = explanation;
            this.withoutProperty = withoutProperty;
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
