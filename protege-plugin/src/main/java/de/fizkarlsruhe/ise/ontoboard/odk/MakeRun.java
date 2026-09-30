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

    /** The command for a target, run from the directory the Makefile is in. */
    public static List<String> command(String target) {
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("no target to make");
        }
        // No -j: ODK recipes are not written to be parallel-safe, and interleaved output from a
        // parallel build is unreadable, which defeats the point of keeping the transcript.
        return Arrays.asList("make", target.trim());
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
        if (!ProcessRunner.isAvailable(runner, "make", "--version")) {
            return toolingAdvice(odkRunner(directory) != null,
                    ProcessRunner.isAvailable(runner, "docker", "--version"),
                    ProcessRunner.isAvailable(runner, "sh", "--version"),
                    isWindows());
        }
        return null;
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
     * Makefile says so - the one this project keeps as a fixture prints
     * {@code Usage: ... sh run.sh make ... command} in its help target - and the wrapper is 150
     * lines that mount the repository into {@code obolibrary/odkfull} and run the target there.
     *
     * <p>It sits at the repository root, two levels above {@code src/ontology}, which is where
     * {@link #workingDirectory} lands. Checked for both names because ODK ships {@code run.bat}
     * alongside {@code run.sh} for Windows.
     */
    static File odkRunner(File ontologyDirectory) {
        if (ontologyDirectory == null) {
            return null;
        }
        File src = ontologyDirectory.getParentFile();
        File root = src == null ? null : src.getParentFile();
        if (root == null) {
            return null;
        }
        File shell = new File(root, "run.sh");
        if (shell.isFile()) {
            return shell;
        }
        File batch = new File(root, "run.bat");
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
            String how = "This is an ODK repository, and ODK builds run inside the "
                    + "obolibrary/odkfull Docker image rather than against tools on your PATH - "
                    + "its recipes use owltools, wget and ROBOT plugins that live in the image. "
                    + "Run the target from a terminal at the repository root with "
                    + "'sh run.sh make <target>' (run.bat on Windows).";
            return hasDocker ? how
                    : how + " Docker was not found either, so install Docker Desktop first: "
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
