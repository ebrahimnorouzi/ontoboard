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

    /**
     * A runner that answers for some tools and not others.
     *
     * <p>The single-boolean helper above cannot express the case that matters here - Docker
     * present while make is absent - which is the configuration of the machine that reported the
     * bug, and the one the whole Docker route exists for.
     */
    private static ProcessRunner.Runner runnerWith(final String... availableTools) {
        final java.util.Set<String> available =
                new java.util.HashSet<String>(Arrays.asList(availableTools));
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File directory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                boolean known = !command.isEmpty() && available.contains(command.get(0));
                return new ProcessRunner.Outcome(known ? 0 : 127,
                        Collections.<String>emptyList(), false);
            }
        };
    }

    /** A project laid out the way ODK lays one out, with a runner beside the Makefile. */
    private static File odkProject(File root) throws IOException {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "Makefile").toPath(), "all:\n".getBytes("UTF-8"));
        Files.write(new File(ontology, "run.sh").toPath(),
                "docker run --rm -ti obolibrary/odkfull:v1.5.4 \"$@\"".getBytes("UTF-8"));
        return new File(ontology, "mwo-edit.owl");
    }

    // ---------- the Docker route ----------

    /**
     * An ODK project with a working Docker goes through Docker, even when make is present.
     *
     * <p>Preferring Docker over an available make is deliberate and is the whole point. An ODK
     * Makefile does not merely need make: its recipes reach for owltools, wget and a robot
     * carrying plugin jars that exist only inside the image. A host make parses the Makefile
     * successfully and then dies somewhere in the middle of a recipe, which is worse than not
     * starting - the user gets a half-written build instead of an instruction.
     */
    @Test
    void anOdkProjectPrefersAContainerEvenWhenMakeIsThere(@TempDir File root) throws Exception {
        File edit = odkProject(root);

        assertEquals(MakeRun.Route.ODK_IN_CONTAINER,
                MakeRun.routeFor(edit, runnerWith("docker", "make")));
        assertNull(MakeRun.whyNotRunnable(edit, runnerWith("docker", "make")),
                "nothing to complain about when it can run");
    }

    /**
     * Docker installed but not running is its own case, and the commonest one.
     *
     * <p>On the machine that reported this, {@code docker --version} answered 29.5.2 while the
     * daemon was down and every real command failed with "failed to connect to the docker API at
     * npipe:////./pipe/dockerDesktopLinuxEngine". So availability is probed with {@code docker
     * info}, which needs the daemon, and the advice distinguishes "install it" from "start it" -
     * two different actions, and telling somebody to install what they already have is how a
     * message stops being believed.
     */
    @Test
    void dockerInstalledButNotRunningIsSaidDifferentlyFromDockerAbsent(@TempDir File root)
            throws Exception {
        File edit = odkProject(root);

        // No docker at all, but make is there. An ODK project still refuses, because a host
        // make would die partway through a recipe rather than at the start.
        assertEquals(MakeRun.Route.NOT_RUNNABLE, MakeRun.routeFor(edit, runnerWith("make")));

        String down = MakeRun.toolingAdvice(true, true, false, true);
        assertTrue(down.contains("not responding"), down);
        assertTrue(down.contains("start Docker Desktop"), down);
        assertFalse(down.contains("Install Docker Desktop"),
                "it is already installed; do not send them to the download page: " + down);

        String absent = MakeRun.toolingAdvice(true, false, false, true);
        assertTrue(absent.contains("Install Docker Desktop"), absent);
        assertTrue(absent.contains("docker.com"), absent);
    }

    /** A project with no ODK runner and no make still gets the make advice. */
    @Test
    void aPlainMakefileWithoutMakeIsStillAMakeProblem(@TempDir File directory) throws Exception {
        File ontology = projectWithMakefile(directory);

        assertEquals(MakeRun.Route.NOT_RUNNABLE, MakeRun.routeFor(ontology, runnerWith("docker")));
        assertTrue(MakeRun.whyNotRunnable(ontology, runnerWith("docker")).contains("install make"));
    }

    /**
     * The composed command, which is what actually runs.
     *
     * <p>Composed rather than delegated to the project's own run.sh, because that script passes
     * {@code -ti}: {@code -t} allocates a pseudo-terminal, a plugin has none, and Docker refuses
     * with "the input device is not a TTY". Verified against the real thing - this exact command
     * shape ran `make sparql_test` against MWO in 13 seconds, exit 0, four ROBOT checks passing.
     */
    @Test
    void theDockerCommandMountsTheRepositoryRootAndCarriesNoTty(@TempDir File root)
            throws Exception {
        File edit = odkProject(root);
        File ontology = MakeRun.workingDirectory(edit);

        List<String> command = MakeRun.dockerCommand(ontology, "obolibrary/odkfull:v1.5.4",
                "sparql_test");

        assertEquals("docker", command.get(0));
        assertEquals("run", command.get(1));
        assertTrue(command.contains("--rm"), "a container per build would accumulate: " + command);
        assertFalse(command.contains("-ti"), "a plugin has no terminal: " + command);
        assertFalse(command.contains("-t"), "and -t alone is enough to break it: " + command);
        assertEquals("make", command.get(command.size() - 2));
        assertEquals("sparql_test", command.get(command.size() - 1));
        assertTrue(command.contains("obolibrary/odkfull:v1.5.4"), command.toString());

        String mount = command.get(command.indexOf("-v") + 1);
        assertTrue(mount.endsWith(":/work"), mount);
        assertFalse(mount.contains("\\"),
                "a backslash is an escape inside a bind specification: " + mount);
        assertTrue(mount.startsWith(root.getAbsolutePath().replace('\\', '/')),
                "the repository root is mounted, not src/ontology: " + mount);
        assertEquals("/work/src/ontology", command.get(command.indexOf("-w") + 1));
    }

    /** A target is still required, and a directory that is not src/ontology is refused. */
    @Test
    void theDockerCommandRefusesWhatItCannotPlace(@TempDir File root) {
        assertThrows(IllegalArgumentException.class,
                () -> MakeRun.dockerCommand(new File(root, "src/ontology"), null, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> MakeRun.dockerCommand(null, null, "all"));
    }

    /**
     * The project's own pinned image is used, not a guess.
     *
     * <p>go-ontology pins {@code obolibrary/odkfull:v1.5.4} and its comment says the version must
     * be coordinated with its pipeline; MWO's run.bat names the image with no tag. Building
     * somebody's ontology against a different ODK than their CI uses is how a release stops being
     * reproducible, so the pin is read rather than assumed.
     */
    @Test
    void theImageIsReadFromTheProjectsOwnRunner() {
        assertEquals("obolibrary/odkfull:v1.5.4", MakeRun.imageFrom(
                "docker run -m 12g -v $PWD/../../:/work --rm -ti obolibrary/odkfull:v1.5.4 \"$@\""));
        assertEquals("obolibrary/odkfull", MakeRun.imageFrom(
                "docker run -v %cd%\\..\\..\\:/work --rm -ti obolibrary/odkfull %*"));
        assertEquals("obolibrary/odklite:v1.4", MakeRun.imageFrom(
                "IMAGE=odklite\ndocker run --rm obolibrary/odklite:v1.4 \"$@\""));
        // A tagged mention beats an earlier bare one: run.sh composes $ODK_IMAGE:$ODK_TAG and
        // often names the plain image in a comment above it.
        assertEquals("obolibrary/odkfull:v1.6", MakeRun.imageFrom(
                "# see obolibrary/odkfull for details\ndocker run obolibrary/odkfull:v1.6 \"$@\""));
        assertEquals(MakeRun.DEFAULT_IMAGE, MakeRun.imageFrom("docker run something-else"));
        assertEquals(MakeRun.DEFAULT_IMAGE, MakeRun.imageFrom(null));
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

    /**
     * An ODK repository is told to use ODK's own runner, not to install make.
     *
     * <p>The field report this comes from: the maintainer opened MWO, asked for a build, and was
     * told "it needs make and robot installed - not Docker". Both halves are wrong for a real ODK
     * repository. Its recipes call owltools and wget and a robot that loads plugin jars from
     * /tools/robot-plugins inside obolibrary/odkfull, so installing make and robot buys a build
     * that fails further in; and the fixture Makefile this project already keeps documents the
     * real instruction in its own help target - "sh run.sh make ... command".
     */
    @Test
    void anOdkRepositoryIsToldOntoBoardWillRunIt() {
        String advice = MakeRun.toolingAdvice(true, true, true, false);

        assertTrue(advice.contains("odkfull"), "say where the build actually runs: " + advice);
        assertTrue(advice.contains("OntoBoard will run the build for you"),
                "1.70.0 runs it rather than describing how: " + advice);
        assertFalse(advice.contains("from a terminal"),
                "sending them to a terminal is what this release stopped doing: " + advice);
    }

    /** And when Docker is missing, that is the one thing to fix. */
    @Test
    void anOdkRepositoryWithoutDockerIsToldToGetDocker() {
        String advice = MakeRun.toolingAdvice(true, false, true, true);

        assertTrue(advice.contains("Install Docker Desktop"), advice);
        assertTrue(advice.contains("docker.com"), "an instruction needs somewhere to go: "
                + advice);
    }

    /**
     * Our own scaffold's Makefile is the case where installing make is genuinely the answer -
     * and on Windows it is only half of it.
     *
     * <p>The generated recipes use rm -f, mkdir -p, cp, cat and date +%Y-%m-%d. cmd.exe has none
     * of them, so make has to find an sh.exe to hand the recipes to. A message that says only
     * "install make" sends a Windows user to a second failure that looks nothing like the first.
     */
    @Test
    void windowsIsToldAboutTheShellAsWellAsMake() {
        String withoutShell = MakeRun.toolingAdvice(false, false, false, true);

        assertTrue(withoutShell.contains("POSIX shell"), withoutShell);
        assertTrue(withoutShell.contains("Git for Windows"), "name the thing that provides one: "
                + withoutShell);

        String withShell = MakeRun.toolingAdvice(false, false, true, true);

        assertTrue(withShell.contains("make alone"),
                "an sh already on the PATH is worth saying: " + withShell);
        assertFalse(withShell.contains("Git for Windows"),
                "do not ask for what is already there: " + withShell);
    }

    /** Elsewhere the package manager is the whole answer. */
    @Test
    void unixIsToldItsPackageManager() {
        String advice = MakeRun.toolingAdvice(false, false, true, false);

        assertTrue(advice.contains("apt install make") || advice.contains("brew install make"),
                advice);
        assertFalse(advice.contains("cmd.exe"), "not a Windows problem here: " + advice);
    }

    /**
     * ODK's runner sits beside the Makefile, in src/ontology.
     *
     * <p>This test asserted the repository root when it was written, and was wrong. Checked
     * afterwards against the two real ODK repositories on the machine that reported the bug -
     * go-ontology and environmental-exposure-ontology - and both keep run.sh and run.bat in
     * src/ontology, next to the Makefile. go-ontology's is six lines wrapping
     * {@code docker run ... obolibrary/odkfull:v1.5.4 "$@"}.
     *
     * <p>The consequence of getting it wrong was total and silent: odkRunner returned null for
     * every real project, so the ODK branch of the advice never ran and those users were told to
     * install make - which is the advice 1.69.0 existed to stop giving them.
     */
    @Test
    void theOdkRunnerSitsBesideTheMakefile(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());

        assertNull(MakeRun.odkRunner(ontology), "nothing there yet");

        File runner = new File(ontology, "run.sh");
        Files.write(runner.toPath(), "#!/bin/sh".getBytes("UTF-8"));

        assertEquals(runner, MakeRun.odkRunner(ontology));
    }

    /**
     * The repository root is still honoured, second.
     *
     * <p>Nothing in ODK forbids keeping the wrapper at the top, and a project that has moved it is
     * better served than refused. Beside-the-Makefile wins when both exist, because that is where
     * the one ODK generates lives.
     */
    @Test
    void theRepositoryRootIsCheckedSecond(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        File atRoot = new File(root, "run.sh");
        Files.write(atRoot.toPath(), "#!/bin/sh".getBytes("UTF-8"));

        assertEquals(atRoot, MakeRun.odkRunner(ontology), "found when it is the only one");

        File beside = new File(ontology, "run.sh");
        Files.write(beside.toPath(), "#!/bin/sh".getBytes("UTF-8"));

        assertEquals(beside, MakeRun.odkRunner(ontology), "beside the Makefile wins");
    }

    /** ODK ships run.bat beside run.sh, and a Windows checkout may have only that one. */
    @Test
    void theWindowsRunnerCountsToo(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        File batch = new File(ontology, "run.bat");
        Files.write(batch.toPath(), "@echo off".getBytes("UTF-8"));

        assertEquals(batch, MakeRun.odkRunner(ontology));
    }

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

    /**
     * "make: not found" inside a transcript reads as the project being broken. It is not.
     *
     * <p>This test used to assert the words "not Docker", on the reasoning that the generated
     * Makefile calls robot directly and users assume ODK means Docker. That reasoning was sound
     * for a project this plugin scaffolded and wrong for every other kind, which is what a user
     * is most likely to have open - see {@link #anOdkRepositoryIsPointedAtItsOwnRunner}. What is
     * still true, and is what this test is actually for, is that a missing tool must be reported
     * as a machine that is not set up rather than as a project that is broken. The fixture here
     * has no run.sh, so it takes the scaffolded-project branch.
     */
    @Test
    void makeMissingIsReportedAsSetupRatherThanAsABrokenProject(@TempDir File directory)
            throws Exception {
        File ontology = projectWithMakefile(directory);

        String why = MakeRun.whyNotRunnable(ontology, runnerThatSays(false));

        assertNotNull(why);
        assertTrue(why.contains("not on the PATH"), why);
        assertTrue(why.contains("install make"),
                "a setup problem has to come with the way out of it: " + why);
        assertFalse(why.contains("Makefile may have changed")
                        || why.contains("no rule for"),
                "nothing here says the project is at fault: " + why);
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
