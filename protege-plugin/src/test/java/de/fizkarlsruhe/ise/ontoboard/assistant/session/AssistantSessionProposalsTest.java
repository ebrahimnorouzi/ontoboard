package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Proposals in a session: an answer's blocks become cards, Accept is one change list, and a
 * removal is checked by the reasoner before the person decides.
 */
class AssistantSessionProposalsTest {

    private static final Executor INLINE = Runnable::run;

    /** What a listener heard about proposals. */
    static final class Heard implements SessionListener {
        final List<Proposal> offered = new ArrayList<Proposal>();
        final List<Proposal> changed = new ArrayList<Proposal>();
        final List<ProposalCheck.Check> checks = new ArrayList<ProposalCheck.Check>();
        final List<String> notices = new ArrayList<String>();
        int proposalsAtFinish = -1;

        @Override
        public void onTurnFinished(Turn turn) {
            proposalsAtFinish = turn.getProposals().size();
        }

        @Override
        public void onProposals(Turn turn, List<Proposal> proposals) {
            offered.addAll(proposals);
        }

        @Override
        public void onProposalChanged(Proposal proposal) {
            changed.add(proposal);
        }

        @Override
        public void onProposalChecked(Proposal proposal, ProposalCheck.Check check) {
            checks.add(check);
        }

        @Override
        public void onNotice(String message) {
            notices.add(message);
        }
    }

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
    private final AssistantSessionTest.ScriptedClient client =
            new AssistantSessionTest.ScriptedClient();
    private final Heard heard = new Heard();
    private final List<List<OWLOntologyChange>> applied = new ArrayList<List<OWLOntologyChange>>();
    private IRI selected = MiniPizza.iri("VegetarianPizza");
    private AssistantSession session;

    @BeforeEach
    void open() {
        session = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return pizza;
            }

