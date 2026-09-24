package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The self-check's own behaviour.
 *
 * <p>Note what a green run here does and does not mean. It means the check's logic is right and its
 * verdict wording is what {@code tools/smoke.ps1} matches. It does <em>not</em> mean the report works
 * inside a bundle - that is the whole reason {@link SelfCheck} exists, and only a running Protege can
 * answer it. Maven runs against an exploded classpath where the resource lookups that fail under
 * Felix succeed.
 */
class SelfCheckTest {

    @Test
    void everyCheckPassesOnTheClasspath() {
        SelfCheck.Result result = SelfCheck.run();

        assertEquals(6, result.getChecks().size(), "expected six checks: " + result.getChecks());
        assertTrue(result.isPassed(), "the self-check must pass where the resources are reachable, "
                + "otherwise it cannot distinguish a bundle problem from its own bug: "
                + result.getChecks());
    }

    /** The rule count is the check most likely to drift, so it is stated and asserted. */
    @Test
    void theProfileCheckCountsRobotsOwnRules() {
        SelfCheck.Check profile = SelfCheck.run().getChecks().get(0);

        assertTrue(profile.isPassed(), profile.toString());
        assertTrue(profile.getDetail().startsWith(SelfCheck.EXPECTED_RULES + " rules"),
                "robot-core 1.9.8 ships " + SelfCheck.EXPECTED_RULES + " report rules; a change "
                        + "here means a robot-core upgrade moved them: " + profile.getDetail());
    }

    /** The export must produce rows in the host, where POI and IOHelper could fail and nowhere else. */
    @Test
    void theExportCheckProducesTerms() {
        SelfCheck.Check export = SelfCheck.run().getChecks().get(4);

        assertTrue(export.isPassed(), export.toString());
        assertFalse(export.getDetail().startsWith("0 terms"), export.getDetail());
    }

    /** The explanation path must produce a justification, not merely fail to throw. */
    @Test
    void theExplainCheckProducesAJustification() {
        SelfCheck.Check explain = SelfCheck.run().getChecks().get(5);

        assertTrue(explain.isPassed(), explain.toString());
        assertFalse(explain.getDetail().startsWith("0 "), explain.getDetail());
    }

    /** An empty report is the failure mode this whole check exists to catch, so 0 must not pass. */
    @Test
    void anEmptyReportIsAFailureNotGoodNews() {
        SelfCheck.Check endToEnd = SelfCheck.run().getChecks().get(3);

        assertTrue(endToEnd.isPassed(), endToEnd.toString());
        assertFalse(endToEnd.getDetail().startsWith("0 findings"),
                "the check ontology has no labels, no title and no licence, so a working report "
                        + "must find something: " + endToEnd.getDetail());
    }

    /**
     * The summary wording is an interface.
     *
     * <p>{@code tools/smoke.ps1} greps for it and the release receipts record it, so a reworded
     * summary would silently turn a failing host into a passing one.
     */
    @Test
    void theSummaryIsTheLineTheSmokeScriptMatches() {
        SelfCheck.Result result = SelfCheck.run();

        assertEquals("OntoBoard self-check: PASS 6/6", result.summary());
        assertTrue(result.summary().startsWith("OntoBoard self-check: "),
                "tools/smoke.ps1 matches this prefix");
    }
}
