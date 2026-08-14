package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class OntologyProjectionTest {

    private static final String NS = "http://example.org/tiny#";
    private OWLOntology ontology;

    @BeforeEach
    void loadFixture() throws Exception {
        ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
    }

    private static Set<String> iris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS + n).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void projectsOnlyEntitiesThatAreOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertEquals(1, p.getNodes().size());
        assertEquals(NS + "Person", p.getNodes().get(0).getId());
        assertEquals(NodeKind.CLASS, p.getNodes().get(0).getKind());
    }

    @Test
    void projectsSubClassOfBetweenTwoOnCanvasClasses() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Agent"));
        assertEquals(2, p.getNodes().size());
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.SUBCLASS, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Agent", edge.getTargetId());
    }

    @Test
    void omitsSubClassOfWhenTheSuperClassIsNotOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertTrue(p.getEdges().isEmpty());
    }

    /** Ontologies written by the retired web app used rdfs:domain/rdfs:range for edges. */
    @Test
    void projectsLegacyDomainRangePairsAsAPropertyEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Organization"));
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Organization", edge.getTargetId());
        assertEquals("worksFor", edge.getLabel());
    }

    @Test
    void projectsClassAssertionAsATypeEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "alice"));

        CanvasNode alice = p.getNodes().stream()
                .filter(n -> n.getId().equals(NS + "alice")).findFirst()
                .orElseThrow(() -> new AssertionError("alice node not found"));
        assertEquals(NodeKind.INDIVIDUAL, alice.getKind());

        assertEquals(1, p.getEdges().size());
        assertEquals(CanvasEdge.Kind.TYPE, p.getEdges().get(0).getKind());
        assertEquals(NS + "alice", p.getEdges().get(0).getSourceId());
        assertEquals(NS + "Person", p.getEdges().get(0).getTargetId());
    }
}