            @Override
            public IRI selected() {
                return selected;
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, INLINE, INLINE);
        session.setClientFactory((connection, key) -> client);
        session.addListener(heard);
        session.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withModel("qwen2.5:7b"), "");
        session.setProposalApplier(changes -> {
            applied.add(changes);
            pizza.getOWLOntologyManager().applyChanges(changes);
        });
    }

    private static String block(String body) {
        return "I suggest this.\n```ontoboard-proposal\n" + body + "\n```\n";
    }

    private static final String DEFINITION = "action: set\nsubject: vegetarian pizza\n"
            + "property: definition\nvalue: A pizza whose toppings are all vegetarian.";

    private Proposal answerWith(String reply) {
        client.deltas = Arrays.asList(reply);
        session.send("Write a definition.");
        return heard.offered.get(heard.offered.size() - 1);
    }

    @Test
    void anAnswersBlocksAreOfferedOnTheTurnBeforeItIsShownFinished() {
        Proposal proposal = answerWith(block(DEFINITION));

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals(1, session.turns().get(0).getProposals().size());
        assertEquals(1, heard.proposalsAtFinish, "the card is there when the turn is drawn");
    }

    @Test
    void acceptingADefinitionIsOneApplyWithRemoveAddAndProvenance() {
        Proposal proposal = answerWith(block(DEFINITION));

        session.accept(proposal);

        assertEquals(1, applied.size());
        assertEquals(2, applied.get(0).size());
        OWLAxiom added = applied.get(0).get(1).getAxiom();
        assertEquals("OntoBoard assistant (ollama qwen2.5:7b)", added.getAnnotations().iterator()
                .next().getValue().asLiteral().get().getLiteral());
        assertEquals(Proposal.Status.ACCEPTED,
                session.turns().get(0).getProposals().get(0).getStatus());
        assertEquals(Proposal.Status.ACCEPTED, heard.changed.get(0).getStatus());
    }

    @Test
    void acceptingTwiceAppliesOnce() {
        Proposal proposal = answerWith(block(DEFINITION));

        session.accept(proposal);
        session.accept(proposal);

        assertEquals(1, applied.size());
    }

    @Test
    void provenanceOffWritesNoAnnotation() {
        session.setProvenance(false);
        Proposal proposal = answerWith(block(DEFINITION));

        session.accept(proposal);

        assertTrue(applied.get(0).get(1).getAxiom().getAnnotations().isEmpty());
    }

    @Test
    void anAxiomAlreadyGoneBecomesInvalid() {
        OWLAxiom disjoint = factory.getOWLDisjointClassesAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")),
                factory.getOWLClass(MiniPizza.iri("MeatTopping")));
        Proposal proposal = answerWith(block("action: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, disjoint)));
        pizza.getOWLOntologyManager().removeAxiom(pizza, disjoint);

        session.accept(proposal);

        assertTrue(applied.isEmpty());
        Proposal now = session.turns().get(0).getProposals().get(0);
        assertEquals(Proposal.Status.INVALID, now.getStatus());
        assertTrue(now.getProblem().contains("already gone"), now.getProblem());
    }

    @Test
    void aDefinitionChangedMeanwhileIsCheckedAgainOnAccept() {
        Proposal proposal = answerWith(block(DEFINITION));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(IRI.create(
                        "http://purl.obolibrary.org/obo/IAO_0000115")), selected,
                factory.getOWLLiteral("A pizza whose toppings are all vegetarian.")));

        session.accept(proposal);

        assertTrue(applied.isEmpty());
        assertEquals(Proposal.Status.INVALID,
                session.turns().get(0).getProposals().get(0).getStatus());
    }

    /**
     * Review I1 (1.137.0): the card struck through "A pizza with no meat."; the person then
     * rewrote the definition in Protégé. Accept must not replace their text, which the card
     * never showed.
     */
    @Test
    void aDefinitionEditedToAnotherValueIsNotReplacedUnseen() {
        Proposal proposal = answerWith(block(DEFINITION));
        org.semanticweb.owlapi.model.OWLAnnotationProperty definition =
                factory.getOWLAnnotationProperty(IRI.create(
                        "http://purl.obolibrary.org/obo/IAO_0000115"));
        pizza.getOWLOntologyManager().removeAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                definition, selected, factory.getOWLLiteral("A pizza with no meat.")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                definition, selected, factory.getOWLLiteral("A pizza without meat or fish.")));

        session.accept(proposal);

        assertTrue(applied.isEmpty(), "nothing the card did not show is replaced");
        Proposal now = session.turns().get(0).getProposals().get(0);
        assertEquals(Proposal.Status.INVALID, now.getStatus());
        assertTrue(now.getProblem().contains("changed since"), now.getProblem());
    }

    @Test
    void aRemovalIsCheckedByTheReasonerBeforeAccept() {
        OWLNamedIndividual x = factory.getOWLNamedIndividual(MiniPizza.iri("x"));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")), x));
        OWLAxiom meat = factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("MeatTopping")), x);
        pizza.getOWLOntologyManager().addAxiom(pizza, meat);
        session.setReasoner(Reasoners.Choice.HERMIT.newFactory(), "HermiT");

        answerWith(block("action: remove-axiom\naxiom: " + AssistantRendering.render(pizza, meat)));

        assertEquals(1, heard.checks.size());
        assertTrue(heard.checks.get(0).isSolved(), heard.checks.get(0).describe());
        assertTrue(heard.checks.get(0).describe().contains("HermiT"),
                heard.checks.get(0).describe());
        assertTrue(applied.isEmpty(), "checking applies nothing");
    }

    /**
     * Asked by the person (1.151.0): after the repair was accepted, the chip still told the model
     * the ontology was inconsistent. Accepting a change asks the reasoner about the problem at
     * once, and the chip goes when the problem has.
     */
    @Test
    void acceptingARepairAsksTheReasonerAboutTheProblemAtOnce() {
        org.semanticweb.owlapi.reasoner.OWLReasonerFactory hermit =
                Reasoners.Choice.HERMIT.newFactory();
        session.setReasoner(hermit, "HermiT");
        String removal = removalOfAClash();
        session.addContext(de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems
                .inconsistency(pizza, de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems
                        .diagnose(pizza, hermit), "HermiT"));
        Proposal proposal = answerWith(removal);

        session.accept(proposal);

        for (de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem item
                : session.context()) {
            assertFalse(item.getKind()
                    == de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem.Kind.PROBLEM,
                    session.context().toString());
        }
        assertTrue(heard.notices.toString().contains("HermiT finds no such problem now"),
                heard.notices.toString());
        assertEquals(1, client.calls, "nothing more was sent");

        // Another change to the logic before the next question: what the reasoner found may no
        // longer hold, so it is not said.
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("Gone")),
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping"))));
        session.ontologyChanged();
        answerWith("Fine.");
        assertFalse(client.asked.getSystem().contains("asked again after the ontology changed"),
                client.asked.getSystem());
    }

    private String removalOfAClash() {
        OWLNamedIndividual x = factory.getOWLNamedIndividual(MiniPizza.iri("x"));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")), x));
        OWLAxiom meat = factory.getOWLClassAssertionAxiom(
                factory.getOWLClass(MiniPizza.iri("MeatTopping")), x);
        pizza.getOWLOntologyManager().addAxiom(pizza, meat);
        return block("action: remove-axiom\naxiom: " + AssistantRendering.render(pizza, meat));
    }

    /**
     * Review I4 (1.137.0): checks queued on the model's executor, so the next question waited
     * behind a long classification. They have an executor of their own now.
     */
    @Test
    void checksRunOnTheirOwnExecutor() {
        List<Runnable> queued = new ArrayList<Runnable>();
        session.setCheckExecutor(queued::add);
        session.setReasoner(Reasoners.Choice.HERMIT.newFactory(), "HermiT");

        answerWith(removalOfAClash());

        assertTrue(heard.checks.isEmpty(), "the model's executor did not run the check");
        assertEquals(1, queued.size());
        queued.remove(0).run();
        assertEquals(1, heard.checks.size());
        assertTrue(heard.checks.get(0).isSolved(), heard.checks.get(0).describe());
    }

    @Test
    void stopSkipsChecksNotYetStarted() {
        List<Runnable> queued = new ArrayList<Runnable>();
        session.setCheckExecutor(queued::add);
        session.setReasoner(Reasoners.Choice.HERMIT.newFactory(), "HermiT");
        answerWith(removalOfAClash());

        session.stop();
        queued.remove(0).run();

        assertEquals(1, heard.checks.size());
        assertTrue(String.valueOf(heard.checks.get(0).getError()).contains("Stopped"),
                String.valueOf(heard.checks.get(0).getError()));
    }

    /**
     * Review R5 (1.138.0): accepting the label made the same answer's definition card invalid,
     * because Accept looked the term up by its old name again.
     */
    @Test
    void acceptingALabelLeavesTheSameAnswersDefinitionCardGood() {
        answerWith(block("action: set\nsubject: vegetarian pizza\nproperty: label\n"
                + "language: en\nvalue: veggie pizza") + block(DEFINITION));
        List<Proposal> cards = session.turns().get(0).getProposals();

        session.accept(cards.get(0));
        session.accept(cards.get(1));

        assertEquals(2, applied.size());
        assertEquals(Proposal.Status.ACCEPTED,
                session.turns().get(0).getProposals().get(1).getStatus());
    }

    /**
     * Review R4: two cards both struck the one untagged label; accepting the English one took it
     * away, and the German one, which would now only add, was refused as "changed".
     */
    @Test
    void aCardWhoseStruckValueIsAlreadyGoneMayStillAdd() {
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("VegetarianTopping"),
                factory.getOWLLiteral("veg topping")));
        answerWith(block("action: set\nsubject: veg topping\nproperty: label\nlanguage: en\n"
                + "value: vegetarian topping") + block("action: set\nsubject: veg topping\n"
                + "property: label\nlanguage: de\nvalue: vegetarisches Topping"));
        List<Proposal> cards = session.turns().get(0).getProposals();

        session.accept(cards.get(0));
        session.accept(cards.get(1));

        assertEquals(2, applied.size(), session.turns().get(0).getProposals().toString());
    }

    /** Review R2 (1.138.0): Stop interrupts the reasoning under way, not only the queue. */
    @Test
    void stopInterruptsACheckUnderWay() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService thread =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        session.setCheckExecutor(thread);
        session.setReasoner(de.fizkarlsruhe.ise.ontoboard.assistant.proposals
                .ProposalValidatorTestAccess.endless(started), "Endless");
        answerWith(removalOfAClash());
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "it started");

        session.stop();
        thread.shutdown();

        assertTrue(thread.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
                "the check ended");
        assertEquals(1, heard.checks.size());
        assertTrue(String.valueOf(heard.checks.get(0).getError()).contains("Stopped"),
                String.valueOf(heard.checks.get(0).getError()));
    }

    /** 1.139.0: "∃ made explicit" - an added axiom is one change, naming the model. */
    @Test
    void acceptingAnAddedAxiomAddsItWithProvenance() {
        Proposal proposal = answerWith(block("action: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'"));

        session.accept(proposal);

        assertEquals(1, applied.size());
        OWLAxiom added = applied.get(0).get(0).getAxiom();
        assertTrue(pizza.containsAxiomIgnoreAnnotations(added));
        assertEquals("OntoBoard assistant (ollama qwen2.5:7b)", added.getAnnotations().iterator()
                .next().getValue().asLiteral().get().getLiteral());
    }

    @Test
    void aReplacementWhoseOldAxiomIsGoneIsNotApplied() {
        OWLAxiom old = factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("CheeseTopping")),
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")));
        Proposal proposal = answerWith(block("action: replace-axiom\naxiom: "
                + AssistantRendering.render(pizza, old)
                + "\nwith: 'cheese topping' SubClassOf PizzaTopping"));
        pizza.getOWLOntologyManager().removeAxiom(pizza, old);

        session.accept(proposal);

        assertTrue(applied.isEmpty());
        assertEquals(Proposal.Status.INVALID,
                session.turns().get(0).getProposals().get(0).getStatus());
    }

    @Test
    void rejectingMarksItRejectedAndAppliesNothing() {
        Proposal proposal = answerWith(block(DEFINITION));

        session.reject(proposal);
        session.accept(proposal);

        assertTrue(applied.isEmpty());
        assertEquals(Proposal.Status.REJECTED,
                session.turns().get(0).getProposals().get(0).getStatus());
    }

    /**
     * Review 1.139.0 I2: Accept read an axiom card's text again by name, so renaming 'cheese
     * topping' in the same answer made its add and replace cards "not in this ontology".
     */
    @Test
    void acceptingALabelLeavesAxiomCardsOffered() {
        answerWith(block("action: set\nsubject: cheese topping\nproperty: label\n"
                + "language: en\nvalue: cheese")
                + block("action: add-axiom\naxiom: Margherita SubClassOf hasTopping only"
                        + " 'cheese topping'")
                + block("action: replace-axiom\naxiom: 'cheese topping' SubClassOf"
                        + " VegetarianTopping\nwith: 'cheese topping' SubClassOf PizzaTopping"));
        List<Proposal> cards = session.turns().get(0).getProposals();
        for (Proposal card : cards) {
            assertEquals(Proposal.Status.OFFERED, card.getStatus(), card.getProblem());
        }

        session.accept(cards.get(0));
        session.accept(cards.get(1));
        session.accept(cards.get(2));

        List<Proposal> after = session.turns().get(0).getProposals();
        assertEquals(Proposal.Status.ACCEPTED, after.get(1).getStatus(), after.get(1).getProblem());
        assertEquals(Proposal.Status.ACCEPTED, after.get(2).getStatus(), after.get(2).getProblem());
        assertEquals(3, applied.size());
    }

    /** Review 1.139.0 I3: Revise on an axiom card sent "for null null". */
    @Test
    void reviseAnAxiomCardNamesTheAxiom() {
        Proposal added = answerWith(block("action: add-axiom\naxiom: Margherita SubClassOf"
                + " hasTopping only 'cheese topping'"));
        client.deltas = Arrays.asList("ok");
        session.revise(added, "use some");
        Proposal replaced = answerWith(block("action: replace-axiom\naxiom: 'cheese topping'"
                + " SubClassOf VegetarianTopping\nwith: 'cheese topping' SubClassOf PizzaTopping"));
        client.deltas = Arrays.asList("ok");
        session.revise(replaced, "keep it vegetarian");

        List<Turn> turns = session.turns();
        assertEquals("Revise your proposal for adding Margherita SubClassOf hasTopping only"
                + " 'cheese topping': use some", turns.get(1).getMessage());
        assertEquals("Revise your proposal for replacing 'cheese topping' SubClassOf"
                + " VegetarianTopping with 'cheese topping' SubClassOf PizzaTopping: keep it"
                + " vegetarian", turns.get(3).getMessage());
    }

    @Test
    void reviseSendsTheFixedWordingAndMarksItRevised() {
        Proposal proposal = answerWith(block(DEFINITION));
        client.deltas = Arrays.asList("ok");

        session.revise(proposal, "mention the base");

        assertEquals("Revise your proposal for vegetarian pizza definition: mention the base",
                session.turns().get(1).getMessage());
        assertEquals(Proposal.Status.REVISED,
                session.turns().get(0).getProposals().get(0).getStatus());
    }

    /** Review (1.137.0): Revise while an answer was coming cleared the field and sent nothing. */
    /** 1.141.0: a sentence that cannot be sent while busy adds no chips either. */
    @Test
    void writeAsOwlWhileBusyAddsNothing() {
        List<Runnable> queue = new ArrayList<Runnable>();
        AssistantSession busy = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return pizza;
            }

            @Override
            public IRI selected() {
                return selected;
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, queue::add, INLINE);
        busy.setClientFactory((connection, key) -> client);
        busy.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA).withModel("m"), "");
        busy.send("first");

        busy.writeAsOwl("Every vegetarian pizza hasTopping at least one cheese topping.");

        assertEquals(1, busy.context().size(), busy.context().toString());
        assertEquals(1, busy.turns().size());
    }

    @Test
    void reviseWhileBusyIsNotSentAndTheCardStaysOpen() {
        Proposal proposal = answerWith(block(DEFINITION));
        List<Runnable> queue = new ArrayList<Runnable>();
        AssistantSession busy = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return pizza;
            }

            @Override
            public IRI selected() {
                return selected;
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, queue::add, INLINE);
        busy.setClientFactory((connection, key) -> client);
        busy.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA).withModel("m"), "");
        busy.send("first");
        queue.remove(0).run();
        Proposal card = busy.turns().get(0).getProposals().get(0);
        busy.send("second");

        boolean sent = busy.revise(card, "shorter");

        assertFalse(sent);
        assertEquals(Proposal.Status.OFFERED, busy.turns().get(0).getProposals().get(0)
                .getStatus());
        assertTrue(session.revise(proposal, "shorter"), "not busy: sent");
    }

    @Test
    void suggestADefinitionAsksForTheGenusFromTheAssertedParent() {
        client.deltas = Arrays.asList("ok");

        session.suggestDefinition();

        String asked = session.turns().get(0).getMessage();
        assertTrue(asked.contains("vegetarian pizza"), asked);
        assertTrue(asked.contains("Pizza"), asked);
        assertTrue(asked.contains("ontoboard-proposal"), asked);
    }

    /** Review I2: the header said "Suggest" (it reads imports) while the request said "Write". */
    @Test
    void suggestADefinitionSeesAnImportedDefinition() {
        final OWLOntology edit = de.fizkarlsruhe.ise.ontoboard.assistant.proposals
                .ProposalValidatorTestAccess.importing(pizza);
        AssistantSession onEdit = new AssistantSession(new OntologyAccess() {
            @Override
            public OWLOntology ontology() {
                return edit;
            }

            @Override
            public IRI selected() {
                return selected;
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, INLINE, INLINE);
        onEdit.setClientFactory((connection, key) -> client);
        onEdit.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA).withModel("m"), "");
        client.deltas = Arrays.asList("ok");

        onEdit.suggestDefinition();

        assertTrue(onEdit.turns().get(0).getMessage().startsWith("Suggest a better definition"),
                onEdit.turns().get(0).getMessage());
    }

    @Test
    void suggestADefinitionWithNothingSelectedSaysSo() {
        selected = null;

        session.suggestDefinition();

        assertTrue(session.turns().isEmpty());
        assertFalse(heard.notices.isEmpty());
    }
}
