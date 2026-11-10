package de.fizkarlsruhe.ise.ontoboard.axiom;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectUnionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Whether a relation fits its property's domain and range, as the open ontology states them -
 * said in the relation dialog, before OK (1.135.1, Windows study rehearsal).
 *
 * <p>The egg guide's slips were accepted without a word: the output relation drawn from the egg
 * to the boiling, a value's relation to its quality drawn the wrong way. The property's domain
 * puts the egg under a process, an imported rule says no object is a process, and a build several
 * tasks later failed on a class the participant had stopped thinking about.
 *
 * <p>Told axioms only, no reasoner: the dialog must open at once, and a warning it cannot be sure
 * of is worse than none. A class under a domain only by inference gets a warning that a reasoner
 * would not give; a domain this cannot read (an anonymous one other than a union or an
 * intersection) is taken to fit. Only the existential reading is checked, the one an arrow means
 * and the one that puts its source under the domain.
 */
public final class PropertyFit {

    private PropertyFit() {
    }

    /** A warning for a relation that does not fit, in plain words; null when it fits. */
    public static String warningFor(OWLOntology ontology, EdgeAxioms.Candidate candidate,
            OWLClass source, OWLObjectProperty property, OWLClass target) {
        if (ontology == null || candidate != EdgeAxioms.Candidate.EXISTENTIAL || property == null
                || source == null || target == null) {
            return null;
        }
        String misfit = misfit(ontology, source, property, target);
        if (misfit == null) {
            return null;
        }
        if (misfit(ontology, target, property, source) == null) {
            misfit += " Drawn the other way, from " + name(ontology, target) + " to "
                    + name(ontology, source) + ", it would fit.";
        }
        return misfit;
    }

    /**
     * A note when the relation starts at a term an import brings, or null (1.135.1, Windows study
     * rehearsal): relating from PMDco's mass wrote an axiom about PMDco's term into the
     * participant's file, and nothing said so.
     */
    public static String importedNote(OWLOntology ontology, OWLClass source) {
        if (ontology == null || source == null || ontology.isDeclared(source, Imports.EXCLUDED)
                || !ontology.isDeclared(source, Imports.INCLUDED)) {
            return null;
        }
        return name(ontology, source) + " comes from an import: this saves a statement about it in"
                + " your ontology.";
    }

    /** What does not fit, the source against the domains first, or null. */
    private static String misfit(OWLOntology ontology, OWLClass source, OWLObjectProperty property,
            OWLClass target) {
        Set<OWLClass> above = toldAncestors(ontology, source);
        for (OWLClassExpression domain : scopes(ontology, property, true)) {
            if (!covers(above, domain)) {
                String where = said(ontology, domain);
                return name(ontology, source) + " is not under " + where + ", the domain of "
                        + name(ontology, property) + ". A reasoner will put every "
                        + name(ontology, source) + " under " + where + " as well; if nothing can be"
                        + " under both, " + name(ontology, source) + " can have no members.";
            }
        }
        Set<OWLClass> below = toldAncestors(ontology, target);
        for (OWLClassExpression range : scopes(ontology, property, false)) {
            if (!covers(below, range)) {
                String where = said(ontology, range);
                return name(ontology, target) + " is not under " + where + ", the range of "
                        + name(ontology, property) + ". A reasoner will conclude that what a "
                        + name(ontology, source) + " " + name(ontology, property) + " is under "
                        + where + " as well; if nothing can be under both, "
                        + name(ontology, source) + " can have no members.";
            }
        }
        return null;
    }

    /** The class and every named class above it, as the imports closure states them. */
    static Set<OWLClass> toldAncestors(OWLOntology ontology, OWLClass cls) {
        Set<OWLClass> found = new LinkedHashSet<OWLClass>();
        Deque<OWLClass> next = new ArrayDeque<OWLClass>();
        next.add(cls);
        while (!next.isEmpty()) {
            OWLClass at = next.removeFirst();
            if (!found.add(at)) {
                continue;
            }
            for (OWLOntology part : ontology.getImportsClosure()) {
                for (OWLSubClassOfAxiom axiom : part.getSubClassAxiomsForSubClass(at)) {
                    named(axiom.getSuperClass(), next);
                }
                for (OWLEquivalentClassesAxiom axiom : part.getEquivalentClassesAxioms(at)) {
                    for (OWLClassExpression same : axiom.getClassExpressionsMinus(at)) {
                        named(same, next);
                    }
                }
            }
        }
        return found;
    }

