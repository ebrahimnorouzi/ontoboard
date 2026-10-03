package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Deleting a property arrow, and the two cases where it must not be allowed to.
 *
 * <p>The new arrows this release draws come from places where the axiom carries more than the
 * one relation. Retracting the axiom behind a conjunct would remove the other conjuncts;
 * retracting the one behind an equivalence would turn a defined class into a primitive one,
 * which changes what the ontology means. Both are refused, by name, before anything is removed.
 *
 * <p>Worth stating why the refusal ships with the feature rather than after it: these arrows
 * could not be drawn at all until now, so this is the first release in which the destructive
 * path exists. The last two releases each found a destructive path that was unreachable only by
 * accident, and that is not the same as safe.
 */
class PropertyEdgeRemovalTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private String id(PropertyEdgeId.Origin origin, String qualifier) {
        return PropertyEdgeId.of(origin, qualifier, NS + "Pizza", NS + "hasTopping",
                NS + "Topping");
    }

    // ---------- what may be removed ----------

    /** A restriction standing alone under SubClassOf is exactly one axiom, so it goes. */
    @Test
    void aRestrictionThatOwnsItsAxiomIsRetracted() {
        org.semanticweb.owlapi.model.OWLAxiom axiom = factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectMaxCardinality(1, property("hasTopping"), cls("Topping")));
        manager.addAxiom(ontology, axiom);

        List<OWLOntologyChange> changes = AxiomRemoval.removalsFor(ontology,
                id(PropertyEdgeId.Origin.SUBCLASS, "max1"));

        assertEquals(1, changes.size());
        assertEquals(axiom, changes.get(0).getAxiom());
    }

    /** A scoped domain reads backwards from the arrow, and is still retracted exactly. */
    @Test
    void aScopedDomainIsRetractedDespiteReadingBackwards() {
        org.semanticweb.owlapi.model.OWLAxiom axiom = factory.getOWLSubClassOfAxiom(
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping")),
                cls("Pizza"));
        manager.addAxiom(ontology, axiom);

        List<OWLOntologyChange> changes = AxiomRemoval.removalsFor(ontology,
                id(PropertyEdgeId.Origin.SCOPED_DOMAIN, "some"));

        assertEquals(1, changes.size());
        assertEquals(axiom, changes.get(0).getAxiom());
    }

    /** An axiom the ontology does not hold yields no changes, as everywhere else here. */
    @Test
    void anAxiomThatIsNotThereRemovesNothing() {
        assertTrue(AxiomRemoval.removalsFor(ontology,
                id(PropertyEdgeId.Origin.SUBCLASS, "some")).isEmpty());
    }

    // ---------- what must not be removed ----------

    /**
     * A conjunct cannot be deleted, because the axiom carries its siblings.
     *
     * <p>The ontology must be untouched afterwards: the test asserts the refusal AND that
     * nothing was computed, because a refusal that still returned changes would be worse than
     * no refusal.
     */
    @Test
    void aConjunctIsRefusedRatherThanTakingItsSiblingsWithIt() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectIntersectionOf(cls("Food"),
                        factory.getOWLObjectSomeValuesFrom(property("hasTopping"),
                                cls("Topping")))));
        int before = ontology.getAxiomCount();

        AxiomRemoval.RefusedException refused =
                assertThrows(AxiomRemoval.RefusedException.class,
                        () -> AxiomRemoval.removalsFor(ontology,
                                id(PropertyEdgeId.Origin.CONJUNCT, "some")));

        assertTrue(refused.getMessage().contains("conjunct"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Protege"), refused.getMessage());
        assertEquals(before, ontology.getAxiomCount(), "nothing may be removed by a refusal");
    }

    /** A class's definition cannot be deleted one arrow at a time. */
    @Test
    void anEquivalenceIsRefusedBecauseItIsTheDefinition() {
        manager.addAxiom(ontology, factory.getOWLEquivalentClassesAxiom(cls("Pizza"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping"))));
        int before = ontology.getAxiomCount();

        AxiomRemoval.RefusedException refused =
                assertThrows(AxiomRemoval.RefusedException.class,
                        () -> AxiomRemoval.removalsFor(ontology,
                                id(PropertyEdgeId.Origin.EQUIVALENCE, "some")));

        assertTrue(refused.getMessage().toLowerCase().contains("definition"),
                refused.getMessage());
        assertEquals(before, ontology.getAxiomCount());
    }

    /**
     * A refusal is still an {@code UnknownEdgeException} to anything catching one.
     *
     * <p>Three call sites catch that type - the canvas and two paths in the collaboration
     * mapper - and a refusal that escaped one of them would reach the event thread uncaught.
     */
    @Test
    void aRefusalIsCaughtByEveryExistingHandler() {
        assertThrows(AxiomRemoval.UnknownEdgeException.class,
                () -> AxiomRemoval.removalsFor(ontology,
                        id(PropertyEdgeId.Origin.EQUIVALENCE, "some")));
    }

    /** A refusal does not read as "I could not understand this", because that is not what it is. */
    @Test
    void aRefusalDoesNotClaimConfusion() {
        AxiomRemoval.UnknownEdgeException refused =
                assertThrows(AxiomRemoval.UnknownEdgeException.class,
                        () -> AxiomRemoval.removalsFor(ontology,
                                id(PropertyEdgeId.Origin.CONJUNCT, "some")));

        assertTrue(!refused.getMessage().contains("Cannot work out"),
                "the axiom is understood perfectly well; it is the deletion that is unsafe: "
                        + refused.getMessage());
    }

    // ---------- malformed ids ----------

    /** A malformed property id is refused as unreadable, not acted on. */
    @Test
    void aMalformedPropertyIdIsRefused() {
        for (String broken : new String[] {"pe|", "pe|sub|some", "pe|nope|some|a|b|c"}) {
            assertThrows(AxiomRemoval.UnknownEdgeException.class,
                    () -> AxiomRemoval.removalsFor(ontology, broken), broken);
        }
    }

    /** An unknown qualifier is refused rather than guessed into some other restriction. */
    @Test
    void anUnknownQualifierIsRefused() {
        AxiomRemoval.UnknownEdgeException refused =
                assertThrows(AxiomRemoval.UnknownEdgeException.class,
                        () -> AxiomRemoval.removalsFor(ontology,
                                id(PropertyEdgeId.Origin.SUBCLASS, "sideways")));

        assertTrue(refused.getMessage().contains("sideways"), refused.getMessage());
    }
}
