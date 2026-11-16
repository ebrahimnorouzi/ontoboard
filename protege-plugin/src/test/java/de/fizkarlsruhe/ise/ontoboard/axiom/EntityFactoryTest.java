package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Where a term named after what was typed goes (TermMinter's own mode): the ontology's. */
    @Test
    void newEntitiesLandInTheOntologysOwnNamespace() {
        assertEquals("http://example.org/mine#", EntityFactory.namespaceOf(ontology),
                "a new term must belong to the ontology being edited");
    }

    @Test
    void anExistingDelimiterIsNotDoubled() throws Exception {
        OWLOntology hashed = manager.createOntology(IRI.create("http://example.org/h#"));
        assertEquals("http://example.org/h#", EntityFactory.namespaceOf(hashed));
    }

    @Test
    void anAnonymousOntologyFallsBackRatherThanCrashing() throws Exception {
        OWLOntology anonymous = manager.createOntology();
        assertEquals(EntityFactory.FALLBACK_NAMESPACE, EntityFactory.namespaceOf(anonymous));
    }

    /**
     * F7: a blank name is refused with a sentence, on the path the board and the relation
     * dialog both take - for a class, an individual and a property alike. (It was tested on
     * iriFor, which no production path called any more.)
     */
    @Test
    void blankNamesAreRejected() {
        for (String blank : new String[] {"   ", "", null}) {
            EntityFactory.NameCheck check = EntityFactory.checkName(ontology, blank, "property");
            assertEquals(EntityFactory.NameCheck.Verdict.REFUSED, check.verdict(), blank);
            assertEquals("Give the new property a name.", check.sentence());
        }
        assertEquals("Give the new class a name.",
                EntityFactory.checkName(ontology, " ", "class").sentence());
    }

    /**
     * F7: a name another term here already has as its label is asked about - two terms may be
     * meant to read alike, but nothing else says they now do; any other name goes, trimmed.
     */
    @Test
    void aNameAnotherTermHasIsAskedAboutAndAnyOtherGoes() {
        IRI sample = IRI.create("http://example.org/mine#Sample");
        manager.applyChanges(EntityFactory.declare(ontology, sample, EntityFactory.Kind.CLASS));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLAnnotationAssertionAxiom(
                manager.getOWLDataFactory().getRDFSLabel(), sample,
                manager.getOWLDataFactory().getOWLLiteral("sample")));

        EntityFactory.NameCheck twin = EntityFactory.checkName(ontology, " Sample ", "property");
        EntityFactory.NameCheck fresh = EntityFactory.checkName(ontology, " specimen ",
                "property");

        assertEquals(EntityFactory.NameCheck.Verdict.ASK, twin.verdict());
        assertEquals("\"Sample\" is already the name of Sample.\nMake another property with "
                + "the same name?", twin.sentence());
        assertEquals(EntityFactory.NameCheck.Verdict.GOES, fresh.verdict());
        assertEquals("specimen", fresh.name());
    }

    @Test
    void declaringAClassProducesExactlyOneDeclarationAxiom() {
        IRI iri = IRI.create("http://example.org/mine#Sample");
        List<OWLOntologyChange> changes =
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.CLASS);

        assertEquals(1, changes.size());
        manager.applyChanges(changes);
        assertTrue(ontology.containsClassInSignature(iri),
                "the class should exist after applying the change");
    }

    @Test
    void declaringAnIndividualProducesAnIndividualNotAClass() {
        IRI iri = IRI.create("http://example.org/mine#sample1");
        manager.applyChanges(
                EntityFactory.declare(ontology, iri, EntityFactory.Kind.INDIVIDUAL));

        assertTrue(ontology.containsIndividualInSignature(iri));
        assertTrue(!ontology.containsClassInSignature(iri),
                "an individual must not also be declared a class");
    }

    @Test
    void declaringAnObjectPropertyProducesAProperty() {
        IRI iri = IRI.create("http://example.org/mine#relatesTo");
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
        IRI iri = IRI.create("http://example.org/mine#Sample");
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
