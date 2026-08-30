package de.fizkarlsruhe.ise.ontoboard.axiom;

import java.util.ArrayList;
import java.util.List;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;

/**
 * The three edges that need no property: subclass, sub-property and type.
 *
 * <p>The canvas drew all three and could create none of them. The legend advertised
 * {@code rdfs:subClassOf}, {@code rdf:type} and {@code rdfs:subPropertyOf}, the stylesheet had a
 * look for each, the projection rendered them from the ontology - and the only authoring path,
 * the relation dialog, offered six property restrictions and nothing else. So a user could see
 * three kinds of arrow, and to draw one had to leave the canvas for Protege's class hierarchy.
 *
 * <p>{@link EdgeAxioms} could not simply grow three more cases: every one of its candidates is a
 * reading of {@code A --R--> B} and takes a property. These take none, and which of them is even
 * legal depends on what the two ends <em>are</em> - which is the interesting part, and the reason
 * this is a class rather than three method calls.
 *
 * <p>Pure functions over {@link OWLDataFactory} - no Protege types, no mutation - so every form is
 * unit-testable. Callers apply the result through {@code OWLModelManager}.
 */
public final class HierarchyAxioms {

    /** An edge that needs no property to mean something. */
    public enum Kind {
        /** {@code SubClassOf(A B)} - A is a kind of B. */
        SUBCLASS_OF("Subclass of", "A ⊑ B",
                "A is a kind of B. Every instance of A is also an instance of B - the ordinary "
                        + "parent link, and the one the class hierarchy shows."),

        /** {@code SubObjectPropertyOf(P Q)} or its data-property twin. */
        SUB_PROPERTY_OF("Sub-property of", "P ⊑ Q",
                "Every pair related by P is also related by Q. Use it when one relation is a "
                        + "special case of another - 'has mother' under 'has parent'."),

        /** {@code ClassAssertion(B a)} - a is one of these. */
        TYPE("Instance of", "B(a)",
                "This individual is an instance of that class. The membership link, as opposed "
                        + "to the subclass link between two classes.");

        private final String displayName;
        private final String dlNotation;
        private final String explanation;

        Kind(String displayName, String dlNotation, String explanation) {
            this.displayName = displayName;
            this.dlNotation = dlNotation;
            this.explanation = explanation;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** The description-logic form, so somebody who reads DL can check the reading at a glance. */
        public String getDlNotation() {
            return dlNotation;
        }

        public String getExplanation() {
            return explanation;
        }

        @Override
        public String toString() {
            return displayName + "  (" + dlNotation + ")";
        }
    }

    private HierarchyAxioms() {
    }

    /**
     * Which of these an edge from {@code source} to {@code target} could legally mean.
     *
     * <p>The point of asking rather than offering all three: only one is ever legal for a given
     * pair, and which one is decided entirely by what the two ends are. Two classes can only be a
     * subclass link; an individual and a class can only be a type assertion; two object properties
     * can only be a sub-property link. Offering a choice would be offering two ways to get an
     * error, and letting a user pick "subclass of" between an individual and a class is exactly
     * the confusion between {@code rdf:type} and {@code rdfs:subClassOf} that this diagram exists
     * to make visible.
     *
     * <p>Returns a list rather than a single value because the caller shows it, and an empty list
     * is a meaningful answer - a class and a property have no hierarchy relationship at all, and
     * saying so beats writing an axiom nobody meant.
     */
    public static List<Kind> applicableTo(OWLEntity source, OWLEntity target) {
        List<Kind> applicable = new ArrayList<Kind>();
        if (source == null || target == null || source.equals(target)) {
            // A term is not its own parent, and asserting it would make the ontology
            // unsatisfiable in the sub-property case and merely useless in the others.
            return applicable;
        }
        if (source instanceof OWLClass && target instanceof OWLClass) {
            applicable.add(Kind.SUBCLASS_OF);
        } else if (source instanceof OWLNamedIndividual && target instanceof OWLClass) {
            applicable.add(Kind.TYPE);
        } else if (source instanceof OWLObjectProperty && target instanceof OWLObjectProperty) {
            applicable.add(Kind.SUB_PROPERTY_OF);
        } else if (source instanceof OWLDataProperty && target instanceof OWLDataProperty) {
            applicable.add(Kind.SUB_PROPERTY_OF);
        }
        return applicable;
    }

    /**
     * Why this pair cannot be linked, for a user who tried.
     *
     * <p>"Nothing happened" is the worst possible answer to a deliberate gesture, and the reason
     * is nearly always something the user can act on - they picked a data property and an object
     * property, or dragged from the class to the individual rather than the other way round.
     *
     * @return null when {@link #applicableTo} would return something
     */
    public static String whyNot(OWLEntity source, OWLEntity target) {
        if (source == null || target == null) {
            return "Pick two terms first.";
        }
        if (source.equals(target)) {
            return "A term cannot be its own parent.";
        }
        if (!applicableTo(source, target).isEmpty()) {
            return null;
        }
        if (source instanceof OWLClass && target instanceof OWLNamedIndividual) {
            return "A class cannot be an instance of an individual. If you meant that the "
                    + "individual is one of these, start the link at the individual.";
        }
        if (source instanceof OWLObjectProperty && target instanceof OWLDataProperty
                || source instanceof OWLDataProperty && target instanceof OWLObjectProperty) {
            return "An object property and a data property cannot be in the same hierarchy - one "
                    + "relates things to things, the other things to values.";
        }
        return "There is no hierarchy relationship between " + kindNameOf(source) + " and "
                + kindNameOf(target) + ". Use Create relation to link them with a property.";
    }

    /** What an entity is, in the words the canvas legend uses. */
    private static String kindNameOf(OWLEntity entity) {
        if (entity instanceof OWLClass) {
            return "a class";
        }
        if (entity instanceof OWLNamedIndividual) {
            return "an individual";
        }
        if (entity instanceof OWLObjectProperty) {
            return "an object property";
        }
        if (entity instanceof OWLDataProperty) {
            return "a data property";
        }
        return "that";
    }

    /**
     * The axiom for one of these edges.
     *
     * <p>Note the direction on {@link Kind#TYPE}: the edge is drawn from the individual to its
     * class, and {@code ClassAssertion} takes the class first. Getting that backwards produces a
     * valid axiom saying something quite different, which is the kind of mistake nothing catches.
     *
     * @throws IllegalArgumentException when the pair cannot mean this, with the reason from
     *     {@link #whyNot} - a caller that has already asked {@link #applicableTo} will never see it
     */
    public static OWLAxiom build(OWLDataFactory factory, Kind kind, OWLEntity source,
            OWLEntity target) {
        if (!applicableTo(source, target).contains(kind)) {
            String reason = whyNot(source, target);
            throw new IllegalArgumentException(reason == null
                    ? kind.getDisplayName() + " does not apply to these two terms" : reason);
        }
        switch (kind) {
            case TYPE:
                return factory.getOWLClassAssertionAxiom((OWLClass) target,
                        (OWLNamedIndividual) source);
            case SUB_PROPERTY_OF:
                if (source instanceof OWLObjectProperty) {
                    return factory.getOWLSubObjectPropertyOfAxiom((OWLObjectProperty) source,
                            (OWLObjectProperty) target);
                }
                return factory.getOWLSubDataPropertyOfAxiom((OWLDataProperty) source,
                        (OWLDataProperty) target);
            case SUBCLASS_OF:
            default:
                return factory.getOWLSubClassOfAxiom((OWLClass) source, (OWLClass) target);
        }
    }
}
