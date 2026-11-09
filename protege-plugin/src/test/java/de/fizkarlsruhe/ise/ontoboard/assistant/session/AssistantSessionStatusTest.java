package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
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
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * What the status line says (maintainer's request: show that the model is generating, and what
 * it is doing now): sending, waiting, writing, checking, done - and stopped or failed.
 */
class AssistantSessionStatusTest {

    private static final Executor INLINE = Runnable::run;

    private final OWLOntology pizza = MiniPizza.load();
    private final AssistantSessionTest.ScriptedClient client =
            new AssistantSessionTest.ScriptedClient();
    private final List<String> statuses = new ArrayList<String>();
    private final List<Boolean> working = new ArrayList<Boolean>();
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
                return MiniPizza.iri("VegetarianPizza");
            }

            @Override
            public File ontologyFile() {
                return null;
            }
        }, INLINE, INLINE);
        session.setClientFactory((connection, key) -> client);
        session.setConnection(Connection.of("Ollama", ConnectionKind.OLLAMA)
                .withModel("llama3.1"), "");
        session.addListener(new SessionListener() {
            @Override
            public void onStatus(String text, boolean busy) {
                statuses.add(text);
                working.add(busy);
            }
        });
    }

    private String last() {
        return statuses.get(statuses.size() - 1);
    }

    @Test
    void aTurnGoesFromSendingToWaitingToWritingToDone() {
        client.deltas = Arrays.asList("A vegetarian ", "pizza.");

        session.send("What is it?");

        assertTrue(statuses.get(0).startsWith("Sending ~"), statuses.toString());
        assertTrue(statuses.get(0).endsWith(" tokens to localhost…"), statuses.toString());
        assertEquals("Waiting for llama3.1…", statuses.get(1));
        assertTrue(statuses.contains("Writing… 2 words"), statuses.toString());
        assertTrue(statuses.contains("Writing… 3 words"), statuses.toString());
        assertTrue(last().startsWith("Done in "), statuses.toString());
        assertTrue(working.get(0));
        assertFalse(working.get(working.size() - 1));
    }

    @Test
    void aStoppedAnswerSaysStopped() {
        client.deltas = Arrays.asList("Half");
        client.failure = new ModelCallException(ModelCallException.Kind.STOPPED, "Stopped.");

        session.send("q");

        assertEquals("Stopped.", last());
        assertFalse(working.get(working.size() - 1));
    }

    @Test
    void aFailedAnswerSaysWhy() {
        client.failure = new ModelCallException(ModelCallException.Kind.UNREACHABLE,
                "Nothing answers at localhost:11434.");

        session.send("q");

        assertEquals("Failed: Nothing answers at localhost:11434.", last());
    }

    @Test
    void aRemovalIsCheckedBeforeDone() {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        OWLAxiom disjoint = factory.getOWLDisjointClassesAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")),
                factory.getOWLClass(MiniPizza.iri("MeatTopping")));
        session.setReasoner(Reasoners.Choice.HERMIT.newFactory(), "HermiT");
        client.deltas = Arrays.asList("```ontoboard-proposal\naction: remove-axiom\naxiom: "
                + AssistantRendering.render(pizza, disjoint) + "\n```");

        session.send("q");

        assertTrue(statuses.contains("Checking 1 proposal with HermiT…"), statuses.toString());
        assertTrue(last().startsWith("Done in "), statuses.toString());
    }
}
