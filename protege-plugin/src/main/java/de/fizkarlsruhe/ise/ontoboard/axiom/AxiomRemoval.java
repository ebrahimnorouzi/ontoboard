package de.fizkarlsruhe.ise.ontoboard.axiom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
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

        UnknownEdgeException(String fullMessage) {
            super(fullMessage);
        }
    }

    /**
     * Thrown when the axiom behind an edge is known exactly, and removing it would take more
     * with it than the one arrow.
     *
     * <p>A separate type with its own wording, because "cannot work out which axiom this is" is
     * the opposite of what has happened and would send a user looking for a defect. A
     * restriction inside a conjunction or inside a class's definition is understood perfectly
     * well; it is the deletion that is unsafe, and the message says what to do instead.
     *
     * <p>A subclass of {@link UnknownEdgeException} on purpose: three call sites already catch
     * that - the canvas, and two paths in the collaboration mapper - and a refusal that escaped
     * one of them would reach the event thread as an uncaught exception.
     */
    public static final class RefusedException extends UnknownEdgeException {
        private static final long serialVersionUID = 1L;

        RefusedException(String why) {
            super(why);
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
        // Split keeping empty segments. Java's default split drops trailing empties, so an id of
        // nothing but delimiters - "|", "||" - yields a ZERO-length array, and parts[0] threw
        // ArrayIndexOutOfBoundsException instead of the UnknownEdgeException this class documents
        // as its refusal. Both callers catch only UnknownEdgeException, so it escaped: onto the
        // event thread from an inbound collaboration operation whose data.id a peer controls, and
        // past the dialog in the canvas. With -1 the empty segments survive and the existing
        // length checks do the work.
        String[] parts = edgeId.split("\\|", -1);
        if (parts.length == 0) {
            throw new UnknownEdgeException(edgeId, "no segments");
        }

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
        if (de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.is(edgeId)) {
            return propertyEdge(ontology, edgeId);
        }
        throw new UnknownEdgeException(edgeId, "unrecognised kind '" + parts[0] + "'");
    }

    /**
     * A property arrow from a restriction, retracted only where that is exactly one axiom.
     *
     * <p>Two of the four origins are refused, and the refusal is the point of recording the
     * origin in the id at all. A restriction inside an {@code ObjectIntersectionOf} shares its
     * axiom with the other conjuncts, and a restriction inside an {@code EquivalentClasses}
     * shares it with the whole definition of the class - so in both cases the one axiom that
     * could be removed carries far more than the arrow the user right-clicked. Removing it
     * would delete relations they can see nothing wrong with, or silently turn a defined class
     * into a primitive one, which changes what the ontology means.
     *
     * <p>This is the same lesson as the ODK regeneration guard: a destructive path is not safe
     * because nobody has walked down it yet. These arrows could not be drawn at all before this
     * release, so the refusal arrives with them rather than after someone's definition is gone.
     */
    private static List<OWLOntologyChange> propertyEdge(OWLOntology ontology, String edgeId) {
        de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Parsed parsed =
                de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.parse(edgeId);
        if (parsed == null) {
            throw new UnknownEdgeException(edgeId, "malformed property edge id");
        }
        if (!parsed.getOrigin().isRetractable()) {
            throw new RefusedException(parsed.getOrigin().getRefusal());
        }

        OWLDataFactory f = ontology.getOWLOntologyManager().getOWLDataFactory();
        OWLObjectProperty property =
                f.getOWLObjectProperty(IRI.create(parsed.getProperty()));
        OWLClass subject = f.getOWLClass(IRI.create(parsed.getSubject()));
        OWLClass filler = f.getOWLClass(IRI.create(parsed.getFiller()));

        if (parsed.getOrigin()
                == de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Origin.SCOPED_DOMAIN) {
            // SubClassOf(ObjectSomeValuesFrom(R B) A) - the axiom reads the other way round from
            // the arrow, which is why the origin has to be carried rather than inferred.
            return one(ontology, f.getOWLSubClassOfAxiom(
                    f.getOWLObjectSomeValuesFrom(property, filler), subject));
        }

        org.semanticweb.owlapi.model.OWLClassExpression restriction =
                restrictionOf(f, parsed, property, filler);
        if (restriction == null) {
            throw new UnknownEdgeException(edgeId,
                    "unrecognised qualifier '" + parsed.getQualifier() + "'");
        }
        return one(ontology, f.getOWLSubClassOfAxiom(subject, restriction));
    }

    /** The class expression a qualifier stands for, or null when the qualifier is not one. */
    private static org.semanticweb.owlapi.model.OWLClassExpression restrictionOf(OWLDataFactory f,
            de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Parsed parsed,
            OWLObjectProperty property, OWLClass filler) {
        String shape = parsed.getShape();
        int cardinality = parsed.getCardinality();
        if ("some".equals(shape)) {
            return f.getOWLObjectSomeValuesFrom(property, filler);
        }
        if ("only".equals(shape)) {
            return f.getOWLObjectAllValuesFrom(property, filler);
        }
        if (cardinality < 0) {
            return null;
        }
        if ("min".equals(shape)) {
            return f.getOWLObjectMinCardinality(cardinality, property, filler);
        }
        if ("max".equals(shape)) {
            return f.getOWLObjectMaxCardinality(cardinality, property, filler);
        }
        if ("exactly".equals(shape)) {
            return f.getOWLObjectExactCardinality(cardinality, property, filler);
        }
        return null;
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
