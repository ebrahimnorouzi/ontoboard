package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClient;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.StreamListener;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLine;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLines;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A session: what is sent, what is refused before anything is sent, and what the person sees.
 * Executors run inline, and the model is scripted, so nothing here touches a network.
 */
class AssistantSessionTest {

    private static final Executor INLINE = Runnable::run;

    /** A model that answers from a script and remembers what it was asked. */
    static final class ScriptedClient implements ModelClient {
        List<String> deltas = new ArrayList<String>();
        ModelCallException failure;
        ChatRequest asked;
        int calls;
        boolean stopped;

        @Override
        public List<String> listModels() {
            return Collections.emptyList();
        }

        @Override
        public Reply chat(ChatRequest request, StreamListener listener)
                throws ModelCallException {
            asked = request;
            calls++;
            StringBuilder text = new StringBuilder();
            for (String delta : deltas) {
                listener.onText(delta);
                text.append(delta);
            }
            if (failure != null) {
                throw failure;
            }
            return new Reply(text.toString(), 10, 5, "end_turn", 3);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    /** Everything a listener heard, in order. */
    static final class Heard implements SessionListener {
        final List<String> notices = new ArrayList<String>();
        final List<String> text = new ArrayList<String>();
        final List<Turn> finished = new ArrayList<Turn>();
        List<NameResolver.Resolution> needs;
        List<ContextItem> context;

        @Override
        public void onText(Turn turn, String delta) {
            text.add(delta);
        }

        @Override
        public void onTurnFinished(Turn turn) {
            finished.add(turn);
        }

        @Override
        public void onNeeds(List<NameResolver.Resolution> resolutions) {
            needs = resolutions;
        }

        @Override
        public void onNotice(String message) {
            notices.add(message);
        }

        @Override
        public void onContextChanged(List<ContextItem> items) {
            context = items;
        }
    }

    private final OWLOntology pizza = MiniPizza.load();
    private IRI selected = MiniPizza.iri("VegetarianPizza");
    private final ScriptedClient client = new ScriptedClient();
    private final Heard heard = new Heard();
    private final List<String> confirmations = new ArrayList<String>();
    private boolean confirm = true;
    private AssistantSession session;

    private final Connection ollama = Connection.of("Ollama", ConnectionKind.OLLAMA)
            .withModel("llama3.1");
    private final Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC)
            .withModel("claude-x");

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
        session.setRole("Ontology expert", "ROLE");
        session.setConfirmer((host, tokens, subject) -> {
            confirmations.add(host + "|" + subject);
            return confirm;
        });
        session.setConnection(ollama, "");
    }

    @Test
    void sendStreamsIntoTheListenerAndRecordsTheTurn() {
        client.deltas = Arrays.asList("A vegetarian ", "pizza.");

        session.send("What is it?");

        assertEquals(Arrays.asList("A vegetarian ", "pizza."), heard.text);
        Turn turn = session.turns().get(0);
        assertEquals("What is it?", turn.getMessage());
        assertEquals("A vegetarian pizza.", turn.getReply());
        assertTrue(turn.isFinished());
        assertNull(turn.getError());
        assertFalse(session.isBusy());
        assertEquals(Arrays.asList("vegetarian pizza"), turn.getContextTitles());
        assertTrue(client.asked.getSystem().startsWith("ROLE"), client.asked.getSystem());
        assertTrue(client.asked.getSystem().contains("ontoboard-need"), "the fixed rules");
    }

    @Test
    void theSelectedTermIsAlwaysTheFirstItemAndCannotBeRemoved() {
        session.addContext(ContextItem.term(MiniPizza.iri("CheeseTopping"), false));
        ContextItem follows = session.context().get(0);

        session.removeContext(follows);

        assertTrue(follows.isFollowingSelection());
        assertEquals(2, session.context().size());
        assertEquals(MiniPizza.iri("VegetarianPizza"), session.context().get(0).getIri());

        selected = null;
        assertEquals(1, session.context().size(), "nothing selected: only what was added");
    }

