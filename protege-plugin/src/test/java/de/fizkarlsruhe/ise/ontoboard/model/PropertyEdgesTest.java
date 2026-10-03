package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
 * The axiom shapes a property arrow can be written in.
 *
 * <p>Three of them were read before this and the rest were dropped without trace, which on the
 * ontology this was reported against meant <em>everything</em>. Measured on it: 74 SubClassOf
 * axioms, not one with a restriction as its superclass, against 34 EquivalentClasses axioms of
 * which 32 contain one - 51 authored relationships, every one invisible, on a board that was
 * carrying 27 property boxes with nothing attached to them. The boxes were the symptom.
 *
 * <p>Two rules hold throughout and are each pinned by a test rather than assumed: the filler
 * must already be on the board, because this canvas never drags a term onto a diagram nobody
 * asked for; and the ids of the shapes that were drawn before are byte-identical, because they
 * travel between peers in a live session.
 */
class PropertyEdgesTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
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
        Set<String> on = new LinkedHashSet<String>();
        for (String name : names) {
            on.add(NS + name);
        }
        return on;
    }

    private List<CanvasEdge> propertyEdges(Set<String> on) {
        List<CanvasEdge> found = new ArrayList<CanvasEdge>();
        for (CanvasEdge edge : OntologyProjection.project(ontology, on).getEdges()) {
            if (edge.getKind() == CanvasEdge.Kind.OBJECT_PROPERTY
                    || edge.getKind() == CanvasEdge.Kind.DATA_PROPERTY) {
                found.add(edge);
            }
        }
        return found;
    }

    private static String describe(CanvasEdge edge) {
        return edge.getSourceId().replace(NS, "") + " --" + edge.getLabel() + "--> "
                + edge.getTargetId().replace(NS, "");
    }

    private static List<String> described(List<CanvasEdge> edges) {
        List<String> all = new ArrayList<String>();
        for (CanvasEdge edge : edges) {
            all.add(describe(edge));
        }
        return all;
    }

    // ---------- the shape that was already read, unchanged ----------

    /**
     * A plain existential still draws, and its id is still the one peers know.
     *
     * <p>The id is the compatibility guarantee. It travels on the wire in a shared session and
     * {@code AxiomRemoval} parses it, so a changed spelling would reach an older build as an
     * unrecognised edge - and reach this one as a stale sidecar nobody can delete from.
     */
    @Test
    void aPlainExistentialKeepsItsOldId() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping"))));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Topping"));

        assertEquals(1, edges.size(), described(edges).toString());
        assertEquals("rest|some|" + NS + "Pizza|" + NS + "hasTopping|" + NS + "Topping",
                edges.get(0).getId());
    }

    /** And a universal keeps its id and its "(only)" suffix. */
    @Test
    void aUniversalKeepsItsOldId() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectAllValuesFrom(property("hasTopping"), cls("Topping"))));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Topping"));

        assertEquals("rest|only|" + NS + "Pizza|" + NS + "hasTopping|" + NS + "Topping",
                edges.get(0).getId());
        assertTrue(edges.get(0).getLabel().endsWith("(only)"), edges.get(0).getLabel());
    }

    // ---------- the shapes that were invisible ----------

    /**
     * A restriction inside a conjunction is drawn, and so are its siblings.
     *
     * <p>{@code SubClassOf(A, B and R some C)} is how an ontology says "an A is a B, and is
     * related by R to a C". Both halves are real, and neither was drawn.
     */
    @Test
    void aRestrictionInsideAConjunctionIsDrawn() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLObjectIntersectionOf(
                cls("Food"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping")),
                factory.getOWLObjectSomeValuesFrom(property("hasBase"), cls("Base")))));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Food", "Topping", "Base"));

        assertEquals(2, edges.size(), described(edges).toString());
        assertTrue(described(edges).contains("Pizza --hasTopping--> Topping"), described(edges)
                .toString());
        assertTrue(described(edges).contains("Pizza --hasBase--> Base"), described(edges)
                .toString());
    }

    /**
     * A restriction in a class's definition is drawn.
     *
     * <p>This is the one that mattered. An OBO-style ontology defines its classes with
     * {@code EquivalentClasses}, and all of that was invisible.
     */
    @Test
    void aRestrictionInsideAnEquivalenceIsDrawn() {
        add(factory.getOWLEquivalentClassesAxiom(cls("VegetarianPizza"),
                factory.getOWLObjectIntersectionOf(cls("Pizza"),
                        factory.getOWLObjectAllValuesFrom(property("hasTopping"),
                                cls("VegetarianTopping")))));

        List<CanvasEdge> edges =
                propertyEdges(board("VegetarianPizza", "Pizza", "VegetarianTopping"));

        assertEquals(1, edges.size(), described(edges).toString());
        assertEquals("VegetarianPizza --hasTopping (only)--> VegetarianTopping",
                describe(edges.get(0)));
        assertEquals(PropertyEdgeId.Origin.EQUIVALENCE,
                PropertyEdgeId.parse(edges.get(0).getId()).getOrigin());
    }

    /** Cardinality restrictions draw, with the count in the label. */
    @Test
    void cardinalityRestrictionsDrawWithTheirCount() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectMaxCardinality(1, property("hasBase"), cls("Base"))));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectMinCardinality(2, property("hasTopping"), cls("Topping"))));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Base", "Topping"));

        assertEquals(2, edges.size(), described(edges).toString());
        assertTrue(described(edges).contains("Pizza --hasBase (max 1)--> Base"),
                described(edges).toString());
        assertTrue(described(edges).contains("Pizza --hasTopping (min 2)--> Topping"),
                described(edges).toString());
    }

    /** A hasValue restriction points at the individual it names. */
    @Test
    void aHasValueRestrictionPointsAtTheIndividual() {
        add(factory.getOWLSubClassOfAxiom(cls("ItalianPizza"),
                factory.getOWLObjectHasValue(property("hasCountry"), individual("italy"))));

        List<CanvasEdge> edges = propertyEdges(board("ItalianPizza", "italy"));

        assertEquals(1, edges.size(), described(edges).toString());
        assertEquals("ItalianPizza --hasCountry (value)--> italy", describe(edges.get(0)));
    }

    /**
     * A scoped domain draws, which closes a round trip the canvas could not complete.
     *
     * <p>{@code EdgeAxioms} offers six readings when a user draws an arrow and the projection
     * could read back only three. Choose "scoped domain" and the axiom went into the ontology
     * while the arrow vanished at the next refresh - the worst possible outcome, because the
     * edit succeeded and the evidence of it disappeared.
     */
    @Test
    void aScopedDomainDrawsTheArrowThatWroteIt() {
        add(factory.getOWLSubClassOfAxiom(
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping")),
                cls("Pizza")));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Topping"));

        assertEquals(1, edges.size(), described(edges).toString());
        assertEquals("Pizza --hasTopping--> Topping", describe(edges.get(0)),
                "the arrow points the way the user drew it, not the way the axiom reads");
        assertEquals(PropertyEdgeId.Origin.SCOPED_DOMAIN,
                PropertyEdgeId.parse(edges.get(0).getId()).getOrigin());
    }

    /** A functionality restriction draws too, which closes a fourth of the six. */
    @Test
    void aFunctionalityRestrictionDraws() {
        add(de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms.build(factory,
                de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms.Candidate.FUNCTIONALITY,
                cls("Pizza"), property("hasBase"), cls("Base")));

        assertEquals(1, propertyEdges(board("Pizza", "Base")).size(),
                "the canvas must be able to draw every axiom it offers to write");
    }

    // ---------- what is deliberately not drawn ----------

    /**
     * A union is not a conjunction and its operands are not drawn.
     *
     * <p>{@code A ⊑ (R some B) ⊔ (S some C)} says one of them holds. Drawing both would be a
     * statement the ontology does not make, which is worse than drawing neither.
     */
    @Test
    void aDisjunctionIsNotDrawn() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLObjectUnionOf(
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping")),
                factory.getOWLObjectSomeValuesFrom(property("hasBase"), cls("Base")))));

        assertTrue(propertyEdges(board("Pizza", "Topping", "Base")).isEmpty(),
                "an arrow for a disjunct would assert something nobody wrote");
    }

    /** A negation cannot be an arrow either. */
    @Test
    void aComplementIsNotDrawn() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLObjectComplementOf(
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Meat")))));

        assertTrue(propertyEdges(board("Pizza", "Meat")).isEmpty());
    }

    /**
     * The opt-in rule survives every new shape.
     *
     * <p>An arrow may reveal a relation between two terms the user chose; it may never drag a
     * third onto the diagram. This is the rule the whole canvas is built around and the easiest
     * one to lose while adding reading power.
     */
    @Test
    void nothingIsDrawnToATermThatIsNotOnTheBoard() {
        add(factory.getOWLEquivalentClassesAxiom(cls("VegetarianPizza"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Vegetable"))));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLObjectIntersectionOf(
                factory.getOWLObjectSomeValuesFrom(property("hasBase"), cls("Base")))));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectMaxCardinality(1, property("hasBase"), cls("Base"))));

        assertTrue(propertyEdges(board("VegetarianPizza", "Pizza")).isEmpty(),
                "neither Vegetable nor Base is on the board");
    }

    // ---------- labels, and one cell per id ----------

    /**
     * An arrow is named the way every node on the board is named.
     *
     * <p>Arrows carried the IRI fragment while nodes carried the label, so on an OBO or ODK
     * ontology - which is what this plugin is for - the one part of the diagram that names a
     * relation was the one part written in numbers: {@code RO_0002202} between two boxes
     * reading "larval stage" and "adult stage".
     */
    @Test
    void anArrowCarriesThePropertysLabelRatherThanItsIdentifier() {
        OWLObjectProperty obscure = factory.getOWLObjectProperty(
                IRI.create("http://purl.obolibrary.org/obo/RO_0002202"));
        add(factory.getOWLDeclarationAxiom(obscure));
        add(factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(), obscure.getIRI(),
                factory.getOWLLiteral("develops from")));
        add(factory.getOWLSubClassOfAxiom(cls("Adult"),
                factory.getOWLObjectSomeValuesFrom(obscure, cls("Larva"))));

        List<CanvasEdge> edges = propertyEdges(board("Adult", "Larva"));

        assertEquals("develops from", edges.get(0).getLabel());
    }

    /**
     * One cell per id, however many axioms say the same thing.
     *
     * <p>An import closure restates axioms across modules - on a real board ten ids arrived two
     * to four times - and the renderer inserts a cell per list entry while indexing only the
     * last. The surplus cells are drawn, hit-tested and exported, and the ones the index has
     * forgotten cannot be selected or deleted.
     */
    @Test
    void theSameRelationStatedTwiceDrawsOneArrow() throws Exception {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping"))));
        // The same relation again, from a conjunction - a different axiom, the same arrow.
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"), factory.getOWLObjectIntersectionOf(
                cls("Food"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping")))));

        Set<String> ids = new LinkedHashSet<String>();
        List<CanvasEdge> all =
                OntologyProjection.project(ontology, board("Pizza", "Food", "Topping")).getEdges();
        for (CanvasEdge edge : all) {
            assertTrue(ids.add(edge.getId()), "drawn twice: " + edge.getId());
        }
        assertFalse(ids.isEmpty());
    }

    /** Two different qualifiers between the same pair are two arrows, not one. */
    @Test
    void differentQualifiersAreDifferentArrows() {
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectSomeValuesFrom(property("hasTopping"), cls("Topping"))));
        add(factory.getOWLSubClassOfAxiom(cls("Pizza"),
                factory.getOWLObjectAllValuesFrom(property("hasTopping"), cls("Topping"))));

        List<CanvasEdge> edges = propertyEdges(board("Pizza", "Topping"));

        assertEquals(2, edges.size(), described(edges).toString());
        assertEquals(new LinkedHashSet<String>(Arrays.asList(
                        "Pizza --hasTopping--> Topping",
                        "Pizza --hasTopping (only)--> Topping")),
                new LinkedHashSet<String>(described(edges)));
    }
}
