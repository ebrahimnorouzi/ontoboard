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
        java.util.List<String> added = membership.expandOneHop(ontology, NS + "Person");

        // Agent via SubClassOf, Organization via the worksFor domain/range pair, alice via rdf:type.
        assertEquals(3, added.size());
        assertTrue(membership.contains(NS + "Agent"));
        assertTrue(membership.contains(NS + "Organization"));
        assertTrue(membership.contains(NS + "alice"));
    }

    /**
     * The identifiers, not just how many.
     *
     * <p>This is the defect the 1.59.0 expansion work rests on: the method returned a count, so the
     * caller had nothing to place, nothing to save and nothing to collapse. Four symptoms, one missing
     * return value. Named here so a future simplification back to an int has to delete a test that
     * says why.
     */
    @Test
    void expandOneHopSaysWhichTermsItAdded() {
        membership.add(NS + "Person");

        java.util.List<String> added = membership.expandOneHop(ontology, NS + "Person");

        assertTrue(added.contains(NS + "Agent"), added.toString());
        assertTrue(added.contains(NS + "Organization"), added.toString());
        assertTrue(added.contains(NS + "alice"), added.toString());
        assertFalse(added.contains(NS + "Person"), "the term expanded is not its own neighbour");
    }

    /** A second expansion of the same term adds nothing, and says so by returning nothing. */
    @Test
    void expandingTwiceAddsNothingTheSecondTime() {
        membership.add(NS + "Person");
        membership.expandOneHop(ontology, NS + "Person");

        assertTrue(membership.expandOneHop(ontology, NS + "Person").isEmpty());
    }

    @Test
    void expandOneHopDoesNotCountEntitiesAlreadyPresent() {
        membership.add(NS + "Person");
        membership.add(NS + "Agent");
        assertEquals(2, membership.expandOneHop(ontology, NS + "Person").size());
    }
}
