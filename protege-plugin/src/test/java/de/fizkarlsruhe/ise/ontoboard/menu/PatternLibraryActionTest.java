package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternLibraryActionTest {

    /**
     * A search finds a pattern that is not among the suggestions.
     *
     * <p>The pizza walkthrough's three searches each listed nothing in a new project, because the
     * dialog opens on "Suggested for this ontology" and a search looked only among those.
     */
    @Test
    void aSearchFindsWhatIsNotSuggested() {
        List<DesignPattern> none = Collections.<DesignPattern>emptyList();
        for (String[] search : new String[][] {
            {"components", "componency"}, {"classifies", "classification"}, {"cost", "price"}}) {
            List<DesignPattern> shown = PatternLibraryAction.suggestedThenTheRest(none,
                    PatternLibrary.matching(PatternLibrary.all(), search[0]), true);
            assertTrue(idsOf(shown).contains(search[1]), search[0] + " lists " + idsOf(shown));
        }
    }

    /** The suggestions still come first, each once, and with nothing typed they are all there is. */
    @Test
    void theSuggestionsComeFirst() {
        DesignPattern price = PatternLibrary.find("price");
        List<DesignPattern> matching = new ArrayList<DesignPattern>(PatternLibrary.all());

        List<DesignPattern> shown = PatternLibraryAction.suggestedThenTheRest(
                Arrays.asList(price), matching, true);
        assertEquals("price", shown.get(0).getId());
        assertEquals(matching.size(), shown.size(), "nothing listed twice");

        assertEquals(Arrays.asList(price), PatternLibraryAction.suggestedThenTheRest(
                Arrays.asList(price), matching, false));
    }

    private static List<String> idsOf(List<DesignPattern> patterns) {
        List<String> ids = new ArrayList<String>();
        for (DesignPattern pattern : patterns) {
            ids.add(pattern.getId());
        }
        return ids;
    }
}
