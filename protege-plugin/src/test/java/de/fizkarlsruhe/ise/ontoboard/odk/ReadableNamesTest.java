package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Rename IRIs - Readable names from labels (1.155.0): a project that chose readable names gives
 * its numbered terms that no release has carried the IRI their label makes. A released term keeps
 * its IRI - somebody may use it; a term without a label, or whose name is taken, is left and said.
 */
class ReadableNamesTest {

    private static final String NUMBERED = "https://example.org/EGG_";
    private static final String NS = "https://example.org/egg/";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology ontology;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("https://example.org/egg.owl"));
    }

    private OWLEntity term(OWLEntity entity, String label) {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(entity));
        if (label != null) {
            manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), entity.getIRI(), factory.getOWLLiteral(label, "en")));
        }
        return entity;
    }

    private OWLEntity numberedClass(String number, String label) {
        return term(factory.getOWLClass(IRI.create(NUMBERED + number)), label);
    }

    private ReadableNames.Plan plan(Set<String> released) {
        return ReadableNames.plan(ontology, NUMBERED, NS, released);
    }

    @Test
    void anUnreleasedNumberedTermGetsTheNameItsLabelMakes() {
        numberedClass("0000001", "boiled egg");
        term(factory.getOWLObjectProperty(IRI.create(NUMBERED + "0000002")), "has part");
        term(factory.getOWLNamedIndividual(IRI.create(NUMBERED + "0000003")), "my egg");

        ReadableNames.Plan plan = plan(Collections.<String>emptySet());

        assertEquals(NS + "BoiledEgg", plan.mappings().get(NUMBERED + "0000001"));
        assertEquals(NS + "hasPart", plan.mappings().get(NUMBERED + "0000002"));
        assertEquals(NS + "myEgg", plan.mappings().get(NUMBERED + "0000003"));
        assertTrue(plan.left().isEmpty(), String.valueOf(plan.left()));
    }

    @Test
    void aReleasedTermKeepsItsIri() {
        numberedClass("0000001", "boiled egg");

        ReadableNames.Plan plan = plan(new HashSet<String>(
                Collections.singletonList(NUMBERED + "0000001")));

        assertTrue(plan.mappings().isEmpty());
        assertTrue(plan.left().get(0).contains("release"), String.valueOf(plan.left()));
    }

    @Test
    void aTermWithoutALabelIsLeftAndSaid() {
        numberedClass("0000001", null);

        ReadableNames.Plan plan = plan(Collections.<String>emptySet());

        assertTrue(plan.mappings().isEmpty());
        assertTrue(plan.left().get(0).contains("EGG_0000001"), String.valueOf(plan.left()));
        assertTrue(plan.left().get(0).contains("label"), String.valueOf(plan.left()));
    }

    @Test
    void twoThatWouldShareANameAreBothLeft() {
        numberedClass("0000001", "soft egg");
        numberedClass("0000002", "Soft Egg");

        ReadableNames.Plan plan = plan(Collections.<String>emptySet());

        assertTrue(plan.mappings().isEmpty(), String.valueOf(plan.mappings()));
        assertEquals(2, plan.left().size(), String.valueOf(plan.left()));
        assertTrue(plan.left().get(0).contains(NS + "SoftEgg"), String.valueOf(plan.left()));
    }

    @Test
    void aNameAnotherTermHasIsLeft() {
        term(factory.getOWLClass(IRI.create(NS + "HardEgg")), "hard egg, as named");
        numberedClass("0000001", "hard egg");

        ReadableNames.Plan plan = plan(Collections.<String>emptySet());

        assertTrue(plan.mappings().isEmpty());
        assertTrue(plan.left().get(0).contains(NS + "HardEgg"), String.valueOf(plan.left()));
    }

    @Test
    void termsOutsideTheNumberingAreNotTouched() {
        term(factory.getOWLClass(IRI.create("http://purl.obolibrary.org/obo/BFO_0000001")),
                "entity");
        term(factory.getOWLClass(IRI.create(NS + "Egg")), "egg");

        ReadableNames.Plan plan = plan(Collections.<String>emptySet());

        assertTrue(plan.mappings().isEmpty());
        assertFalse(plan.left().stream().anyMatch(line -> line.contains("BFO")));
    }
}
