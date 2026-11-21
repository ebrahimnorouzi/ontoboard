package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the pattern chooser tells somebody about to import a pattern a reasoner objects to.
 *
 * <p>The four findings were measured, written to {@code patterns/soundness.tsv} and published on
 * the web pages - and the web pages are the one place a curator working inside Protégé is not
 * looking. Importing a pattern whose class cannot have an instance makes their own ontology
 * unsatisfiable, and the first they would hear of it is their own reasoner refusing to classify
 * something that looks unrelated.
 */
class ReasonerVerdictTest {

    /**
     * The shipped table, as it reaches a running plugin: off the classpath, by resource name.
     *
     * <p>THIS ASSERTS THE TABLE WAS FOUND, not merely that a lookup answered. The first version
     * checked {@code forPattern("actingfor").isClean()}, which passes when the table is MISSING -
     * an unknown pattern is reported clean, deliberately, because absence is not a finding. The
     * resource was in fact absent: {@code patterns/soundness.tsv} was not in the pom's include
     * list, so nothing was bundled and every verdict came back UNKNOWN while the test went
     * green. A test that cannot tell "checked and fine" from "never read" is no test.
     */
    @Test
    void theShippedTableIsOnTheClasspath() {
        // Proved by a row only the table can know: template-instance ships without a worked
        // example. (Since 1.155.0 no bundled pattern is one a reasoner objects to, so a flagged
        // pattern can no longer be the proof.)
        assertTrue(ReasonerVerdict.forPattern("template-instance").hasNoExample(),
                "patterns/soundness.tsv is not on the classpath - check the pom's resource "
                + "includes. Without it every pattern looks clean, including a broken one.");
        assertFalse(ReasonerVerdict.forPattern("actingfor").isWorthSaying(),
                "a clean pattern gets no box: one on every pattern is one nobody reads");
        assertTrue(ReasonerVerdict.forPattern("actingfor").isClean(), "actingfor is clean");
    }

    /**
     * OUTSIDE OWL 2 DL IS NOT INCONSISTENT, and the two must not be run together.
     *
     * <p>One is a contradiction; the other is a question no DL reasoner can be asked. Telling
     * somebody their pattern is inconsistent when it is merely outside the profile sends them
     * looking for a clash that is not there.
     */
    @Test
    void beingOutsideTheProfileIsNotCalledInconsistent() throws Exception {
        ReasonerVerdict verdict = refused();

        assertTrue(verdict.isWorthSaying(), "one a reasoner refuses");
        assertEquals("not OWL 2 DL", verdict.getShortLabel());
        assertTrue(verdict.describe().contains("outside OWL 2 DL"), verdict.describe());
        assertFalse(verdict.describe().contains("inconsistent"),
                "a question no reasoner can answer is not a contradiction: " + verdict.describe());
    }

    /** As soundness.tsv recorded roledependentnames before 1.155.0 curated it. */
    private static ReasonerVerdict refused() throws Exception {
        return ReasonerVerdict.read(new StringReader(
                "refused\t0\t34\tfalse\tNOT_OWL_2_DL\t35\tfalse\tNOT_OWL_2_DL\t\n")).get("refused");
    }

    /** An unsatisfiable class is named, and the consequence of using it is spelled out. */
    @Test
    void anUnsatisfiableClassIsNamedWithItsConsequence() throws Exception {
        // As soundness.tsv recorded spatial-graph-adapter-sga before 1.155.0 curated it.
        ReasonerVerdict verdict = ReasonerVerdict.read(new StringReader("sga\t0\t42\ttrue"
                + "\tAttribute; ContextualizedRelation\t-\t-\tnone\tno example.ttl\n")).get("sga");

        assertTrue(verdict.isWorthSaying());
        assertTrue(verdict.getShortLabel().startsWith("unsatisfiable class"),
                verdict.getShortLabel());
        assertTrue(verdict.describe().contains("Attribute"), verdict.describe());
        assertTrue(verdict.describe().contains("makes your ontology inconsistent"),
                "the consequence is the part that matters: " + verdict.describe());
    }

    /**
     * A pattern the table does not mention says nothing, rather than implying it passed.
     *
     * <p>A contributed pattern out of the user's own folder has no row, and nor would a shipped
     * one in a jar whose table predates it. Treating absence as a pass would be the library
     * vouching for a file it has never read.
     */
    @Test
    void aPatternWithNoRowSaysNothing() {
        ReasonerVerdict unknown = ReasonerVerdict.forPattern("a-pattern-from-somebodys-folder");

        assertFalse(unknown.isWorthSaying(), "no row is not a finding");
        assertEquals("", unknown.getShortLabel());
        assertEquals("", unknown.describe());
        assertFalse(ReasonerVerdict.forPattern(null).isWorthSaying(), "null is not a finding");
    }

