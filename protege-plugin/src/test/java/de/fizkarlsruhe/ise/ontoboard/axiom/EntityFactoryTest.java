package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class EntityFactoryTest {

    private OWLOntologyManager manager;
    private OWLOntology ontology;

    @BeforeEach
    void setUp() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/mine"));
    }

    @Test
    void newEntitiesLandInTheOntologysOwnNamespace() {
        assertEquals("http://example.org/mine#Sample",
                EntityFactory.iriFor(ontology, "Sample").toString(),
                "a new term must belong to the ontology being edited");
    }

    @Test
    void anExistingDelimiterIsNotDoubled() throws Exception {
        OWLOntology hashed = manager.createOntology(IRI.create("http://example.org/h#"));
        assertEquals("http://example.org/h#Sample",
                EntityFactory.iriFor(hashed, "Sample").toString());
    }

    @Test
    void anAnonymousOntologyFallsBackRatherThanCrashing() throws Exception {
        OWLOntology anonymous = manager.createOntology();
        assertEquals(EntityFactory.FALLBACK_NAMESPACE + "Sample",
                EntityFactory.iriFor(anonymous, "Sample").toString());
    }

    @Test
    void nameIsTrimmedBeforeUse() {
        assertEquals("http://example.org/mine#Sample",
                EntityFactory.iriFor(ontology, "  Sample  ").toString());
    }

    /** A blank name would mint a bare '#' IRI - an entity nobody can refer to. */
    @Test
    void blankNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, ""));
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, null));
    }

    @Test
    void namesContainingDelimitersOrSpacesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, "a#b"));
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, "a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> EntityFactory.iriFor(ontology, "two words"));
    }

    @Test
    void declaringAClassProducesExactlyOneDeclarationAxiom() {
        IRI iri = EntityFactory.iriFor(ontology, "Sample");
        List<OWLOntologyChange> changes =
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.CLASS);

        assertEquals(1, changes.size());
        manager.applyChanges(changes);
        assertTrue(ontology.containsClassInSignature(iri),
                "the class should exist after applying the change");
    }

    @Test
    void declaringAnIndividualProducesAnIndividualNotAClass() {
        IRI iri = EntityFactory.iriFor(ontology, "sample1");
        manager.applyChanges(
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.INDIVIDUAL));

        assertTrue(ontology.containsIndividualInSignature(iri));
        assertTrue(!ontology.containsClassInSignature(iri),
                "an individual must not also be declared a class");
    }

    @Test
    void declaringAnObjectPropertyProducesAProperty() {
        IRI iri = EntityFactory.iriFor(ontology, "relatesTo");
        manager.applyChanges(
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.OBJECT_PROPERTY));

        assertTrue(ontology.containsObjectPropertyInSignature(iri));
    }

    /**
     * Re-declaring would add a duplicate axiom and mark the ontology dirty for no reason,
     * which in Protege means a spurious "unsaved changes" prompt.
     */
    @Test
    void redeclaringAnExistingEntityProducesNoChanges() {
        IRI iri = EntityFactory.iriFor(ontology, "Sample");
        manager.applyChanges(EntityFactory.declare(ontology, iri, EntityFactory.Kind.CLASS));
        int axiomsAfterFirst = ontology.getAxiomCount();

        List<OWLOntologyChange> second =
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.CLASS);

        assertTrue(second.isEmpty(), "expected no changes for an already-declared entity");
        assertEquals(axiomsAfterFirst, ontology.getAxiomCount());
    }

    @Test
    void entityForReturnsTheRightTypeWithoutCreatingAxioms() {
        IRI iri = IRI.create("http://example.org/mine#X");
        int before = ontology.getAxiomCount();

        assertTrue(EntityFactory.entityFor(manager.getOWLDataFactory(), iri,
                EntityFactory.Kind.CLASS).isOWLClass());
        assertTrue(EntityFactory.entityFor(manager.getOWLDataFactory(), iri,
                EntityFactory.Kind.INDIVIDUAL).isOWLNamedIndividual());
        assertTrue(EntityFactory.entityFor(manager.getOWLDataFactory(), iri,
                EntityFactory.Kind.OBJECT_PROPERTY).isOWLObjectProperty());

        assertEquals(before, ontology.getAxiomCount(), "entityFor must not mutate anything");
    }
}
