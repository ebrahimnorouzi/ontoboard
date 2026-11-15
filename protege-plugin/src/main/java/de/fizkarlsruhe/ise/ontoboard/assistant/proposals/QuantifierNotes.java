package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectCardinalityRestriction;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * The quantifier trap, said on the card (1.139.0; the domain experts asked for "∃ and ∀ made
 * explicit"): {@code C SubClassOf p only F} says what a C's p may be, not that a C has any. A C
 * with no p at all satisfies it. When the class has no {@code some}, {@code min} or
 * {@code exactly} on the same property, the card says so.
 */
public final class QuantifierNotes {

    private QuantifierNotes() {
    }

    /** Notes for an axiom about to be added; empty when there is nothing to say. */
    public static List<String> of(OWLOntology ontology, OWLAxiom axiom) {
        if (!(axiom instanceof OWLSubClassOfAxiom)
                || ((OWLSubClassOfAxiom) axiom).getSubClass().isAnonymous()) {
            return Collections.emptyList();
        }
        OWLSubClassOfAxiom subClassOf = (OWLSubClassOfAxiom) axiom;
        OWLClass subject = subClassOf.getSubClass().asOWLClass();
        List<String> notes = new ArrayList<String>();
        for (OWLClassExpression part : conjuncts(subClassOf.getSuperClass())) {
            if (!(part instanceof OWLObjectAllValuesFrom)) {
                continue;
            }
            OWLObjectPropertyExpression property = ((OWLObjectAllValuesFrom) part).getProperty();
            if (!existential(ontology, subject, property, subClassOf.getSuperClass())) {
                String p = property.isAnonymous() ? property.toString()
                        : DisplayLabels.forEntity(ontology, property.asOWLObjectProperty());
                String c = DisplayLabels.forEntity(ontology, subject);
                notes.add("\"only\" on " + p + " says what a " + c + "'s " + p + " may be, not"
                        + " that it has one: a " + c + " with no " + p + " at all satisfies it."
                        + " Add \"" + p + " some …\" if one is required.");
            }
        }
        return notes;
    }

    /** Whether the class requires at least one value of the property, here or in the ontology. */
    private static boolean existential(OWLOntology ontology, OWLClass subject,
            OWLObjectPropertyExpression property, OWLClassExpression proposed) {
        if (requires(proposed, property)) {
            return true;
        }
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(subject)) {
                if (requires(axiom.getSuperClass(), property)) {
                    return true;
                }
            }
            for (OWLEquivalentClassesAxiom axiom : part.getEquivalentClassesAxioms(subject)) {
                for (OWLClassExpression expression : axiom.getClassExpressions()) {
                    if (requires(expression, property)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean requires(OWLClassExpression expression,
            OWLObjectPropertyExpression property) {
        for (OWLClassExpression part : conjuncts(expression)) {
            if (part instanceof OWLObjectSomeValuesFrom
                    && ((OWLObjectSomeValuesFrom) part).getProperty().equals(property)) {
                return true;
            }
            if (part instanceof OWLObjectCardinalityRestriction
                    && ((OWLObjectCardinalityRestriction) part).getProperty().equals(property)
                    && ((OWLObjectCardinalityRestriction) part).getCardinality() > 0
                    && part.getClassExpressionType()
                            != org.semanticweb.owlapi.model.ClassExpressionType.OBJECT_MAX_CARDINALITY) {
                return true;
            }
        }
        return false;
    }

    private static Set<OWLClassExpression> conjuncts(OWLClassExpression expression) {
        if (expression instanceof OWLObjectIntersectionOf) {
            return ((OWLObjectIntersectionOf) expression).asConjunctSet();
        }
        return Collections.singleton(expression);
    }
}
