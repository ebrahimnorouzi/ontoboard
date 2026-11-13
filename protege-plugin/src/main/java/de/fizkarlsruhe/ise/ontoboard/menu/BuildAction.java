package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
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

    /** The ODK image that runs this project, when the build goes through a container. */
    private volatile String image = MakeRun.DEFAULT_IMAGE;

    /** Why that image is not the project's own ODK, for the transcript; null when it is. */
    private volatile String imageNote;

    /** {@code docker} or {@code podman}, decided with the route. */
    private volatile String runtime = "docker";

    /**
     * Set when OntoBoard's own engine builds an ODK project - no container runs it: what the
     * engine offers for the project. Null for every other route.
     */
    private volatile de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan engine;

    /** The machine, asked once: its memory decides Java's when the project names none. */
    private volatile OdkContainer.Host host = OdkContainer.Host.unknown();

    @Override
    protected String operationName() {
        return "Build";
    }

    @Override
    protected boolean configure() {
        File editFile = fileOf(getOWLModelManager().getActiveOntology());
        // A build reads the file, not the window. Unsaved edits were silently left out of it -
        // a test that should have failed passed, and nothing said why.
        if (SaveFirst.isNeeded(getOWLModelManager()) && SaveFirst.ask(getOWLWorkspace(),
                getOWLModelManager(), "The ontology has changes that are not saved yet. A build "
                        + "checks the file on disk, so it would not see them.",
                "Save and build", "Build the saved file", "Cannot build")
                == SaveFirst.Answer.CANCELLED) {
            return false;
        }
        route = MakeRun.routeFor(editFile, ProcessRunner.real());
        String found = MakeRun.containerRuntime(ProcessRunner.real());
        runtime = found == null ? "docker" : found;
        imageNote = null;
        engine = null;
        // An ODK project that no container runs is built by OntoBoard's own engine - ODK's
        // Makefile and recipes, inside Protege - unless the person settled otherwise.
        String noContainer = null;
        String howToGetOne = null;
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            // An image that is on this machine, or none: a runtime handed a name it does not
            // have downloads it, five gigabytes, without a word.
            OdkImage.Choice choice = OdkImage.forProject(MakeRun.workingDirectory(editFile),
                    OdkImage.probe(runtime, ProcessRunner.real()));
            if (choice.reference() == null) {
                noContainer = "ODK's image is not on this machine";
                howToGetOne = choice.note();
            } else {
                image = choice.reference();
                imageNote = choice.note();
                host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
            }
        } else if (route == MakeRun.Route.NOT_RUNNABLE
                && de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile)) {
            noContainer = de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.noContainerBecause(
                    ProcessRunner.real());
        }
        if (noContainer != null) {
            de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.WithoutContainer without =
                    de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.withoutContainer(editFile,
                            de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.use(), noContainer,
                            howToGetOne);
            if (without.plan() == null) {
                javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                        ReleaseAction.commandsShown(without.refusal()), "Cannot build",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return false;
            }
            engine = without.plan();
        } else {
            String why = MakeRun.whyNotRunnable(editFile, ProcessRunner.real());
            if (why != null) {
                javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(), why,
                        "Cannot build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return false;
            }
        }
        List<String> targets = engine != null ? engine.targets()
                : route == MakeRun.Route.IN_PROCESS
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
                        // test, which the guide and the docs say to run: it is the one that runs
                        // your own tests as well. all came first in the list and was preselected.
                        .defaultValue(engine != null ? engine.defaultTarget()
                                : targets.contains("test") ? "test" : targets.get(0))
                        .required()
                        .help(engine != null ? engine.help() : helpFor())
                        .build());

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Build",
                engine != null ? engine.explanation()
                        : "Checks the ontology. test runs the reasoner, the quality report, your "
                                + "own tests and the OWL profile check, inside Protege - nothing "
                                + "else needs installing. The whole log is kept, and can be saved "
                                + "from the result.",
                parameters);
        if (chosen == null) {
            return false;
        }
        target = chosen.get(OPTION_TARGET);
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        // What Protege has open, as it is before the target runs: what the run rewrites of
        // it - the import modules ODK's full test builds again, a product somebody has
        // open - is read again afterwards, whoever ran it.
        de.fizkarlsruhe.ise.ontoboard.odk.FileStamps open = stampsOfWhatIsOpen();
        OperationResult.Builder result = OperationResult.of(operationName());
        OperationResult built = runTheTarget(ontology, result);
        return readAgainWhatWasWritten(open, result) ? result.build() : built;
    }

    /** The target, run by whoever was decided; what came of it goes into the result. */
    private OperationResult runTheTarget(OWLOntology ontology,
            OperationResult.Builder result) {
        File editFile = fileOf(ontology);
        File directory = MakeRun.workingDirectory(editFile);
        result.note("Target: " + target);
        result.note("Directory: " + directory.getAbsolutePath());

        if (engine != null) {
            return runWithTheEngine(editFile, result);
        }
        if (route == MakeRun.Route.IN_PROCESS) {
            return runHere(editFile, ontology, result);
        }

        List<String> command = commandForRoute(directory, editFile);
        if (route == MakeRun.Route.ODK_IN_CONTAINER && imageNote != null) {
            // Not the ODK that wrote this project's Makefile: said, because a build that
            // differs from the project's CI is otherwise a riddle.
            result.warn(imageNote);
        }
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

        return result.summary("make " + target + " succeeded in " + seconds + "s.").build();
    }

    /**
     * An ODK project's target, run by OntoBoard's own engine: ODK's Makefile read and run as
     * make runs it, ROBOT inside Protege, no container and no other program. The transcript
     * reads as make's own; a run can be stopped between two lines.
     */
    private OperationResult runWithTheEngine(File editFile, OperationResult.Builder result) {
        result.note("Run by OntoBoard itself: ODK's own Makefile and recipes, inside Protege, "
                + "without a container.");
        long started = System.currentTimeMillis();
        de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.Outcome outcome =
                de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.run(editFile, target,
                        java.util.Collections.<String, String>emptyMap(),
                        new java.util.function.BooleanSupplier() {
                            @Override
                            public boolean getAsBoolean() {
                                return BackgroundRun.abandoned();
                            }
                        });
        long seconds = (System.currentTimeMillis() - started) / 1000;

        List<String> transcript = outcome.getTranscript();
        int shown = 0;
        for (String line : transcript) {
            if (shown++ >= MAX_LINES) {
                break;
            }
            result.note(line);
        }
        if (transcript.size() > MAX_LINES) {
            result.warn("Transcript cut after " + MAX_LINES + " of " + transcript.size()
                    + " lines.");
        }
        result.note("Took " + seconds + "s, " + transcript.size() + " lines of output.");
        if (!outcome.isOk()) {
            return result.failed(outcome.getFailure()).build();
        }
        List<String> passing =
                de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.passingChecks(transcript);
        return result.summary(target + " succeeded in " + seconds + "s, run by OntoBoard "
                + "itself" + (passing.isEmpty() ? "." : "; " + passing.size() + " of ODK's and "
                + "your checks passed.")).build();
    }

    /** The command this route runs, already decided in {@link #configure()}. */
    private List<String> commandForRoute(File directory, File editFile) {
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return MakeRun.containerCommand(runtime, directory, image, host, target);
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
        return inProcessResult(result, target, outcome, seconds);
    }

    /**
     * What an in-process build reports, apart from running it, so a test can read it.
     *
     * <p>The summary says what passed and what to read (1.135.1, Windows study rehearsal). It is
     * the part nobody scrolls to: the result dialog cannot be resized, its log box shows six lines,
     * and a passing test's PASS was the ninth and HermiT's warning the seventh.
     */
    static OperationResult inProcessResult(OperationResult.Builder result, String target,
            de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets.Outcome outcome,
            long seconds) {
        for (String line : outcome.getTranscript()) {
            result.note(line);
        }
        for (File file : outcome.getWritten()) {
            result.wrote(file);
        }
        for (String warning : outcome.getWarnings()) {
            result.warn(warning);
        }
        String tests = testsSaid(outcome.getPassingChecks());
        if (!outcome.isOk()) {
            return result.failed(outcome.getFailure() + tests).build();
        }
        return result.summary(target + " finished in " + seconds
                + "s, inside Protege - nothing was installed or started." + tests
                + warningsSaid(outcome.getWarnings())).build();
    }

    /** How many passing tests the summary names before it counts the rest. */
    static final int TESTS_NAMED = 4;

    /** The tests that passed, for the summary: {@code " Tests: PASS a.rq, PASS b.rq."}. */
    static String testsSaid(List<String> passing) {
        if (passing == null || passing.isEmpty()) {
            return "";
        }
        List<String> named = new ArrayList<String>();
        for (String check : passing.subList(0, Math.min(TESTS_NAMED, passing.size()))) {
            named.add("PASS " + check);
        }
        int more = passing.size() - named.size();
        return " Tests: " + String.join(", ", named) + (more > 0 ? " and " + more + " more" : "")
                + ".";
    }

    /** The first warning's first sentence, for the summary; the whole of each is in the log. */
    static String warningsSaid(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "";
        }
        String first = warnings.get(0).trim();
        int end = first.indexOf(". ");
        String sentence = end < 0 ? first : first.substring(0, end + 1);
        return " " + sentence + (warnings.size() == 1 ? " More in the log."
                : " That and " + (warnings.size() - 1) + " more warning"
                        + (warnings.size() == 2 ? "" : "s") + " in the log.");
    }

    /** The reasoner the in-process build uses. ELK, as the generated recipe specifies. */
    private de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice reasoner() {
        return de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK;
    }

    /** What the target chooser says about where the build will run. */
    private String helpFor() {
        String common = " What it rewrites that Protege has open is read again when it is "
                + "done.";
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
