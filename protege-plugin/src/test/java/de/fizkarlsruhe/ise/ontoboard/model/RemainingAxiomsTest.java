package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The five relations that said something about the board and drew nothing.
 *
 * <p>Measured on MWO 3.0.1 before 1.124.0: 15 DisjointClasses, 38 InverseObjectProperties, 3
 * SubPropertyChainOf, 5 ObjectPropertyAssertion, 0 HasKey, and 25 property characteristics -
 * every one of them hover text, which on a diagram means invisible. A class with no edges on
 * screen could be constrained in six ways and look unconstrained.
 *
 * <p>Each is tested for the thing that was wrong - that it draws at all - and for the board's
 * opt-in rule, which is what keeps disjointness from flooding a taxonomy.
 */
class RemainingAxiomsTest {

    private static final String NS = "http://example.org/rest#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology ontology;

    @BeforeEach
    void emptyOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/rest"));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private OWLNamedIndividual individual(String name) {
        return factory.getOWLNamedIndividual(IRI.create(NS + name));
    }

    private void add(org.semanticweb.owlapi.model.OWLAxiom axiom) {
        manager.addAxiom(ontology, axiom);
    }

    private Set<String> board(String... names) {
        Set<String> on = new HashSet<String>();
        for (String name : names) {
            on.add(NS + name);
        }
        return on;
    }

    private List<CanvasEdge> edgesOfKind(Projection projection, CanvasEdge.Kind kind) {
        List<CanvasEdge> found = new ArrayList<CanvasEdge>();
        for (CanvasEdge edge : projection.getEdges()) {
            if (edge.getKind() == kind) {
                found.add(edge);
            }
        }
        return found;
    }

    /** {@code DisjointClasses(A B)} - the one relation that says two terms do NOT meet. */
    @Test
    void disjointnessIsDrawn() {
        add(factory.getOWLDisjointClassesAxiom(cls("A"), cls("B")));

        Projection drawn = OntologyProjection.project(ontology, board("A", "B"));

        assertEquals(1, edgesOfKind(drawn, CanvasEdge.Kind.DISJOINT).size(), "one pair, one line");
    }

    /**
     * One line per pair, however the axiom is written, and never two on top of each other.
     *
     * <p>An n-ary DisjointClasses means every pair is disjoint. Three classes is three lines, and
     * restating the same pair in a second axiom must not draw a second line over the first.
     */
    @Test
    void anNaryDisjointnessIsOneLinePerPair() {
        add(factory.getOWLDisjointClassesAxiom(cls("A"), cls("B"), cls("C")));
        add(factory.getOWLDisjointClassesAxiom(cls("B"), cls("A")));

        Projection drawn = OntologyProjection.project(ontology, board("A", "B", "C"));

        assertEquals(3, edgesOfKind(drawn, CanvasEdge.Kind.DISJOINT).size(),
                "A-B, A-C, B-C and nothing twice: " + idsOf(drawn));
    }

    /** Both ends have to be on the board, or a taxonomy would fill with red lines. */
    @Test
    void disjointnessNeedsBothEndsOnTheBoard() {
        add(factory.getOWLDisjointClassesAxiom(cls("A"), cls("B")));

        Projection drawn = OntologyProjection.project(ontology, board("A"));

        assertTrue(edgesOfKind(drawn, CanvasEdge.Kind.DISJOINT).isEmpty(), idsOf(drawn).toString());
    }

    /** {@code InverseObjectProperties(R S)}, between the two property nodes. */
    @Test
    void anInversePairIsDrawnBetweenTheTwoPropertyNodes() {
        add(factory.getOWLInverseObjectPropertiesAxiom(property("hasPart"), property("partOf")));

        Projection drawn = OntologyProjection.project(ontology, board("hasPart", "partOf"));

        assertEquals(1, edgesOfKind(drawn, CanvasEdge.Kind.INVERSE).size(),
                "properties are nodes when you add them, and this is the line between them");
    }

    /** {@code ObjectPropertyAssertion(R a b)} - a fact between two individuals. */
    @Test
    void aFactBetweenTwoIndividualsIsDrawn() {
        add(factory.getOWLObjectPropertyAssertionAxiom(
                property("knows"), individual("alice"), individual("bob")));

        Projection drawn = OntologyProjection.project(ontology, board("alice", "bob"));

        List<CanvasEdge> arrows = edgesOfKind(drawn, CanvasEdge.Kind.OBJECT_PROPERTY);
        assertEquals(1, arrows.size(), idsOf(drawn).toString());
        assertEquals("knows", arrows.get(0).getLabel(),
                "a fact carries the property's name and nothing about quantification");
    }

    /**
     * {@code SubPropertyChainOf} - three terms and an order, so no two-ended shape fits.
     *
     * <p>It reuses the node that already stands for "something the ontology says that is not a
     * term", with the links numbered so the order survives a glance.
     */
    @Test
    void aPropertyChainIsDrawnAsANodeWithNumberedLinks() {
        add(factory.getOWLSubPropertyChainOfAxiom(
                Arrays.asList(property("hasPart"), property("hasPart")), property("hasPart")));

        Projection drawn = OntologyProjection.project(ontology, board("hasPart"));

        CanvasNode chain = null;
        for (CanvasNode node : drawn.getNodes()) {
            if (node.getKind() == NodeKind.EXPRESSION) {
                chain = node;
            }
        }
        assertNotNull(chain, "no chain node: " + drawn.getNodes());
        assertTrue(chain.getLabel().startsWith("chain: "), chain.getLabel());
        assertTrue(chain.getLabel().contains(" o "), "the composition reads as one: "
                + chain.getLabel());
        assertTrue(edgesOfKind(drawn, CanvasEdge.Kind.SUB_PROPERTY).size() >= 2,
                "the chain reaches its super-property and its links: " + idsOf(drawn));
    }

    /** {@code HasKey} - the properties that identify an instance of a class. */
    @Test
    void aKeyIsDrawn() {
        add(factory.getOWLHasKeyAxiom(cls("Person"), property("hasPassport")));

        Projection drawn = OntologyProjection.project(ontology, board("Person", "hasPassport"));

        List<CanvasEdge> keys = edgesOfKind(drawn, CanvasEdge.Kind.OBJECT_PROPERTY);
        assertEquals(1, keys.size(), idsOf(drawn).toString());
        assertEquals("key", keys.get(0).getLabel());
    }

    /**
     * A property's characteristics ride on its label, because an edge would misstate the arity.
     *
     * <p>Transitive is a fact about one property. There is no second term for an arrow to reach,
     * so drawing one would invent a relation.
     */
    @Test
    void aPropertysCharacteristicsAreOnItsLabel() {
        add(factory.getOWLTransitiveObjectPropertyAxiom(property("hasPart")));
        add(factory.getOWLFunctionalObjectPropertyAxiom(property("hasPart")));
        add(factory.getOWLDeclarationAxiom(property("hasPart")));

        Projection drawn = OntologyProjection.project(ontology, board("hasPart"));

        CanvasNode node = null;
        for (CanvasNode one : drawn.getNodes()) {
            if (one.getKind() == NodeKind.OBJECT_PROPERTY) {
                node = one;
            }
        }
        assertNotNull(node, "" + drawn.getNodes());
        assertTrue(node.getLabel().contains("transitive"), node.getLabel());
        assertTrue(node.getLabel().contains("functional"), node.getLabel());
    }

    /** A property with no characteristics keeps its plain name. */
    @Test
    void aPlainPropertyKeepsItsPlainLabel() {
        add(factory.getOWLDeclarationAxiom(property("knows")));

        Projection drawn = OntologyProjection.project(ontology, board("knows"));

        for (CanvasNode node : drawn.getNodes()) {
            if (node.getKind() == NodeKind.OBJECT_PROPERTY) {
                assertFalse(node.getLabel().contains("("),
                        "nothing to say, so nothing added: " + node.getLabel());
            }
        }
    }

    private List<String> idsOf(Projection projection) {
        List<String> ids = new ArrayList<String>();
        for (CanvasEdge edge : projection.getEdges()) {
            ids.add(edge.getId());
        }
        return ids;
    }
}