    private static void named(OWLClassExpression expression, Deque<OWLClass> into) {
        if (!expression.isAnonymous()) {
            into.add(expression.asOWLClass());
        } else if (expression instanceof OWLObjectIntersectionOf) {
            for (OWLClassExpression operand : ((OWLObjectIntersectionOf) expression).getOperands()) {
                if (!operand.isAnonymous()) {
                    into.add(operand.asOWLClass());
                }
            }
        }
    }

    /** The domains, or the ranges, of the property and of every property it is told to be under. */
    static List<OWLClassExpression> scopes(OWLOntology ontology, OWLObjectProperty property,
            boolean domains) {
        List<OWLClassExpression> found = new ArrayList<OWLClassExpression>();
        Set<OWLObjectProperty> seen = new HashSet<OWLObjectProperty>();
        Deque<OWLObjectProperty> next = new ArrayDeque<OWLObjectProperty>();
        next.add(property);
        while (!next.isEmpty()) {
            OWLObjectProperty at = next.removeFirst();
            if (!seen.add(at)) {
                continue;
            }
            for (OWLOntology part : ontology.getImportsClosure()) {
                if (domains) {
                    for (OWLObjectPropertyDomainAxiom axiom : part.getObjectPropertyDomainAxioms(at)) {
                        found.add(axiom.getDomain());
                    }
                } else {
                    for (OWLObjectPropertyRangeAxiom axiom : part.getObjectPropertyRangeAxioms(at)) {
                        found.add(axiom.getRange());
                    }
                }
                for (OWLSubObjectPropertyOfAxiom axiom
                        : part.getObjectSubPropertyAxiomsForSubProperty(at)) {
                    if (!axiom.getSuperProperty().isAnonymous()) {
                        next.add(axiom.getSuperProperty().asOWLObjectProperty());
                    }
                }
            }
        }
        return found;
    }

    /** Whether a class with these named ancestors is under the scope; anything unread fits. */
    static boolean covers(Set<OWLClass> ancestors, OWLClassExpression scope) {
        if (scope.isOWLThing()) {
            return true;
        }
        if (!scope.isAnonymous()) {
            return ancestors.contains(scope.asOWLClass());
        }
        if (scope instanceof OWLObjectIntersectionOf) {
            for (OWLClassExpression operand : ((OWLObjectIntersectionOf) scope).getOperands()) {
                if (!operand.isAnonymous() && !ancestors.contains(operand.asOWLClass())) {
                    return false;
                }
            }
            return true;
        }
        if (scope instanceof OWLObjectUnionOf) {
            for (OWLClassExpression operand : ((OWLObjectUnionOf) scope).getOperands()) {
                if (operand.isAnonymous() || ancestors.contains(operand.asOWLClass())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** A scope in words: a class's label, or "A or B" for a union. */
    private static String said(OWLOntology ontology, OWLClassExpression scope) {
        if (!scope.isAnonymous()) {
            return name(ontology, scope.asOWLClass());
        }
        List<String> names = new ArrayList<String>();
        if (scope instanceof OWLObjectUnionOf || scope instanceof OWLObjectIntersectionOf) {
            Set<OWLClassExpression> operands = scope instanceof OWLObjectUnionOf
                    ? ((OWLObjectUnionOf) scope).getOperands()
                    : ((OWLObjectIntersectionOf) scope).getOperands();
            for (OWLClassExpression operand : operands) {
                if (!operand.isAnonymous()) {
                    names.add(name(ontology, operand.asOWLClass()));
                }
            }
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names.isEmpty() ? "its scope"
                : String.join(scope instanceof OWLObjectUnionOf ? " or " : " and ", names);
    }

    private static String name(OWLOntology ontology, org.semanticweb.owlapi.model.OWLEntity entity) {
        return DisplayLabels.forEntity(ontology, entity);
    }
}
