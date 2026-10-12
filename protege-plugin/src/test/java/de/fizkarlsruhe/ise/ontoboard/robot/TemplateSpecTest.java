package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Whether ROBOT will accept a template row.
 *
 * <p>The important test here is {@link #everyVerdictAgreesWithRobotsOwnValidator}, which does not
 * trust the transcription at all: it asks {@code TemplateHelper.validateTemplateString} - the
 * real method, out of the robot-core jar this plugin embeds - for the same strings and requires
 * the same answer. A transcription pinned against a copy of itself proves nothing, and this one
 * decides whether somebody's whole sheet builds.
 */
class TemplateSpecTest {

    /**
     * Strings worth asking about: every form robot-core accepts, every near-miss a person types,
     * and the four that {@link TemplateColumns} lets through but ROBOT does not.
     */
    private static final List<String> CORPUS = Arrays.asList(
            // the eight bare keywords
            "ID", "LABEL", "CLASS_TYPE", "PROPERTY_TYPE", "INDIVIDUAL_TYPE", "DOMAIN", "RANGE",
            "AP",
            // and the same with an argument, which is the easy mistake
            "ID 1", "LABEL %", "DOMAIN %", "RANGE %", "DOMAIN SPLIT=|", "RANGE SPLIT=,",
            "CLASS_TYPE SPLIT=|", "ID SPLIT=|",
            // TYPE and CHARACTERISTIC, which do take SPLIT=
            "TYPE", "TYPE SPLIT=|", "TYPE SPLIT=", "TYPE %", "CHARACTERISTIC",
            "CHARACTERISTIC SPLIT=,",
            // the annotation family
            "A rdfs:label", "AL rdfs:label@en", "AT dcterms:date^^xsd:date", "AI foaf:homepage",
            "A http://purl.obolibrary.org/obo/IAO_0000115", "A rdfs:label SPLIT=|",
            "A rdfs:label@en", "A", "AL", "AT", "AI", "A ", "AP SPLIT=|",
            // axiom annotations
            ">A rdfs:comment", ">>A rdfs:comment", ">>>A rdfs:comment", "> A rdfs:comment",
            ">AL rdfs:label@en", ">AT ex:p^^xsd:string", ">AI ex:p", ">SC %", ">C %", ">I ex:p",
            // class expressions
            "C %", "SC %", "EC %", "DC %", "CI", "CI ", "SC", "C", "SC 'annealing process'",
            // properties
            "P %", "SP %", "EP %", "DP %", "IP %", "PI", "PI ", "SP", "P",
            // individuals
            "I ex:p", "I http://purl.obolibrary.org/obo/BFO_0000050", "II", "II ", "TI %",
            "SI %", "DI %", "I", "I ",
            // nonsense
            "LABEL2", "a rdfs:label", "id", "Id", "TYPES", "XX", "SPLIT=|", "%", ">",
            "A rdfs:label  SPLIT=|");

    /**
     * THE TEST THAT MATTERS. Same question, same strings, robot-core's own answer.
     *
     * <p>{@code validateTemplateString} is public and robot-core is on this test's classpath, so
     * there is no excuse for transcribing it and hoping. The one deliberate divergence is the
     * blank string, which robot-core's validator refuses and {@code Template.addTable} never
     * shows it - the empty cell is skipped before validation, and clearing a cell is the
     * supported way to turn a column off.
     */
    @Test
    void everyVerdictAgreesWithRobotsOwnValidator() {
        List<String> disagreements = new ArrayList<String>();
        for (String spec : CORPUS) {
            boolean ours = TemplateSpec.isAccepted(spec);
            boolean theirs = org.obolibrary.robot.TemplateHelper.validateTemplateString(spec);
            if (ours != theirs) {
                disagreements.add("\"" + spec + "\": we say " + ours + ", robot says " + theirs);
            }
        }
        assertEquals("[]", disagreements.toString(),
                "the transcription has drifted from robot-core " + robotVersion());
    }

    /** Guards the test above against passing because the corpus is all one answer. */
    @Test
    void theCorpusContainsBothAnswersSoTheAgreementTestIsNotVacuous() {
        int accepted = 0;
        for (String spec : CORPUS) {
            if (org.obolibrary.robot.TemplateHelper.validateTemplateString(spec)) {
                accepted++;
            }
        }
        assertTrue(accepted > 20, "only " + accepted + " of " + CORPUS.size() + " accepted");
        assertTrue(accepted < CORPUS.size() - 20,
                accepted + " of " + CORPUS.size() + " accepted - too few refusals to prove "
                        + "anything");
    }

    private static String robotVersion() {
        java.net.URL where = org.obolibrary.robot.TemplateHelper.class.getProtectionDomain()
                .getCodeSource().getLocation();
        return String.valueOf(where);
    }

    // ---------- the blank cell ----------

    /**
     * {@code Template.addTable} tests {@code templates.get(i).trim().isEmpty()} and skips the
     * column before it validates anything, so an empty template string is the supported way to
     * turn a column off - its heading and its data are then ignored, whatever they are.
     */
    @Test
    void anEmptyTemplateStringIsLegalEvenThoughTheValidatorAloneRefusesIt() {
        assertTrue(TemplateSpec.isAccepted(""));
        assertTrue(TemplateSpec.isAccepted("   "));
        assertTrue(TemplateSpec.isAccepted(null));
        assertFalse(org.obolibrary.robot.TemplateHelper.validateTemplateString(""),
                "if robot ever accepts a blank string, this divergence can go");
    }

    // ---------- what the refusal says ----------

    @Test
    void aRefusalQuotesRobotsOwnErrorAndSaysHowBadItIs() {
        String refusal = TemplateSpec.refusal("A rdfs:label@en SPLIT");
        assertEquals("", refusal, "that one is accepted, so there is nothing to say");

        String real = TemplateSpec.refusal("XX");
        assertTrue(real.contains("UNKNOWN TEMPLATE ERROR"), real);
        assertTrue(real.contains("whole sheet"), "it costs the sheet, not the column: " + real);
    }

    // ---------- the whole header ----------

    private static List<String> row(String... cells) {
        return Arrays.asList(cells);
    }

    private static TemplateSpec.Problem only(List<TemplateSpec.Problem> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.get(0);
    }

    @Test
    void aGoodHeaderHasNothingWrongWithIt() {
        assertTrue(TemplateSpec.check(
                row("ID", "Label", "Parent"),
                row("ID", "LABEL", "SC %")).isEmpty());
    }

    /** The real MatWerk shape, which must come back clean. */
    @Test
    void theShapeTheLiveSheetsUseIsClean() {
        assertTrue(TemplateSpec.check(
                row("#", "TYPE", "Label", "participates in"),
                row("ID", "TYPE", "A rdfs:label",
                        "I http://purl.obolibrary.org/obo/RO_0000056 SPLIT=,")).isEmpty());
    }

    /**
     * A template string with no heading is a hard ColumnException, and making row 1 editable is
     * what put it within reach of a keystroke.
     */
    @Test
    void aTemplateStringWithNoHeadingBreaksTheSheet() {
        TemplateSpec.Problem problem = only(TemplateSpec.check(
                row("ID", ""),
                row("ID", "A rdfs:label")));

        assertEquals(2, problem.getColumn());
        assertEquals(TemplateSpec.Severity.BREAKS_THE_SHEET, problem.getSeverity());
        assertTrue(problem.getMessage().contains("COLUMN MISMATCH"), problem.getMessage());
    }

    /** But a column with no template string needs no heading - it is off. */
    @Test
    void aColumnThatIsTurnedOffNeedsNoHeading() {
        assertTrue(TemplateSpec.check(row("ID", ""), row("ID", "")).isEmpty());
        assertTrue(TemplateSpec.check(row("ID", "Scratch"), row("ID", "")).isEmpty());
    }

    @Test
    void aSheetWithNeitherIdNorLabelCannotSayWhatItsRowsAreAbout() {
        TemplateSpec.Problem problem = only(TemplateSpec.check(
                row("Label", "Parent"),
                row("A rdfs:label", "SC %")));

        assertEquals(0, problem.getColumn(), "it is about the header, not one column");
        assertTrue(problem.getMessage().contains("\"ID\" or \"LABEL\""), problem.getMessage());
        assertEquals("ID", problem.getSuggestion());
    }

    @Test
    void anEmptyHeaderHasNothingToComplainAbout() {
        assertTrue(TemplateSpec.check(row("", ""), row("", "")).isEmpty());
        assertTrue(TemplateSpec.check(null, null).isEmpty());
    }

    @Test
    void aSpecRobotRefusesIsReportedWithSomethingToPutInstead() {
        List<TemplateSpec.Problem> problems = TemplateSpec.check(
                row("ID", "Domain"),
                row("ID", "DOMAIN %"));

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(TemplateSpec.Severity.BREAKS_THE_SHEET, problems.get(0).getSeverity());
        assertEquals("DOMAIN", problems.get(0).getSuggestion(),
                "DOMAIN is matched with equals, so the argument is the whole fault");
    }

    // ---------- legal, and silently useless ----------

    @Test
    void theColumnsRobotAcceptsAndThenIgnoresAreCalledOut() {
        List<TemplateSpec.Problem> problems = TemplateSpec.check(
                row("ID", "Same as"),
                row("ID", "II"));

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(TemplateSpec.Severity.DOES_NOTHING, problems.get(0).getSeverity());
        assertTrue(problems.get(0).getMessage().contains("produces nothing"),
                problems.get(0).getMessage());
    }

    /** {@code >} only annotates in front of the A family; ROBOT takes >SC and discards it. */
    @Test
    void anAxiomAnnotationOnAClassColumnIsAcceptedAndDiscarded() {
        List<TemplateSpec.Problem> problems = TemplateSpec.check(
                row("ID", "Parent", "Source"),
                row("ID", "SC %", ">SC %"));

        boolean said = false;
        for (TemplateSpec.Problem problem : problems) {
            if (problem.getMessage().contains("only works in front of A")) {
                said = true;
                assertEquals(TemplateSpec.Severity.DOES_NOTHING, problem.getSeverity());
            }
        }
        assertTrue(said, problems.toString());
    }

    @Test
    void anAxiomAnnotationThatFollowsNothingAnnotatesNothing() {
        List<TemplateSpec.Problem> problems = TemplateSpec.check(
                row("Source", "ID"),
                row(">A rdfs:comment", "ID"));

        boolean said = false;
        for (TemplateSpec.Problem problem : problems) {
            if (problem.getMessage().contains("follows nothing")) {
                said = true;
            }
        }
        assertTrue(said, problems.toString());
    }

    /** It annotates across blanks and other > columns, which is what ROBOT's walk does. */
    @Test
    void anAxiomAnnotationReachesPastBlankColumnsToItsTarget() {
        assertTrue(TemplateSpec.check(
                row("ID", "Definition", "Scratch", "Source"),
                row("ID", "A IAO:0000115", "", ">A IAO:0000119")).isEmpty());
    }

    /**
     * {@code >>} with no {@code >} before it is a bare NullPointerException out of ROBOT, which
     * names neither the column nor the sheet.
     */
    @Test
    void aSecondLevelAnnotationWithNothingToAnnotateBreaksTheSheet() {
        List<TemplateSpec.Problem> problems = TemplateSpec.check(
                row("ID", "Definition", "Who said so"),
                row("ID", "A IAO:0000115", ">>A IAO:0000119"));

        boolean said = false;
        for (TemplateSpec.Problem problem : problems) {
            if (problem.getMessage().contains("NullPointerException")) {
                said = true;
                assertEquals(TemplateSpec.Severity.BREAKS_THE_SHEET, problem.getSeverity());
            }
        }
        assertTrue(said, problems.toString());
    }

    @Test
    void aSecondLevelAnnotationAfterAFirstLevelOneIsFine() {
        assertTrue(TemplateSpec.check(
                row("ID", "Definition", "Source", "Who said so"),
                row("ID", "A IAO:0000115", ">A IAO:0000119", ">>A IAO:0000119")).isEmpty());
    }

    // ---------- the menu ----------

    @Test
    void everyFormOfferedIsOneRobotAccepts() {
        List<String> refused = new ArrayList<String>();
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            // The placeholders are filled in before the string reaches ROBOT; this is what the
            // editor substitutes when somebody picks one.
            String filled = form.getSpec()
                    .replace("<property>", "rdfs:label")
                    .replace("<language>", "en")
                    .replace("<datatype>", "xsd:string")
                    .replace("<expression>", "%");
            if (!TemplateSpec.isAccepted(filled)) {
                refused.add(form.getSpec() + " -> " + filled);
            }
        }
        assertEquals("[]", refused.toString(), "the menu offers something ROBOT refuses");
    }

    @Test
    void theMenuLeavesOutTheFormsThatDoNothing() {
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            assertFalse(form.getSpec().equals("II"), "II is legal and produces nothing");
            assertFalse(form.getSpec().equals("PI"),
                    "PI is undocumented and silent on a data property");
        }
    }

    @Test
    void theMenuCoversWhatBothCorporaActuallyUse() {
        Set<String> offered = new LinkedHashSet<String>();
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            offered.add(form.getSpec());
        }
        // The live MatWerk tabs use the first four; the hand-written templates use the rest.
        // Measured: a list drawn from either corpus alone misses half of what gets typed.
        for (String needed : new String[] {"ID", "TYPE", "A <property>", "I <property>",
            "LABEL", "SC %", "AL <property>@<language>", "AT <property>^^<datatype>",
            ">A <property>", "DOMAIN", "RANGE"}) {
            assertTrue(offered.contains(needed), "the menu is missing " + needed + ": " + offered);
        }
    }

    @Test
    void theFormsThatTakeNoSplitAreTheOnesMatchedByEquals() {
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            if (form.getSpec().isEmpty()) {
                continue;
            }
            String bare = form.getSpec();
            boolean matchedByEquals = Arrays.asList("ID", "LABEL", "CLASS_TYPE", "PROPERTY_TYPE",
                    "INDIVIDUAL_TYPE", "DOMAIN", "RANGE").contains(bare);
            if (matchedByEquals) {
                assertFalse(form.takesSplit(), bare + " breaks the sheet with SPLIT=");
                assertFalse(TemplateSpec.isAccepted(bare + " SPLIT=|"),
                        bare + " SPLIT=| should be refused by robot");
            }
        }
    }

    @Test
    void everyFormSaysWhatItIsFor() {
        for (TemplateSpec.Form form : TemplateSpec.forms()) {
            assertFalse(form.getMeaning().trim().isEmpty(), form.getSpec() + " has no meaning");
            assertFalse(form.getGroup().trim().isEmpty(), form.getSpec() + " has no group");
        }
        assertTrue(TemplateSpec.aboutSplit().contains("literally"));
    }
}