    /** The parser reads the columns PatternSoundness writes, and skips what it cannot use. */
    @Test
    void theTableIsParsedByColumnAndShortRowsAreSkipped() throws Exception {
        Map<String, ReasonerVerdict> read = ReasonerVerdict.read(new StringReader(
                "# a comment\n"
                + "id\timports\tpattern_axioms\tpattern_consistent\tpattern_unsat"
                + "\texample_axioms\tmerged_consistent\tmerged_unsat\tnotes\n"
                + "clean\t1\t35\ttrue\tnone\t7\ttrue\tnone\t\n"
                + "unsat\t0\t42\ttrue\tWidget\t8\ttrue\tnone\t\n"
                + "refused\t0\t34\tfalse\tNOT_OWL_2_DL\t9\tfalse\tNOT_OWL_2_DL\t\n"
                + "noexample\t0\t4\ttrue\tnone\t-\t-\tnone\tno example.ttl\n"
                + "truncated\t0\t4\ttrue\n"
                + "\n"));

        assertEquals("[clean, unsat, refused, noexample]", read.keySet().toString(),
                "the short row and the blank line are skipped, not guessed at");
        assertTrue(read.get("clean").isClean());
        assertFalse(read.get("unsat").isClean());
        assertEquals("unsatisfiable class", read.get("unsat").getShortLabel());
        assertEquals("not OWL 2 DL", read.get("refused").getShortLabel());
        assertTrue(read.get("noexample").isClean(),
                "shipping without a worked example is not a defect in the pattern");
        assertTrue(read.get("noexample").hasNoExample());
    }

    /** Two unsatisfiable classes read as plural, one as singular. */
    @Test
    void theWordingAgreesWithTheNumberOfClasses() throws Exception {
        Map<String, ReasonerVerdict> read = ReasonerVerdict.read(new StringReader(
                "one\t0\t1\ttrue\tWidget\t1\ttrue\tnone\t\n"
                + "two\t0\t1\ttrue\tWidget; Gadget\t1\ttrue\tnone\t\n"));

        assertEquals("unsatisfiable class", read.get("one").getShortLabel());
        assertEquals("unsatisfiable classes", read.get("two").getShortLabel());
        assertTrue(read.get("one").describe().contains("that class"), read.get("one").describe());
        assertTrue(read.get("two").describe().contains("those classes"),
                read.get("two").describe());
    }

    /**
     * Every pattern the soundness table flags is one the chooser will speak up about.
     *
     * <p>The two halves could drift apart: a verdict the table records and the dialog treats as
     * clean would be a finding nobody is ever shown.
     */
    @Test
    void everyFlaggedPatternIsOneTheChooserWarnsAbout() {
        int flagged = 0;
        for (DesignPattern pattern : PatternLibrary.all()) {
            ReasonerVerdict verdict = ReasonerVerdict.forPattern(pattern.getId());
            if (verdict.isWorthSaying()) {
                flagged++;
                assertFalse(verdict.describe().isEmpty(),
                        pattern.getId() + " is flagged with nothing to say about it");
                assertFalse(verdict.getShortLabel().isEmpty(), pattern.getId());
            }
        }
        assertEquals(0, flagged,
                "since 1.155.0 no bundled pattern is one a reasoner objects to: the three that"
                + " were - roledependentnames, winstonpartwhole, spatial-graph-adapter-sga - were"
                + " curated, each change recorded in the pattern. One flagged now is new.");
    }

    /**
     * THE WARNING REACHES THE DIALOG, which is the whole point of reading the table at runtime.
     *
     * <p>A verdict the reader can produce and the chooser never shows would leave the finding
     * exactly where it already was: on a web page the curator is not looking at.
     */
    @Test
    void theChooserShowsTheWarningForAFlaggedPatternAndNotForACleanOne() throws Exception {
        // A bundled pattern, shown with the verdict it had before 1.155.0 curated it.
        DesignPattern flagged = PatternLibrary.find("winstonpartwhole");
        String warned = PatternSummary.asHtml(flagged, PatternLibrary.contentsOf(flagged), null, null,
                refused());

        assertTrue(warned.contains("A reasoner objects"), warned);
        assertTrue(warned.contains("not OWL 2 DL"), warned);
        assertTrue(warned.contains("outside OWL 2 DL"),
                "the sentence, not only the label, has to be there");
        assertTrue(warned.contains("OntoBoard has not repaired this one"),
                "it says that OntoBoard's curation has not reached it: " + warned);

        DesignPattern clean = PatternLibrary.find("actingfor");
        String quiet = PatternSummary.asHtml(clean, PatternLibrary.contentsOf(clean));

        assertFalse(quiet.contains("A reasoner objects"),
                "a box on every pattern is a box nobody reads: " + quiet);
    }
}
