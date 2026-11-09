package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The model asking for context instead of guessing (spec D7). */
class NeedBlockTest {

    @Test
    void namesComeOutInOrder() {
        String reply = "I cannot tell without the toppings.\n\n```ontoboard-need\nhasTopping\n"
                + "cheese topping\n```\n";

        assertEquals(Arrays.asList("hasTopping", "cheese topping"), NeedBlock.parse(reply));
    }

    @Test
    void twoBlocksAreMergedAndDuplicatesDropped() {
        String reply = "```ontoboard-need\na\nb\n```\nmore\n```ontoboard-need\nb\nc\n```";

        assertEquals(Arrays.asList("a", "b", "c"), NeedBlock.parse(reply));
    }

    @Test
    void bulletsQuotesAndBlankLinesAreTidied() {
        String reply = "```ontoboard-need\n- `hasTopping`\n\n* \"cheese topping\"\n```";

        assertEquals(Arrays.asList("hasTopping", "cheese topping"), NeedBlock.parse(reply));
    }

    @Test
    void anUnclosedBlockAtTheEndStillCounts() {
        assertEquals(Arrays.asList("hasTopping"),
                NeedBlock.parse("Text.\n```ontoboard-need\nhasTopping\n"));
    }

    @Test
    void otherFencesAreIgnored() {
        assertEquals(Collections.emptyList(),
                NeedBlock.parse("```sparql\nSELECT * WHERE { ?s ?p ?o }\n```"));
    }

    @Test
    void withoutBlocksLeavesTheProse() {
        String prose = NeedBlock.withoutBlocks(
                "I need more.\n\n```ontoboard-need\nhasTopping\n```\n");

        assertEquals("I need more.", prose);
        assertTrue(NeedBlock.withoutBlocks("```sparql\nx\n```").contains("```sparql"));
    }
}
