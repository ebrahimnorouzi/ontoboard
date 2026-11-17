package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.BuildSwitches;
import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Each of ODK's switches is an option of its own: this, then the switch's name. */
    private static final String OPTION_SWITCH = "switch.";

    /**
     * How much of the transcript to keep.
     *
     * <p>A full ODK release prints tens of thousands of lines, nearly all of it progress. Keeping
     * every one makes a dialog that takes seconds to open and cannot be scrolled usefully; the
     * result says when it has been cut rather than quietly showing a tail.
     */
    private static final int MAX_LINES = 4000;

    private volatile String target = "";

    /** ODK's switches whose boxes were not ticked: on make's command line as NAME=false. */
    private volatile Set<BuildSwitches.Switch> off = EnumSet.noneOf(BuildSwitches.Switch.class);

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
        // Asking the machine and the engine takes a moment before the form is on screen to say
        // anything (minor 9): the wait cursor says it meanwhile.
        java.awt.Component window = getOWLWorkspace();
        java.awt.Cursor was = window.getCursor();
        window.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        List<String> targets;
        try {
            if (!askTheMachineAndTheEngine(editFile)) {
                return false;
            }
            targets = engine != null ? engine.targets() : MakeRun.targetsFor(editFile);
        } finally {
            window.setCursor(was);
        }
        if (targets.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    "The Makefile in " + MakeRun.workingDirectory(editFile).getAbsolutePath()
                            + " declares no targets this can run.",
                    "Nothing to build", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return chooseTheTarget(editFile, targets);
    }

    /**
     * Who builds - the container, ODK's tools, OntoBoard itself - and whether it can; a refusal
     * is shown. False when nothing can build.
     */
    private boolean askTheMachineAndTheEngine(File editFile) {
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
            // Refused for want of DOSDP-Tools: offered, and the engine asked again once it is
            // there. Declined where nothing runs, the offer was the refusal.
            String cure = DosdpOffer.forBuild(without.plan() == null ? without.refusal() : null,
                    without.plan() == null ? java.util.Collections.<String, String>emptyMap()
                            : without.plan().refused());
            if (cure != null) {
                if (DosdpOffer.ask(getOWLWorkspace(), cure, "Cannot build",
                        without.plan() != null)) {
                    without = de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.withoutContainer(
                            editFile, de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute.use(),
                            noContainer, howToGetOne);
                } else if (without.plan() == null) {
                    return false;
                }
            }
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
                javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                        ReleaseAction.wrapped(why), "Cannot build",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                return false;
            }
        }
        return true;
    }

    /**
     * The form: which target, of those there are, and - for an ODK project - ODK's switches,
     * each box ticked as ODK's default has it, with the make command they come to under them.
     * Where OntoBoard's own engine builds, the target is asked about again with the switches
     * that are off; refused, the form comes back with what was chosen. False when cancelled.
     */
    private boolean chooseTheTarget(File editFile, List<String> targets) {
        Map<String, String> was = null;
        boolean switches = takesOdksSwitches();
        while (true) {
            // test, which the guide and the docs say to run: it is the one that runs your own
            // tests as well. all came first in the list and was preselected.
            String first = engine != null ? engine.defaultTarget()
                    : targets.contains("test") ? "test" : targets.get(0);
            Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Build",
                    engine != null ? engine.explanation()
                            : explanationFor(route, editFile, image, runtime),
                    form(targets, first, engine != null ? engine.help()
                            : helpFor(route, editFile, image, runtime), switches, was),
                    switches ? BuildAction::commandShown : null);
            if (chosen == null) {
                return false;
            }
            target = chosen.get(OPTION_TARGET);
            off = switches ? offIn(chosen) : EnumSet.noneOf(BuildSwitches.Switch.class);
            String why = engine == null || off.isEmpty() ? null
                    : de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.whyNotWith(editFile, target,
                            off);
            if (why == null) {
                return true;
            }
            javax.swing.JOptionPane.showMessageDialog(getOWLWorkspace(),
                    ReleaseAction.wrapped(why), "Cannot build with those boxes",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            was = chosen;
        }
    }

    /**
     * Build's form: the target, then - for an ODK project - ODK's four switches, each ticked as
     * ODK's default has it, or as it was chosen when the form comes back.
     *
     * @param was what was chosen when the form comes back, or null the first time
     */
    static List<Parameter> form(List<String> targets, String first, String targetHelp,
            boolean switches, Map<String, String> was) {
        List<Parameter> parameters = new ArrayList<Parameter>();
        parameters.add(Parameter.of(OPTION_TARGET, "Target", Parameter.Kind.CHOICE)
                .choices(targets.toArray(new String[0]))
                .defaultValue(was != null ? was.get(OPTION_TARGET) : first)
                .required()
                .help(targetHelp)
                .build());
        if (switches) {
            for (BuildSwitches.Switch one : BuildSwitches.Switch.values()) {
                parameters.add(Parameter.of(OPTION_SWITCH + one.name(), one.words(),
                        Parameter.Kind.FLAG)
                        .defaultValue(was != null ? was.get(OPTION_SWITCH + one.name())
                                : "true")
                        .help(switchHelp(one))
                        .build());
            }
        }
        return parameters;
    }

    /** What the form's values come to, as the line under it says it: make's command. */
    static String commandShown(Map<String, String> values) {
        return BuildSwitches.command(String.valueOf(values.get(OPTION_TARGET)), offIn(values));
    }

    /** Whether the Makefile is ODK's, whose switches the form then offers. */
    private boolean takesOdksSwitches() {
        return engine != null || route == MakeRun.Route.ODK_IN_CONTAINER
                || route == MakeRun.Route.ODK_NATIVE;
    }

    /** The switches whose boxes are not ticked. */
    private static Set<BuildSwitches.Switch> offIn(Map<String, String> values) {
        Set<BuildSwitches.Switch> off = EnumSet.noneOf(BuildSwitches.Switch.class);
        for (BuildSwitches.Switch one : BuildSwitches.Switch.values()) {
            if (!"true".equalsIgnoreCase(values.get(OPTION_SWITCH + one.name()))) {
                off.add(one);
            }
        }
        return off;
    }

    private static String switchHelp(BuildSwitches.Switch one) {
        switch (one) {
            case IMP:
                return "ODK's IMP. Ticked, as ODK's default, the import modules are built again "
                        + "from their sources, as ODK's test does. Unticked (IMP=false), they are "
                        + "used as they are: quicker, and nothing is downloaded for them.";
            case MIR:
                return "ODK's MIR. Ticked, as ODK's default, the imports' sources - the mirrors - "
                        + "are downloaded again before the modules are built from them. Unticked "
                        + "(MIR=false), the mirrors already in src/ontology/mirror are used; a "
                        + "fresh clone has none, and then this box has to be ticked.";
            case PAT:
                return "ODK's PAT. Ticked, as ODK's default, the DOSDP patterns' files - each "
                        + "pipeline's axioms, definitions.owl - are made again. Unticked "
                        + "(PAT=false), they are used as they are. It matters only in a project "
                        + "with patterns.";
            default:
                return "ODK's COMP. Ticked, as ODK's default, the components are built again. "
                        + "Unticked (COMP=false), they are used as they are. It matters only in "
                        + "a project with components.";
        }
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
            return runWithTheEngine(editFile, ontology, result);
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
        return processResult(result, target, outcome, seconds, editFile, ontology);
    }

    /**
     * An ODK project's target, run by OntoBoard's own engine: ODK's Makefile read and run as
     * make runs it, ROBOT inside Protege, no container and no other program. The transcript
     * reads as make's own; a run can be stopped between two lines.
     */
    private OperationResult runWithTheEngine(File editFile, OWLOntology ontology,
            OperationResult.Builder result) {
        result.note("Run by OntoBoard itself: ODK's own Makefile and recipes, inside Protege, "
                + "without a container.");
        // As ODK's make would be run for it, so that the same can be run in a terminal.
        result.note("Command: " + BuildSwitches.command(target, off));
        String memory = de.fizkarlsruhe.ise.ontoboard.odk.RobotMemory.noteHere(
                MakeRun.workingDirectory(editFile));
        if (memory != null) {
            result.warn(memory);
        }
        long started = System.currentTimeMillis();
        de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.Outcome outcome =
                de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.run(editFile, target,
                        BuildSwitches.variables(off),
                        new java.util.function.BooleanSupplier() {
                            @Override
                            public boolean getAsBoolean() {
                                return BackgroundRun.abandoned();
                            }
                        });
        long seconds = (System.currentTimeMillis() - started) / 1000;
        return engineResult(result, target, outcome, seconds, editFile, ontology);
    }

    /**
     * What a target run by OntoBoard itself reports, apart from running it, so a test can
     * read it.
     */
    static OperationResult engineResult(OperationResult.Builder result, String target,
            de.fizkarlsruhe.ise.ontoboard.odk.OdkEngine.Outcome outcome, long seconds,
            File editFile, OWLOntology ontology) {
        List<String> transcript = outcome.getTranscript();
        noteTheTranscript(result, transcript, seconds);
        if (!outcome.isOk()) {
            // A run the person stopped, or one refused before its first line, is that:
            // whatever it had found by then is not why it ended.
            return result.failed(outcome.wasStopped() || !outcome.ranAnything()
                    ? outcome.getFailure()
                    : whyItFailed(target, transcript, outcome.getFailure(), editFile,
                            ontology)).build();
        }
        return passed(result, target + " succeeded in " + seconds + "s, run by OntoBoard "
                + "itself", transcript, true, ontology, new ArrayList<String>());
    }

    /**
     * What a target run by make - in ODK's container, or with the tools of the machine -
     * reports, apart from running it, so a test can read it.
     */
    static OperationResult processResult(OperationResult.Builder result, String target,
            ProcessRunner.Outcome outcome, long seconds, File editFile, OWLOntology ontology) {
        List<String> warnings = new ArrayList<String>();
        // F5: make ran the Makefile as it is, where OntoBoard's engine would have refused it.
        String notOdks = de.fizkarlsruhe.ise.ontoboard.odk.FallbackEligibility.notOdksMakefile(
                editFile);
        if (notOdks != null) {
            result.warn(notOdks);
            warnings.add(notOdks);
        }
        List<String> transcript = outcome.getOutput();
        noteTheTranscript(result, transcript, seconds);
        String failure = MakeRun.explain(target, outcome);
        if (failure != null) {
            // The transcript is above; this is the sentence that says where to look in it.
            return result.failed(outcome.wasCancelled() || outcome.timedOut() ? failure
                    : whyItFailed(target, transcript, failure, editFile, ontology)).build();
        }
        return passed(result, "make " + target + " succeeded in " + seconds + "s", transcript,
                de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease.isOdkProject(editFile), ontology,
                warnings);
    }

    /**
     * Why a run failed, as a person can act on it (1.135.1, Windows study rehearsal; said of
     * ODK's run since 1.153.0). make's last word on a failing check is a line of CSV, and on
     * a failing report one finding out of however many. Where the transcript says more -
     * which check and what its rule is, which terms by their names, what to do on the board,
     * what the reasoner found - that is said first, then where make stopped; otherwise the
     * sentence of whoever ran the target stands. Then the project's own tests that had
     * passed by then: a build that fails for something else has still passed them.
     */
    private static String whyItFailed(String target, List<String> transcript, String runners,
            File editFile, OWLOntology ontology) {
        return de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.whyARunFailed(target, transcript,
                runners, editFile, ontology)
                + testsSaid(de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.ownAmong(
                        de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.passed(transcript)));
    }

    /**
     * What a run that passed says above its log: who ran it and how long it took, how many
     * checks passed, the project's own tests among them by name, and what HermiT sees that
     * ELK, which ODK's Makefile reasons with, does not.
     *
     * <p>The summary is the part nobody has to scroll to (1.135.1): the result dialog cannot
     * be resized, its log box shows six lines, and a passing test's PASS is far below them.
     *
     * @param odks     whether the checks are an ODK project's: ODK's own and the project's
     * @param warnings what was already warned of in the result, said first in the summary
     */
    private static OperationResult passed(OperationResult.Builder result, String ran,
            List<String> transcript, boolean odks, OWLOntology ontology, List<String> warnings) {
        List<String> passed =
                de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.passed(transcript);
        if (de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.reasonedWithElk(transcript)) {
            String second = de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.secondOpinion(
                    ontology,
                    de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.Choice.ELK.newFactory());
            if (second != null) {
                result.warn(second);
                warnings.add(second);
            }
        }
        return result.summary(ran + (passed.isEmpty() ? "." : "; " + passed.size()
                + (odks ? " of ODK's and your checks passed." : " checks passed."))
                + testsSaid(de.fizkarlsruhe.ise.ontoboard.odk.OdkTargets.ownAmong(passed))
                + warningsSaid(warnings)).build();
    }

    /** The run's lines as far as they are kept, and how long it took. */
    private static void noteTheTranscript(OperationResult.Builder result,
            List<String> transcript, long seconds) {
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
    }

    /** The command this route runs, already decided in {@link #configure()}. */
    private List<String> commandForRoute(File directory, File editFile) {
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return MakeRun.containerCommand(runtime, directory, image, host, target,
                    BuildSwitches.variables(off));
        }
        if (route == MakeRun.Route.ODK_NATIVE) {
            return de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.nativeCommand(
                    de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.activationScript(
                            de.fizkarlsruhe.ise.ontoboard.odk.Toolchain.nativeEnvironment()),
                    target, BuildSwitches.variables(off));
        }
        // Some other Makefile: ODK's switches are not offered, and none is off.
        return MakeRun.command(target, BuildSwitches.variables(off));
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

    /**
     * What the form says above its Target field, for a build that make runs: where it runs.
     * (The sentence written for the build OntoBoard ran inside Protege for its own projects
     * stood over every one of these up to 1.153.0, a build in ODK's container included.)
     */
    static String explanationFor(MakeRun.Route route, File editFile, String image,
            String runtime) {
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.inContainer(editFile, image,
                    runtime).explanation();
        }
        if (route == MakeRun.Route.ODK_NATIVE) {
            return "Runs a target of this project's own Makefile with ODK's tools as they are "
                    + "installed on this machine, without a container. test checks the "
                    + "ontology: the reasoner, ODK's checks and your own, the quality report "
                    + "and the OWL profile. The whole log is kept, and can be saved from the "
                    + "result.";
        }
        return "Runs a target of this project's own Makefile with the make of this machine; "
                + "the programs its recipes name have to be on the PATH. The whole log is "
                + "kept, and can be saved from the result.";
    }

    /** What the Target field's help says, for a build that make runs. */
    static String helpFor(MakeRun.Route route, File editFile, String image, String runtime) {
        if (route == MakeRun.Route.ODK_IN_CONTAINER) {
            return de.fizkarlsruhe.ise.ontoboard.odk.BuildPlan.inContainer(editFile, image,
                    runtime).help();
        }
        String common = " What it rewrites that Protege has open is read again when it is "
                + "done.";
        if (route == MakeRun.Route.ODK_NATIVE) {
            return "Read from this project's own Makefile, most useful first. test is the "
                    + "full check; test_fast is the same on the imports as they are, without "
                    + "fetching them again." + common;
        }
        return "Read from this project's own Makefile, most useful first." + common;
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
