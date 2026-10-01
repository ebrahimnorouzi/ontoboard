package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Project &gt; Build - run one of the project's own make targets, and keep the transcript.
 *
 * <p>{@code MakeTargets} could already read a Makefile and order its targets sensibly, tested
 * against a real 767-line ODK one, and nothing could run any of them. Wanting what a target does
 * meant leaving Protege for a terminal, which is most of the reason this plugin exists.
 *
 * <p>The transcript is the product, not a side effect. A build fails in the middle of several
 * hundred lines of ROBOT output and the useful part is usually well above the end, so a result
 * saying only "make failed (exit 2)" would send somebody to the terminal anyway. It is savable
 * from the result dialog, which is what makes it something to attach to an issue.
 */
public class BuildAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_TARGET = "target";

    /**
     * How much of the transcript to keep.
     *
     * <p>A full ODK release prints tens of thousands of lines, nearly all of it progress. Keeping
     * every one makes a dialog that takes seconds to open and cannot be scrolled usefully; the
     * result says when it has been cut rather than quietly showing a tail.
     */
    private static final int MAX_LINES = 4000;

    private volatile String target = "";

    /** Decided in {@link #configure()} so the run does not probe the machine a second time. */
    private volatile MakeRun.Route route = MakeRun.Route.NOT_RUNNABLE;

    /** The ODK image this project pins, when the build goes through a container. */
    private volatile String image = MakeRun.DEFAULT_IMAGE;

    /** {@code docker} or {@code podman}, decided with the route. */
    private volatile String runtime = "docker";

    @Override
    protected String operationName() {
        return "Build";
    }

    @Override
    protected boolean configure() {
        File editFile = fileOf(getOWLModelManager().getActiveOntology());
        route = MakeRun.routeFor(editFile, ProcessRunner.real());
        image = MakeRun.imageFor(MakeRun.workingDirectory(editFile));
        String found = MakeRun.containerRuntime(ProcessRunner.real());
        runtime = found == null ? "docker" : found;
        String why = MakeRun.whyNotRunnable(editFile, ProcessRunner.real());
        if (why != null) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), why,
                    "Cannot build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        List<String> targets = route == MakeRun.Route.IN_PROCESS
                ? de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.TARGETS
                : MakeRun.targetsFor(editFile);
        if (targets.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The Makefile in " + MakeRun.workingDirectory(editFile).getAbsolutePath()
                            + " declares no targets this can run.",
                    "Nothing to build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        List<Parameter> parameters = Arrays.asList(
                Parameter.of(OPTION_TARGET, "Target", Parameter.Kind.CHOICE)
                        .choices(targets.toArray(new String[0]))
                        .defaultValue(targets.get(0))
                        .required()
                        .help(helpFor())
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Build",
                "Runs one of this project's make targets and keeps the whole transcript, which "
                        + "you can save from the result.",
                parameters);
        if (chosen == null) {
            return false;
        }
        target = chosen.get(OPTION_TARGET);
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File editFile = fileOf(ontology);
        File directory = MakeRun.workingDirectory(editFile);
        OperationResult.Builder result = OperationResult.of(operationName());
        result.note("Target: " + target);
        result.note("Directory: " + directory.getAbsolutePath());

        if (route == MakeRun.Route.IN_PROCESS) {
            return runHere(editFile, ontology, result);
        }

        List<String> command = commandForRoute(directory, editFile);
        // In the transcript, before anything runs. A build that fails is diagnosed by running the
        // same command in a terminal, and until now the user had no way to know what it was.
        result.note("Command: " + join(command));

        ProcessRunner.Outcome outcome;
        long started = System.currentTimeMillis();
        try {
            outcome = ProcessRunner.real().run(directory, command, MakeRun.TIMEOUT_MINUTES, null);
        } catch (IOException cannotRun) {
            return result.failed("Could not start the build: " + cannotRun.getMessage()
                    + " (command: " + join(command) + ")").build();
        }
        long seconds = (System.currentTimeMillis() - started) / 1000;

        int shown = 0;
        for (String line : outcome.getOutput()) {
            if (shown++ >= MAX_LINES) {
                break;
            }
            result.note(line);
        }
        if (outcome.getOutput().size() > MAX_LINES) {
            result.warn("Transcript cut after " + MAX_LINES + " of "
                    + outcome.getOutput().size() + " lines.");
        }
        result.note("Took " + seconds + "s, " + outcome.getOutput().size() + " lines of output.");

        String failure = MakeRun.explain(target, outcome);
        if (failure != null) {
            // The transcript is above; this is the sentence that says where to look in it.
            return result.failed(failure).build();
        }

        String reload = MakeRun.reloadHint(target, editFile);
        if (reload != null) {
            result.warn(reload);
        }
        return result.summary("make " + target + " succeeded in " + seconds + "s.").build();
    }

    /** The command this route runs, already decided in {@link #configure()}. */
    private List<String> commandForRoute(File directory, File editFile) {
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return MakeRun.containerCommand(runtime, directory, image, target);
        }
        if (route == MakeRun.Route.ODK_NATIVE) {
            return de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.nativeCommand(
                    de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.activationScript(
                            de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.nativeEnvironment()),
                    target);
        }
        return MakeRun.command(target);
    }

    /**
     * The build this plugin runs itself, for a project it scaffolded.
     *
     * <p>No process at all: every target is mapped onto the embedded robot-core, so this works
     * with nothing installed, on every platform. The transcript is the same shape a real build
     * produces, so the result dialog does not need to know which route ran.
     */
    private OperationResult runHere(File editFile, OWLOntology ontology,
            OperationResult.Builder result) {
        long started = System.currentTimeMillis();
        de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.Outcome outcome =
                de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.run(editFile, ontology,
                        reasoner().newFactory(), target);
        long seconds = (System.currentTimeMillis() - started) / 1000;
        for (String line : outcome.getTranscript()) {
            result.note(line);
        }
        for (File file : outcome.getWritten()) {
            result.wrote(file);
        }
        if (!outcome.isOk()) {
            return result.failed(outcome.getFailure()).build();
        }
        String reload = MakeRun.reloadHint(target, editFile);
        if (reload != null) {
            result.warn(reload);
        }
        return result.summary(target + " finished in " + seconds
                + "s, inside Protege - nothing was installed or started.").build();
    }

    /** The reasoner the in-process build uses. ELK, as the generated recipe specifies. */
    private de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice reasoner() {
        return de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK;
    }

    /** What the target chooser says about where the build will run. */
    private String helpFor() {
        String common = " It writes files Protege will not notice changing, so reopen the "
                + "ontology afterwards if the target rewrote it.";
        if (route == MakeRun.Route.IN_PROCESS) {
            return "This project was scaffolded by OntoBoard, so its build runs inside Protege "
                    + "against the embedded ROBOT - no make, no robot on your PATH, and no "
                    + "container. 'all' and 'reason' regenerate the published file, 'report' runs "
                    + "the quality checks, 'test' runs all four." + common;
        }
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return "Read from this project's own Makefile, most useful first. This is an ODK "
                    + "project, so the build runs inside " + image + " through " + runtime
                    + " - the same image its CI uses. The first run of a target may take a "
                    + "while." + common;
        }
        if (route == MakeRun.Route.ODK_NATIVE) {
            return "Read from this project's own Makefile, most useful first. This is an ODK "
                    + "project and you have a native ODK environment configured, so the build "
                    + "runs directly on your machine with no container." + common;
        }
        return "Read from this project's own Makefile, most useful first. The build runs with "
                + "make and robot on your PATH." + common;
    }

    /** A command as one line, quoting only the arguments that need it. */
    private static String join(List<String> command) {
        StringBuilder line = new StringBuilder();
        for (String part : command) {
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(part.indexOf(' ') >= 0 ? "\"" + part + "\"" : part);
        }
        return line.toString();
    }

    /** The ontology's own file, or null when it has never been saved. */
}
