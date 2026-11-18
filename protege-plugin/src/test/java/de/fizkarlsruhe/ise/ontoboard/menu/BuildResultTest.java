package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine;
import de.fizkarlsruhe.ise.ontoboard.odk.SeededProject;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What a build says above its log (1.135.1, Windows study rehearsal). The result dialog cannot be
 * resized and its log box shows six lines: a passing test's PASS was the ninth, and HermiT's
 * warning the seventh, so a participant looking for "PASS" or for what went wrong had to scroll.
 *
 * <p>Said then of the build OntoBoard ran for its own projects. The project is ODK's now and the
 * run is ODK's Makefile's - by OntoBoard itself, in ODK's container, or with ODK's tools on the
 * machine - and the same is said of it, whoever ran it: read off what make and ROBOT printed.
 */
class BuildResultTest {

    private static final String QUESTION_FILE =
            "every_kind_of_egg_boiling_dataset_has_some_has_part.rq";

    /** What make in a container hands back: the lines, and how it ended. */
    private static ProcessRunner.Outcome asAProcess(OdkEngine.Outcome ran) {
        return new ProcessRunner.Outcome(ran.isOk() ? 0 : 2,
                new ArrayList<String>(ran.getTranscript()), false);
    }

    // ------------------------------------------------------------------ a run that passed

