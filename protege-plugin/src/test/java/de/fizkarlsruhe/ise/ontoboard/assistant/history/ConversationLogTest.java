package de.fizkarlsruhe.ise.ontoboard.assistant.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalValidator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A conversation saved in the project (maintainer: "saved and annotated clearly, when, which
 * models used"): a header saying when, with which OntoBoard, about which ontology, with which
 * model and role; each turn with its time, context, model, time taken and tokens; and every
 * proposal with what the person did with it.
 */
class ConversationLogTest {

    @TempDir
    File dir;

    private final OWLOntology pizza = MiniPizza.load();
    private static final long STARTED = 1_793_000_000_000L;

    private final ConversationLog.Header header = new ConversationLog.Header(STARTED, "1.137.0",
            "http://example.org/mini-pizza", null, "Ollama", "ollama", "localhost",
            "qwen2.5:7b", "Ontology expert");

    private static final String REPLY = "The definition says what it lacks.\n"
            + "```ontoboard-proposal\naction: set\nsubject: vegetarian pizza\nproperty: definition\n"
            + "value: A pizza whose toppings are all vegetarian.\nreason: Names the genus.\n```";

    private Turn answered(String question, String reply) {
        Turn turn = new Turn(question, Arrays.asList("vegetarian pizza"), "Ollama",
                "ollama qwen2.5:7b");
        turn.append(reply);
        turn.finish(new Reply(reply, 1840, 96, "stop", 4200));
        turn.setProposals(new ProposalValidator(pizza).validateAll(ProposalBlock.parse(reply)));
        return turn;
    }

    private String read(ConversationLog log) throws IOException {
        return new String(Files.readAllBytes(log.getFile().toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void theHeaderSaysWhenWhatAndWithWhichModel() throws IOException {
        ConversationLog log = ConversationLog.start(new File(dir, "conversations"),
                "2026-11-06-143012-ollama", header);

        log.write(Arrays.asList(answered("Write a definition.", REPLY)),
                Collections.<String>emptyList(), proposal -> null);

        String text = read(log);
        assertEquals(new File(new File(dir, "conversations"), "2026-11-06-143012-ollama.md"),
                log.getFile());
        String started = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                .format(new Date(STARTED));
        assertTrue(text.contains("| Started | " + started + " |"), text);
        assertTrue(text.contains("| OntoBoard | 1.137.0 |"), text);
        assertTrue(text.contains("| Ontology | http://example.org/mini-pizza |"), text);
        assertTrue(text.contains("| Ontology version | (none) |"), text);
        assertTrue(text.contains("| Connection | Ollama (ollama) at localhost |"), text);
        assertTrue(text.contains("| Model | qwen2.5:7b |"), text);
        assertTrue(text.contains("| Role | Ontology expert |"), text);
    }

    @Test
    void eachTurnSaysItsContextModelTimeAndTokens() throws IOException {
        ConversationLog log = ConversationLog.start(dir, "t", header);

        log.write(Arrays.asList(answered("Write a definition.", REPLY)),
                Collections.<String>emptyList(), proposal -> null);

        String text = read(log);
        assertTrue(text.contains("Write a definition."), text);
        assertTrue(text.contains("Context: vegetarian pizza"), text);
        assertTrue(text.contains("qwen2.5:7b (ollama) · 4.2 s · 1,840 → 96 tokens"), text);
        assertTrue(text.contains("The definition says what it lacks."), text);
    }

    @Test
    void proposalsAreRecordedWithWhatWasDone() throws IOException {
        Turn turn = answered("Write a definition.", REPLY);
        Proposal offered = turn.getProposals().get(0);
        turn.replaceProposal(offered.withStatus(Proposal.Status.ACCEPTED));
        ConversationLog log = ConversationLog.start(dir, "t", header);

        log.write(Arrays.asList(turn), Collections.<String>emptyList(), proposal -> null);

        String text = read(log);
        assertTrue(text.contains("Set the definition of vegetarian pizza: accepted"), text);
        assertTrue(text.contains("was: A pizza with no meat."), text);
        assertTrue(text.contains("now: A pizza whose toppings are all vegetarian."), text);
        assertTrue(text.contains("why: Names the genus."), text);
    }

    /** 1.139.0: a replacement is recorded with the axiom it removes and the one it adds. */
    @Test
    void aReplacementIsRecordedWithBothAxioms() throws IOException {
        Turn turn = answered("Fix it.", "```ontoboard-proposal\naction: replace-axiom\n"
                + "axiom: 'cheese topping' SubClassOf VegetarianTopping\n"
                + "with: 'cheese topping' SubClassOf PizzaTopping\n```");
        ConversationLog log = ConversationLog.start(dir, "t", header);

        log.write(Arrays.asList(turn), Collections.<String>emptyList(), proposal -> null);

        String text = read(log);
        assertTrue(text.contains("Replace an axiom: not decided"), text);
        assertTrue(text.contains("removes: 'cheese topping' SubClassOf VegetarianTopping"), text);
        assertTrue(text.contains("adds: 'cheese topping' SubClassOf PizzaTopping"), text);
    }

    /** Review 1.139.0 I1: the log says what was written, under the ontology's names. */
    @Test
    void theLogRecordsTheAxiomAsWritten() throws IOException {
        Turn turn = answered("Cheese.", "```ontoboard-proposal\naction: add-axiom\n"
                + "axiom: 'veggie pizza' SubClassOf hasTopping some 'cheese topping'\n```");
        ConversationLog log = ConversationLog.start(dir, "t", header);

        log.write(Arrays.asList(turn), Collections.<String>emptyList(), proposal -> null);

        String text = read(log);
        assertTrue(text.contains("adds: 'vegetarian pizza' SubClassOf hasTopping some"
                + " 'cheese topping'"), text);
    }

    @Test
    void aKeyInAReplyIsNeverWritten() throws IOException {
        ConversationLog log = ConversationLog.start(dir, "t", header);

        log.write(Arrays.asList(answered("my key is sk-test-1234567890abcdef, is it safe?",
                "Do not share sk-test-1234567890abcdef.")),
                Arrays.asList("sk-test-1234567890abcdef"), proposal -> null);

        String text = read(log);
        assertFalse(text.contains("sk-test-1234567890abcdef"), text);
        assertTrue(text.contains("(your key)"), text);
    }

    @Test
    void eachWriteIsTheWholeConversationOnce() throws IOException {
        ConversationLog log = ConversationLog.start(dir, "t", header);
        Turn first = answered("First?", "One.");
        Turn second = answered("Second?", "Two.");

        log.write(Arrays.asList(first), Collections.<String>emptyList(), proposal -> null);
        log.write(Arrays.asList(first, second), Collections.<String>emptyList(),
                proposal -> null);

        String text = read(log);
        assertEquals(1, text.split("\\| OntoBoard \\|", -1).length - 1, text);
        assertTrue(text.indexOf("First?") < text.indexOf("Second?"), text);
    }

    @Test
    void theFileNameIsTheStartAndTheConnection() {
        String expected = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT)
                .format(new Date(STARTED)) + "-my-ollama";

        assertEquals(expected, ConversationLog.stem(STARTED, "My Ollama!"));
    }
}
