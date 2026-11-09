package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A search puts the pattern whose name says it first, so the row the browser selects is the one
 * that was meant.
 *
 * <p>Found by three testers and two scenario builders: a search for {@code place} selected a
 * pattern about material entities, with <i>Place Pattern</i> tenth, and the study's own searches -
 * {@code measured}, {@code dataset}, {@code participation} - put another pattern in front of the
 * one the guide named.
 */
class PatternSearchOrderTest {

    private static String firstFor(String query) {
        List<DesignPattern> ordered = PatternLibrary.nameMatchesFirst(
                PatternLibrary.matching(query), query);
        return ordered.get(0).getId();
    }

    @Test
    void theStudysSearchesFindTheGuidesPatternFirst() {
        assertEquals("pmdco-measurement-and-setpoint", firstFor("measured"));
        assertEquals("nfdi-dataset-description", firstFor("dataset"));
        assertEquals("participation", firstFor("participation"));
    }

    @Test
    void aWordOfTheNameBeatsADescription() {
        assertEquals("Place Pattern", PatternLibrary.nameMatchesFirst(
                PatternLibrary.matching("place"), "place").get(0).getName());
    }

    @Test
    void theRanksAreAsDocumented() {
        String[] place = {"place"};
        assertEquals(0, PatternLibrary.nameRank("Place Pattern", place));
        assertEquals(1, PatternLibrary.nameRank("Smarthome Place Pattern", place));
        assertEquals(2, PatternLibrary.nameRank("Measurement and setpoint", new String[] {"measured"}));
        assertEquals(3, PatternLibrary.nameRank("Pattern 6: Value Specification", new String[] {"measured"}));
        assertEquals(0, PatternLibrary.nameRank("Pattern 6: Value Specification",
                new String[] {"value", "specification"}));
    }
}
