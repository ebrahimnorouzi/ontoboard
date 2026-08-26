package de.fizkarlsruhe.ise.ontoboard.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;

/**
 * Inferred edges, against a real reasoner.
 *
 * <p>OWL API's structural reasoner computes the told-subsumption closure, which is enough to give
 * a genuine inference to assert on: with A subclass of B and B subclass of C, "A is a C" is
 * entailed and not asserted. Using a real reasoner rather than a stub matters here because the
 * things most likely to be wrong are all reasoner behaviours - what "direct" returns, whether
 * owl:Thing comes back, what happens to an unsatisfiable class.
 *
 * <p>The bug class this guards against is a canvas that shows nothing and means two different
 * things by it: "your ontology entails nothing new" and "no reasoner is running". Those must never
 * look alike, which is why the no-reasoner case throws instead of returning an empty list.
 */
class InferredEdgesTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void aChainToInferAcross() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();

        // Dog < Mammal < Animal. "Dog is an Animal" is entailed, never stated.
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Mammal"), cls("Animal")));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLReasoner reasoner() {
        return new StructuralReasonerFactory().createReasoner(ontology);
    }

    private static Set<String> onCanvas(String... names) {
        Set<String> iris = new HashSet<String>();
        for (String name : names) {
            iris.add(NS + name);
        }
        return iris;
    }

    private static List<String> described(List<CanvasEdge> edges) {
        List<String> described = new ArrayList<String>();
        for (CanvasEdge edge : edges) {
            described.add(edge.getSourceId().replace(NS, "") + " -> "
                    + edge.getTargetId().replace(NS, ""));
        }
        Collections.sort(described);
        return described;
    }

    private CanvasEdge assertedSubClass(String child, String parent) {
        return new CanvasEdge("sub|" + NS + child + "|" + NS + parent, NS + child, NS + parent,
                "rdfs:subClassOf", CanvasEdge.Kind.SUBCLASS);
    }

    // ---------- the inference itself ----------

    /**
     * The direct assertions are already drawn, so the only thing worth adding is what the reasoner
     * worked out - and with a two-step chain and only the ends on the board, that is exactly one
     * edge.
     */
    @Test
    void aSubsumptionThroughAnUnshownIntermediateBecomesAnEdge() {
        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog", "Animal"), Collections.<CanvasEdge>emptyList(), factory);

        assertEquals(Arrays.asList("Dog -> Animal"), described(inferred));
    }

    @Test
    void anInferredEdgeIsMarkedInferredAndNotAsAnAssertedSubclass() {
        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog", "Animal"), Collections.<CanvasEdge>emptyList(), factory);

        assertEquals(CanvasEdge.Kind.INFERRED_SUBCLASS, inferred.get(0).getKind(),
                "drawn as an assertion, a reasoner's conclusion invites someone to delete an "
                        + "axiom that does not exist");
    }

    /** Two arrows between one pair of nodes is noise, and the asserted one is the truer of them. */
    @Test
    void anInferenceThatDuplicatesAnAssertedEdgeIsNotDrawnTwice() {
        List<CanvasEdge> asserted = Arrays.asList(assertedSubClass("Dog", "Mammal"));

        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog", "Mammal"), asserted, factory);

        assertTrue(inferred.isEmpty(),
                "Dog -> Mammal is already on the diagram as an axiom: " + described(inferred));
    }

    /**
     * Inferring towards something nobody asked to see would drag it onto the diagram, which breaks
     * the opt-in rule the whole canvas is built on.
     */
    @Test
    void nothingIsInferredTowardsAClassThatIsNotOnTheBoard() {
        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog"), Collections.<CanvasEdge>emptyList(), factory);

        assertTrue(inferred.isEmpty(), described(inferred).toString());
    }

    /** owl:Thing is above everything, so an edge to it carries no information. */
    @Test
    void owlThingIsNeverAnInferredParent() {
        Set<String> board = onCanvas("Dog", "Mammal", "Animal");
        board.add("http://www.w3.org/2002/07/owl#Thing");

        for (CanvasEdge edge : InferredEdges.subClassEdges(reasoner(), board,
                Collections.<CanvasEdge>emptyList(), factory)) {
            assertFalse(edge.getTargetId().endsWith("owl#Thing"), edge.getTargetId());
        }
    }

    @Test
    void anOntologyThatStatesEverythingItEntailsYieldsNoEdges() {
        List<CanvasEdge> asserted = Arrays.asList(
                assertedSubClass("Dog", "Mammal"), assertedSubClass("Mammal", "Animal"));

        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog", "Mammal"), asserted, factory);

        assertTrue(inferred.isEmpty(), described(inferred).toString());
    }

    @Test
    void theSameOntologyInfersTheSameEdgesInTheSameOrder() {
        Set<String> board = onCanvas("Dog", "Mammal", "Animal");

        String first = described(InferredEdges.subClassEdges(reasoner(), board,
                Collections.<CanvasEdge>emptyList(), factory)).toString();
        String second = described(InferredEdges.subClassEdges(reasoner(), board,
                Collections.<CanvasEdge>emptyList(), factory)).toString();

        assertEquals(first, second, "edges that reorder look like the ontology changed");
    }

    /**
     * The property that makes the full-closure approach safe. With the whole chain on the board,
     * Dog's ancestors are Mammal AND Animal - but Animal is reachable through Mammal, so drawing it
     * too would put a second arrow across the diagram for a fact the first one already shows. That
     * mesh was the reason direct-only looked attractive; measuring nearness among the shown nodes
     * gets the benefit without the cost.
     */
    @Test
    void anAncestorReachableThroughANearerOneIsNotDrawnAsWell() {
        List<CanvasEdge> inferred = InferredEdges.subClassEdges(reasoner(),
                onCanvas("Dog", "Mammal", "Animal"), Collections.<CanvasEdge>emptyList(),
                factory);

        assertEquals(Arrays.asList("Dog -> Mammal", "Mammal -> Animal"), described(inferred),
                "Dog -> Animal is implied by the chain and must not be drawn as well");
    }

    @Test
    void anEmptyBoardInfersNothingWithoutComplaining() {
        assertTrue(InferredEdges.subClassEdges(reasoner(), new HashSet<String>(),
                Collections.<CanvasEdge>emptyList(), factory).isEmpty());
    }

    // ---------- no reasoner is not the same as no inferences ----------

    /**
     * The distinction the class exists to preserve. Returning an empty list here would tell the
     * user their ontology entails nothing, when the truth is that nothing has been asked.
     */
    @Test
    void noReasonerIsReportedRatherThanLookingLikeNoInferences() {
        InferredEdges.NotAvailable thrown = assertThrows(InferredEdges.NotAvailable.class,
                () -> InferredEdges.subClassEdges(null, onCanvas("Dog"),
                        Collections.<CanvasEdge>emptyList(), factory));

        assertTrue(thrown.getMessage().contains("Reasoner"), thrown.getMessage());
        assertTrue(thrown.getMessage().toLowerCase().contains("start"),
                "the message must say what to do: " + thrown.getMessage());
    }

    /**
     * Protege hands out a no-op reasoner when none has been started, and it answers every query
     * with nothing at all - indistinguishable from a fully-stated ontology unless it is detected.
     */
    @Test
    void aNoOpReasonerIsReportedRatherThanTreatedAsAnAnswer() {
        OWLReasoner noOp = new NamedNoOpReasoner("NoOpReasoner");

        InferredEdges.NotAvailable thrown = assertThrows(InferredEdges.NotAvailable.class,
                () -> InferredEdges.subClassEdges(noOp, onCanvas("Dog"),
                        Collections.<CanvasEdge>emptyList(), factory));

        assertTrue(thrown.getMessage().contains("ELK") || thrown.getMessage().contains("HermiT"),
                "the message should name a reasoner that works: " + thrown.getMessage());
    }

    @Test
    void theNoOpNameIsRecognisedInTheFormsProtegeAndOthersUse() {
        assertTrue(InferredEdges.isNoOp(new NamedNoOpReasoner("NoOpReasoner")));
        assertTrue(InferredEdges.isNoOp(new NamedNoOpReasoner("no-op reasoner")));
        assertTrue(InferredEdges.isNoOp(new NamedNoOpReasoner("none")));
        assertTrue(InferredEdges.isNoOp(new NamedNoOpReasoner(null)));
        assertFalse(InferredEdges.isNoOp(reasoner()),
                "the structural reasoner does infer; treating it as a no-op would hide real work");
    }

    // ---------- unsatisfiable classes ----------

    @Test
    void anUnsatisfiableClassOnTheBoardIsReported() {
        manager.addAxiom(ontology, factory.getOWLDisjointClassesAxiom(cls("Dog"), cls("Mammal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Impossible"), cls("Dog")));
        manager.addAxiom(ontology,
                factory.getOWLSubClassOfAxiom(cls("Impossible"), cls("Mammal")));

        // The structural reasoner does not detect this, so the assertion is about the contract
        // rather than the finding: whatever it reports must be confined to the board and must not
        // include owl:Nothing itself.
        Set<String> unsatisfiable =
                InferredEdges.unsatisfiableClasses(reasoner(), onCanvas("Impossible", "Dog"));

        for (String iri : unsatisfiable) {
            assertTrue(onCanvas("Impossible", "Dog").contains(iri), iri);
            assertFalse(iri.endsWith("owl#Nothing"), "owl:Nothing is not a modelling error");
        }
    }

    @Test
    void unsatisfiableClassesWithoutAReasonerIsEmptyRatherThanThrowing() {
        assertTrue(InferredEdges.unsatisfiableClasses(null, onCanvas("Dog")).isEmpty(),
                "this one is a marker on nodes, not a user action, so it degrades quietly");
        assertTrue(InferredEdges.unsatisfiableClasses(new NamedNoOpReasoner("NoOpReasoner"),
                onCanvas("Dog")).isEmpty());
    }

    /**
     * A reasoner whose only interesting property is its name.
     *
     * <p>Extends OWL API's own structural reasoner so every one of the interface's several dozen
     * methods behaves sensibly without being stubbed; only the name is overridden, because the name
     * is what {@code isNoOp} reads.
     */
    private final class NamedNoOpReasoner
            extends org.semanticweb.owlapi.reasoner.structural.StructuralReasoner {
        private final String name;

        NamedNoOpReasoner(String name) {
            super(ontology, new org.semanticweb.owlapi.reasoner.SimpleConfiguration(),
                    org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING);
            this.name = name;
        }

        @Override
        public String getReasonerName() {
            return name;
        }
    }
}