    @Test
    void addedTermsAreSentAsContextSections() {
        session.addContext(ContextItem.term(MiniPizza.iri("CheeseTopping"), false));
        session.addContext(ContextItem.term(MiniPizza.iri("CheeseTopping"), false));

        session.send("q");

        assertEquals(2, session.context().size(), "added once");
        assertTrue(client.asked.getSystem().contains("[context: cheese topping]"),
                client.asked.getSystem());
        assertEquals(2, heard.context.size());
    }

    @Test
    void localOnlyRefusesACloudConnection() {
        session.setLocalModelsOnly(true);
        session.setConnection(claude, "sk-x");

        session.send("q");

        assertEquals(0, client.calls);
        assertEquals(Arrays.asList("This project allows local models only"
                + " (assistant/project.properties), and Claude sends to api.anthropic.com."),
                heard.notices);
        assertTrue(session.turns().isEmpty());
    }

    @Test
    void localOnlyAllowsOllamaOnThisMachine() {
        session.setLocalModelsOnly(true);

        session.send("q");

        assertEquals(1, client.calls);
    }

    @Test
    void tooLargeForOllamaIsRefusedWithTheNumbers() {
        session.setConnection(ollama.withContextLength(100), "");
        session.addContext(ContextItem.wholeOntology());

        session.send("q");

        assertEquals(0, client.calls);
        assertEquals(1, heard.notices.size());
        String notice = heard.notices.get(0);
        assertTrue(notice.startsWith("This request is about "), notice);
        assertTrue(notice.contains("tokens and Ollama has a context length of 100, which must also"
                + " hold an answer of up to 2,048 tokens. Remove some chips (× on a chip, or"
                + " Remove all), clear the conversation, or raise the context length in"
                + " Settings."), notice);
    }

    @Test
    void theFirstCloudSendAsksOnceAndADeclineSendsNothing() {
        session.setConnection(claude, "sk-x");
        confirm = false;

        session.send("q");

        assertEquals(0, client.calls);
        assertEquals(Arrays.asList("Not sent."), heard.notices);
        assertEquals(Arrays.asList("api.anthropic.com|vegetarian pizza"), confirmations);

        confirm = true;
        session.send("q");
        session.send("q again");

        assertEquals(2, client.calls);
        assertEquals(2, confirmations.size(), "asked again after a decline, then never");
    }

    @Test
    void localConnectionsNeverAsk() {
        session.send("q");

        assertEquals(1, client.calls);
        assertTrue(confirmations.isEmpty());
    }

    @Test
    void withoutAConfirmerACloudSendIsNotSent() {
        session.setConfirmer(null);
        session.setConnection(claude, "sk-x");

        session.send("q");

        assertEquals(0, client.calls);
    }

    @Test
    void stopKeepsThePartialTextAndSaysStopped() {
        client.deltas = Arrays.asList("Half");
        client.failure = new ModelCallException(ModelCallException.Kind.STOPPED, "Stopped.");

        session.send("q");

        Turn turn = session.turns().get(0);
        assertEquals("Half", turn.getReply());
        assertEquals("Stopped.", turn.getError());
        assertTrue(heard.notices.isEmpty(), "a stop the person asked for is not an error");
        assertFalse(session.isBusy());
    }

    @Test
    void aFailureIsANoticeAndAFailedTurn() {
        client.failure = new ModelCallException(ModelCallException.Kind.UNAUTHORIZED,
                "api.anthropic.com refused the key (401): invalid. Check the key in Settings.");

        session.send("q");

        assertEquals("api.anthropic.com refused the key (401): invalid. Check the key in"
                + " Settings.", session.turns().get(0).getError());
        assertEquals(1, heard.notices.size());
    }

    @Test
    void noConnectionSaysSetOneUp() {
        session.setConnection(null, null);

        session.send("q");

        assertEquals(Arrays.asList("Set up a model in the Settings tab first."), heard.notices);
    }

