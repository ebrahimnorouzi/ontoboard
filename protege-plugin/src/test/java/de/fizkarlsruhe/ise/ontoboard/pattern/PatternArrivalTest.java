package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
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
}
