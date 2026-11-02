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

        // Agent via SubClassOf, alice via rdf:type, and worksFor - its domain, drawn from the
        // property's own node since 1.131.0. Organization was the fourth while a domain and range
        // were one arrow between the two classes; it is worksFor's neighbour now, one hop on.
        assertEquals(3, added.size(), added.toString());
        assertTrue(membership.contains(NS + "Agent"));
        assertFalse(membership.contains(NS + "Organization"));
        assertTrue(membership.contains(NS + "alice"));
        assertTrue(membership.contains(NS + "worksFor"),
                "the property the arrow stands for is a neighbour of both its ends. The hop used "
                        + "to keep only entity.isOWLClass(), so a class whose only relation is a "
                        + "restriction had the filler as a neighbour and not the property - 102 "
                        + "of them on MWO 3.0.1");
    }

    /**
     * A hop on a property reaches the terms its arrows join, and its super- and sub-properties.
     *
     * <p>This direction returned <b>nothing at all</b> until 1.122.0, for every property in every
     * ontology: the collector had no branch that started from one. Measured: 85 of 85 object
     * properties on MWO 3.0.1, 112 of 112 on DUL, 60 of 60 on NFDIcore. After the change, 0 of
     * 112 on DUL and 10 of 85 on MWO - those ten being properties the canvas draws no edge for at
     * all, which is the honest answer rather than a borrowed one.
     */
    @Test
    void expandOneHopOnAPropertyReachesWhatItsArrowsJoin() {
        membership.add(NS + "worksFor");

        java.util.List<String> added = membership.expandOneHop(ontology, NS + "worksFor");

        assertTrue(added.contains(NS + "Person"), added.toString());
        assertTrue(added.contains(NS + "Organization"), added.toString());
    }

    /**
     * One hop is adjacency in the graph the canvas draws, which is why it is asked of the
     * projection rather than of the axioms.
     *
     * <p>The rule used to be a second, hand-written reading of the axioms, and it disagreed with
     * the canvas in four measured ways at once: nothing for a property, no property inside a
     * restriction, imports excluded while the canvas includes them, and a domain-to-range jump
     * that is two hops and added 21 non-neighbours on MWO. Pinned here so that a term the board
     * would join to this one cannot stop being a neighbour without a test saying so.
     */
    @Test
    void everyNeighbourIsAnEndOfAnEdgeTheCanvasWouldDraw() {
        java.util.Set<String> hop = CanvasMembership.neighboursOf(ontology, NS + "Person");

        de.fizkarlsruhe.ise.ontoboard.model.Projection whole =
                de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection.project(ontology,
                        de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection.termIdentifiers(
                                ontology, org.semanticweb.owlapi.model.parameters.Imports
                                        .INCLUDED));
        for (String neighbour : hop) {
            boolean joined = false;
            for (de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge edge : whole.getEdges()) {
                boolean ends = (NS + "Person").equals(edge.getSourceId())
                        || (NS + "Person").equals(edge.getTargetId());
                if (!ends) {
                    continue;
                }
                if (neighbour.equals(edge.getSourceId()) || neighbour.equals(edge.getTargetId())
                        || neighbour.equals(de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId
                                .propertyIn(edge.getId()))) {
                    joined = true;
                    break;
                }
            }
            assertTrue(joined, neighbour + " is a neighbour of Person but no edge joins them");
        }
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
        assertTrue(added.contains(NS + "worksFor"), added.toString());
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

        // alice and worksFor: the three neighbours less the one already there.
        assertEquals(2, membership.expandOneHop(ontology, NS + "Person").size());
    }
}
