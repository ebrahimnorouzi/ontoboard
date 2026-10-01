package de.fizkarlsruhe.ise.ontoboard.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Why the reasoner drew a dotted edge, against a real reasoner.
 *
 * <p>The ontology is the same chain {@link InferredEdgesTest} uses - Dog below Mammal below
 * Animal - because that is the case the canvas actually produces: {@link InferredEdges} draws to
 * the nearest ancestor <em>on the board</em>, so with Mammal left off there is an edge from Dog
 * to Animal and no single axiom says so. An explanation that could only name an axiom already in
 * the ontology would be useless for exactly the edges that need explaining.
 *
 * <p>ELK rather than the structural reasoner the sibling test uses. Justifications are computed
 * by taking axioms away and asking whether the conclusion survives, and that question is
 * {@code isEntailed}, which the structural reasoner does not answer for subclass axioms.
 */
class EdgeExplanationTest {

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

    private OWLNamedIndividual individual(String name) {
        return factory.getOWLNamedIndividual(IRI.create(NS + name));
    }

    private static OWLReasonerFactory elk() {
        return Reasoners.Choice.ELK.newFactory();
    }

    private static String flatten(List<List<String>> justifications) {
        StringBuilder text = new StringBuilder();
        for (List<String> one : justifications) {
            text.append(one).append(' ');
        }
        return text.toString();
    }

    // ---------- the edge id is turned back into the claim it makes ----------

