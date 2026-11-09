package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** "Save as Markdown": the conversation, with what it was about and which model answered. */
class MarkdownExportTest {

    @Test
    void theConversationIsWrittenWithItsContextAndNoKey() {
        Connection claude = Connection.of("Claude", ConnectionKind.ANTHROPIC).withModel("claude-x")
                .withKeyVariable("ANTHROPIC_API_KEY");
        Turn answered = new Turn("Why is it unsatisfiable?",
                Arrays.asList("vegetarian pizza", "Why Bad is unsatisfiable"), "Claude");
        answered.append("Because of the disjointness.");
        answered.finish(new Reply("Because of the disjointness.", 10, 5, "end_turn", 900));
        Turn stopped = new Turn("And now?", Arrays.asList("vegetarian pizza"), "Claude");
        stopped.append("Partly");
        stopped.fail("Stopped.");

        String markdown = MarkdownExport.of("Mini pizza", claude, "Ontology expert",
                Arrays.asList(answered, stopped));

        assertTrue(markdown.startsWith("# Conversation about Mini pizza\n"), markdown);
        assertTrue(markdown.contains("claude-x"), markdown);
        assertTrue(markdown.contains("Ontology expert"), markdown);
        assertTrue(markdown.contains("Why is it unsatisfiable?"), markdown);
        assertTrue(markdown.contains("Context: vegetarian pizza, Why Bad is unsatisfiable"),
                markdown);
        assertTrue(markdown.contains("Because of the disjointness."), markdown);
        assertTrue(markdown.contains("Partly"), markdown);
        assertTrue(markdown.contains("Stopped."), markdown);
        assertFalse(markdown.contains("sk-"), markdown);
        assertFalse(markdown.contains("ANTHROPIC_API_KEY"), markdown);
    }
}
