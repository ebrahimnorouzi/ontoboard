package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The automatic scores, stated as what a reader can check by eye. */
class ScorersTest {

    private static final Set<String> KNOWN = new HashSet<String>(Arrays.asList(
            "http://example.org/mini-pizza#Pizza"));

    private static BenchCase explain(List<String> terms, List<String> clash) {
        return new BenchCase("e1", BenchCase.EXPLAIN, "o.owl", "x", "unsatisfiable",
                Collections.<String>emptyList(), false, "Ontology: o",
                Collections.<RenderedItem>emptyList(), "Why?", terms, clash,
                Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    private static BenchCase request(boolean control) {
        return new BenchCase(control ? "c2" : "c1", BenchCase.CONTEXT, "o.owl", "x", null,
                Collections.<String>emptyList(), control, "Ontology: o",
                Collections.<RenderedItem>emptyList(), "Which?", Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                control ? Collections.<String>emptyList() : Arrays.asList("vegetarian topping"),
                control ? Collections.<String>emptyList()
                        : Arrays.asList("http://example.org/mini-pizza#VegetarianTopping"));
    }

    @Test
    void namingTheClashAndHalfTheTermsPasses() {
        Score score = Scorers.score(explain(Arrays.asList("bench fault 1", "VegetarianTopping",
                "MeatTopping"), Arrays.asList("VegetarianTopping", "MeatTopping")),
                "Vegetarian topping and meat topping are disjoint.", KNOWN);

        assertEquals(1.0, score.getValues().get("namesClash"), 0.0);
        assertEquals(2.0 / 3, score.getValues().get("termRecall"), 0.001);
        assertTrue(score.isPass());
    }

    @Test
    void missingOneSideOfTheClashFails() {
        Score score = Scorers.score(explain(Arrays.asList("VegetarianTopping", "MeatTopping"),
                Arrays.asList("VegetarianTopping", "MeatTopping")),
                "It is about vegetarian toppings.", KNOWN);

        assertEquals(0.0, score.getValues().get("namesClash"), 0.0);
        assertFalse(score.isPass());
    }

    /** Review #5a: "is it explained" does not name "site", and "pro legal" does not name "role". */
    @Test
    void aLabelInsideOtherWordsIsNotNamed() {
        Score score = Scorers.score(explain(Arrays.asList("site", "role"),
                Arrays.asList("site", "role")), "It is explained by a pro legal argument.", KNOWN);

        assertEquals(0.0, score.getValues().get("namesClash"), 0.0);
        assertEquals(0.0, score.getValues().get("termRecall"), 0.0);
    }

    @Test
    void aShortNameAndItsLabelAreOneName() {
        Score score = Scorers.score(explain(Arrays.asList("VegetarianTopping"),
                Arrays.asList("VegetarianTopping")), "The vegetarian-topping class.", KNOWN);

        assertEquals(1.0, score.getValues().get("namesClash"), 0.0);
    }

    @Test
    void askingForTheRightTermPasses() {
        Score score = Scorers.score(request(false),
                "I need to see it.\n```ontoboard-need\nVegetarianTopping\n```", KNOWN);

        assertEquals(1.0, score.getValues().get("asked"), 0.0);
        assertEquals(1.0, score.getValues().get("askedRight"), 0.0);
        assertTrue(score.isPass());
    }

    @Test
    void askingForTheWrongTermFails() {
        Score score = Scorers.score(request(false), "```ontoboard-need\nhasTopping\n```", KNOWN);

        assertEquals(1.0, score.getValues().get("asked"), 0.0);
        assertEquals(0.0, score.getValues().get("askedRight"), 0.0);
        assertFalse(score.isPass());
    }

    @Test
    void answeringWithoutAskingFails() {
        assertFalse(Scorers.score(request(false), "There are three kinds.", KNOWN).isPass());
    }

    @Test
    void aControlThatAsksFails() {
        assertFalse(Scorers.score(request(true), "```ontoboard-need\nx\n```", KNOWN).isPass());
        assertTrue(Scorers.score(request(true), "Cheese topping is one kind.", KNOWN).isPass());
    }

    @Test
    void inventedIrisAreCounted() {
        Score score = Scorers.score(request(true), "See http://example.org/mini-pizza#Pizza and"
                + " http://example.org/mini-pizza#Calzone. Also"
                + " http://example.org/ontoboard-bench#BenchFault1.", KNOWN);

        assertEquals(1.0, score.getValues().get("inventedIris"), 0.0);
    }
}
