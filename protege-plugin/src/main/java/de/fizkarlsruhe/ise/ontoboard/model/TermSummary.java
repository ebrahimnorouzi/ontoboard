package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * The axioms about a term that a hover should mention.
 *
 * <p>Taken from OntoGraf, which offers {@code SUPERCLASSES}, {@code EQUIVALENT_CLASSES} and
 * {@code DISJOINT_CLASSES} as switchable tooltip sections. OntoBoard's hover said what a term
 * is called, what kind it is, whether it is imported and what notes it carries - everything
 * except what the ontology actually says about it.
 *
 * <p><b>Disjointness is the one that matters most</b>, because the canvas draws none anywhere.
 * A term can be disjoint with six others and the board gives no hint of it; this is the only
 * place that fact currently appears.
 *
 * <p>Named classes only, and that is a decision rather than a limitation. An anonymous
 * superclass or equivalent is a restriction, and since 1.75.0 those are <em>drawn</em> - as the
 * arrows leaving the node. Repeating them in the hover would say twice what the diagram already
 * says once, and would need a Manchester renderer on the refresh path to do it. The count of
 * what was left out is reported, so nothing is hidden.
 */
public final class TermSummary {

    /** Beyond this the hover stops being a hover. The rest are counted, not listed. */
    static final int MOST_SHOWN = 6;

    private final List<String> superClasses;
    private final List<String> equivalents;
    private final List<String> disjoints;
    private final int unnamed;

    TermSummary(List<String> superClasses, List<String> equivalents, List<String> disjoints,
            int unnamed) {
        this.superClasses = Collections.unmodifiableList(superClasses);
        this.equivalents = Collections.unmodifiableList(equivalents);
        this.disjoints = Collections.unmodifiableList(disjoints);
        this.unnamed = unnamed;
    }

    /** An empty summary, for a term there is nothing to say about. */
    public static TermSummary empty() {
        return new TermSummary(new ArrayList<String>(), new ArrayList<String>(),
                new ArrayList<String>(), 0);
    }

    /**
     * What the ontology says about one class, imports included.
     *
     * <p>Imports included because a term's parents are usually upstream: a board of BFO and IAO
     * terms whose hover reported no superclasses would be reporting on the edit file rather
     * than on the ontology.
     */
    public static TermSummary of(OWLOntology ontology, OWLClass subject) {
        if (ontology == null || subject == null) {
            return empty();
        }
        TreeSet<String> parents = new TreeSet<String>();
        TreeSet<String> same = new TreeSet<String>();
        TreeSet<String> apart = new TreeSet<String>();
        // Distinct expressions, not occurrences. An import closure restates axioms across
        // modules, so counting occurrences reported a term as having four unnamed relatives
        // when it had one stated four times.
        java.util.Set<OWLClassExpression> unnamed =
                new java.util.HashSet<OWLClassExpression>();

        // One pass over the closure, which already includes this ontology. An earlier version
        // walked this ontology first and then the closure "minus itself", on the assumption
        // that getImportsClosure returns the same object - it does not, the identity check
        // never fired, and every local axiom was counted twice.
        for (OWLOntology one : ontology.getImportsClosure()) {
            for (OWLSubClassOfAxiom axiom : one.getSubClassAxiomsForSubClass(subject)) {
                collect(ontology, axiom.getSuperClass(), parents, unnamed);
            }
            for (OWLEquivalentClassesAxiom axiom : one.getEquivalentClassesAxioms(subject)) {
                for (OWLClassExpression expression : axiom.getClassExpressions()) {
                    if (!expression.equals(subject)) {
                        collect(ontology, expression, same, unnamed);
                    }
                }
            }
            for (OWLDisjointClassesAxiom axiom : one.getDisjointClassesAxioms(subject)) {
                for (OWLClassExpression expression : axiom.getClassExpressions()) {
                    if (!expression.equals(subject)) {
                        collect(ontology, expression, apart, unnamed);
                    }
                }
            }
        }

        return new TermSummary(new ArrayList<String>(parents), new ArrayList<String>(same),
                new ArrayList<String>(apart), unnamed.size());
    }

    /** Adds a named expression's label, or records an anonymous one as drawn rather than listed. */
    private static void collect(OWLOntology ontology, OWLClassExpression expression,
            TreeSet<String> into, java.util.Set<OWLClassExpression> unnamed) {
        if (expression == null) {
            return;
        }
        if (expression.isAnonymous()) {
            unnamed.add(expression);
            return;
        }
        OWLClass named = expression.asOWLClass();
        if (named.isOWLThing()) {
            // Everything is below owl:Thing; saying so uses a line of the hover to say nothing.
            return;
        }
        into.add(DisplayLabels.forEntity(ontology, named));
    }

    /** Named superclasses, sorted. */
    public List<String> getSuperClasses() {
        return superClasses;
    }

    /** Named equivalent classes, sorted. */
    public List<String> getEquivalents() {
        return equivalents;
    }

    /** Named disjoint classes, sorted. The canvas draws these nowhere else. */
    public List<String> getDisjoints() {
        return disjoints;
    }

    /** How many related expressions were anonymous, and so are drawn rather than listed. */
    public int getUnnamedCount() {
        return unnamed;
    }

    /** Nothing to say. */
    public boolean isEmpty() {
        return superClasses.isEmpty() && equivalents.isEmpty() && disjoints.isEmpty();
    }

    /**
     * A list for a hover: at most {@link #MOST_SHOWN}, then a count of the rest.
     *
     * <p>Truncated rather than scrolled, because a tooltip that fills the screen is one people
     * learn to dismiss before reading.
     */
    public static String joined(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        int shown = Math.min(values.size(), MOST_SHOWN);
        for (int at = 0; at < shown; at++) {
            if (at > 0) {
                text.append(", ");
            }
            text.append(values.get(at));
        }
        if (values.size() > shown) {
            text.append(" and ").append(values.size() - shown).append(" more");
        }
        return text.toString();
    }
}
