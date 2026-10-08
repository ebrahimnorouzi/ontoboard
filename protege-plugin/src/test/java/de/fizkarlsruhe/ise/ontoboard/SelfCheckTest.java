package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

        assertEquals(9, result.getChecks().size(), "expected nine checks: " + result.getChecks());
        assertTrue(result.isPassed(), "the self-check must pass where the resources are reachable, "
                + "otherwise it cannot distinguish a bundle problem from its own bug: "
                + result.getChecks());
    }

    /** The rule count is the check most likely to drift, so it is stated and asserted. */
    @Test
    void theProfileCheckCountsRobotsOwnRules() {
        SelfCheck.Check profile = checkNamed("robot report profile readable");

        assertTrue(profile.isPassed(), profile.toString());
        assertTrue(profile.getDetail().startsWith(SelfCheck.EXPECTED_RULES + " rules"),
                "robot-core 1.9.8 ships " + SelfCheck.EXPECTED_RULES + " report rules; a change "
                        + "here means a robot-core upgrade moved them: " + profile.getDetail());
    }

    /** The export must produce rows in the host, where POI and IOHelper could fail and nowhere else. */
    @Test
    void theExportCheckProducesTerms() {
        SelfCheck.Check export = checkNamed("robot export runs end to end");

        assertTrue(export.isPassed(), export.toString());
        assertFalse(export.getDetail().startsWith("0 terms"), export.getDetail());
    }

    /**
     * Writing an export is a different path from building one, and it had its own bug.
     *
     * <p>Until 1.48.0 the export check stopped at building a table. The writing was handed ROBOT's
     * format name where ROBOT wants the string that separates two values in one cell, so a
     * multi-valued cell came out as {@code pizza basetsvpizza topping} - in the written file and in
     * the dialog. Nothing threw, which is why only a comparison against the real command line found
     * it.
     */
    @Test
    void theExportSeparatorCheckWritesABar() {
        SelfCheck.Check separator = checkNamed("robot export writes ROBOT's cell separator");

        assertTrue(separator.isPassed(), separator.toString());
    }

    /** The explanation path must produce a justification, not merely fail to throw. */
    @Test
    void theExplainCheckProducesAJustification() {
        SelfCheck.Check explain = checkNamed("robot explain runs end to end");

        assertTrue(explain.isPassed(), explain.toString());
        assertFalse(explain.getDetail().startsWith("0 "), explain.getDetail());
    }

    /**
     * Every class plugin.xml names loads and constructs.
     *
     * <p>Green here proves only that they resolve on Maven's classpath - which
     * {@code PluginXmlTest} already knew. The value of this check is where it runs: under Felix, an
     * action that references a Protege type the bundle never imported loads in a test and dies on
     * click.
     */
    @Test
    void theMenuCheckLoadsEveryDeclaredClass() {
        SelfCheck.Check menu = checkNamed("menu classes resolve");

        assertTrue(menu.isPassed(), menu.toString());
        assertTrue(menu.getDetail().contains("classes named in plugin.xml loaded"),
                menu.getDetail());
        assertFalse(menu.getDetail().startsWith("0/"), menu.getDetail());
    }

    /**
     * Every view plugin.xml registers is named, so the self-test knows what to insist on.
     *
     * <p>The self-test opens the OntoBoard tab and asks {@code ViewHealth} what built. Until
     * 1.113.0 it only required that <em>something</em> had, which was enough while there was
     * one view and useless the moment there were two: Protege constructs the visible tab of a
     * tabbed group and not the one behind it, so the sheet editor never built and the check
     * still passed. This is the list it compares against, and a view added to plugin.xml and
     * forgotten here cannot happen - the list is read from the extension points.
     */
    @Test
    void everyRegisteredViewIsNamedSoTheSelfTestCanInsistOnIt() throws Exception {
        java.util.List<String> views = SelfCheck.declaredViewNames();

        assertTrue(views.contains("SchemaCanvasView"), views.toString());
        assertTrue(views.contains("SheetEditorView"),
                "a view that nothing insists on is one nobody has seen work: " + views);
        assertFalse(views.contains("OntoBoardTab"),
                "that is a workspace tab, not a view component: " + views);
        for (String name : views) {
            assertFalse(name.contains("."),
                    "simple names are what ViewHealth reports: " + name);
        }
    }

    /** An empty report is the failure mode this whole check exists to catch, so 0 must not pass. */
    @Test
    void anEmptyReportIsAFailureNotGoodNews() {
        SelfCheck.Check endToEnd = checkNamed("robot report runs end to end");

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

        assertEquals("OntoBoard self-check: PASS 9/9", result.summary());
        assertTrue(result.summary().startsWith("OntoBoard self-check: "),
                "tools/smoke.ps1 matches this prefix");
    }

    /**
     * A check by name rather than by position.
     *
     * <p>These were indexed by position until 1.48.0, which meant inserting a check in the middle
     * silently repointed four tests at their neighbours - they kept passing while testing the wrong
     * thing. A name that no longer exists fails here instead, and names the alternatives.
     */
    private static SelfCheck.Check checkNamed(String name) {
        for (SelfCheck.Check check : SelfCheck.run().getChecks()) {
            if (name.equals(check.getName())) {
                return check;
            }
        }
        StringBuilder available = new StringBuilder();
        for (SelfCheck.Check check : SelfCheck.run().getChecks()) {
            available.append("\n  ").append(check.getName());
        }
        fail("no self-check named \"" + name + "\". The checks are:" + available);
        return null;
    }
}
