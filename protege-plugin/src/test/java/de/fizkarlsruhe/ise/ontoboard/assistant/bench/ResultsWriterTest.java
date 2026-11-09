package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/** The summary a reader quotes from. */
class ResultsWriterTest {

    private static Score score(String column, double value, boolean pass) {
        LinkedHashMap<String, Double> values = new LinkedHashMap<String, Double>();
        values.put(column, value);
        return new Score(values, pass);
    }

    /**
     * Review #5c: askedRight is always 0 on a control, so averaging controls with held-back cases
     * caps the mean below 1 for a perfect model. They get a table each.
     */
    @Test
    void controlsAreSummarisedApart() {
        String summary = ResultsWriter.summary(Arrays.asList(
                new ResultsWriter.Row("c1", BenchCase.CONTEXT, false, "m", 1,
                        score("askedRight", 1.0, true), 10, 1, 1, null),
                new ResultsWriter.Row("k1", BenchCase.CONTEXT, true, "m", 1,
                        score("askedRight", 0.0, true), 10, 1, 1, null)), "test");

        assertTrue(summary.contains("## context-request\n"), summary);
        assertTrue(summary.contains("## context-request (controls)\n"), summary);
        assertTrue(summary.contains("| m | 1 | 1 | 1/1 (100%) | 1.00 |"), summary);
    }

    /**
     * Review 1.148.0 bench-01 and bench-03: summary.md called validProposals "the share of
     * proposals OntoBoard would offer", where an offered comment card or a repeated removal
     * counts against it, and collateral "other axioms removed", where for a problem the ontology
     * already had it counts the removals that were not needed.
     */
    @Test
    void theRepairColumnsAreDescribedAsTheyAreCounted() {
        String summary = ResultsWriter.summary(Arrays.asList(
                new ResultsWriter.Row("r1", BenchCase.REPAIR, false, "m", 1,
                        score("validProposals", 0.5, false), 10, 1, 1, null)), "test");

        // Review 1.149.0 bench-04 and bench-06: an addition that changes nothing counts against
        // too, and an unsolved run of a problem the ontology had counts the removals outside
        // its justifications; review 1.150.0 bench-01: every justification the loop showed.
        assertTrue(summary.contains("removedFault (what was removed is the injected axiom, or an"
                + " axiom of a justification the loop showed)"), summary);
        assertTrue(summary.contains("collateral (other axioms removed; for a problem the"
                + " ontology already had, the removals that were not needed, or, when it was not"
                + " solved, the removals outside every justification the loop showed)"), summary);
        assertTrue(summary.contains("validProposals (over all rounds, the share of the answers'"
                + " blocks that changed the logic: removals and replacements applied, and"
                + " additions; annotation cards, blocks shown as Cannot be applied, and a removal"
                + " or an addition that changes nothing count against it)"), summary);
        org.junit.jupiter.api.Assertions.assertFalse(summary.contains("OntoBoard would offer:"),
                summary);
    }
}