    /** An inferred subclass edge stands for a SubClassOf axiom between its two ends. */
    @Test
    void anInferredSubclassEdgeBecomesASubClassOfAxiom() {
        OWLAxiom entailment = EdgeExplanation.entailmentFor(
                InferredEdges.SUBCLASS_ID_PREFIX + NS + "Dog|" + NS + "Animal", factory);

        assertEquals(factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")), entailment);
    }

    /** An inferred type edge stands for a ClassAssertion, with the arguments the other way up. */
    @Test
    void anInferredTypeEdgeBecomesAClassAssertion() {
        OWLAxiom entailment = EdgeExplanation.entailmentFor(
                InferredEdges.TYPE_ID_PREFIX + NS + "rex|" + NS + "Animal", factory);

        assertEquals(factory.getOWLClassAssertionAxiom(cls("Animal"), individual("rex")),
                entailment);
    }

    /**
     * An asserted edge is refused rather than explained.
     *
     * <p>Not pedantry: an asserted edge already is an axiom, so the honest answer is "look at it",
     * and silently explaining it would spend a reasoner run to reproduce what the edge says.
     */
    @Test
    void anAssertedEdgeHasNothingToExplain() {
        for (String assertedId : new String[] {"sub|" + NS + "Dog|" + NS + "Mammal",
                "rest|some|a|b|c", "", "|", "inf|", "inf|a|", null}) {
            EdgeExplanation.NotInferredException refused =
                    assertThrows(EdgeExplanation.NotInferredException.class,
                            () -> EdgeExplanation.entailmentFor(assertedId, factory),
                            "should refuse: " + assertedId);
            assertTrue(refused.getMessage().contains("nothing to explain"),
                    refused.getMessage());
        }
    }

    // ---------- the explanation itself ----------

    /**
     * The edge the canvas draws is explained by the two axioms behind it.
     *
     * <p>This is the whole feature. Neither axiom mentions both Dog and Animal, so nothing short
     * of a justification could have produced this answer.
     */
    @Test
    void theAxiomsBehindAnInferredEdgeAreNamed() {
        EdgeExplanation.Why why = EdgeExplanation.explain(ontology, elk(),
                InferredEdges.SUBCLASS_ID_PREFIX + NS + "Dog|" + NS + "Animal");

        assertFalse(why.isEmpty(), "the chain entails it, so a justification exists");
        String found = flatten(why.getJustifications());
        assertTrue(found.contains("Dog") && found.contains("Mammal") && found.contains("Animal"),
                "the justification must name the intermediate nobody put on the board: " + found);
        assertTrue(why.getEntailment().contains("Dog") && why.getEntailment().contains("Animal"),
                "the result must restate what was asked: " + why.getEntailment());
    }

    /**
     * Explaining reads the ontology and does not change it.
     *
     * <p>The technique is subtractive - axioms are taken away and the reasoner asked whether the
     * conclusion survives - so the obvious implementation of it would mutate the ontology it was
     * handed. That ontology is the one open in Protege.
     */
    @Test
    void explainingLeavesTheOntologyAlone() {
        Set<OWLAxiom> before = new HashSet<OWLAxiom>(ontology.getAxioms());

        EdgeExplanation.explain(ontology, elk(),
                InferredEdges.SUBCLASS_ID_PREFIX + NS + "Dog|" + NS + "Animal");

        assertEquals(before, new HashSet<OWLAxiom>(ontology.getAxioms()),
                "the open ontology must be exactly as it was");
    }

    /**
     * Two routes to the same conclusion are reported as two, and the summary says what that
     * means.
     *
     * <p>It changes what the user does: deleting the one axiom they found will not remove the
     * edge while the other justification stands, and a result that showed only one would have
     * them delete an axiom and watch the edge stay.
     */
    @Test
    void independentJustificationsAreCountedAndTheSummarySaysWhy() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Pet")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Pet"), cls("Animal")));

        EdgeExplanation.Why why = EdgeExplanation.explain(ontology, elk(),
                InferredEdges.SUBCLASS_ID_PREFIX + NS + "Dog|" + NS + "Animal");

        assertEquals(2, why.getJustifications().size(),
                "both routes are justifications: " + flatten(why.getJustifications()));
        assertTrue(why.getSummary().contains("independent"), why.getSummary());
        assertTrue(why.getSummary().contains("will not"), why.getSummary());
    }

    /** One justification reads as one, and says the inference goes when it is broken. */
    @Test
    void aSingleJustificationSaysSo() {
        EdgeExplanation.Why why = EdgeExplanation.explain(ontology, elk(),
                InferredEdges.SUBCLASS_ID_PREFIX + NS + "Dog|" + NS + "Animal");

        assertEquals(1, why.getJustifications().size());
        assertTrue(why.getSummary().startsWith("One justification"), why.getSummary());
        assertTrue(why.getSummary().contains("the inference goes"), why.getSummary());
    }

    /** An inferred type edge is explained the same way, which is the other half of the canvas. */
    @Test
    void anInferredTypeIsExplainedToo() {
        manager.addAxiom(ontology,
                factory.getOWLClassAssertionAxiom(cls("Dog"), individual("rex")));

        EdgeExplanation.Why why = EdgeExplanation.explain(ontology, elk(),
                InferredEdges.TYPE_ID_PREFIX + NS + "rex|" + NS + "Animal");

        assertFalse(why.isEmpty(), "rex is a Dog, Dog is below Animal, so rex is an Animal");
        String found = flatten(why.getJustifications());
        assertTrue(found.contains("rex"), found);
    }

    // ---------- what is refused before any work is done ----------

    /**
     * Without a reasoner there is nothing to explain with, and the refusal is immediate.
     *
     * <p>Inferences stay on the board once drawn, so a user can switch the reasoner off and still
     * be looking at dotted edges. Finding out after a minute in the explanation generator is
     * worse than finding out at once.
     */
    @Test
    void noReasonerIsRefusedBeforeAnyWork() {
        String why = EdgeExplanation.whyUnavailable(null);

        assertNotNull(why);
        assertTrue(why.contains("Reasoner menu"), why);
    }

    /** A no-op reasoner infers nothing, so it cannot say why anything was inferred. */
    @Test
    void aNoOpReasonerIsRefusedByName() {
        OWLReasoner noOp = new NamedReasoner("NoOpReasoner");

        String why = EdgeExplanation.whyUnavailable(noOp);

        assertNotNull(why);
        assertTrue(why.contains("NoOpReasoner"), why);
        assertTrue(why.contains("ELK"), why);
    }

    /** A real reasoner passes the check, or the feature could never run. */
    @Test
    void aRealReasonerIsAccepted() {
        OWLReasoner elk = elk().createReasoner(ontology);
        try {
            assertNull(EdgeExplanation.whyUnavailable(elk), "ELK must be allowed to explain");
        } finally {
            elk.dispose();
        }
    }

    /** A reasoner that is only a name, for the no-op check. */
    private static final class NamedReasoner
            extends org.semanticweb.owlapi.reasoner.structural.StructuralReasoner {
        private final String name;

        NamedReasoner(String name) {
            super(emptyOntology(), new org.semanticweb.owlapi.reasoner.SimpleConfiguration(),
                    org.semanticweb.owlapi.reasoner.BufferingMode.NON_BUFFERING);
            this.name = name;
        }

        private static OWLOntology emptyOntology() {
            try {
                return OWLManager.createOWLOntologyManager().createOntology();
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override
        public String getReasonerName() {
            return name;
        }
    }
}