    @Test
    void aNeedBlockOffersTheResolvedTerms() {
        client.deltas = Arrays.asList("I need more.\n```ontoboard-need\ncheese topping\n"
                + "calzone\n```");

        session.send("Which toppings?");

        assertEquals(2, heard.needs.size());
        assertTrue(heard.needs.get(0).isFound());
        assertFalse(heard.needs.get(1).isFound());
    }

    @Test
    void addAndAskAgainResendsWithTheTerms() {
        client.deltas = Arrays.asList("```ontoboard-need\ncheese topping\n```");
        session.send("Which toppings?");
        client.deltas = Arrays.asList("Cheese.");

        session.addAndAskAgain(heard.needs);

        assertEquals(2, client.calls);
        assertEquals("Which toppings?", session.turns().get(1).getMessage());
        assertTrue(client.asked.getSystem().contains("[context: cheese topping]"),
                client.asked.getSystem());
    }

    @Test
    void askAboutLineAndExplainLineSendTheExactWording() {
        TermLine restriction = null;
        for (TermLine line : TermLines.of(pizza, MiniPizza.iri("VegetarianPizza"))) {
            if ("hasTopping only VegetarianTopping".equals(line.getText())) {
                restriction = line;
            }
        }

        session.askAboutLine(restriction);
        session.explainLine(restriction);

        assertEquals("About this line of vegetarian pizza: " + restriction.getRendered()
                + "\nWhat does it say, and is it well written?",
                session.turns().get(0).getMessage());
        assertEquals("Explain this axiom in plain language: what it says, what follows from it,"
                + " and what it does not say.\n" + restriction.getRendered(),
                session.turns().get(1).getMessage());
    }

    @Test
    void clearForgetsTurnsButNotTheCloudConfirmation() {
        session.setConnection(claude, "sk-x");
        session.send("q");

        session.clear();
        session.send("q2");

        assertEquals(1, session.turns().size());
        assertEquals(1, confirmations.size());
    }

    @Test
    void nothingIsSentWhileBusy() {
        AssistantSession slow = new AssistantSession(new OntologyAccess() {
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
        }, runnable -> { }, INLINE);
        slow.setClientFactory((connection, key) -> client);
        slow.setConnection(ollama, "");

        slow.send("first");
        slow.send("second");
        slow.stop();

        assertEquals(1, slow.turns().size(), "the background never ran, so the first is open");
        assertTrue(slow.isBusy());
        assertTrue(client.stopped, "Stop reaches the client of the call in progress");
    }

    private final org.semanticweb.owlapi.reasoner.OWLReasonerFactory hermit =
            de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.HERMIT.newFactory();

    /** {@code name} a vegetarian topping and a meat topping, which are disjoint: a clash. */
    private List<org.semanticweb.owlapi.model.OWLAxiom> clash(String name) {
        org.semanticweb.owlapi.model.OWLDataFactory data =
                pizza.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLNamedIndividual x =
                data.getOWLNamedIndividual(MiniPizza.iri(name));
        List<org.semanticweb.owlapi.model.OWLAxiom> axioms = Arrays.asList(
                data.getOWLClassAssertionAxiom(
                        data.getOWLClass(MiniPizza.iri("VegetarianTopping")), x),
                data.getOWLClassAssertionAxiom(data.getOWLClass(MiniPizza.iri("MeatTopping")), x));
        pizza.getOWLOntologyManager().addAxioms(pizza,
                new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>(axioms));
        return axioms;
    }

    /** The chip "Explain the reasoner problem…" adds for the ontology as it is now. */
    private ContextItem explained() {
        return de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems.inconsistency(pizza,
                de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems.diagnose(pizza, hermit),
                "HermiT");
    }

    private boolean anyProblem() {
        for (ContextItem item : session.context()) {
            if (item.getKind() == ContextItem.Kind.PROBLEM) {
                return true;
            }
        }
        return false;
    }

