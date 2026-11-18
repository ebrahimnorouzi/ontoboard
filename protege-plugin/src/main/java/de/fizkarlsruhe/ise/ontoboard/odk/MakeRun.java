package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Running an ODK project's own build.
 *
 * <p>{@link MakeTargets} could read a Makefile and list its targets in a sensible order, was
 * tested against a real 767-line ODK Makefile, and nothing could run any of them. A user who
 * wanted the thing the target does had to leave Protege for a terminal - which is most of the
 * reason the plugin exists.
 *
 * <p>The transcript is the product here, not a side effect. A build that fails does so in the
 * middle of several hundred lines of ROBOT output, and the useful part is usually forty lines up
 * from the end; a result that reported only "make failed (exit 2)" would send somebody to the
 * terminal anyway, which is precisely what this is for.
 *
 * <p>Nothing here touches the ontology Protege has open. {@code make} writes files behind
 * Protege's back; the action that ran it has Protege read again what it has open of them
 * ({@code menu.WhatARunWrote}).
 */
public final class MakeRun {

    /**
     * How long a build may take before it is abandoned.
     *
     * <p>Generous, because a full ODK release on a large ontology genuinely takes tens of minutes
     * and killing it at five would be worse than useless. Bounded, because a recipe waiting on
     * input nobody can give it would otherwise hold a thread for the rest of the session.
     */
    public static final long TIMEOUT_MINUTES = 45;

    private MakeRun() {
    }

    /** The default ODK image, used when a project's runner does not name one. */
    public static final String DEFAULT_IMAGE = "obolibrary/odkfull";

    /**
     * How a given project's make can be started on this machine, in preference order.
     *
     * <p>These are the routes that start a program. An ODK project that none of them runs
     * is built by OntoBoard's own engine ({@link BuildPlan#withoutContainer}), which is
     * decided by the action; a project of OntoBoard's older layout, whose Makefile was
     * OntoBoard's own and ran inside Protege up to 1.135, is not built at all any more -
     * {@link #whyNotRunnable} says how it becomes an ODK project.
     */
    public enum Route {
        /** An ODK project, with a native ODK environment configured: no container. */
        ODK_NATIVE,
        /** An ODK project, through Docker or Podman, in the project's own ODK image. */
        ODK_IN_CONTAINER,
        /** Some other Makefile, and {@code make} is on the PATH. */
        MAKE_ON_PATH,
        /** None of the above; {@link #whyNotRunnable} says what to do. */
        NOT_RUNNABLE
    }

    /** The command for a target, run from the directory the Makefile is in. */
    public static List<String> command(String target) {
        return command(target, java.util.Collections.<String, String>emptyMap());
    }

