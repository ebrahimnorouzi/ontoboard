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
import java.util.ArrayList;

/**
 * Running an ODK project's own build from inside Protege.
 *
 * <p>{@code MakeTargets} could read a Makefile and order its targets, was tested against a real
 * 767-line ODK one, and nothing could run a single target - so wanting the thing a target does
 * meant leaving Protege for a terminal, which is most of the reason the plugin exists.
 *
 * <p>What is worth testing is not that {@code make} works. It is the surrounding judgement: that a
 * missing tool is reported as a setup problem rather than a broken project, that a failure points
 * at the part of a several-hundred-line transcript that explains it. What a target rewrites of
 * what Protege has open is read again by the action that ran it ({@code WhatARunWrote}).
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

    /** Build's switches that are off go on make's command line on every route that runs make. */
    @Test
    void theSwitchesThatAreOffGoOnMakesCommandLineOnEveryRoute(@TempDir File root)
            throws Exception {
        File ontology = MakeRun.workingDirectory(odkProject(root));
        java.util.Map<String, String> off = BuildSwitches.variables(java.util.EnumSet.of(
                BuildSwitches.Switch.IMP, BuildSwitches.Switch.MIR));

        assertEquals(Arrays.asList("make", "test", "IMP=false", "MIR=false"),
                MakeRun.command("test", off));
        List<String> container = MakeRun.containerCommand("docker", ontology,
                "obolibrary/odkfull:v1.6.1", OdkContainer.Host.unknown(), "test", off);
        assertEquals(Arrays.asList("make", "test", "IMP=false", "MIR=false"),
                container.subList(container.size() - 4, container.size()));
        List<String> inTheEnvironment = Toolchain.nativeCommand(new File("/odk/activate"), "test",
                off);
        assertTrue(inTheEnvironment.get(2).endsWith(" && make test IMP=false MIR=false"),
                inTheEnvironment.get(2));
    }

    /** A target is still required, and a directory that is not src/ontology is refused. */
    @Test
    void theDockerCommandRefusesWhatItCannotPlace(@TempDir File root) {
        assertThrows(IllegalArgumentException.class,
                () -> MakeRun.dockerCommand(new File(root, "src/ontology"), null, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> MakeRun.dockerCommand(null, null, "all"));
    }

    // Which image runs a project - its own pin, the ODK that wrote its Makefile, or the pinned
    // one - is OdkImage's to decide now, and OdkImageTest's to check: a tag written out in the
    // project's run.sh (go-ontology's v1.5.4) is still its pin.

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

    /**
     * ODK's full targets fetch the project's imports. Without the network they stop in the
     * middle of a download, and what make says last names a mirror file, not the cause. The
     * fast targets build from the imports as they are: that is what to run instead.
     */
    @Test
    void aDownloadThatFailsOffersTheFastTarget() {
        for (String cause : new String[] {
            "curl: (6) Could not resolve host: purl.obolibrary.org",
            "curl: (7) Failed to connect to purl.obolibrary.org port 80: Connection refused",
            "curl: (28) Operation timed out after 200001 milliseconds with 0 bytes received",
            "java.net.UnknownHostException: purl.obolibrary.org",
            "java.net.SocketTimeoutException: connect timed out"}) {
            String explanation = MakeRun.explain("test", failedWith(
                    "curl -L http://purl.obolibrary.org/obo/ro.owl --create-dirs -o "
                            + "tmp/ro-download.owl --retry 4 --max-time 200", cause,
                    "make: *** [Makefile:397: mirror-ro] Error 6"));

            assertTrue(explanation.contains("test_fast"), cause + " -> " + explanation);
            assertTrue(explanation.contains("download"), explanation);
        }
        assertTrue(MakeRun.explain("prepare_release", failedWith(
                "curl: (6) Could not resolve host: purl.obolibrary.org"))
                .contains("prepare_release_fast"));
        assertTrue(MakeRun.explain("refresh-imports", failedWith(
                "curl: (6) Could not resolve host: purl.obolibrary.org"))
                .contains("network"), "a refresh is the download: there is no fast one");
        assertFalse(MakeRun.explain("refresh-imports", failedWith(
                "curl: (6) Could not resolve host: purl.obolibrary.org")).contains("_fast"));
    }

    @Test
    void aTargetThatNoLongerExistsSaysTheMakefileMayHaveChanged() {
        String explanation = MakeRun.explain("gone",
                failedWith("make: *** No rule to make target 'gone'.  Stop."));

        assertTrue(explanation.contains("gone"), explanation);
        assertTrue(explanation.contains("Makefile may have changed"), explanation);
    }

    /**
     * Seen live (1.153.0) on a real project whose own rules build its import modules from
     * downloaded ontologies: test_fast, which downloads nothing, stopped with "No rule to
     * make target 'mirror/ro.owl', needed by 'imports/ro_import.owl'" - and the result said
     * make has no rule for 'test_fast'. The rule that is missing is the one make names.
     */
    @Test
    void aMissingRuleForSomethingElseIsNotSaidOfTheTarget() {
        String explanation = MakeRun.explain("test_fast", failedWith(
                "make[1]: *** No rule to make target 'mirror/ro.owl', needed by "
                        + "'imports/ro_import.owl'.  Stop.",
                "make: *** [Makefile:116: test_fast] Error 2"));

        assertEquals("make has no rule to make 'mirror/ro.owl', which "
                + "'imports/ro_import.owl' needs, so 'test_fast' stopped. A file under mirror/ "
                + "is an imported ontology as it was downloaded: test_fast and "
                + "prepare_release_fast download nothing, so a project whose own rules build "
                + "its import modules from the downloads needs test or prepare_release, "
                + "which fetch them first.", explanation);
        String other = MakeRun.explain("all", failedWith(
                "make: *** No rule to make target 'x.owl', needed by 'y.owl'.  Stop."));
        assertEquals("make has no rule to make 'x.owl', which 'y.owl' needs, so 'all' "
                + "stopped.", other);
    }

    @Test
    void qualityViolationsArePointedAtTheReport() {
        String explanation = MakeRun.explain("report",
                failedWith("ERROR Report failed with 12 violations"));

        assertTrue(explanation.contains("Quality report"), explanation);
    }

    /**
     * Seen live (1.153.0): a release that stopped at an import said "The quality report
     * found violations the project treats as failures" - every run of ODK's prints the
     * report's count of violations, and the words "violation" and "report" anywhere in
     * the log were taken for the reason. The report is the reason when it stopped the run.
     */
    @Test
    void aReportThatWasOnlyPrintedIsNotTheReason() {
        String explanation = MakeRun.explain("prepare_release", failedWith(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--fail-on ERROR --print 5 -o reports/egg-edit.owl-obo-report.tsv",
                "Violations: 3", "-----------------", "ERROR:      0", "WARN:       3",
                "INFO:       0",
                "ERROR: You have configured measurement as a custom module;",
                "       This rule needs to be overwritten in egg.Makefile!",
                "make: *** [Makefile:421: imports/measurement_import.owl] Error 1"));

        assertFalse(explanation.contains("quality report"), explanation);
        assertEquals("make prepare_release failed (exit 2): make: *** [Makefile:421: "
                + "imports/measurement_import.owl] Error 1", explanation);

        String stopped = MakeRun.explain("test", failedWith(
                "robot --catalog catalog-v001.xml report -i tmp/merged-egg-edit.ofn -l true "
                        + "--fail-on ERROR --print 5 -o reports/egg-edit.owl-obo-report.tsv",
                "Violations: 1", "-----------------", "ERROR:      1", "WARN:       0",
                "INFO:       0", "", "First 1 violations:",
                "ERROR\tmissing_label\tb\trdfs:label\t",
                "make: *** [Makefile:230: reports/egg-edit.owl-obo-report.tsv] Error 1"));
        assertTrue(stopped.startsWith("The quality report found 1 error: missing_label b."),
                stopped);
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

    /**
     * A project OntoBoard 1.135 or before made has OntoBoard's own Makefile. Up to 1.135 its
     * build ran inside Protege; a project is ODK's now, and such a one is carried over, not
     * built - on every machine alike, whatever is installed on it: the older Makefile's
     * targets are not ODK's. (macOS ships make, and a host make died on that Makefile's
     * first robot call; with robot on the PATH it ran. Neither is offered any more.)
     */
    @Test
    void aProjectOfTheOlderLayoutIsCarriedOverNotBuilt(@TempDir File where) throws Exception {
        File root = new File(where, "eggboil");
        OdkMigrationTest.copy(MigrationFixtureTest.fixture("odk/migration/eggboil-1.135.2"),
                root);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");

        for (ProcessRunner.Runner machine : Arrays.asList(runnerWith(), runnerWith("make"),
                runnerWith("make", "robot"), runnerWith("docker", "make", "robot"))) {
            assertEquals(MakeRun.Route.NOT_RUNNABLE, MakeRun.routeFor(edit, machine));
            assertEquals(OdkMigration.CARRY_OVER + " After that, Build runs ODK's targets on "
                    + "it.", MakeRun.whyNotRunnable(edit, machine));
        }
        assertTrue(OdkMigration.CARRY_OVER.startsWith("This project was made by OntoBoard "
                + "1.135 or before"), OdkMigration.CARRY_OVER);
        assertTrue(OdkMigration.CARRY_OVER.contains(
                "OntoBoard > Project > Update project files..."), OdkMigration.CARRY_OVER);
        for (MakeRun.Route route : MakeRun.Route.values()) {
            assertFalse(route.name().contains("PROCESS"), "the route for OntoBoard's own "
                    + "Makefile, inside Protege, is gone: " + route);
        }
    }

    // ------------------------------------------------------------------ review of 1.153.0

    /** The file of the profile check lies under reports/: that makes it no quality report. */
    @Test
    void aFileUnderReportsIsNotTheQualityReport() {
        String explanation = MakeRun.explain("prepare_release", failedWith(
                "PASS Rule ../sparql/a-violation.sparql: 0 violation(s)",
                "PROFILE VIOLATION ERROR https://example.org/egg.owl violates profile DL",
                "make: *** [Makefile:251: reports/validate_profile_owl2dl_egg.owl.txt] Error 1"));

        assertEquals("make prepare_release failed (exit 2): make: *** [Makefile:251: "
                + "reports/validate_profile_owl2dl_egg.owl.txt] Error 1", explanation);
    }

    /**
     * curl says each try that failed, also when the next one went through. A download that
     * went through is not why a build stopped much later, for something else.
     */
    @Test
    void aDownloadThatWentThroughOnAnotherTryIsNotTheReason() {
        List<String> lines = new ArrayList<String>(Arrays.asList(
                "curl -L http://purl.obolibrary.org/obo/ro.owl --create-dirs -o "
                        + "tmp/ro-download.owl --retry 4 --max-time 200",
                "curl: (28) Operation timed out after 200001 milliseconds with 0 bytes received",
                "Warning: Problem : timeout. Will retry in 1 seconds. 4 retries left.",
                "robot convert -i tmp/ro-download.owl -o tmp/mirror-ro.owl"));
        for (int step = 0; step < 20; step++) {
            lines.add("robot --catalog catalog-v001.xml extract --term-file imports/x" + step
                    + "_terms.txt");
        }
        lines.add("robot --catalog catalog-v001.xml reason --input egg-edit.owl");
        lines.add("make: *** [Makefile:240: reason_test] Error 1");

        String explanation = MakeRun.explain("test", failedWith(lines.toArray(new String[0])));

        assertFalse(explanation.contains("download"), explanation);
        assertTrue(explanation.contains("reason_test"), explanation);
    }
}
