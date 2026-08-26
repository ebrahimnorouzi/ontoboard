package de.fizkarlsruhe.ise.ontoboard.axiom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * Works out which axiom an edge on the canvas stands for, so it can be retracted.
 *
 * <p>Edge ids are built by {@code OntologyProjection} in a fixed pipe-separated format. This
 * parses them back. It is deliberately strict: an unrecognised or malformed id throws rather
 * than guessing, because guessing here means deleting the wrong axiom from a user's ontology.
 *
 * <p>Pure functions over an {@link OWLOntology} - nothing is mutated. Callers apply the
 * returned changes through {@code OWLModelManager}.
 */
public final class AxiomRemoval {

    /** Thrown when an edge id cannot be understood; never delete on a guess. */
    public static class UnknownEdgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnknownEdgeException(String edgeId, String why) {
            super("Cannot work out which axiom edge '" + edgeId + "' represents: " + why
                    + ". Refusing to delete anything.");
        }
    }

    private AxiomRemoval() {
    }

    /**
     * True when this edge stands for global {@code rdfs:domain}/{@code rdfs:range} axioms.
     * Those are shared by every edge using the same property, so the UI must warn before
     * retracting them.
     */
    public static boolean isGlobalDomainRange(String edgeId) {
        return edgeId != null && edgeId.startsWith("dr|");
    }

    /** The changes that retract exactly what {@code edgeId} draws, and nothing else. */
    public static List<OWLOntologyChange> removalsFor(OWLOntology ontology, String edgeId) {
        if (edgeId == null || edgeId.isEmpty()) {
            throw new UnknownEdgeException(String.valueOf(edgeId), "empty id");
        }
        OWLDataFactory f = ontology.getOWLOntologyManager().getOWLDataFactory();
        String[] parts = edgeId.split("\\|");

        if ("sub".equals(parts[0])) {
            require(parts, 3, edgeId);
            return one(ontology, f.getOWLSubClassOfAxiom(
                    f.getOWLClass(IRI.create(parts[1])), f.getOWLClass(IRI.create(parts[2]))));
        }
        if ("type".equals(parts[0])) {
            require(parts, 3, edgeId);
            return one(ontology, f.getOWLClassAssertionAxiom(
                    f.getOWLClass(IRI.create(parts[2])),
                    f.getOWLNamedIndividual(IRI.create(parts[1]))));
        }
        if ("rest".equals(parts[0])) {
            require(parts, 5, edgeId);
            OWLAxiom axiom;
            if ("some".equals(parts[1])) {
                axiom = f.getOWLSubClassOfAxiom(f.getOWLClass(IRI.create(parts[2])),
                        f.getOWLObjectSomeValuesFrom(
                                f.getOWLObjectProperty(IRI.create(parts[3])),
                                f.getOWLClass(IRI.create(parts[4]))));
            } else if ("only".equals(parts[1])) {
                axiom = f.getOWLSubClassOfAxiom(f.getOWLClass(IRI.create(parts[2])),
                        f.getOWLObjectAllValuesFrom(
                                f.getOWLObjectProperty(IRI.create(parts[3])),
                                f.getOWLClass(IRI.create(parts[4]))));
            } else {
                throw new UnknownEdgeException(edgeId, "unknown qualifier '" + parts[1] + "'");
            }
            return one(ontology, axiom);
        }
        if ("data".equals(parts[0])) {
            require(parts, 4, edgeId);
            return one(ontology, f.getOWLSubClassOfAxiom(
                    f.getOWLClass(IRI.create(parts[1])),
                    f.getOWLDataSomeValuesFrom(
                            f.getOWLDataProperty(IRI.create(parts[2])),
                            f.getOWLDatatype(IRI.create(parts[3])))));
        }
        if ("subprop".equals(parts[0])) {
            require(parts, 3, edgeId);
            // Which kind of property it is cannot be read off the id, so both forms are offered
            // and one() keeps whichever the ontology actually holds. Guessing from the id would
            // mean encoding the kind into it, and the id has to stay stable for edges already
            // saved in sidecars.
            List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
            OWLAxiom objectForm = f.getOWLSubObjectPropertyOfAxiom(
                    f.getOWLObjectProperty(IRI.create(parts[1])),
                    f.getOWLObjectProperty(IRI.create(parts[2])));
            OWLAxiom dataForm = f.getOWLSubDataPropertyOfAxiom(
                    f.getOWLDataProperty(IRI.create(parts[1])),
                    f.getOWLDataProperty(IRI.create(parts[2])));
            if (ontology.containsAxiom(objectForm)) {
                changes.add(new RemoveAxiom(ontology, objectForm));
            }
            if (ontology.containsAxiom(dataForm)) {
                changes.add(new RemoveAxiom(ontology, dataForm));
            }
            return changes;
        }
        if ("dr".equals(parts[0])) {
            require(parts, 4, edgeId);
            return domainAndRange(ontology, parts[1], parts[2], parts[3]);
        }
        throw new UnknownEdgeException(edgeId, "unrecognised kind '" + parts[0] + "'");
    }

    /**
     * A legacy domain/range edge is drawn from two separate global axioms, so both are
     * retracted. Only axioms that actually exist are returned - the property may declare a
     * domain but no range, in which case there is one change, not two.
     */
    private static List<OWLOntologyChange> domainAndRange(OWLOntology ontology,
            String domainIri, String propertyIri, String rangeIri) {
        OWLDataFactory f = ontology.getOWLOntologyManager().getOWLDataFactory();
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();

        OWLObjectPropertyDomainAxiom domain = f.getOWLObjectPropertyDomainAxiom(
                f.getOWLObjectProperty(IRI.create(propertyIri)),
                f.getOWLClass(IRI.create(domainIri)));
        if (ontology.containsAxiom(domain)) {
            changes.add(new RemoveAxiom(ontology, domain));
        }
        OWLObjectPropertyRangeAxiom range = f.getOWLObjectPropertyRangeAxiom(
                f.getOWLObjectProperty(IRI.create(propertyIri)),
                f.getOWLClass(IRI.create(rangeIri)));
        if (ontology.containsAxiom(range)) {
            changes.add(new RemoveAxiom(ontology, range));
        }
        return changes;
    }

    /** Retracts the axiom only if the ontology actually asserts it. */
    private static List<OWLOntologyChange> one(OWLOntology ontology, OWLAxiom axiom) {
        if (!ontology.containsAxiom(axiom)) {
            return Collections.emptyList();
        }
        return Collections.<OWLOntologyChange>singletonList(new RemoveAxiom(ontology, axiom));
    }

    private static void require(String[] parts, int expected, String edgeId) {
        if (parts.length != expected) {
            throw new UnknownEdgeException(edgeId,
                    "expected " + expected + " segments, found " + parts.length);
        }
    }
}
