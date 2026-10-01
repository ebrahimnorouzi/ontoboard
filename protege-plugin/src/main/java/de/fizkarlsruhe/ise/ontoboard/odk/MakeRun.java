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
 * <p>Nothing here touches the ontology Protege has open. {@code make} writes files, and Protege
 * will not notice - {@link #reloadHint} says which ones, so a caller can tell the user rather than
 * leaving them looking at a stale window.
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
     * How a given project's build can actually be started on this machine.
     *
     * <p>In preference order, and the order is the point: the routes that need nothing installed
     * come first, and a container is the last resort rather than the assumption.
     */
    public enum Route {
        /** A project OntoBoard scaffolded: every target runs inside Protege, needing nothing. */
        IN_PROCESS,
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
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("no target to make");
        }
        // No -j: ODK recipes are not written to be parallel-safe, and interleaved output from a
        // parallel build is unreadable, which defeats the point of keeping the transcript.
        return Arrays.asList("make", target.trim());
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
        // First: can we simply do it ourselves? A project this plugin scaffolded needs no
        // tools at all, on any platform, because every ROBOT call in its generated Makefile has
        // an in-process implementation here. InProcessTargets refuses anything it did not write.
        if (InProcessTargets.whyNotEligible(editFile) == null) {
            return Route.IN_PROCESS;
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
     * Whether Docker can actually run something, which is not the same as being installed.
     *
     * <p>{@code docker --version} answers from the CLI alone and says nothing about the engine. On
     * the machine this was written for, the CLI reported 29.5.2 while the daemon was down and
     * every command failed with "failed to connect to the docker API at
     * npipe:////./pipe/dockerDesktopLinuxEngine". {@code docker info} is the one that needs the
     * daemon, so it is the one asked.
     */
    static boolean dockerUsable(ProcessRunner.Runner runner) {
        return ProcessRunner.isAvailable(runner, "docker", "info");
    }

    /**
     * Which container runtime can actually run something, or null.
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
        if (dockerUsable(runner)) {
            return "docker";
        }
        return ProcessRunner.isAvailable(runner, "podman", "info") ? "podman" : null;
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
     * directory {@code /work/src/ontology}. Heap settings match {@code run.bat}. {@code --rm}
     * because a container per build would otherwise accumulate silently.
     */
    public static List<String> dockerCommand(File ontologyDirectory, String image, String target) {
        return containerCommand("docker", ontologyDirectory, image, target);
    }

    /** The same, naming the runtime - {@code docker} or {@code podman}. */
    public static List<String> containerCommand(String runtime, File ontologyDirectory,
            String image, String target) {
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("no target to make");
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
        List<String> command = new ArrayList<String>();
        command.add(runtime == null || runtime.trim().isEmpty() ? "docker" : runtime.trim());
        command.add("run");
        command.add("--rm");
        command.add("-v");
        // Forward slashes: Docker Desktop accepts them on Windows, and they keep a backslash from
        // being read as an escape inside the colon-separated bind specification.
        command.add(root.getAbsolutePath().replace(BACKSLASH, '/') + ":/work");
        command.add("-w");
        command.add("/work/src/ontology");
        command.add("-e");
        command.add("ROBOT_JAVA_ARGS=-Xmx8G");
        command.add("-e");
        command.add("JAVA_OPTS=-Xmx8G");
        command.add(image == null || image.trim().isEmpty() ? DEFAULT_IMAGE : image.trim());
        command.add("make");
        command.add(target.trim());
        return command;
    }

    /** The path separator Windows uses and a Docker bind specification cannot carry. */
    private static final char BACKSLASH = '\\';

    /**
     * The ODK image a project pins, read from its own runner.
     *
     * <p>Worth reading rather than assuming: go-ontology pins {@code obolibrary/odkfull:v1.5.4}
     * and says in a comment that the version must be coordinated with its pipeline, while MWO's
     * {@code run.bat} names {@code obolibrary/odkfull} with no tag at all. Building somebody's
     * ontology against a different ODK than their CI uses is how a release stops being
     * reproducible.
     *
     * <p>A tagged mention beats an untagged one wherever both appear, because {@code run.sh}
     * composes its reference from {@code $ODK_IMAGE:$ODK_TAG} and may name the bare image in a
     * comment first.
     */
    static String imageFrom(String runnerScript) {
        if (runnerScript == null) {
            return DEFAULT_IMAGE;
        }
        java.util.regex.Matcher mentions = java.util.regex.Pattern
                .compile("obolibrary/(?:odkfull|odklite)(?::[A-Za-z0-9._-]+)?")
                .matcher(runnerScript);
        String found = null;
        while (mentions.find()) {
            String candidate = mentions.group();
            if (found == null || (candidate.indexOf(':') >= 0 && found.indexOf(':') < 0)) {
                found = candidate;
            }
        }
        return found == null ? DEFAULT_IMAGE : found;
    }

    /** The image this project pins, read from its runner, or the default. */
    public static String imageFor(File ontologyDirectory) {
        File runner = odkRunner(ontologyDirectory);
        if (runner == null) {
            return DEFAULT_IMAGE;
        }
        try {
            return imageFrom(new String(
                    java.nio.file.Files.readAllBytes(runner.toPath()), "UTF-8"));
        } catch (IOException unreadable) {
            return DEFAULT_IMAGE;
        }
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
        return toolingAdvice(odkRunner(directory) != null,
                ProcessRunner.isAvailable(runner, "docker", "--version"),
                ProcessRunner.isAvailable(runner, "sh", "--version"),
                isWindows());
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

    /**
     * What a failed build most likely means, in terms a user can act on.
     *
     * <p>make's own exit codes say nothing, and the reason is somewhere in the transcript. These
     * are the ones worth naming; everything else is handed over with the last meaningful line,
     * which is where a build tool puts its complaint.
     */
    public static String explain(String target, ProcessRunner.Outcome outcome) {
        if (outcome.isSuccess()) {
            return null;
        }
        if (outcome.timedOut()) {
            return "The build was still running after " + TIMEOUT_MINUTES + " minutes and was "
                    + "stopped. A release on a large ontology can genuinely take that long - run "
                    + "it in a terminal if so. If it was not doing anything, a recipe may have "
                    + "been waiting for input, which it cannot get from here.";
        }
        String transcript = joined(outcome.getOutput()).toLowerCase(Locale.ROOT);
        if (transcript.contains("no rule to make target")) {
            return "make has no rule for '" + target + "'. The Makefile may have changed since "
                    + "this list was read - reopen the dialog to read it again.";
        }
        if (transcript.contains("robot: not found")
                || transcript.contains("'robot' is not recognized")
                || transcript.contains("robot: command not found")) {
            return "The build calls robot and could not find it. Install ROBOT and put it on the "
                    + "PATH - the generated Makefile calls it directly rather than through Docker.";
        }
        if (transcript.contains("java.lang.outofmemoryerror")) {
            return "ROBOT ran out of memory. Give it more with ROBOT_JAVA_ARGS, for example "
                    + "-Xmx8G, before running the build.";
        }
        if (transcript.contains("violation") && transcript.contains("report")) {
            return "The quality report found violations the project treats as failures. Run "
                    + "OntoBoard > ROBOT > Quality report to see them one by one.";
        }
        String tail = outcome.lastMeaningfulLine();
        return "make " + target + " failed (exit " + outcome.getExitCode() + ")"
                + (tail.isEmpty() ? "." : ": " + tail);
    }

    /**
     * The files this target is likely to have rewritten, so a caller can say so.
     *
     * <p>Protege holds the ontology in memory and knows nothing about a file changing underneath
     * it. Somebody who runs a build that regenerates the very file they have open, and is not
     * told, will carry on editing a stale copy and overwrite the build's output when they save.
     */
    public static String reloadHint(String target, File editFile) {
        if (editFile == null || target == null) {
            return null;
        }
        String lower = target.toLowerCase(Locale.ROOT);
        if (lower.equals("reason") || lower.equals("all") || lower.startsWith("prepare_release")
                || lower.equals("release")) {
            String id = editFile.getName().replaceAll("(?i)-edit\\.(owl|obo)$", "");
            return "This target rewrites " + id + ".owl. Protege will not notice a file changing "
                    + "on disk - reopen it if you were looking at it.";
        }
        if (lower.startsWith("import")) {
            return "This target rewrites the import modules. Reload the ontology to pick them up.";
        }
        return null;
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