    /**
     * Asked by the person (1.151.0): the problem chip was what the reasoner found when it was
     * explained, and every later question told the model the ontology was inconsistent - after
     * the clash was repaired too - without asking the reasoner whether it still was. A chip from
     * before an edit is asked about again before it is sent, and left out when the problem is
     * gone.
     */
    @Test
    void aProblemExplainedBeforeAnEditIsAskedAboutAgainBeforeItIsSent() {
        session.setReasoner(hermit, "HermiT");
        List<org.semanticweb.owlapi.model.OWLAxiom> clash = clash("clashOld");
        session.addContext(explained());
        pizza.getOWLOntologyManager().removeAxiom(pizza, clash.get(1));
        session.ontologyChanged();

        session.send("What next?");

        assertEquals(1, client.calls);
        assertFalse(client.asked.getSystem().contains("The ontology is inconsistent"),
                client.asked.getSystem());
        assertFalse(anyProblem(), session.context().toString());
        assertTrue(heard.notices.toString().contains("HermiT finds no such problem now, so"
                + " \"Why the ontology is inconsistent\" is no longer sent"), heard.notices
                        .toString());
    }

    /**
     * The conversation still holds "Why is the ontology inconsistent?" and its answer: with the
     * chip gone and nothing said, a model went on as if it were. The next request says once what
     * the reasoner found when asked again; a later one, or one after another change to the logic,
     * does not.
     */
    @Test
    void theModelIsToldOnceWhatTheReasonerFoundWhenAskedAgain() {
        session.setReasoner(hermit, "HermiT");
        List<org.semanticweb.owlapi.model.OWLAxiom> clash = clash("clashOld");
        session.addContext(explained());
        pizza.getOWLOntologyManager().removeAxiom(pizza, clash.get(1));
        session.ontologyChanged();
        String told = "HermiT, asked again after the ontology changed, finds the ontology"
                + " consistent now.";

        session.send("What next?");
        String first = client.asked.getSystem();
        session.send("And then?");
        String second = client.asked.getSystem();

        assertTrue(first.contains(told), first);
        assertFalse(second.contains(told), second);
    }

    /** Still there, it is sent as the reasoner finds it now: the new clash, not the old one. */
    @Test
    void aProblemThatStillHoldsIsSentAsTheReasonerFindsItNow() {
        session.setReasoner(hermit, "HermiT");
        List<org.semanticweb.owlapi.model.OWLAxiom> old = clash("clashOld");
        session.addContext(explained());
        pizza.getOWLOntologyManager().removeAxiom(pizza, old.get(1));
        clash("clashNew");
        session.ontologyChanged();

        session.send("What next?");

        String system = client.asked.getSystem();
        assertTrue(system.contains("The ontology is inconsistent"), system);
        assertTrue(system.contains("clashNew"), system);
        assertFalse(system.contains("clashOld"), system);
    }

    /** Nothing changed since it was explained: the reasoner is not run again for it. */
    @Test
    void aProblemNotEditedSinceIsSentWithoutAskingAgain() {
        List<Runnable> queued = new ArrayList<Runnable>();
        session.setCheckExecutor(queued::add);
        session.setReasoner(hermit, "HermiT");
        clash("clashOld");
        session.addContext(explained());

        session.send("What next?");

        assertTrue(queued.isEmpty(), "the reasoner was run again");
        assertTrue(client.asked.getSystem().contains("clashOld"), client.asked.getSystem());
    }

    /** Stop while the reasoner asks again: nothing is sent, and the session is free again. */
    @Test
    void stopWhileTheProblemIsAskedAboutAgainSendsNothing() {
        List<Runnable> queued = new ArrayList<Runnable>();
        session.setCheckExecutor(queued::add);
        session.setReasoner(hermit, "HermiT");
        List<org.semanticweb.owlapi.model.OWLAxiom> clash = clash("clashOld");
        session.addContext(explained());
        pizza.getOWLOntologyManager().removeAxiom(pizza, clash.get(1));
        session.ontologyChanged();

        session.send("What next?");
        boolean waited = session.isBusy() && client.calls == 0;
        session.stop();
        for (Runnable each : queued) {
            each.run();
        }

        assertTrue(waited, "it waited for the reasoner");
        assertFalse(session.isBusy());
        assertEquals(0, client.calls, "nothing is sent once stopped");
    }

