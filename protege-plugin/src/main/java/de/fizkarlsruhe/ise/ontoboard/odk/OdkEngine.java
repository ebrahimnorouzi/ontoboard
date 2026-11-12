package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A target of an ODK project, run by OntoBoard itself - for a machine where ODK's container
 * cannot run.
 *
 * <p>Nothing of the build is OntoBoard's own: the Makefile is ODK's, read and run as make runs
 * it ({@link MakeFile#run}); each recipe line is read as the shell reads it
 * ({@link RecipeShell}); the programs a line names are ROBOT, inside this JVM, and stand-ins
 * for the few other programs of ODK's image. What OntoBoard decides is only whether it may:
 *
 * <ol>
 *   <li>the Makefile is the one ODK writes for the project's configuration, and the project's
 *       own makefile changes nothing of what the target runs ({@link FallbackEligibility});</li>
 *   <li>every line the run is going to need can be run here - asked before the first line
 *       runs, so that a target is done or not started, never half done for want of a
 *       program.</li>
 * </ol>
 *
 * <p>Then make runs: it decides as it goes, says each line, and stops at the first one that
 * fails. The transcript reads as make's own.
 */
public final class OdkEngine {

    private OdkEngine() {
    }

    /** What a run did. */
    public static final class Outcome {
        private final String failure;
        private final List<String> transcript;
        private final boolean ranAnything;

        private Outcome(String failure, List<String> transcript, boolean ranAnything) {
            this.failure = failure;
            this.transcript = Collections.unmodifiableList(new ArrayList<String>(transcript));
            this.ranAnything = ranAnything;
        }

        public boolean isOk() {
            return failure == null;
        }

        /** Why the target was not made, in a sentence; null when it was. */
        public String getFailure() {
            return failure;
        }

        /** What make and the programs said, line by line. */
        public List<String> getTranscript() {
            return transcript;
        }

        /** False when the target was refused before its first line ran: nothing was touched. */
        public boolean ranAnything() {
            return ranAnything;
        }
    }

    /**
     * Runs a target with the programs OntoBoard has for ODK's recipes ({@link OdkTools}): ROBOT
     * inside this JVM, and the stand-ins for the other programs of ODK's image.
     *
     * @param editFile    the project's edit file, in {@code src/ontology}
     * @param commandLine the variables of this run, as on make's command line
     * @param stopped     asked before each line: whether the person has stopped the run; may be
     *                    null
     */
    public static Outcome run(File editFile, String goal, Map<String, String> commandLine,
            BooleanSupplier stopped) {
        return run(editFile, goal, commandLine, OdkTools.forProject(), stopped);
    }

    /**
     * Why this target is not run here, or null when it is - with the programs OntoBoard has.
     * Nothing is run to find out.
     */
    public static String whyNot(File editFile, String goal, Map<String, String> commandLine) {
        return whyNot(editFile, goal, commandLine, OdkTools.forProject());
    }

    /**
     * Runs a target.
     *
     * @param editFile    the project's edit file, in {@code src/ontology}
     * @param commandLine the variables of this run, as on make's command line
     * @param tools       the programs a recipe line may name
     * @param stopped     asked before each line: whether the person has stopped the run
     */
    static Outcome run(File editFile, final String goal, Map<String, String> commandLine,
            final Map<String, RecipeShell.Tool> tools, final BooleanSupplier stopped) {
        final List<String> transcript = new ArrayList<String>();

        // 1. and 2. May it, and can it? Asked before one line runs.
        String refused = whyNot(editFile, goal, commandLine, tools);
        if (refused != null) {
            return new Outcome(refused, transcript, false);
        }

        // 3. make runs.
        final File ontology = editFile.getAbsoluteFile().getParentFile();
        final boolean[] ran = {false};
        final boolean[] wasStopped = {false};
        final String[] complaint = {null};
        boolean made;
        try {
            made = MakeFile.read(MAKEFILE, MakeFile.onDisk(ontology), commandLine).run(goal,
                    new MakeFile.Executor() {
                        @Override
                        public int run(MakeFile.Step step) {
                            if (stopped != null && stopped.getAsBoolean()) {
                                wasStopped[0] = true;
                                transcript.add("Stopped.");
                                return STOPPED;
                            }
                            // A real run can reach what the look from before it did not show:
                            // each line is asked again as it comes.
                            String why = !MAKEFILE.equals(step.file())
                                    ? "a rule of the project's own, " + step.file() + ":"
                                            + step.line() + ", which is make's to run"
                                    : refusalOf(step.command(), tools);
                            if (why != null) {
                                complaint[0] = why;
                                transcript.add(why);
                                return 1;
                            }
                            ran[0] = true;
                            int before = transcript.size();
                            int status;
                            try {
                                status = RecipeShell.parse(step.command()).run(tools, ontology,
                                        transcript);
                            } catch (RuntimeException | LinkageError broke) {
                                // A program that breaks fails its line, as one that fails
                                // does: make says so and stops, and the run ends in order.
                                transcript.add(broke.getMessage() == null ? broke.toString()
                                        : broke.getMessage());
                                status = 1;
                            }
                            if (status != 0) {
                                complaint[0] = lastOf(transcript, before);
                            }
                            return status;
                        }

                        @Override
                        public void say(String line) {
                            transcript.add(line);
                        }
                    });
        } catch (MakeFile.MakeException cannotFollow) {
            return new Outcome("OntoBoard's own engine cannot follow this project's Makefile "
                    + "for '" + goal + "': " + cannotFollow.getMessage() + ". make can, in "
                    + "ODK's container: start Docker - or Podman.", transcript, ran[0]);
        }
        if (made) {
            return new Outcome(null, transcript, ran[0]);
        }
        if (wasStopped[0]) {
            return new Outcome("You stopped '" + goal + "'. What it had written by then is "
                    + "still there, so the next run starts from a half-finished state - run "
                    + "the target clean first if that matters.", transcript, ran[0]);
        }
        return new Outcome(whyItStopped(goal, transcript, complaint[0]), transcript, ran[0]);
    }

    /**
     * Why this target is not run here, or null when it is: what {@link #run} would refuse
     * before its first line, found without running anything. For a list of targets to offer.
     */
    static String whyNot(File editFile, String goal, Map<String, String> commandLine,
            Map<String, RecipeShell.Tool> tools) {
        // May it? The Makefile is ODK's, and nothing of the project's own is in the way.
        FallbackEligibility.Verdict verdict = FallbackEligibility.of(editFile, goal,
                commandLine);
        if (!verdict.allowed()) {
            return verdict.why();
        }
        // Can it? Every line of the run, as it looks from here.
        String cannot = firstRefusal(verdict.plan(), tools);
        return cannot == null ? null : "'" + goal + "' needs something OntoBoard's own engine "
                + "does not do: " + cannot + ". Nothing was run. In ODK's container it runs as "
                + "it is: start Docker - or Podman.";
    }

    private static final String MAKEFILE = "Makefile";

    /** The exit status of a line that was not run because the person stopped the run. */
    private static final int STOPPED = 130;

    /** Why the first line of a plan that cannot be run here cannot, or null when all can. */
    private static String firstRefusal(List<MakeFile.Step> plan,
            Map<String, RecipeShell.Tool> tools) {
        for (MakeFile.Step step : plan) {
            String why = step.kind() == MakeFile.Step.Kind.COMMAND
                    ? refusalOf(step.command(), tools) : firstRefusal(step.steps(), tools);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String refusalOf(String command, Map<String, RecipeShell.Tool> tools) {
        try {
            return RecipeShell.parse(command).whyNot(tools);
        } catch (RecipeShell.NotRead notRead) {
            return notRead.getMessage();
        }
    }

    /** The last line with something in it that was said from an index on, or null. */
    private static String lastOf(List<String> transcript, int from) {
        for (int i = transcript.size() - 1; i >= from; i--) {
            if (!transcript.get(i).trim().isEmpty()) {
                return transcript.get(i).trim();
            }
        }
        return null;
    }

    /**
     * The sentence for a run make stopped: which target, and what the line that failed said.
     * make's own line names the target - the first such line, from the make that was deepest.
     */
    private static String whyItStopped(String goal, List<String> transcript, String complaint) {
        String target = null;
        for (String line : transcript) {
            java.util.regex.Matcher stop = STOP.matcher(line);
            if (stop.matches()) {
                target = stop.group(1);
                break;
            }
        }
        String last = lastOf(transcript, 0);
        if (target == null) {
            // make had no rule, or no makefile: its last line is the whole reason.
            return "'" + goal + "' was not made: " + (last == null ? "make stopped." : last);
        }
        return "'" + goal + "' stopped at " + target + (complaint == null ? "."
                : ": " + complaint) + " The log has every line up to there.";
    }

    private static final java.util.regex.Pattern STOP = java.util.regex.Pattern.compile(
            "make(?:\\[\\d+\\])?: \\*\\*\\* \\[[^\\]]*: ([^\\]]+)\\] Error \\d+");
}
