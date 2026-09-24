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

        assertEquals(4, result.getChecks().size(), "expected four checks: " + result.getChecks());
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

        assertEquals("OntoBoard self-check: PASS 4/4", result.summary());
        assertTrue(result.summary().startsWith("OntoBoard self-check: "),
                "tools/smoke.ps1 matches this prefix");
    }
}