    @Test
    void aPassingBuildNamesEachOfTheProjectsTestsAboveTheLog(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, true);
        OdkEngine.Outcome ran = SeededProject.run(edit, "test");
        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));

        OperationResult result = BuildAction.engineResult(OperationResult.of("Build"), "test",
                ran, 1, edit, ontology);

        String summary = result.getSummary();
        assertTrue(result.isSuccess(), summary);
        assertTrue(summary.startsWith("test succeeded in 1s, run by OntoBoard itself; 6 of ODK's "
                + "and your checks passed. Tests: PASS check_labels-violation.sparql, PASS "
                + QUESTION_FILE + "."), summary);
        assertFalse(summary.contains("owldef-self-reference"),
                "ODK's own checks are counted, not named: " + summary);
        assertTrue(result.getLog().contains("PASS Rule ../sparql/" + QUESTION_FILE
                + ": 0 violation(s)"), "the log keeps make's own lines");
    }

    /**
     * F5: make runs a Makefile somebody edited as it is - in ODK's container, or with ODK's
     * tools - where OntoBoard's engine refuses it. The build says so, above its log.
     */
    @Test
    void aBuildByMakeOfAnEditedMakefileSaysSo(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.open(edit);
        OdkEngine.Outcome ran = SeededProject.run(edit, "test_fast");
        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));
        File makefile = new File(edit.getParentFile(), "Makefile");
        java.nio.file.Files.write(makefile.toPath(), (new String(java.nio.file.Files.readAllBytes(
                makefile.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                + "\n# a line of their own\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"),
                "test_fast", asAProcess(ran), 1, edit, ontology);

        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus(),
                result.getSummary());
        assertTrue(result.getSummary().contains("This project's Makefile is not the one ODK "
                + "v1.6.1 writes for its configuration"), result.getSummary());
        assertTrue(String.join("\n", result.getLog()).contains("Update project files..."),
                "the way back is in the log");
    }

    @Test
    void aBuildInTheContainerSaysTheSameOfItsTests(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, true);
        OdkEngine.Outcome ran = SeededProject.run(edit, "test");
        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"), "test",
                asAProcess(ran), 1, edit, ontology);

        assertEquals("make test succeeded in 1s; 6 of ODK's and your checks passed. Tests: PASS "
                + "check_labels-violation.sparql, PASS " + QUESTION_FILE + ".",
                result.getSummary());
        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus());
    }

    /**
     * The 1.154.0 review's I2: a run in ODK's container with "Download the imports' sources
     * again" unticked, on a clone without mirror/, stops on a mirror it may not download - and
     * Build's result names that box, as the engine's route does before it runs.
     */
    @Test
    void aContainerRunWithoutMirrorsNamesTheBoxThatDownloadsThem(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.open(edit);
        ProcessRunner.Outcome stopped = new ProcessRunner.Outcome(2, Arrays.asList(
                "make[1]: *** No rule to make target 'mirror/iao.owl', needed by "
                        + "'imports/iao_import.owl'.  Stop.",
                "make: *** [Makefile:116: test] Error 2"), false);

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"), "test",
                stopped, 1, edit, ontology, java.util.EnumSet.of(
                        de.fizkarlsruhe.ise.ontoboard.odk.BuildSwitches.Switch.MIR));

        assertEquals(OperationResult.Status.FAILED, result.getStatus(), result.getSummary());
        assertTrue(result.getSummary().contains("Download the imports' sources again"),
                result.getSummary());
    }

    /**
     * What ELK cannot see, HermiT is asked about after a run that passed, and the answer stands
     * above the log: the build keeps ODK's verdict and says what a fuller reasoner adds.
     */
    @Test
    void whatOnlyHermiTSeesIsSaidAboveTheLog(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.open(edit);
        SeededProject.whatOnlyHermiTSees(ontology, "egg mass value");
        SeededProject.save(ontology, edit);
        OdkEngine.Outcome ran = SeededProject.run(edit, "reason_test");
        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));

        OperationResult itself = BuildAction.engineResult(OperationResult.of("Build"),
                "reason_test", ran, 1, edit, ontology);
        OperationResult container = BuildAction.processResult(OperationResult.of("Build"),
                "reason_test", asAProcess(ran), 1, edit, ontology);

        for (OperationResult result : Arrays.asList(itself, container)) {
            String summary = result.getSummary();
            assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus(),
                    summary);
            assertTrue(summary.contains(" HermiT finds that egg mass value can have no "
                    + "members. More in the log."), summary);
            assertTrue(String.join("\n", result.getLog()).contains("A relation or parent added "
                    + "to it is the usual cause:"), "the whole warning is in the log");
        }
        assertTrue(itself.getSummary().startsWith("reason_test succeeded in 1s, run by "
                + "OntoBoard itself. HermiT finds"), itself.getSummary());
        assertTrue(container.getSummary().startsWith("make reason_test succeeded in 1s. HermiT "
                + "finds"), container.getSummary());
    }

    /** HermiT is asked after a run that reasoned with ELK, not after one that did not reason. */
    @Test
    void aRunThatDidNotReasonGetsNoSecondOpinion(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.open(edit);
        SeededProject.whatOnlyHermiTSees(ontology, "egg mass value");
        SeededProject.save(ontology, edit);
        OdkEngine.Outcome ran = SeededProject.run(edit, "sparql_test");
        assertTrue(ran.isOk(), String.valueOf(ran.getFailure()));

        OperationResult result = BuildAction.engineResult(OperationResult.of("Build"),
                "sparql_test", ran, 1, edit, ontology);

        assertEquals(OperationResult.Status.SUCCEEDED, result.getStatus(), result.getSummary());
        assertFalse(result.getSummary().contains("HermiT"), result.getSummary());
    }

    /** A Makefile that is not ODK's is not said to have run ODK's checks. */
    @Test
    void aMakefileThatIsNotOdksCountsItsChecksWithoutNamingOdk(@TempDir File where) {
        File edit = new File(where, "egg-edit.owl");
        ProcessRunner.Outcome outcome = new ProcessRunner.Outcome(0, Arrays.asList(
                "robot verify --queries checks/a.rq checks/b.rq",
                "PASS Rule checks/a.rq: 0 violation(s)",
                "PASS Rule checks/b.rq: 0 violation(s)"), false);

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"), "check",
                outcome, 3, edit, null);

        assertEquals("make check succeeded in 3s; 2 checks passed. Tests: PASS a.rq, PASS b.rq.",
                result.getSummary());
    }

    // ------------------------------------------------------------------ a run that failed

    /**
     * make's last word on a failing question is a line of CSV: the IRI of a class. The result
     * says which question, about which term by its name, and what to do on the board - and
     * then where make stopped.
     */
    @Test
    void aFailingQuestionIsExplainedWhoeverRanTheTarget(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, false);
        OdkEngine.Outcome ran = SeededProject.run(edit, "test");
        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));

        OperationResult itself = BuildAction.engineResult(OperationResult.of("Build"), "test",
                ran, 1, edit, ontology);
        OperationResult container = BuildAction.processResult(OperationResult.of("Build"),
                "test", asAProcess(ran), 1, edit, ontology);

        for (OperationResult result : Arrays.asList(itself, container)) {
            String summary = result.getSummary();
            assertEquals(OperationResult.Status.FAILED, result.getStatus(), summary);
            assertTrue(summary.startsWith(QUESTION_FILE + " (Every kind of egg boiling dataset "
                    + "has some has part?): egg boiling dataset. The model does not answer this "
                    + "question yet. To make it answer, right-click egg boiling dataset on the "
                    + "board"), summary);
            assertTrue(summary.contains(" 'test' stopped at question_test; the log has every "
                    + "line up to there."), summary);
            assertTrue(summary.endsWith(" Tests: PASS check_labels-violation.sparql."),
                    "what passed before it is still said: " + summary);
            assertFalse(summary.contains(SeededProject.DATASET.toString()), summary);
        }
    }

    /** The label check passes, then the quality report stops the run: both are said. */
    @Test
    void aBuildThatFailsAfterATestSaysWhyAndThatTheTestPassed(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, true);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        for (String id : new String[] {"EGGBOIL_0005001", "EGGBOIL_0005002"}) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(
                    df.getOWLClass(IRI.create(SeededProject.NS + id))));
            SeededProject.label(ontology, IRI.create(SeededProject.NS + id), "boiled egg");
        }
        SeededProject.save(ontology, edit);
        OdkEngine.Outcome ran = SeededProject.run(edit, "test");
        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));

        String summary = BuildAction.engineResult(OperationResult.of("Build"), "test", ran, 1,
                edit, ontology).getSummary();

        assertTrue(summary.startsWith("The quality report found 2 errors: duplicate_label "
                + "boiled egg; duplicate_label boiled egg. All of them are in reports/"), summary);
        assertTrue(summary.contains(" 'test' stopped at "), summary);
        assertTrue(summary.endsWith("; the log has every line up to there. Tests: PASS "
                + "check_labels-violation.sparql."), summary);
    }

    /** What the transcript does not explain is said as whoever ran the target says it. */
    @Test
    void aRunThatFailedForSomethingElseKeepsTheRunnersOwnSentence(@TempDir File where) {
        File edit = new File(where, "egg-edit.owl");
        List<String> lines = Arrays.asList("robot merge --input x.owl",
                "x.owl: No such file or directory",
                "make: *** [Makefile:300: x.owl] Error 1");
        ProcessRunner.Outcome outcome = new ProcessRunner.Outcome(2, lines, false);

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"), "all",
                outcome, 1, edit, null);

        assertEquals(MakeRun.explain("all", outcome), result.getSummary());
        assertEquals("make all failed (exit 2): make: *** [Makefile:300: x.owl] Error 1",
                result.getSummary());
    }

    /** A run the person stopped is that, whatever it had printed by then. */
    @Test
    void aRunThePersonStoppedSaysOnlyThat(@TempDir File where) {
        File edit = new File(where, "egg-edit.owl");
        List<String> lines = Arrays.asList(
                "FAIL Rule ../sparql/b-violation.sparql: 1 violation(s)",
                "term", "https://example.org/t1");
        ProcessRunner.Outcome outcome = new ProcessRunner.Outcome(130, lines, false, true);

        OperationResult result = BuildAction.processResult(OperationResult.of("Build"), "test",
                outcome, 1, edit, null);

        assertEquals(OperationResult.Status.FAILED, result.getStatus());
        assertEquals(MakeRun.explain("test", outcome), result.getSummary());
        assertTrue(result.getSummary().startsWith("You stopped the build."),
                result.getSummary());
    }

    /**
     * The same of a run by OntoBoard itself: stopped while the questions were being asked, it
     * had already said which one found something, and the result still says only that it was
     * stopped - the run was not finished, and what it found is not the reason it ended.
     */
    @Test
    void aRunByOntoBoardItselfThatThePersonStoppedSaysOnlyThat(@TempDir File where)
            throws Exception {
        final File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, false);
        // Stopped as soon as the questions have left their rows: after that line, before the next.
        final File rows = new File(edit.getParentFile(), "reports/"
                + QUESTION_FILE.replace(".rq", ".csv"));
        OdkEngine.Outcome ran = OdkEngine.run(edit, "question_test",
                java.util.Collections.<String, String>emptyMap(),
                new java.util.function.BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        return rows.isFile();
                    }
                });
        assertTrue(ran.wasStopped(), String.join("\n", ran.getTranscript()));
        assertTrue(String.join("\n", ran.getTranscript()).contains("FAIL Rule"),
                String.join("\n", ran.getTranscript()));

        OperationResult result = BuildAction.engineResult(OperationResult.of("Build"),
                "question_test", ran, 1, edit, ontology);

        assertEquals(ran.getFailure(), result.getSummary());
        assertTrue(result.getSummary().startsWith("You stopped 'question_test'."),
                result.getSummary());
    }
}
