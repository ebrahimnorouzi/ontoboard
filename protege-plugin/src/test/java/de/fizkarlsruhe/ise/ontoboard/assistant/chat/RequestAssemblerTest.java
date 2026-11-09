package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Message;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One request, assembled the same way for the plugin and the benchmark; and what it shows. */
class RequestAssemblerTest {

    private static final List<RenderedItem> CONTEXT = Arrays.asList(
            new RenderedItem("vegetarian pizza", "Class: vegetarian pizza\n  IRI: x\n"),
            new RenderedItem("cheese topping", "Class: cheese topping\n"));

    private static Turn finished(String message, String reply) {
        Turn turn = new Turn(message, Collections.<String>emptyList(), "Claude");
        turn.append(reply);
        turn.finish(new Reply(reply, 1, 1, "end_turn", 5));
        return turn;
    }

    private static Assembled assemble(List<Turn> history, String message) {
        return RequestAssembler.assemble("ROLE TEXT", "FIXED RULES", "Ontology: http://o", CONTEXT,
                history, message, 2048);
    }

    @Test
    void theSystemTextIsRoleRulesHeaderThenContext() {
        String system = assemble(Collections.<Turn>emptyList(), "q").getRequest().getSystem();

        int role = system.indexOf("ROLE TEXT");
        int rules = system.indexOf("FIXED RULES");
        int header = system.indexOf("[ontology]\nOntology: http://o");
        int first = system.indexOf("[context: vegetarian pizza]");
        int second = system.indexOf("[context: cheese topping]");
        assertTrue(role == 0 && role < rules && rules < header && header < first
                && first < second, system);
    }

    @Test
    void historyBecomesAlternatingMessages() {
        Assembled assembled = assemble(Arrays.asList(finished("q1", "a1"), finished("q2", "a2")),
                "q3");

        List<Message> messages = assembled.getRequest().getMessages();
        assertEquals(5, messages.size());
        assertEquals("q1", messages.get(0).getText());
        assertEquals(Message.Role.ASSISTANT, messages.get(1).getRole());
        assertEquals("a2", messages.get(3).getText());
        assertEquals("q3", messages.get(4).getText());
        assertEquals(Message.Role.USER, messages.get(4).getRole());
        assertEquals(2048, assembled.getRequest().getMaxOutputTokens());
    }

    @Test
    void failedTurnsAreNotResent() {
        Turn stopped = new Turn("q1", Collections.<String>emptyList(), "Claude");
        stopped.append("half");
        stopped.fail("Stopped.");

        List<Message> messages =
                assemble(Arrays.asList(stopped, finished("q2", "a2")), "q3").getRequest()
                        .getMessages();

        assertEquals(3, messages.size());
        assertEquals("q2", messages.get(0).getText());
    }

    @Test
    void theOldestTurnsAreDroppedPastTheBudgetAndCounted() {
        StringBuilder long4000 = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            long4000.append('w');
        }
        List<Turn> history = new ArrayList<Turn>();
        for (int i = 0; i < 10; i++) {
            history.add(finished("question " + i + " " + long4000, "answer " + i));
        }

        Assembled assembled = assemble(history, "now");

        assertTrue(assembled.getDroppedTurns() > 0);
        assertTrue(assembled.getRequest().getMessages().get(0).getText()
                .startsWith("question " + assembled.getDroppedTurns()), "the oldest went first");
        Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC);
        assertTrue(assembled.describe(claude).contains(assembled.getDroppedTurns()
                + " earlier turns left out to stay within the budget."));
    }

    @Test
    void describeShowsTheDestinationAndNeverTheKey() {
        Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC).withModel("claude-x");

        String shown = assemble(Arrays.asList(finished("q1", "a1")), "q2").describe(claude);

        assertTrue(shown.startsWith("To: api.anthropic.com (cloud)\n"), shown);
        assertTrue(shown.contains("Model: claude-x"), shown);
        assertTrue(shown.contains("Sign-in: x-api-key: (your key)"), shown);
        assertTrue(shown.contains("About ~"), shown);
        assertTrue(shown.contains("ROLE TEXT"), shown);
        assertTrue(shown.contains("[context: cheese topping]"), shown);
        assertTrue(shown.contains("[you]\nq2"), shown);
        assertTrue(shown.contains("[assistant]\na1"), shown);
        assertTrue(Connection.of("o", ConnectionKind.OPENAI_COMPATIBLE).getKind() != null);
        assertTrue(assemble(Collections.<Turn>emptyList(), "q")
                .describe(Connection.of("o", ConnectionKind.OPENAI_COMPATIBLE))
                .contains("Sign-in: Authorization: Bearer (your key)"));
    }

    @Test
    void anOllamaConnectionHasNoSignIn() {
        String shown = assemble(Collections.<Turn>emptyList(), "q")
                .describe(Connection.of("Ollama", ConnectionKind.OLLAMA));

        assertTrue(shown.startsWith("To: localhost (this machine)\n"), shown);
        assertTrue(shown.contains("Sign-in: none"), shown);
        assertFalse(shown.contains("(your key)"), shown);
    }

    @Test
    void theEstimateCountsEverythingSent() {
        Assembled assembled = assemble(Collections.<Turn>emptyList(), "q");

        assertTrue(assembled.getEstimatedTokens()
                >= assembled.getRequest().getSystem().length() / 4);
    }
}
