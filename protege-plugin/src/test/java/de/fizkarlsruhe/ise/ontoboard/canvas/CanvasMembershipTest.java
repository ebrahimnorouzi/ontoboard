package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class CanvasMembershipTest {

    private static final String NS = "http://example.org/tiny#";
    private OWLOntology ontology;
    private CanvasMembership membership;

    @BeforeEach
    void setUp() throws Exception {
        ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
        membership = new CanvasMembership(new CanvasLayout());
    }

    @Test
    void startsEmptyBecauseTheCanvasIsOptIn() {
        assertEquals(0, membership.size());
    }

    @Test
    void addIsIdempotent() {
        assertTrue(membership.add(NS + "Person"));
        assertFalse(membership.add(NS + "Person"));
        assertEquals(1, membership.size());
    }

    @Test
    void removeTakesTheEntityOffTheCanvasButLeavesTheOntologyAlone() {
        membership.add(NS + "Person");
        int axiomsBefore = ontology.getAxiomCount();

        assertTrue(membership.remove(NS + "Person"));
        assertFalse(membership.contains(NS + "Person"));
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "removing from the canvas must never delete axioms");
    }

    @Test
    void expandOneHopPullsInDirectlyRelatedEntitiesOnly() {
        membership.add(NS + "Person");
        int added = membership.expandOneHop(ontology, NS + "Person");

        // Agent via SubClassOf, Organization via the worksFor domain/range pair, alice via rdf:type.
        assertEquals(3, added);
        assertTrue(membership.contains(NS + "Agent"));
        assertTrue(membership.contains(NS + "Organization"));
        assertTrue(membership.contains(NS + "alice"));
    }

    @Test
    void expandOneHopDoesNotCountEntitiesAlreadyPresent() {
        membership.add(NS + "Person");
        membership.add(NS + "Agent");
        assertEquals(2, membership.expandOneHop(ontology, NS + "Person"));
    }
}
