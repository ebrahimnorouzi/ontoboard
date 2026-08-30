package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Running an ODK project's own build from inside Protege.
 *
 * <p>{@code MakeTargets} could read a Makefile and order its targets, was tested against a real
 * 767-line ODK one, and nothing could run a single target - so wanting the thing a target does
 * meant leaving Protege for a terminal, which is most of the reason the plugin exists.
 *
 * <p>What is worth testing is not that {@code make} works. It is the surrounding judgement: that a
 * missing tool is reported as a setup problem rather than a broken project, that a failure points
 * at the part of a several-hundred-line transcript that explains it, and that a target which
 * rewrites the file Protege has open says so - because Protege will not notice, and somebody who
 * carries on editing will overwrite the build's output when they save.
 */
class MakeRunTest {

    private static ProcessRunner.Runner runnerThatSays(final boolean available) {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File directory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                return new ProcessRunner.Outcome(available ? 0 : 127,
                        Collections.<String>emptyList(), false);
            }
        };
    }

    private static ProcessRunner.Outcome failedWith(String... lines) {
        return new ProcessRunner.Outcome(2, Arrays.asList(lines), false);
    }

    private static File projectWithMakefile(File directory) throws IOException {
        File ontology = new File(directory, "mwo-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        Files.write(new File(directory, "Makefile").toPath(),
                "all: reason\nreason:\n\trobot reason\n".getBytes("UTF-8"));
        return ontology;
    }

    // ---------- the command ----------

    @Test
    void theCommandIsPlainMake() {
        assertEquals(Arrays.asList("make", "reason"), MakeRun.command("reason"));
    }

    /**
     * No -j. ODK recipes are not written to be parallel-safe, and interleaved output from a
     * parallel build is unreadable - which defeats keeping the transcript at all.
     */
    @Test
    void theBuildIsNotParallel() {
        assertFalse(MakeRun.command("all").contains("-j"));
    }

    @Test
    void aTargetWithNoNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> MakeRun.command(""));
        assertThrows(IllegalArgumentException.class, () -> MakeRun.command(null));
    }

    @Test
    void theBuildRunsBesideTheEditFile(@TempDir File directory) throws Exception {
        File ontology = projectWithMakefile(directory);

        assertEquals(directory.getCanonicalFile(),
                MakeRun.workingDirectory(ontology).getCanonicalFile());
    }

    // ---------- a setup problem is not a broken project ----------

    /** "make: not found" inside a transcript reads as the project being broken. It is not. */
    @Test
    void makeMissingIsReportedAsSetupAndSaysDockerIsNotNeeded(@TempDir File directory)
            throws Exception {
        File ontology = projectWithMakefile(directory);

        String why = MakeRun.whyNotRunnable(ontology, runnerThatSays(false));

        assertNotNull(why);
        assertTrue(why.contains("not on the PATH"), why);
        assertTrue(why.contains("not Docker"),
                "the generated Makefile calls robot directly, and users assume ODK means Docker: "
                        + why);
    }

    @Test
    void aProjectWithNoMakefileSaysWhereItLooked(@TempDir File directory) throws Exception {
        File ontology = new File(directory, "mwo-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        String why = MakeRun.whyNotRunnable(ontology, runnerThatSays(true));

        assertTrue(why.contains(directory.getAbsolutePath()), why);
    }

    @Test
    void anUnsavedOntologyHasNowhereToBuild() {
        assertTrue(MakeRun.whyNotRunnable(null, runnerThatSays(true)).contains("not been saved"));
    }

    @Test
    void aProjectThatCanBuildIsNotRefused(@TempDir File directory) throws Exception {
        assertNull(MakeRun.whyNotRunnable(projectWithMakefile(directory), runnerThatSays(true)));
    }

    // ---------- pointing at the part of the transcript that matters ----------

    @Test
    void aSuccessfulBuildNeedsNoExplanation() {
        assertNull(MakeRun.explain("all",
                new ProcessRunner.Outcome(0, Arrays.asList("done"), false)));
    }

    /** The commonest real failure, and the fix is not in the ontology. */
    @Test
    void robotMissingIsExplainedAsRobotMissing() {
        String explanation = MakeRun.explain("reason",
                failedWith("robot reason -r ELK", "make: robot: command not found"));

        assertTrue(explanation.contains("PATH"), explanation);
        assertTrue(explanation.toLowerCase().contains("robot"), explanation);
    }

    @Test
    void runningOutOfMemorySaysHowToGiveRobotMore() {
        String explanation = MakeRun.explain("reason",
                failedWith("Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap"));

        assertTrue(explanation.contains("ROBOT_JAVA_ARGS"), explanation);
        assertTrue(explanation.contains("-Xmx"), explanation);
    }

    @Test
    void aTargetThatNoLongerExistsSaysTheMakefileMayHaveChanged() {
        String explanation = MakeRun.explain("gone",
                failedWith("make: *** No rule to make target 'gone'.  Stop."));

        assertTrue(explanation.contains("gone"), explanation);
        assertTrue(explanation.contains("Makefile may have changed"), explanation);
    }

    @Test
    void qualityViolationsArePointedAtTheReport() {
        String explanation = MakeRun.explain("report",
                failedWith("ERROR Report failed with 12 violations"));

        assertTrue(explanation.contains("Quality report"), explanation);
    }

    /** An unrecognised failure must still carry make's own last word, not swallow it. */
    @Test
    void anUnrecognisedFailureShowsWhatMakeSaid() {
        String explanation = MakeRun.explain("all",
                failedWith("some output", "make: *** [Makefile:12: all] Error 1"));

        assertTrue(explanation.contains("Error 1"), explanation);
        assertTrue(explanation.contains("exit 2"), explanation);
    }

    /** A build killed by the clock is not a build that failed, and the advice differs. */
    @Test
    void aTimedOutBuildIsDistinguishedFromAFailedOne() {
        String explanation = MakeRun.explain("all",
                new ProcessRunner.Outcome(-1, Arrays.asList("Making..."), true));

        assertTrue(explanation.contains(String.valueOf(MakeRun.TIMEOUT_MINUTES)), explanation);
        assertTrue(explanation.contains("waiting for input"), explanation);
    }

    // ---------- Protege will not notice the file changing ----------

    /**
     * The one that costs work. A target that regenerates the open file leaves Protege holding a
     * stale copy, and the next save overwrites what the build produced.
     */
    @Test
    void aTargetThatRewritesTheOpenFileSaysSo(@TempDir File directory) throws Exception {
        File ontology = projectWithMakefile(directory);

        for (String target : new String[] {"all", "reason", "prepare_release", "release"}) {
            String hint = MakeRun.reloadHint(target, ontology);
            assertNotNull(hint, target + " rewrites the ontology and says nothing");
            assertTrue(hint.contains("mwo.owl"), target + " -> " + hint);
            assertTrue(hint.contains("will not notice"), target + " -> " + hint);
        }
    }

    @Test
    void anImportTargetSaysTheModulesChanged(@TempDir File directory) throws Exception {
        String hint = MakeRun.reloadHint("imports", projectWithMakefile(directory));

        assertNotNull(hint);
        assertTrue(hint.contains("import modules"), hint);
    }

    @Test
    void aTargetThatChangesNothingOpenSaysNothing(@TempDir File directory) throws Exception {
        assertNull(MakeRun.reloadHint("report", projectWithMakefile(directory)));
        assertNull(MakeRun.reloadHint("clean", projectWithMakefile(directory)));
    }

    @Test
    void nothingToHintAboutIsNotAFailure() {
        assertNull(MakeRun.reloadHint("all", null));
        assertNull(MakeRun.reloadHint(null, new File("x-edit.owl")));
    }

    // ---------- the targets offered ----------

    @Test
    void theTargetsComeFromTheProjectsOwnMakefile(@TempDir File directory) throws Exception {
        File ontology = projectWithMakefile(directory);

        List<String> targets = MakeRun.targetsFor(ontology);

        assertTrue(targets.contains("all"), targets.toString());
        assertTrue(targets.contains("reason"), targets.toString());
    }

    @Test
    void noMakefileMeansNoTargetsRatherThanAnError(@TempDir File directory) throws Exception {
        File ontology = new File(directory, "x-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        assertTrue(MakeRun.targetsFor(ontology).isEmpty());
        assertTrue(MakeRun.targetsFor(null).isEmpty());
    }
}
