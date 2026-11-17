package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Git... as it says what it did. */
class GitActionTest {

    /** One file is said as one: "Committed 1 files." was 1.153.0's (its minor 12). */
    @Test
    void oneCommittedFileIsSaidAsOne() {
        assertEquals("Committed 1 file.", GitAction.committed(1));
        assertEquals("Committed 3 files.", GitAction.committed(3));
    }
}
