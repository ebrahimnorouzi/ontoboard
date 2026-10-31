package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PatternArrivalTest {

    @AfterEach
    void forget() {
        PatternArrival.forget();
    }

    /**
     * The canvas looks before it takes: a refresh that comes before the imported terms exist must
     * leave the note for the refresh that comes after them.
     */
    @Test
    void peekingLeavesTheNoteForLater() {
        PatternArrival.imported("Part of", Arrays.asList("http://example.org/p#Part"));

        PatternArrival.Arrival seen = PatternArrival.peek();
        assertEquals("Part of", seen.getLabel());
        assertSame(seen, PatternArrival.peek(), "peeking twice sees the same note");
        assertSame(seen, PatternArrival.take(), "and taking it gets that note");
        assertNull(PatternArrival.peek());
        assertFalse(PatternArrival.isWaiting());
    }

    /**
     * Two patterns imported while the board is closed both wait for it.
     *
     * <p>Measured in Protege 5.6.9 on a Mac before this: Componency then Classification imported
     * from the Entities tab, the board opened afterwards, and only Classification was framed -
     * Componency was not on the board at all, because the second note had replaced the first.
     */
    @Test
    void twoImportsWithTheBoardClosedBothWait() {
        PatternArrival.imported("pizza", "Componency",
                Arrays.asList("http://example.org/c#Object"));
        PatternArrival.imported("pizza", "Classification",
                Arrays.asList("http://example.org/k#Concept"));

        List<PatternArrival.Arrival> waiting = PatternArrival.waiting();
        assertEquals(2, waiting.size());
        assertEquals("Componency", waiting.get(0).getLabel(), "oldest first");
        assertEquals("Classification", waiting.get(1).getLabel());

        assertTrue(PatternArrival.take(waiting.get(1)), "one can be taken without the other");
        assertEquals("Componency", PatternArrival.peek().getLabel());
    }

    /** Importing a pattern again replaces its note: the later choice of terms is what went in. */
    @Test
    void importingAPatternAgainReplacesItsNote() {
        PatternArrival.imported("pizza", "Price", Arrays.asList("http://example.org/p#Price"));
        PatternArrival.imported("pizza", "Price",
                Arrays.asList("http://example.org/p#Price", "http://example.org/p#Currency"));

        List<PatternArrival.Arrival> waiting = PatternArrival.waiting();
        assertEquals(1, waiting.size());
        assertEquals(2, waiting.get(0).getIris().size());
    }

    /** A board that is never opened does not collect notes without end. */
    @Test
    void onlyTheMostRecentAreKept() {
        for (int at = 0; at < PatternArrival.HELD + 3; at++) {
            PatternArrival.imported("pizza", "Pattern " + at,
                    Arrays.asList("http://example.org/x#T" + at));
        }

        List<PatternArrival.Arrival> waiting = PatternArrival.waiting();
        assertEquals(PatternArrival.HELD, waiting.size());
        assertEquals("Pattern 3", waiting.get(0).getLabel(), "the oldest three were dropped");
    }

    /** A note is for the board of the ontology the pattern went into, and no other. */
    @Test
    void aNoteIsForTheOntologyItWentInto() {
        PatternArrival.imported("pizza", "Place", Arrays.asList("http://example.org/pl#Place"));
        PatternArrival.imported("Place", Arrays.asList("http://example.org/pl#Place"));

        List<PatternArrival.Arrival> waiting = PatternArrival.waiting();
        assertTrue(waiting.get(0).isFor("pizza"));
        assertFalse(waiting.get(0).isFor("another"), "another ontology's board leaves it alone");
        assertTrue(waiting.get(1).isFor("another"), "a note that does not say is for any board");
    }
}