    /**
     * "What is sent" shows the chip as it was explained, and says it is asked about again first:
     * the person reading it was told the ontology was inconsistent though it no longer was.
     */
    @Test
    void whatIsSentSaysAProblemFromBeforeAnEditIsAskedAboutAgainFirst() {
        session.setReasoner(hermit, "HermiT");
        List<org.semanticweb.owlapi.model.OWLAxiom> clash = clash("clashOld");
        session.addContext(explained());
        pizza.getOWLOntologyManager().removeAxiom(pizza, clash.get(1));
        session.ontologyChanged();

        String system = session.preview("What next?").getRequest().getSystem();

        assertTrue(system.contains("The ontology was edited after this was computed: when you"
                + " send, HermiT is asked again first, and this is left out if the problem is"
                + " gone."), system);
        assertEquals(0, client.calls);
    }

    /** A reasoner that cannot run: the chip goes as it was, and says it was not asked again. */
    @Test
    void aProblemTheReasonerCannotAskAboutAgainIsSentSayingSo() {
        clash("clashOld");
        session.addContext(explained());
        session.setReasoner((org.semanticweb.owlapi.reasoner.OWLReasonerFactory)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {org.semanticweb.owlapi.reasoner.OWLReasonerFactory.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getReasonerName")) {
                                return "Broken";
                            }
                            throw new IllegalStateException("no reasoner here");
                        }), "Broken");
        session.ontologyChanged();

        session.send("What next?");

        String system = client.asked.getSystem();
        assertTrue(system.contains("Not checked again since the ontology was last edited: Broken"
                + " could not run (no reasoner here). This is the problem as it was before."),
                system);
        assertTrue(system.contains("clashOld"), system);
        assertTrue(heard.notices.toString().contains("Broken could not check the problem again"
                + " (no reasoner here): it is sent as it was explained, saying so."),
                heard.notices.toString());
    }

    /** Review #1: explaining again must send the new justification, not keep the old chip. */
    @Test
    void reExplainingReplacesTheProblemChip() {
        session.addContext(ContextItem.problem("Why the ontology is inconsistent", "OLD WHY"));
        session.addContext(ContextItem.problem("Why the ontology is inconsistent", "NEW WHY"));

        session.send("q");

        assertTrue(client.asked.getSystem().contains("NEW WHY"), client.asked.getSystem());
        assertFalse(client.asked.getSystem().contains("OLD WHY"), client.asked.getSystem());
    }

    /** Review #4: consent was given to one host; another host is asked about again. */
    @Test
    void aSecondCloudHostIsAskedAgain() {
        session.setConnection(claude, "sk-x");
        session.send("q");
        session.setConnection(Connection.of("OpenAI", ConnectionKind.OPENAI_COMPATIBLE)
                .withModel("m"), "sk-y");

        session.send("q2");

        assertEquals(Arrays.asList("api.anthropic.com|vegetarian pizza",
                "api.openai.com|vegetarian pizza"), confirmations);
        assertEquals(2, client.calls);
    }

    /** Review #6: Ollama's context holds the answer too, so the answer's room is counted. */
    @Test
    void ollamaLeavesRoomForTheAnswer() {
        int prompt = session.preview("q").getEstimatedTokens();
        session.setConnection(ollama.withContextLength(prompt + 10).withMaxOutputTokens(100), "");

        session.send("q");

        assertEquals(0, client.calls);
        assertTrue(heard.notices.get(0).contains("clear the conversation"), heard.notices.get(0));
    }

    /** Review #6: old turns are dropped to make room before anything is refused. */
    @Test
    void ollamaDropsOldTurnsToMakeRoom() {
        StringBuilder long2000 = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            long2000.append('w');
        }
        client.deltas = Arrays.asList(long2000.toString());
        session.send("first " + long2000);
        session.send("second " + long2000);
        int withHistory = session.preview("q").getEstimatedTokens();
        int history = 4 * 500;
        session.setConnection(ollama.withContextLength(withHistory - history + 64 + 50)
                .withMaxOutputTokens(64), "");
        client.deltas = Arrays.asList("ok");

        session.send("q");

        assertEquals(3, client.calls, "sent, with older turns left out: " + heard.notices);
        assertTrue(client.asked.getMessages().size() < 5,
                "messages sent: " + client.asked.getMessages().size());
    }

    /** Review #2: a heavy chip is rendered once per version of the ontology, not per refresh. */
    @Test
    void rendersAreCachedUntilTheOntologyChanges() {
        session.addContext(ContextItem.wholeOntology());

        session.preview("a");
        session.preview("b");
        session.sizeOf(ContextItem.wholeOntology());
        int afterTwo = session.renders();
        session.ontologyChanged();
        session.preview("c");

        assertEquals(2, afterTwo, "the term and the whole ontology, each once");
        assertEquals(4, session.renders());
    }

    /** A session whose model call waits in a queue, so it is busy until the test runs it. */
    private AssistantSession queued(List<Runnable> queue) {
        AssistantSession waiting = new AssistantSession(new OntologyAccess() {
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
        waiting.setClientFactory((connection, key) -> client);
        waiting.setConnection(ollama, "");
        return waiting;
    }

    /** Review (1.137.0): ticking a Settings box stopped the answer that was coming. */
    @Test
    void settingTheSameConnectionAgainDoesNotStopTheAnswer() {
        List<Runnable> queue = new ArrayList<Runnable>();
        AssistantSession waiting = queued(queue);
        waiting.send("q");

        waiting.setConnection(ollama, "");
        assertFalse(client.stopped, "the same connection: the answer goes on");
        waiting.setConnection(claude, "sk-x");
        assertTrue(client.stopped, "another connection: the answer is stopped");
    }

    /** Round-1 M7: an Error escaping the model call left the session busy for good. */
    @Test
    void anErrorInTheModelCallEndsTheTurn() {
        session.setClientFactory((connection, key) -> new ModelClient() {
            @Override
            public List<String> listModels() {
                return Collections.emptyList();
            }

            @Override
            public Reply chat(ChatRequest request, StreamListener listener) {
                throw new StackOverflowError();
            }

            @Override
            public void stop() {
            }
        });

        session.send("q");

        assertFalse(session.isBusy());
        assertTrue(session.turns().get(0).getError() != null);
    }

    @Test
    void anExecutorThatRefusesEndsTheTurn() {
        AssistantSession refusing = new AssistantSession(new OntologyAccess() {
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
        }, work -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        }, INLINE);
        refusing.setClientFactory((connection, key) -> client);
        refusing.setConnection(ollama, "");

        refusing.send("q");

        assertFalse(refusing.isBusy());
        assertTrue(refusing.turns().get(0).getError() != null);
    }

    /** Review (1.137.0): the line's " (en)" was copied into the request, and from there into values. */
    @Test
    void aWordingRequestCarriesTheLinesLanguage() {
        TermLine label = null;
        for (TermLine line : TermLines.of(pizza, selected)) {
            if ("label".equals(line.getKey()) && line.getText().endsWith("(en)")) {
                label = line;
            }
        }

        session.suggestWording(label);

        String asked = client.asked.getMessages().get(client.asked.getMessages().size() - 1)
                .getText();
        assertTrue(asked.contains("language: en"), asked);
        assertFalse(asked.contains("vegetarian pizza (en)"), asked);
        assertTrue(asked.contains("\nvegetarian pizza\n"), asked);
    }

    /**
     * Review R3 (1.138.0): an untagged "… (SEM)" was taken for a language tag; the tag now comes
     * from the literal, never from the text.
     */
    @Test
    void aWordingRequestDoesNotTakeAnAbbreviationForALanguage() {
        org.semanticweb.owlapi.model.OWLDataFactory f =
                pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSComment(), selected,
                f.getOWLLiteral("Seen under scanning electron microscopy (SEM)")));
        TermLine comment = null;
        for (TermLine line : TermLines.of(pizza, selected)) {
            if (line.getText().endsWith("(SEM)")) {
                comment = line;
            }
        }

        session.suggestWording(comment);

        String asked = client.asked.getMessages().get(client.asked.getMessages().size() - 1)
                .getText();
        assertTrue(asked.contains("scanning electron microscopy (SEM)"), asked);
        assertFalse(asked.contains("language:"), asked);
    }

    /** 1.139.0: "Write as OWL…" asks for one add-axiom proposal from the person's sentence. */
    @Test
    void aSentenceIsAskedForAsAnAxiom() {
        session.writeAsOwl("Every vegetarian pizza has at least one cheese topping.");

        String asked = client.asked.getMessages().get(client.asked.getMessages().size() - 1)
                .getText();
        assertTrue(asked.contains("Every vegetarian pizza has at least one cheese topping."),
                asked);
        assertTrue(asked.contains("add-axiom"), asked);
        assertTrue(asked.contains("vegetarian pizza"), asked);
    }

    /**
     * 1.141.0: the terms the sentence names go with it as chips, so the model sees that
     * hasTopping is a property (the live run invented hasReinforcement for 'reinforced with').
     */
    @Test
    void theTermsTheSentenceNamesGoWithItAsChips() {
        session.writeAsOwl("Every vegetarian pizza hasTopping at least one cheese topping.");

        List<ContextItem> items = session.context();
        assertEquals(Arrays.asList(ContextItem.term(MiniPizza.iri("VegetarianPizza"), true),
                ContextItem.term(MiniPizza.iri("hasTopping"), false),
                ContextItem.term(MiniPizza.iri("CheeseTopping"), false)), items);
        String shown = session.preview("Again.").describe(ollama);
        assertTrue(shown.contains("[context: hasTopping]"), shown);
        assertTrue(shown.contains("[context: cheese topping]"), shown);
    }

    private static final String CHEESE =
            "Every vegetarian pizza hasTopping at least one cheese topping.";
    private static final String ONLY_VEGETARIAN =
            "Every vegetarian pizza hasTopping nothing but VegetarianTopping.";

    private static ContextItem chip(String name) {
        return ContextItem.term(MiniPizza.iri(name), false);
    }

    /** 1.142.0 (review 1.141.0 I1): one sentence's terms at a time, not a growing pile. */
    @Test
    void aSecondSentenceReplacesTheFirstSentencesChips() {
        session.writeAsOwl(CHEESE);
        session.writeAsOwl(ONLY_VEGETARIAN);

        assertEquals(Arrays.asList(ContextItem.term(MiniPizza.iri("VegetarianPizza"), true),
                chip("hasTopping"), chip("VegetarianTopping")), session.context());
    }

    @Test
    void aChipThePersonAddedStaysAfterTheNextSentence() {
        session.addContext(chip("CheeseTopping"));
        session.writeAsOwl(CHEESE);

        session.writeAsOwl(ONLY_VEGETARIAN);

        assertTrue(session.context().contains(chip("CheeseTopping")), session.context()
                .toString());
    }

    @Test
    void nothingSentAddsNoChips() {
        session.setConnection(null, "");
        session.writeAsOwl(CHEESE);
        assertEquals(1, session.context().size(), "no connection: " + session.context());

        session.setConnection(claude, "k");
        confirm = false;
        session.writeAsOwl(CHEESE);
        assertEquals(1, session.context().size(), "a declined cloud send: " + session.context());

        session.setLocalModelsOnly(true);
        session.writeAsOwl(CHEESE);
        assertEquals(1, session.context().size(), "local models only: " + session.context());
        assertEquals(0, session.turns().size());
    }

    @Test
    void nothingSentKeepsThePreviousSentencesChips() {
        session.writeAsOwl(CHEESE);
        List<ContextItem> before = session.context();

        session.setConnection(null, "");
        session.writeAsOwl(ONLY_VEGETARIAN);

        assertEquals(before, session.context());
    }

    /** A sentence's terms that would not fit an Ollama context are left out, last first. */
    @Test
    void sentenceChipsThatDoNotFitAreLeftOutNotRefused() {
        String message = Questions.sentenceToAxiom("vegetarian pizza", CHEESE);
        session.addContext(chip("hasTopping"));
        int withOne = session.preview(message).getEstimatedTokens();
        session.removeContext(chip("hasTopping"));
        session.setConnection(ollama.withMaxOutputTokens(100).withContextLength(withOne + 100),
                "");

        session.writeAsOwl(CHEESE);

        assertEquals(1, session.turns().size(), heard.notices.toString());
        assertEquals(Arrays.asList(ContextItem.term(MiniPizza.iri("VegetarianPizza"), true),
                chip("hasTopping")), session.context());
    }

    /**
     * 1.143.0 review M-6: docs/assistant.md says chips you added yourself stay. A chip the person
     * added while it was already one of the sentence's was still taken away by the next sentence.
     */
    @Test
    void aChipAddedWhileItIsASentenceChipStaysAfterTheNextSentence() {
        session.writeAsOwl(CHEESE);
        session.addContext(chip("CheeseTopping"));

        session.writeAsOwl(ONLY_VEGETARIAN);

        assertTrue(session.context().contains(chip("CheeseTopping")), session.context()
                .toString());
    }

    /** 1.143.0 review M-5: terms left out to fit a context are said, not dropped silently. */
    @Test
    void termsLeftOutToFitAreNamedInANotice() {
        String message = Questions.sentenceToAxiom("vegetarian pizza", CHEESE);
        session.addContext(chip("hasTopping"));
        int withOne = session.preview(message).getEstimatedTokens();
        session.removeContext(chip("hasTopping"));
        session.setConnection(ollama.withMaxOutputTokens(100).withContextLength(withOne + 100),
                "");

        session.writeAsOwl(CHEESE);

        String said = String.join(" | ", heard.notices);
        assertTrue(said.contains("cheese topping"), said);
        assertTrue(said.contains("context length"), said);
    }

    @Test
    void nothingLeftOutMeansNoNotice() {
        session.writeAsOwl(CHEESE);

        for (String notice : heard.notices) {
            assertFalse(notice.contains("Left out"), notice);
        }
    }

    @Test
    void removeAllKeepsTheSelectedTerm() {
        session.addContext(chip("CheeseTopping"));
        session.writeAsOwl(ONLY_VEGETARIAN);

        session.removeAllContext();

        assertEquals(Arrays.asList(ContextItem.term(MiniPizza.iri("VegetarianPizza"), true)),
                session.context());
    }

    @Test
    void aRequestTooLargeSaysRemoveAll() {
        session.setConnection(ollama.withMaxOutputTokens(100).withContextLength(110), "");

        session.send("What is it?");

        assertEquals(0, session.turns().size());
        assertTrue(heard.notices.get(heard.notices.size() - 1).contains("Remove all"),
                heard.notices.toString());
    }

    @Test
    void thePreviewIsWhatWouldBeSent() {
        String shown = session.preview("Is this right?").describe(ollama);

        assertTrue(shown.startsWith("To: localhost (this machine)"), shown);
        assertTrue(shown.contains("[you]\nIs this right?"), shown);
        assertTrue(shown.contains("[context: vegetarian pizza]"), shown);
    }
}
