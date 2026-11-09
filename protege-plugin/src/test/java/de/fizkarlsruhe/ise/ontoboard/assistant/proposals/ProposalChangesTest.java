package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.RemoveAxiom;

/** What Accept writes, and what the reasoner says about a removal before it is accepted. */
class ProposalChangesTest {

    private static final IRI DEFINITION = IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");
    private static final IRI CONTRIBUTOR = IRI.create("http://purl.org/dc/terms/contributor");

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();

    private Proposal offered(String block) {
        Proposal proposal = ProposalValidator.validate(pizza, ProposalBlock.parse(
                "```ontoboard-proposal\n" + block + "\n```").get(0));
        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        return proposal;
    }

    private List<String> definitions(IRI subject) {
        List<String> found = new java.util.ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom : pizza.getAnnotationAssertionAxioms(subject)) {
            if (axiom.getProperty().getIRI().equals(DEFINITION)) {
                found.add(axiom.getValue().asLiteral().get().getLiteral());
            }
        }
        return found;
    }

    @Test
    void settingADefinitionReplacesTheOldOneAndSaysWhoProposedIt() {
        Proposal proposal = offered("action: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.");

        List<OWLOntologyChange> changes = ProposalChanges.build(pizza, proposal,
                "OntoBoard assistant (ollama qwen2.5:7b)");
        pizza.getOWLOntologyManager().applyChanges(changes);

        assertEquals(2, changes.size());
        assertTrue(changes.get(0) instanceof RemoveAxiom);
        assertTrue(changes.get(1) instanceof AddAxiom);
        assertEquals(java.util.Arrays.asList("A pizza whose toppings are all vegetarian."),
                definitions(MiniPizza.iri("VegetarianPizza")));
        OWLAxiom added = changes.get(1).getAxiom();
        assertEquals(1, added.getAnnotations().size());
        OWLAnnotation provenance = added.getAnnotations().iterator().next();
        assertEquals(CONTRIBUTOR, provenance.getProperty().getIRI());
        assertEquals("OntoBoard assistant (ollama qwen2.5:7b)",
                provenance.getValue().asLiteral().get().getLiteral());
    }

    @Test
    void withoutProvenanceNothingIsAnnotated() {
        Proposal proposal = offered("action: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.");

        List<OWLOntologyChange> changes = ProposalChanges.build(pizza, proposal, null);

        assertTrue(changes.get(1).getAxiom().getAnnotations().isEmpty());
    }

    @Test
    void aLabelIsWrittenInItsLanguageAndTheOtherLanguageStays() {
        Proposal proposal = offered("action: set\nsubject: vegetarian pizza\nproperty: label\n"
                + "value: veggie pizza");

        pizza.getOWLOntologyManager().applyChanges(ProposalChanges.build(pizza, proposal, null));

        List<String> labels = new java.util.ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom
                : pizza.getAnnotationAssertionAxioms(MiniPizza.iri("VegetarianPizza"))) {
            if (axiom.getProperty().isLabel()) {
                labels.add(axiom.getValue().asLiteral().get().getLiteral() + "@"
                        + axiom.getValue().asLiteral().get().getLang());
            }
        }
        java.util.Collections.sort(labels);
        assertEquals(java.util.Arrays.asList("vegetarische Pizza@de", "veggie pizza@en"), labels);
    }

    @Test
    void addOnlyAdds() {
        Proposal proposal = offered("action: add\nsubject: cheese topping\nproperty: synonym\n"
                + "value: cheese");

        List<OWLOntologyChange> changes = ProposalChanges.build(pizza, proposal, null);

        assertEquals(1, changes.size());
        assertTrue(changes.get(0) instanceof AddAxiom);
    }

    @Test
    void removeAxiomRemovesTheMatchedAxiom() {
        OWLAxiom disjoint = factory.getOWLDisjointClassesAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")),
                factory.getOWLClass(MiniPizza.iri("MeatTopping")));
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, disjoint));

        pizza.getOWLOntologyManager().applyChanges(ProposalChanges.build(pizza, proposal,
                "OntoBoard assistant (x)"));

        assertFalse(pizza.containsAxiom(disjoint));
    }

    @Test
    void addingAnAxiomIsOneChangeThatNamesTheModel() {
        Proposal proposal = offered("action: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'");

        List<OWLOntologyChange> changes = ProposalChanges.build(pizza, proposal,
                "OntoBoard assistant (ollama m)");

        assertEquals(1, changes.size());
        assertTrue(changes.get(0) instanceof AddAxiom);
        assertEquals(proposal.getNewAxiom(), changes.get(0).getAxiom()
                .getAxiomWithoutAnnotations());
        assertEquals(CONTRIBUTOR, changes.get(0).getAxiom().getAnnotations().iterator().next()
                .getProperty().getIRI());
    }

    @Test
    void replacingIsTheRemovalAndTheAdditionTogether() {
        String old = AssistantRendering.render(pizza, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("CheeseTopping")),
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping"))));
        Proposal proposal = offered("action: replace-axiom\naxiom: " + old
                + "\nwith: 'cheese topping' SubClassOf PizzaTopping");

        List<OWLOntologyChange> changes = ProposalChanges.build(pizza, proposal, null);
        pizza.getOWLOntologyManager().applyChanges(changes);

        assertEquals(2, changes.size());
        assertTrue(changes.get(0) instanceof RemoveAxiom);
        assertTrue(changes.get(1) instanceof AddAxiom);
        assertFalse(pizza.containsAxiom(proposal.getMatchedAxiom()));
        assertTrue(pizza.containsAxiom(proposal.getNewAxiom()));
    }

    private OWLAxiom injectClash() {
        OWLNamedIndividual x = factory.getOWLNamedIndividual(MiniPizza.iri("x"));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")), x));
        OWLAxiom meat = factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("MeatTopping")), x);
        pizza.getOWLOntologyManager().addAxiom(pizza, meat);
        return meat;
    }

    @Test
    void theCheckSaysWhenARemovalSolvesTheClash() {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertFalse(check.isConsistentBefore());
        assertTrue(check.isConsistentAfter());
        assertTrue(check.isSolved(), check.describe());
        assertTrue(pizza.containsAxiom(meat), "the check runs on a copy");
    }

    @Test
    void theCheckSaysWhenTheProblemIsStillThere() {
        injectClash();
        OWLAxiom unrelated = factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("NamedPizza")),
                factory.getOWLClass(MiniPizza.iri("Pizza")));
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, unrelated));

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertFalse(check.isSolved());
        assertTrue(check.describe().contains("still inconsistent"), check.describe());
    }

    /** 1.139.0: an addition can break what was fine; the card says so first. */
    @Test
    void anAdditionThatMakesAClassUnsatisfiableIsReported() {
        Proposal proposal = offered("action: add-axiom\n"
                + "axiom: 'cheese topping' SubClassOf MeatTopping");

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        // Margherita has a cheese topping, so it can have no instances either.
        assertEquals(java.util.Arrays.asList("Margherita", "cheese topping"),
                check.getNewlyUnsatisfiable());
        assertFalse(check.isSafe());
        assertTrue(check.describe().contains("Margherita, cheese topping become unsatisfiable"),
                check.describe());
    }

    @Test
    void aHarmlessAdditionSaysSo() {
        Proposal proposal = offered("action: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'");

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertTrue(check.isSafe(), check.describe());
        assertTrue(check.describe().contains("stays consistent"), check.describe());
    }

    /** The pizza example's repair, small: the range becomes decimal and the clash is gone. */
    @Test
    void replacingTheFloatRangeWithDecimalSolvesTheClash() {
        org.semanticweb.owlapi.model.OWLDataProperty price =
                factory.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        OWLAxiom range = factory.getOWLDataPropertyRangeAxiom(price, factory.getOWLDatatype(
                org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_FLOAT.getIRI()));
        pizza.getOWLOntologyManager().addAxiom(pizza, range);
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLDataPropertyAssertionAxiom(
                price, factory.getOWLNamedIndividual(MiniPizza.iri("Naples")),
                factory.getOWLLiteral("9.50",
                        org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_DECIMAL)));
        Proposal proposal = offered("action: replace-axiom\naxiom: "
                + AssistantRendering.render(pizza, range) + "\nwith: hasPrice Range: xsd:decimal");

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertTrue(check.isSolved(), check.describe());
        assertTrue(check.describe().contains("becomes consistent"), check.describe());
    }

    /** "∀ alone" - the quantifier trap domain experts asked to have made explicit. */
    @Test
    void anOnlyWithoutASomeGetsANote() {
        Proposal only = offered("action: add-axiom\n"
                + "axiom: Margherita SubClassOf hasTopping only 'cheese topping'");
        Proposal some = offered("action: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'");

        List<String> onlyNotes = QuantifierNotes.of(pizza, only.getNewAxiom());
        List<String> someNotes = QuantifierNotes.of(pizza, some.getNewAxiom());

        assertTrue(onlyNotes.isEmpty(), "Margherita already has a some on hasTopping: "
                + onlyNotes);
        assertTrue(someNotes.isEmpty(), someNotes.toString());
        List<String> vegetarian = QuantifierNotes.of(pizza, offered("action: add-axiom\naxiom:"
                + " 'vegetarian pizza' SubClassOf hasTopping only 'cheese topping'")
                .getNewAxiom());
        assertEquals(1, vegetarian.size(), vegetarian.toString());
        assertTrue(vegetarian.get(0).contains("hasTopping"), vegetarian.get(0));
    }

    /**
     * Review R1 (1.138.0): passing a configuration made HermiT refuse xsd:date and its kind,
     * so every check on such an ontology failed. It is checked as Protégé's HermiT checks it.
     */
    @Test
    void aDateRangeDoesNotStopTheCheck() {
        org.semanticweb.owlapi.model.OWLDataProperty baked =
                factory.getOWLDataProperty(MiniPizza.iri("bakedOn"));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLDataPropertyRangeAxiom(baked,
                factory.getOWLDatatype(org.semanticweb.owlapi.model.IRI.create(
                        "http://www.w3.org/2001/XMLSchema#date"))));
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertTrue(check.getError() == null, check.getError());
        assertTrue(check.isSolved(), check.describe());
    }

    /** A reasoner that works until it is interrupted, as a long classification does. */
    static org.semanticweb.owlapi.reasoner.OWLReasonerFactory endless(
            final java.util.concurrent.CountDownLatch started) {
        return new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory() {
            @Override
            public org.semanticweb.owlapi.reasoner.OWLReasoner createReasoner(
                    OWLOntology ontology) {
                final java.util.concurrent.atomic.AtomicBoolean interrupted =
                        new java.util.concurrent.atomic.AtomicBoolean();
                return (org.semanticweb.owlapi.reasoner.OWLReasoner) java.lang.reflect.Proxy
                        .newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {org.semanticweb.owlapi.reasoner.OWLReasoner.class},
                                (proxy, method, args) -> {
                                    if ("interrupt".equals(method.getName())) {
                                        interrupted.set(true);
                                        return null;
                                    }
                                    if ("isConsistent".equals(method.getName())) {
                                        started.countDown();
                                        while (!interrupted.get()) {
                                            Thread.sleep(10);
                                        }
                                        throw new org.semanticweb.owlapi.reasoner
                                                .ReasonerInterruptedException();
                                    }
                                    return null;
                                });
            }
        };
    }

    /** Review R2: the timeout bounds the whole check, not each tableau test. */
    @Test
    void aCheckThatRunsTooLongStopsAndSaysSo() {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));
        long previous = ProposalCheck.timeoutMillis();
        ProposalCheck.setTimeoutMillis(300);
        try {
            ProposalCheck.Check check = ProposalCheck.check(pizza,
                    endless(new java.util.concurrent.CountDownLatch(1)), proposal);

            assertTrue(String.valueOf(check.getError()).contains("ran out of time"),
                    String.valueOf(check.getError()));
        } finally {
            ProposalCheck.setTimeoutMillis(previous);
        }
    }

    /**
     * A reasoner that loses its first {@code ignored} interrupts - HermiT clears its flag when a
     * task starts, so an interrupt between two tableau tests is gone - and otherwise works for
     * {@code runMillis} and then says "consistent".
     */
    static org.semanticweb.owlapi.reasoner.OWLReasonerFactory stubborn(final int ignored,
            final long runMillis, final java.util.concurrent.CountDownLatch started) {
        return new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory() {
            @Override
            public org.semanticweb.owlapi.reasoner.OWLReasoner createReasoner(
                    OWLOntology ontology) {
                final java.util.concurrent.atomic.AtomicInteger calls =
                        new java.util.concurrent.atomic.AtomicInteger();
                return (org.semanticweb.owlapi.reasoner.OWLReasoner) java.lang.reflect.Proxy
                        .newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[] {org.semanticweb.owlapi.reasoner.OWLReasoner.class},
                                (proxy, method, args) -> {
                                    if ("interrupt".equals(method.getName())) {
                                        calls.incrementAndGet();
                                        return null;
                                    }
                                    if ("isConsistent".equals(method.getName())) {
                                        started.countDown();
                                        long end = System.currentTimeMillis() + runMillis;
                                        while (System.currentTimeMillis() < end) {
                                            if (calls.get() > ignored) {
                                                throw new org.semanticweb.owlapi.reasoner
                                                        .ReasonerInterruptedException();
                                            }
                                            Thread.sleep(10);
                                        }
                                        return true;
                                    }
                                    if ("getUnsatisfiableClasses".equals(method.getName())) {
                                        return new org.semanticweb.owlapi.reasoner.impl
                                                .OWLClassNode(ontology.getOWLOntologyManager()
                                                        .getOWLDataFactory().getOWLNothing());
                                    }
                                    return null;
                                });
            }
        };
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000L;
    }

    /** Review 1.139.0 I4: one interrupt, lost between two tasks, let the check run to the end. */
    @Test
    void aReasonerThatLosesAnInterruptStillStops() {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));
        long previous = ProposalCheck.timeoutMillis();
        ProposalCheck.setTimeoutMillis(300);
        try {
            long start = System.nanoTime();
            ProposalCheck.Check check = ProposalCheck.check(pizza,
                    stubborn(1, 20_000, new java.util.concurrent.CountDownLatch(1)), proposal);

            assertTrue(String.valueOf(check.getError()).contains("ran out of time"),
                    String.valueOf(check.getError()));
            assertTrue(millisSince(start) < 3_000, millisSince(start) + " ms");
        } finally {
            ProposalCheck.setTimeoutMillis(previous);
        }
    }

    /** Review 1.139.0 I4: a reasoner that never stops is left behind; the card is not. */
    @Test
    void aReasonerThatNeverStopsIsLeftBehind() {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));
        long previous = ProposalCheck.timeoutMillis();
        long grace = ProposalCheck.graceMillis();
        ProposalCheck.setTimeoutMillis(300);
        ProposalCheck.setGraceMillis(300);
        try {
            long start = System.nanoTime();
            ProposalCheck.Check check = ProposalCheck.check(pizza,
                    stubborn(Integer.MAX_VALUE, 4_000, new java.util.concurrent.CountDownLatch(1)),
                    proposal);

            assertTrue(String.valueOf(check.getError()).contains("ran out of time"),
                    String.valueOf(check.getError()));
            assertTrue(millisSince(start) < 3_000, millisSince(start) + " ms");
        } finally {
            ProposalCheck.setTimeoutMillis(previous);
            ProposalCheck.setGraceMillis(grace);
        }
    }

    /** Review 1.139.0 I4: Stop ends a check whose reasoner does not listen. */
    @Test
    void stopEndsAWaitingCheckAtOnce() throws Exception {
        final java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(1);
        final ProposalCheck.Run run = new ProposalCheck.Run();
        Thread stopper = new Thread(() -> {
            try {
                started.await();
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                return;
            }
            run.cancel();
        });
        stopper.start();
        long start = System.nanoTime();

        ProposalCheck.Verdict verdict = ProposalCheck.before(ProposalCheck.snapshot(pizza),
                stubborn(Integer.MAX_VALUE, 4_000, started), run);

        assertEquals("Stopped.", verdict.error);
        assertTrue(millisSince(start) < 2_000, millisSince(start) + " ms");
    }

    /**
     * Review 1.139.0 M1: a cancelled watchdog task kept the check's ontology copy for 60 s. The
     * reasoners other tests leave behind on purpose keep their ticks until they give up (4 s), so
     * the queue is waited for; a cancelled task that stays would stay for a minute.
     */
    @Test
    void aFinishedCheckLeavesNothingQueued() throws InterruptedException {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));

        ProposalCheck.check(pizza, Reasoners.Choice.HERMIT.newFactory(), proposal);

        long end = System.currentTimeMillis() + 10_000;
        while (ProposalCheck.queuedWatchdogTasks() > 0 && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals(0, ProposalCheck.queuedWatchdogTasks());
    }

    /** A reasoner factory that fails the way a broken reasoner plugin does: with an Error. */
    private static final class Broken implements org.semanticweb.owlapi.reasoner.OWLReasonerFactory {
        @Override
        public String getReasonerName() {
            return "Broken";
        }

        @Override
        public org.semanticweb.owlapi.reasoner.OWLReasoner createNonBufferingReasoner(
                OWLOntology ontology) {
            throw new NoClassDefFoundError("org/example/Missing");
        }

        @Override
        public org.semanticweb.owlapi.reasoner.OWLReasoner createReasoner(OWLOntology ontology) {
            throw new NoClassDefFoundError("org/example/Missing");
        }

        @Override
        public org.semanticweb.owlapi.reasoner.OWLReasoner createNonBufferingReasoner(
                OWLOntology ontology,
                org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration configuration) {
            throw new NoClassDefFoundError("org/example/Missing");
        }

        @Override
        public org.semanticweb.owlapi.reasoner.OWLReasoner createReasoner(OWLOntology ontology,
                org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration configuration) {
            throw new NoClassDefFoundError("org/example/Missing");
        }
    }

    /** Review (1.137.0): an Error escaped the check and the card said "Checking…" forever. */
    @Test
    void aReasonerThatThrowsAnErrorIsReported() {
        OWLAxiom meat = injectClash();
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, meat));

        ProposalCheck.Check check = ProposalCheck.check(pizza, new Broken(), proposal);

        assertFalse(check.isSolved());
        assertTrue(check.getError() != null && check.getError().contains("Missing"),
                String.valueOf(check.getError()));
    }

    @Test
    void anUnsatisfiableClassMadeSatisfiableIsSolved() {
        OWLAxiom bad = factory.getOWLSubClassOfAxiom(factory.getOWLClass(MiniPizza.iri("Bad")),
                factory.getOWLClass(MiniPizza.iri("MeatTopping")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("Bad")),
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping"))));
        pizza.getOWLOntologyManager().addAxiom(pizza, bad);
        Proposal proposal = offered("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, bad));

        ProposalCheck.Check check = ProposalCheck.check(pizza,
                Reasoners.Choice.HERMIT.newFactory(), proposal);

        assertEquals(java.util.Arrays.asList("Bad"), check.getUnsatisfiableBefore());
        assertTrue(check.getUnsatisfiableAfter().isEmpty());
        assertTrue(check.isSolved(), check.describe());
    }
}
