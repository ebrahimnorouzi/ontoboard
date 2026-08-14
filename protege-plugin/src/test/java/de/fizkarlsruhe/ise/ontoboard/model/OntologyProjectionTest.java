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
    private static final String NS2 = "http://example.org/restrictions#";
    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private OWLOntology ontology;
    private OWLOntology restrictionsOntology;

    @BeforeEach
    void loadFixture() throws Exception {
        org.semanticweb.owlapi.model.OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ontology = manager.loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
        restrictionsOntology = manager.loadOntologyFromOntologyDocument(
                new File("src/test/resources/fixture-restrictions.ttl"));
    }

    private static Set<String> iris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS + n).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> restrictionIris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS2 + n).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> exact(String... fullIris) {
        return new HashSet<>(Arrays.asList(fullIris));
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

    @Test
    void projectsExistentialRestrictionAsAnObjectPropertyEdge() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Person", "Organization"));
        assertEquals(2, p.getNodes().size());
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Person", edge.getSourceId());
        assertEquals(NS2 + "Organization", edge.getTargetId());
        assertEquals("worksFor", edge.getLabel());
    }

    @Test
    void projectsUniversalRestrictionWithOnlyQualifierInLabel() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Manager", "Person"));
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Manager", edge.getSourceId());
        assertEquals(NS2 + "Person", edge.getTargetId());
        assertEquals("manages (only)", edge.getLabel());
    }

    @Test
    void projectsPlainDataRestrictionAsADataPropertyEdgeWithADatatypeNode() {
        Projection p = OntologyProjection.project(restrictionsOntology, restrictionIris("Student"));

        assertEquals(2, p.getNodes().size());
        CanvasNode datatypeNode = p.getNodes().stream()
                .filter(n -> n.getKind() == NodeKind.DATATYPE).findFirst()
                .orElseThrow(() -> new AssertionError("no datatype node projected"));
        assertEquals(XSD_STRING, datatypeNode.getId());
        assertEquals("string", datatypeNode.getLabel());

        assertEquals(1, p.getEdges().size());
        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.DATA_PROPERTY, edge.getKind());
        assertEquals(NS2 + "Student", edge.getSourceId());
        assertEquals(XSD_STRING, edge.getTargetId());
        assertEquals("hasName", edge.getLabel());
    }

    @Test
    void dedupesDatatypeNodeWhenTwoClassesShareARange() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Student", "Contact"));

        // Student and Contact both restrict a property to xsd:string: exactly one DATATYPE
        // node must be projected, not two, even though two edges point at it.
        assertEquals(3, p.getNodes().size());
        long datatypeNodeCount = p.getNodes().stream().filter(n -> n.getKind() == NodeKind.DATATYPE).count();
        assertEquals(1, datatypeNodeCount);

        assertEquals(2, p.getEdges().size());
        assertTrue(p.getEdges().stream().allMatch(e -> e.getKind() == CanvasEdge.Kind.DATA_PROPERTY));
        assertTrue(p.getEdges().stream().allMatch(e -> e.getTargetId().equals(XSD_STRING)));
    }

    /**
     * Regression test for the bug where a facet-restricted data range (filler is an
     * OWLDatatypeRestriction, not a plain OWLDatatype) hit a {@code return} instead of a
     * {@code continue} and silently aborted the scan of every remaining SubClassOf axiom,
     * non-deterministically depending on the unordered Set's iteration order.
     */
    @Test
    void facetRestrictedDataRangeDoesNotSuppressOtherSubClassEdges() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                restrictionIris("Employee", "Left", "Right"));

        boolean hasLeftToRight = p.getEdges().stream().anyMatch(e -> e.getKind() == CanvasEdge.Kind.SUBCLASS
                && e.getSourceId().equals(NS2 + "Left") && e.getTargetId().equals(NS2 + "Right"));
        assertTrue(hasLeftToRight, "SubClassOf(Left, Right) must survive even though Employee has an "
                + "unhandled facet-restricted data range axiom in the same axiom set");
    }

    @Test
    void localNameStripsTrailingHashBeforeFallingBackToSlashSegment() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                exact("http://example.org/restrictions/thing#"));
        assertEquals(1, p.getNodes().size());
        assertEquals("thing", p.getNodes().get(0).getLabel());
    }

    @Test
    void localNameStripsTrailingSlashBeforeFallingBackToSlashSegment() {
        Projection p = OntologyProjection.project(restrictionsOntology,
                exact("http://example.org/restrictions/container/"));
        assertEquals(1, p.getNodes().size());
        assertEquals("container", p.getNodes().get(0).getLabel());
    }
}
