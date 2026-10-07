package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.pattern.PatternOrder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every ordering the pattern library implements is one its chooser offers.
 *
 * <p>The test that was missing. Two orderings shipped in 1.87.0 implemented, tested, documented
 * and absent from the combo box. {@code PatternOrder.BY_COLLECTION} was written precisely to
 * answer "show me the MWO patterns" once there was more than one collection. "Suggested for this
 * ontology" was the whole recommender - 347 lines, its two signals chosen by measuring against
 * the real ontology, described in {@code docs/odk-workflow.md} as "the browser's first ordering".
 * Neither could be selected by anybody, because the combo box was built from a literal array
 * written before either existed.
 *
 * <p>Nothing caught it because the two halves have no reason to be read together: one class knows
 * how to sort, another knows what to show. Comparing them is the only way this kind of mistake
 * gets found, so it is compared by reflection over {@link PatternOrder}'s constants rather than
 * against a list written here - a new ordering added and not offered fails, which is exactly what
 * happened.
 */
class PatternLibraryOrderingsTest {

    /** Each {@code BY_*} constant, read off the class rather than listed again here. */
    private static List<String> implemented() throws Exception {
        List<String> orderings = new ArrayList<String>();
        for (Field field : PatternOrder.class.getDeclaredFields()) {
            if (field.getName().startsWith("BY_") && field.getType() == String.class) {
                orderings.add((String) field.get(null));
            }
        }
        return orderings;
    }

    /** Nothing that can be sorted by is missing from the chooser. */
    @Test
    void everyOrderingIsOffered() throws Exception {
        List<String> offered = Arrays.asList(PatternLibraryAction.ORDERINGS);
        List<String> implemented = implemented();

        assertEquals(4, implemented.size(),
                "four orderings exist, or this test is reading the wrong fields: " + implemented);
        for (String ordering : implemented) {
            assertTrue(offered.contains(ordering),
                    ordering + " is implemented and the chooser does not offer it");
        }
    }

    /** And the recommender, which is not one of them, is reachable too. */
    @Test
    void theSuggestionsAreReachable() throws Exception {
        List<String> offered = Arrays.asList(PatternLibraryAction.ORDERINGS);

        assertTrue(offered.contains(PatternLibraryAction.SUGGESTED),
                "the recommender has no other way in: " + offered);
        assertEquals(PatternLibraryAction.SUGGESTED, offered.get(0),
                "suggestions first; the docs say so and 159 patterns mean it");
    }

    /** And nothing is offered that nothing can sort. */
    @Test
    void nothingIsOfferedThatDoesNotWork() throws Exception {
        List<String> offered = Arrays.asList(PatternLibraryAction.ORDERINGS);

        assertEquals(implemented().size() + 1, offered.size(),
                "an ordering nobody sorts by would silently fall back to name: " + offered);
    }
}
