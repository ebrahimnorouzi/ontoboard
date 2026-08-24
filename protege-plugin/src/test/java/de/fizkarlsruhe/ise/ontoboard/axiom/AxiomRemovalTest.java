package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class AxiomRemovalTest {

    private static final String NS = "http://example.org/tiny#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void loadFixture() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.loadOntologyFromOntologyDocument(
                new File("src/test/resources/fixture-tiny.ttl"));
        factory = manager.getOWLDataFactory();
    }

    private static Set<String> onCanvas(String... localNames) {
        Set<String> set = new HashSet<String>();
        for (String name : localNames) {
            set.add(NS + name);
        }
        return set;
    }

    private String edgeIdOfKind(Set<String> members, CanvasEdge.Kind kind) {
        Projection projection = OntologyProjection.project(ontology, members);
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getKind() == kind) {
                return edge.getId();
            }
        }
        throw new AssertionError("fixture produced no " + kind + " edge");
    }

    /**
     * The ids come from OntologyProjection, so this asserts the two halves agree rather than
     * hard-coding a format that could drift.
     */
    @Test
    void removesTheSubClassAxiomASubclassEdgeStandsFor() {
        String id = edgeIdOfKind(onCanvas("Person", "Agent"), CanvasEdge.Kind.SUBCLASS);
        int before = ontology.getAxiomCount();

        List<OWLOntologyChange> changes = AxiomRemoval.removalsFor(ontology, id);
        manager.applyChanges(changes);

        assertEquals(1, changes.size());
        assertEquals(before - 1, ontology.getAxiomCount());
        assertTrue(!ontology.containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "Person")),
                factory.getOWLClass(IRI.create(NS + "Agent")))));
    }

    @Test
    void removesTheClassAssertionATypeEdgeStandsFor() {
        String id = edgeIdOfKind(onCanvas("Person", "alice"), CanvasEdge.Kind.TYPE);
        manager.applyChanges(AxiomRemoval.removalsFor(ontology, id));

        assertTrue(!ontology.containsAxiom(factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(IRI.create(NS + "Person")),
                factory.getOWLNamedIndividual(IRI.create(NS + "alice")))));
    }

    /**
     * A legacy domain/range edge is drawn from two separate global axioms; both go, but the
     * property declaration itself must survive - deleting an arrow is not deleting the term.
     */
    @Test
    void removesBothGlobalAxiomsButKeepsThePropertyDeclaration() {
        String id = edgeIdOfKind(onCanvas("Person", "Organization"),
                CanvasEdge.Kind.OBJECT_PROPERTY);
        assertTrue(AxiomRemoval.isGlobalDomainRange(id),
                "the fixture's worksFor edge is the legacy domain/range form");

        manager.applyChanges(AxiomRemoval.removalsFor(ontology, id));

        assertTrue(ontology.containsObjectPropertyInSignature(IRI.create(NS + "worksFor")),
                "the property must still exist after removing the arrow");
        assertTrue(ontology.getObjectPropertyDomainAxioms(
                factory.getOWLObjectProperty(IRI.create(NS + "worksFor"))).isEmpty());
        assertTrue(ontology.getObjectPropertyRangeAxioms(
                factory.getOWLObjectProperty(IRI.create(NS + "worksFor"))).isEmpty());
    }

    @Test
    void removingAnEdgeTwiceIsHarmless() {
        String id = edgeIdOfKind(onCanvas("Person", "Agent"), CanvasEdge.Kind.SUBCLASS);
        manager.applyChanges(AxiomRemoval.removalsFor(ontology, id));
        int after = ontology.getAxiomCount();

        List<OWLOntologyChange> again = AxiomRemoval.removalsFor(ontology, id);

        assertTrue(again.isEmpty(), "an already-retracted axiom yields no changes");
        assertEquals(after, ontology.getAxiomCount());
    }

    @Test
    void anUnrecognisedEdgeKindRefusesRatherThanGuessing() {
        assertThrows(AxiomRemoval.UnknownEdgeException.class,
                () -> AxiomRemoval.removalsFor(ontology, "wat|a|b"));
    }

    @Test
    void aMalformedIdRefusesRatherThanDeletingTheWrongThing() {
        for (String bad : Arrays.asList("sub|onlyone", "rest|some|a|b", "dr|a|b", "", "type|x")) {
            assertThrows(AxiomRemoval.UnknownEdgeException.class,
                    () -> AxiomRemoval.removalsFor(ontology, bad),
                    "should have refused: '" + bad + "'");
        }
    }

    @Test
    void anUnknownRestrictionQualifierIsRefused() {
        assertThrows(AxiomRemoval.UnknownEdgeException.class,
                () -> AxiomRemoval.removalsFor(ontology,
                        "rest|exactly|" + NS + "A|" + NS + "p|" + NS + "B"));
    }

    @Test
    void onlyGlobalDomainRangeEdgesAreFlaggedAsGlobal() {
        assertTrue(AxiomRemoval.isGlobalDomainRange("dr|a|b|c"));
        assertTrue(!AxiomRemoval.isGlobalDomainRange("sub|a|b"));
        assertTrue(!AxiomRemoval.isGlobalDomainRange(null));
    }
}
