package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Profile... as it says what it found. */
class ProfileActionTest {

    /**
     * The reasoner is named with the article its letters are said with - an EL, a QL, an RL:
     * "one a EL reasoner" was 1.153.0's (its minor 7).
     */
    @Test
    void theReasonerIsNamedWithItsArticle() {
        assertEquals("Every axiom is one an EL reasoner can act on.",
                ProfileAction.everyAxiom("EL"));
        assertEquals("Every axiom is one a QL reasoner can act on.",
                ProfileAction.everyAxiom("QL"));
        assertEquals("Every axiom is one an RL reasoner can act on.",
                ProfileAction.everyAxiom("RL"));
    }
}