    /**
     * The same, with variables on make's command line - Build's switches that are off,
     * {@code IMP=false} ({@link BuildSwitches}) - after the target, as ODK's documentation
     * writes them.
     */
    public static List<String> command(String target, java.util.Map<String, String> variables) {
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("no target to make");
        }
        // No -j: ODK recipes are not written to be parallel-safe, and interleaved output from a
        // parallel build is unreadable, which defeats the point of keeping the transcript.
        List<String> command = new java.util.ArrayList<String>(Arrays.asList("make",
                target.trim()));
        for (java.util.Map.Entry<String, String> variable : variables.entrySet()) {
            command.add(variable.getKey() + "=" + variable.getValue());
        }
        return command;
    }

    /**
     * How this project's build can be started here, in order of preference.
     *
     * <p>Docker is preferred for an ODK project even when {@code make} is present, because an ODK
     * Makefile does not only need make: its recipes reach for owltools, wget, and a robot carrying
     * plugin jars from {@code /tools/robot-plugins} inside the image. A host make would parse the
     * Makefile and then fail in the middle of a recipe, which is a worse outcome than not starting.
     */
    public static Route routeFor(File editFile, ProcessRunner.Runner runner) {
        File directory = workingDirectory(editFile);
        if (directory == null || !new File(directory, "Makefile").isFile()) {
            return Route.NOT_RUNNABLE;
        }
        // A project of OntoBoard's older layout. Its Makefile was OntoBoard's own, and up to
        // 1.135 OntoBoard ran it inside Protege; a project is ODK's now, and this one is
        // carried over, not built - whatever is installed. It is never handed to a host
        // make: macOS ships /usr/bin/make with the Command Line Tools (GNU Make 3.81 on the
        // Mac that reported "build is not working"), and that died on the first robot call,
        // which reads as a broken project. whyNotRunnable says what to do instead.
        if (writtenByOntoBoard(directory)) {
            return Route.NOT_RUNNABLE;
        }
        if (odkRunner(directory) != null) {
            // An ODK project is never run with a bare host make. That would be the old defect
            // wearing a new coat: make parses the Makefile happily and then dies partway through
            // a recipe that wanted owltools, wget, or a robot carrying plugin jars from inside
            // the image - leaving a half-written build and a transcript that blames the project.
            //
            // A native ODK environment is different, and is preferred over a container: `odk
            // install` provisions the same tools on the host, so make is then running with
            // everything the recipes expect. ODK supports that on Linux and macOS only.
            if (Toolchain.nativeEnvironment() != null) {
                return Route.ODK_NATIVE;
            }
            return containerRuntime(runner) == null ? Route.NOT_RUNNABLE : Route.ODK_IN_CONTAINER;
        }
        if (ProcessRunner.isAvailable(runner, "make", "--version")) {
            return Route.MAKE_ON_PATH;
        }
        return Route.NOT_RUNNABLE;
    }

    /**
     * Which container runtime can actually run something, or null - by what the person has
     * settled in OntoBoard's settings ({@link OdkRoute}): whether a container is used at all,
     * and which program runs it. Left open, the search is the one described here.
     *
     * <p>Podman is accepted because its command line is Docker's: the same {@code run --rm -v
     * host:container -w dir image command} works unchanged, so supporting it costs a name rather
     * than a code path. It matters because Docker Desktop carries a licence condition that some
     * institutions will not accept, and "independent of Docker" usually means independent of
     * that rather than of containers.
     *
     * <p>Docker first only because it is the one ODK's own documentation names.
     */
    public static String containerRuntime(ProcessRunner.Runner runner) {
        return OdkRoute.runtime(runner);
    }

    /**
     * The command that runs one ODK target inside the ODK image.
     *
     * <p>Composed here rather than by calling the project's own {@code run.sh}, for three reasons,
     * each verified against real projects on the machine that reported the bug.
     *
     * <ul>
     *   <li>Their {@code run.sh} and {@code run.bat} both pass {@code -ti}. {@code -t} allocates a
     *       pseudo-terminal and a plugin has none, so Docker refuses with "the input device is not
     *       a TTY". The wrapper cannot be used unmodified from here, and must not be edited: it is
     *       the user's file and it is committed to their repository.
     *   <li>{@code run.sh} needs {@code sh}, and {@code run.bat} needs a shell to expand
     *       {@code %cd%}. Neither {@code sh} nor {@code make} is on the Windows PATH that Protege
     *       inherits - checked on that machine - while {@code docker} is, because Docker Desktop
     *       puts it there.
     *   <li>A composed command can be printed into the transcript and pasted into a terminal,
     *       which is most of what makes a failed build diagnosable.
     * </ul>
     *
     * <p>The mount mirrors the project's own wrapper: repository root at {@code /work}, working
     * directory {@code /work/src/ontology}. The rest is {@link OdkContainer#command}, which
     * issues what {@code run.sh} issues. {@code --rm} because a container per build would
     * otherwise accumulate silently.
     */
    public static List<String> dockerCommand(File ontologyDirectory, String image, String target) {
        return containerCommand("docker", ontologyDirectory, image, target);
    }

    /** The same, naming the runtime - {@code docker} or {@code podman}. */
    public static List<String> containerCommand(String runtime, File ontologyDirectory,
            String image, String target) {
        return containerCommand(runtime, ontologyDirectory, image, OdkContainer.Host.unknown(),
                target);
    }

    /**
     * The same, on a machine that has been asked about itself: its memory decides Java's when
     * the project names none, and on Linux and macOS the files made belong to the user.
     */
    public static List<String> containerCommand(String runtime, File ontologyDirectory,
            String image, OdkContainer.Host host, String target) {
        return containerCommand(runtime, ontologyDirectory, image, host, target,
                java.util.Collections.<String, String>emptyMap());
    }

    /** The same, with variables on make's command line ({@link #command(String, java.util.Map)}). */
    public static List<String> containerCommand(String runtime, File ontologyDirectory,
            String image, OdkContainer.Host host, String target,
            java.util.Map<String, String> variables) {
        return containerCommandFor(runtime, ontologyDirectory, image, host,
                command(target, variables));
    }

    /**
     * The container invocation for an arbitrary command, not only for {@code make}.
     *
     * <p>Extracted so that running one of the project's own scripts uses the same mount, the
     * same working directory and the same heap settings as a build does, rather than a second
     * composition that drifts from this one. The project's scripts are not peripheral: a real
     * ODK Makefile sets {@code SHELL = $(SCRIPTSDIR)/run-command.sh}, so every recipe line in
     * the build already runs through one.
     *
     * @param inContainer the command and its arguments, as the container will see them - paths
     *     must be the container's, so {@code /work/src/scripts/x.sh} rather than a host path
     */
    public static List<String> containerCommandFor(String runtime, File ontologyDirectory,
            String image, List<String> inContainer) {
        return containerCommandFor(runtime, ontologyDirectory, image,
                OdkContainer.Host.unknown(), inContainer);
    }

    /** The same, on a machine that has been asked about itself. */
    public static List<String> containerCommandFor(String runtime, File ontologyDirectory,
            String image, OdkContainer.Host host, List<String> inContainer) {
        if (inContainer == null || inContainer.isEmpty()) {
            throw new IllegalArgumentException("no command to run in the container");
        }
        if (ontologyDirectory == null) {
            throw new IllegalArgumentException("no project directory to mount");
        }
        File src = ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        if (root == null) {
            throw new IllegalArgumentException(
                    "expected <project>/src/ontology, got " + ontologyDirectory.getAbsolutePath());
        }
        // The project's own setting, not a hardcoded one. It is passed with -e, which wins over
        // anything in the environment, so a project declaring robot_java_args: "-Xmx16G" used to
        // get 8G with nothing to say so - and on a large ontology that is the difference between
        // a build that finishes and one that dies. A project that declares none gets what
        // run.sh would give it: nine tenths of the runtime's memory.
        String declared = OdkBuildSettings.scalarAt(ontologyDirectory, "robot_java_args");
        return OdkContainer.command(
                runtime == null || runtime.trim().isEmpty() ? "docker" : runtime,
                root,
                image == null || image.trim().isEmpty() ? DEFAULT_IMAGE : image,
                host, declared, inContainer);
    }

    /** Where the build runs: the directory holding the Makefile, beside the edit file. */
    public static File workingDirectory(File editFile) {
        return editFile == null ? null : editFile.getParentFile();
    }

    /**
     * Why the build cannot be started, or null.
     *
     * <p>Checked before running rather than reported as a failed build, because "make: not found"
     * inside a transcript reads as the project being broken when it is the machine that is not
     * set up.
     */
    public static String whyNotRunnable(File editFile, ProcessRunner.Runner runner) {
        File directory = workingDirectory(editFile);
        if (directory == null || !directory.isDirectory()) {
            return "This ontology has not been saved, so there is no project directory to build "
                    + "in.";
        }
        if (!new File(directory, "Makefile").isFile()) {
            return "There is no Makefile in " + directory.getAbsolutePath()
                    + ". OntoBoard > Project > New ODK project writes one; a project made another "
                    + "way may keep its build somewhere else.";
        }
        if (routeFor(editFile, runner) != Route.NOT_RUNNABLE) {
            return null;
        }
        if (writtenByOntoBoard(directory)) {
            return OdkMigration.CARRY_OVER + " After that, Build runs ODK's targets on it.";
        }
        return toolingAdvice(odkRunner(directory) != null,
                ProcessRunner.isAvailable(runner, "docker", "--version"),
                ProcessRunner.isAvailable(runner, "sh", "--version"),
                isWindows());
    }

    /**
     * Whether the Makefile beside the edit file opens with the line every Makefile of
     * OntoBoard's older layout opened with, whichever version wrote it.
     */
    static boolean writtenByOntoBoard(File directory) {
        File makefile = directory == null ? null : new File(directory, "Makefile");
        if (makefile == null || !makefile.isFile()) {
            return false;
        }
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(makefile), java.nio.charset.StandardCharsets.UTF_8));
            try {
                String first = reader.readLine();
                return first != null
                        && first.trim().equals(OdkMigration.OLD_MAKEFILE_HEADER);
            } finally {
                reader.close();
            }
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** True on Windows, where "install make" is not by itself a complete instruction. */
    static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * ODK's own Docker wrapper for this project, or null.
     *
     * <p>A real ODK repository is driven through {@code run.sh}, not through {@code make}. Its own
     * Makefile says so, printing {@code Usage: ... sh run.sh make ... command} in its help target,
     * and the wrapper is a few lines that mount the repository into {@code obolibrary/odkfull} and
     * run the argument there.
     *
     * <p><b>It sits beside the Makefile, in {@code src/ontology}.</b> 1.69.0 looked two levels up
     * at the repository root, which is where it is not, so this returned null for every real ODK
     * project and the advice that depends on it never appeared - the exact failure that release
     * was written to fix. Checked against the two ODK repositories on the machine that reported
     * it: go-ontology and environmental-exposure-ontology both keep {@code run.sh} and
     * {@code run.bat} in {@code src/ontology}, next to the Makefile.
     *
     * <p>The repository root is still checked, second, because nothing in ODK forbids it and a
     * project that has moved the wrapper is better served than refused. Both names are checked
     * because ODK ships {@code run.bat} beside {@code run.sh} for Windows.
     *
     * <p>No test caught the original mistake because the fixture this project keeps -
     * {@code src/test/resources/fixture-mwo-Makefile} - is a bare file with no directory around
     * it, so there was no {@code src/ontology} for a runner to sit in. The tests below build the
     * real shape instead.
     */
    static File odkRunner(File ontologyDirectory) {
        if (ontologyDirectory == null) {
            return null;
        }
        File beside = runnerIn(ontologyDirectory);
        if (beside != null) {
            return beside;
        }
        File src = ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        return root == null ? null : runnerIn(root);
    }

    /** {@code run.sh}, or {@code run.bat}, in one directory. */
    private static File runnerIn(File directory) {
        File shell = new File(directory, "run.sh");
        if (shell.isFile()) {
            return shell;
        }
        File batch = new File(directory, "run.bat");
        return batch.isFile() ? batch : null;
    }

    /**
     * What to do about a missing {@code make}, which depends entirely on whose Makefile it is.
     *
     * <p>The message this replaces said one thing in every case: "it needs make and robot
     * installed - not Docker". For a project this plugin scaffolded that is right. For a real ODK
     * repository - which is what somebody is most likely to have open, and what
     * {@link MakeTargets} was specifically built to read - it is the wrong instruction in both
     * halves. ODK builds run inside {@code obolibrary/odkfull}: the recipes call
     * {@code owltools}, {@code wget}, {@code curl} and a {@code robot} that loads plugin jars
     * from {@code /tools/robot-plugins} inside the image. Installing make and robot cannot supply
     * any of that, so following the old advice ends with a build that fails further in.
     *
     * <p>And on Windows, "install make" is incomplete even for our own Makefile: the recipes use
     * {@code rm -f}, {@code mkdir -p}, {@code cp}, {@code cat} and {@code date +%Y-%m-%d}, none of
     * which cmd.exe provides. Make needs to find an {@code sh.exe} to hand them to.
     *
     * <p>Pure so the four cases can be tested; the caller does the probing.
     */
    static String toolingAdvice(boolean odkRepository, boolean hasDocker, boolean hasShell,
            boolean windows) {
        if (odkRepository) {
            // Reached only when Docker cannot run something - routeFor would have taken the
            // ODK_IN_DOCKER branch otherwise - so this is always an instruction about Docker.
            String what = "This is an ODK repository. Its build runs inside the "
                    + "obolibrary/odkfull Docker image, because its recipes use owltools, wget "
                    + "and ROBOT plugins that exist only in that image - installing make and "
                    + "robot would not be enough. OntoBoard will run the build for you once "
                    + "Docker can be reached. ";
            return hasDocker
                    ? what + "Docker is installed but not responding: start Docker Desktop and "
                            + "wait for it to say it is running, then try again."
                    : what + "Install Docker Desktop and start it: "
                            + "https://www.docker.com/products/docker-desktop/";
        }
        String base = "make is not on the PATH, so this project's build cannot be started from "
                + "here.";
        if (!windows) {
            return base + " Install GNU make with your package manager - 'brew install make' on "
                    + "macOS, 'apt install make' on Debian or Ubuntu - and restart Protege so it "
                    + "picks up the new PATH.";
        }
        String windowsAdvice = base + " On Windows the shortest route is Chocolatey "
                + "('choco install make') or Scoop ('scoop install make'), and make also needs a "
                + "POSIX shell to run the recipes with: this Makefile uses rm -f, mkdir -p, cp "
                + "and date, which cmd.exe does not have.";
        return hasShell ? windowsAdvice + " You already have an sh on the PATH, so make alone "
                + "should be enough."
                : windowsAdvice + " Installing Git for Windows provides one. Restart Protege "
                        + "afterwards so it picks up the new PATH.";
    }

    /** make's word on a rule it does not have: the target, and what needed it. */
    private static final java.util.regex.Pattern NO_RULE = java.util.regex.Pattern.compile(
            "No rule to make target '([^']+)'(?:, needed by '([^']+)')?");

    /** What a transcript says, in lower case, when a download failed for want of the network. */
    private static final java.util.regex.Pattern NO_NETWORK = java.util.regex.Pattern.compile(
            "could not resolve host|curl: \\((?:6|7|28|35|52|56)\\)|unknownhostexception"
                    + "|connect timed out|connection timed out|network is unreachable"
                    + "|temporary failure in name resolution|no route to host");

    /**
     * What a failed build most likely means, in terms a user can act on.
     *
     * <p>make's own exit codes say nothing, and the reason is somewhere in the transcript. These
     * are the ones worth naming; everything else is handed over with the last meaningful line,
     * which is where a build tool puts its complaint.
     */
    /**
     * As {@link #explain(String, ProcessRunner.Outcome)}, for a run with Build's switches: where
     * "Download the imports' sources again" was unticked and make stops on a mirror it has no
     * rule for - a fresh clone has none - the way out is that box, said as the engine's route
     * says it before a run (Review Focus 5; the 1.154.0 review's I2).
     *
     * @param off Build's switches that were off; null or empty for ODK's defaults
     */
    public static String explain(String target, ProcessRunner.Outcome outcome,
            java.util.Set<BuildSwitches.Switch> off) {
        if (off != null && off.contains(BuildSwitches.Switch.MIR) && !outcome.isSuccess()
                && !outcome.wasCancelled() && !outcome.timedOut()) {
            java.util.regex.Matcher noRule = NO_RULE.matcher(joined(outcome.getOutput()));
            if (noRule.find() && noRule.group(1).startsWith("mirror/")) {
                String box = BuildSwitches.Switch.MIR.words();
                return "make has no rule to make '" + noRule.group(1) + "'"
                        + (noRule.group(2) == null ? "" : ", which '" + noRule.group(2)
                                + "' needs") + ", so '" + target + "' stopped: a file under "
                        + "mirror/ is an imported ontology as it was downloaded, and with \""
                        + box + "\" unticked nothing downloads it - a fresh clone has none. "
                        + "Tick \"" + box + "\" and make downloads the imports' sources first.";
            }
        }
        return explain(target, outcome);
    }

    public static String explain(String target, ProcessRunner.Outcome outcome) {
        if (outcome.isSuccess()) {
            return null;
        }
        if (outcome.wasCancelled()) {
            // Before the exit code is read. A killed process exits nonzero and prints whatever it
            // had reached, which looks exactly like a build that broke.
            return "You stopped the build. Whatever it had already written to disk is still "
                    + "there, so the next run may start from a half-finished state - "
                    + "'make clean' if that matters.";
        }
        if (outcome.timedOut()) {
            return "The build was still running after " + TIMEOUT_MINUTES + " minutes and was "
                    + "stopped. A release on a large ontology can genuinely take that long - run "
                    + "it in a terminal if so. If it was not doing anything, a recipe may have "
                    + "been waiting for input, which it cannot get from here.";
        }
        String transcript = joined(outcome.getOutput()).toLowerCase(Locale.ROOT);
        java.util.regex.Matcher noRule = NO_RULE.matcher(joined(outcome.getOutput()));
        if (noRule.find() && !noRule.group(1).equals(target)) {
            // The rule that is missing is the one make names, which need not be the
            // target that was asked for.
            String missing = noRule.group(1);
            return "make has no rule to make '" + missing + "'"
                    + (noRule.group(2) == null ? "" : ", which '" + noRule.group(2)
                            + "' needs") + ", so '" + target + "' stopped."
                    + (missing.startsWith("mirror/") ? " A file under mirror/ is an "
                            + "imported ontology as it was downloaded: test_fast and "
                            + "prepare_release_fast download nothing, so a project whose "
                            + "own rules build its import modules from the downloads "
                            + "needs test or prepare_release, which fetch them first."
                            : "");
        }
        if (transcript.contains("no rule to make target")) {
            return "make has no rule for '" + target + "'. The Makefile may have changed since "
                    + "this list was read - reopen the dialog to read it again.";
        }
        if (transcript.contains("robot: not found")
                || transcript.contains("'robot' is not recognized")
                || transcript.contains("robot: command not found")) {
            return "The build calls robot and could not find it. Install ROBOT and put it on the "
                    + "PATH: this Makefile calls it directly.";
        }
        if (transcript.contains("java.lang.outofmemoryerror")) {
            // Naming the YAML key, not the environment variable. The old advice could not work:
            // the container is started with -e ROBOT_JAVA_ARGS=..., which overrides whatever the
            // user had exported, so following it changed nothing and read as ROBOT ignoring the
            // setting.
            return "ROBOT ran out of memory. Raise robot_java_args in the project's "
                    + "<id>-odk.yaml - \"-Xmx16G\" for example - and run the build again. "
                    + "OntoBoard passes that value into the container, so setting "
                    + "ROBOT_JAVA_ARGS in your own shell has no effect on it. "
                    + "Project > Project configuration... edits the key.";
        }
        // Which import's source could not be fetched, from where, and why: when the run
        // stopped there, that is said rather than the mirror make names last.
        ImportFailure fetch = ImportFailure.of(outcome.getOutput());
        if (fetch != null) {
            String fast = fastOf(target);
            return fetch.sentence() + (fast == null ? "" : " Run " + fast + " instead, which "
                    + "builds from the imports as they are.");
        }
        if (theNetworkStoppedIt(outcome.getOutput())) {
            // make's last line names a mirror file, not the cause. ODK's full targets fetch the
            // project's imports; the fast ones build from the imports as they are.
            String fast = fastOf(target);
            return "The build could not download what it fetches from the web - the "
                    + "project's imports - so it stopped: the network, or the server, did not "
                    + "answer." + (fast == null ? " Run it again when the network is back."
                            : " Run " + fast + " instead, which builds from the imports as "
                                    + "they are, or " + target + " again when the network is "
                                    + "back.");
        }
        // The report is the reason when it stopped the run: every run of ODK's prints its
        // count of violations, whatever stops it later.
        String report = OdkTargets.whatTheReportFound(outcome.getOutput());
        if (report != null) {
            return report;
        }
        // The last line's own word, not the folder ODK keeps every check's file in: the
        // profile check's file is reports/validate_profile_..., and is no quality report.
        if (transcript.contains("violation") && outcome.lastMeaningfulLine()
                .toLowerCase(Locale.ROOT).replace("reports/", "").contains("report")) {
            return "The quality report found violations the project treats as failures. Run "
                    + "OntoBoard > Check and test > Quality report to see them one by one.";
        }
        String tail = outcome.lastMeaningfulLine();
        return "make " + target + " failed (exit " + outcome.getExitCode() + ")"
                + (tail.isEmpty() ? "." : ": " + tail);
    }

    /** ODK's target that does what this one does from the imports as they are; null for none. */
    private static String fastOf(String target) {
        return target.equals("all") ? "test_fast"
                : target.equals("test") || target.equals("prepare_release") ? target + "_fast"
                        : null;
    }

    /** How far from its end a transcript's word on the network is why the run stopped. */
    private static final int NETWORK_TAIL = 15;

    /**
     * Whether a download that failed is where the run ended: a line that says so among the
     * transcript's last few. curl says each try that failed, also when the next one went
     * through, so the word somewhere in a long log is no reason (review of 1.153.0).
     */
    private static boolean theNetworkStoppedIt(List<String> output) {
        for (String line : output.subList(Math.max(0, output.size() - NETWORK_TAIL),
                output.size())) {
            if (line != null && NO_NETWORK.matcher(line.toLowerCase(Locale.ROOT)).find()) {
                return true;
            }
        }
        return false;
    }

    private static String joined(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    /** The targets worth offering, best first, or an empty list when there is no Makefile. */
    public static List<String> targetsFor(File editFile) {
        try {
            // ordered, not raw file order. MakeTargets.ordered exists to put all, test, reason,
            // report and prepare_release ahead of the rest - its own javadoc calls that "the
            // difference between a usable menu and a wall" for a Makefile with forty targets -
            // and the only menu that offers targets never called it, while its help text told the
            // user the order was curated.
            return MakeTargets.ordered(MakeTargets.of(editFile));
        } catch (RuntimeException noMakefile) {
            return new ArrayList<String>();
        }
    }
}
