package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Build offers for an ODK project, and where it will run.
 *
 * <p>With ODK's container the list is the project's own Makefile's, {@code test} first: the
 * build its maintainers and its CI run. Without it, OntoBoard's own engine runs ODK's recipes,
 * and the list is what that engine can run for this project - asked target by target, before
 * anything is shown. A target that is not offered is kept with its reason, so that the form
 * can say why {@code test} is missing instead of leaving it out in silence.
 *
 * <p>No Swing: {@code BuildAction} shows what this decides.
 */
public final class BuildPlan {

    /**
     * The targets the engine is asked about, best first: the checks of an ontology, whole and
     * one by one, and clean. A release is Release's.
     */
    static final List<String> IN_PROCESS = Collections.unmodifiableList(Arrays.asList("test",
            "test_fast", "reason_test", "sparql_test", "robot_reports", "validate_idranges",
            "clean"));

    private static final String TEST = "test";
    private static final String FAST = "test_fast";

    private static final String RELOAD = " It writes files Protege will not notice changing, "
            + "so reopen the ontology afterwards if the target rewrote it.";

    private final boolean inProcess;
    private final List<String> targets;
    private final Map<String, String> refused;
    private final String explanation;
    private final String whyNoContainer;

    private BuildPlan(boolean inProcess, List<String> targets, Map<String, String> refused,
            String explanation, String whyNoContainer) {
        this.inProcess = inProcess;
        this.targets = Collections.unmodifiableList(targets);
        this.refused = Collections.unmodifiableMap(refused);
        this.explanation = explanation;
        this.whyNoContainer = whyNoContainer;
    }

    /**
     * The plan when ODK's container runs the build.
     *
     * @param image   the image with its tag, as it is on this machine
     * @param runtime {@code docker} or {@code podman}
     */
    public static BuildPlan inContainer(File editFile, String image, String runtime) {
        return new BuildPlan(false, MakeRun.targetsFor(editFile),
                new LinkedHashMap<String, String>(),
                "Runs a target of this project's own Makefile in ODK's container (" + image
                        + ", through " + runtime + ") - the build its maintainers and its CI "
                        + "run. test checks the ontology: the reasoner, ODK's checks and your "
                        + "own, the quality report and the OWL profile. The whole log is kept, "
                        + "and can be saved from the result.", null);
    }

    /**
     * The plan when OntoBoard's own engine runs the build, with the programs OntoBoard has for
     * ODK's recipes ({@link OdkTools}).
     *
     * @param whyNoContainer why ODK's container does not run it, as the start of a sentence:
     *                       "Docker is not running"
     */
    public static BuildPlan inProcess(File editFile, String whyNoContainer) {
        return inProcess(editFile, OdkTools.forProject(), whyNoContainer);
    }

    /**
     * The plan when OntoBoard's own engine runs the build.
     *
     * @param tools          the programs the engine has
     * @param whyNoContainer why ODK's container does not run it, as the start of a sentence:
     *                       "Docker is not running"
     */
    static BuildPlan inProcess(File editFile, Map<String, RecipeShell.Tool> tools,
            String whyNoContainer) {
        List<String> has = MakeRun.targetsFor(editFile);
        List<String> offered = new ArrayList<String>();
        Map<String, String> refused = new LinkedHashMap<String, String>();
        for (String target : IN_PROCESS) {
            if (!has.contains(target)) {
                continue;
            }
            String why = OdkEngine.whyNot(editFile, target,
                    Collections.<String, String>emptyMap(), tools);
            if (why == null) {
                offered.add(target);
            } else {
                refused.put(target, why);
            }
        }
        return new BuildPlan(true, offered, refused, whyNoContainer + ", so OntoBoard runs "
                + "ODK's own recipes itself, inside Protege - the same Makefile, without the "
                + "container. test checks the ontology: the reasoner, ODK's checks and your "
                + "own, the quality report and the OWL profile; where the project has "
                + "imports it fetches them again first, as ODK's test does. A target the "
                + "engine cannot run for this project is not listed, and the Target "
                + "field's help says why. The whole log is kept, and can be saved from "
                + "the result.",
                whyNoContainer);
    }

    /**
     * What Build does for an ODK project that no container runs: the plan of OntoBoard's own
     * engine, or why nothing runs.
     */
    public static final class WithoutContainer {
        private final BuildPlan plan;
        private final String refusal;

        private WithoutContainer(BuildPlan plan, String refusal) {
            this.plan = plan;
            this.refusal = refusal;
        }

        /** The engine's plan; null when nothing runs. */
        public BuildPlan plan() {
            return plan;
        }

        /** Why nothing runs, for a person to read; null when the engine builds. */
        public String refusal() {
            return refusal;
        }
    }

    /**
     * For an ODK project that no container runs, with the programs OntoBoard has.
     *
     * @param use         what the person settled about containers
     * @param noContainer why no container runs the build, as the start of a sentence
     * @param howToGetOne what to do to have one, said with a refusal; or null
     */
    public static WithoutContainer withoutContainer(File editFile, OdkRoute.Use use,
            String noContainer, String howToGetOne) {
        return withoutContainer(editFile, use, noContainer, howToGetOne, OdkTools.forProject());
    }

    static WithoutContainer withoutContainer(File editFile, OdkRoute.Use use,
            String noContainer, String howToGetOne, Map<String, RecipeShell.Tool> tools) {
        if (!OdkRoute.mayRunWithoutContainer(use)) {
            return new WithoutContainer(null, noContainer + ", and OntoBoard's setting for "
                    + "ODK's targets is \"" + use.label() + "\": nothing is run."
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne));
        }
        BuildPlan plan = inProcess(editFile, tools, noContainer);
        if (plan.targets().isEmpty()) {
            return new WithoutContainer(null, plan.whyNothing());
        }
        return new WithoutContainer(plan, null);
    }

    /** Whether OntoBoard's own engine runs the build, not ODK's container. */
    public boolean isInProcess() {
        return inProcess;
    }

    /** The targets offered, best first; empty when nothing can run here. */
    public List<String> targets() {
        return targets;
    }

    /**
     * The target the form starts with: {@code test}, the one the guide and the docs say to
     * run; without it the fast test; else the first. Null when nothing is offered.
     */
    public String defaultTarget() {
        return targets.contains(TEST) ? TEST : targets.contains(FAST) ? FAST
                : targets.isEmpty() ? null : targets.get(0);
    }

    /** The targets the engine was asked about and does not run here, each with its reason. */
    public Map<String, String> refused() {
        return refused;
    }

    /** The sentence above the form: where the build runs, and what test is. */
    public String explanation() {
        return explanation;
    }

    /** The help of the form's Target field. */
    public String help() {
        if (!inProcess) {
            return "Read from this project's own Makefile, most useful first. test is the "
                    + "full check; test_fast is the same on the imports as they are, without "
                    + "fetching them again." + RELOAD;
        }
        StringBuilder help = new StringBuilder("The targets OntoBoard's own engine can run "
                + "for this project.");
        if (refused.containsKey(TEST)) {
            help.append(" test is not offered: ")
                    .append(OdkEngine.firstSentence(refused.get(TEST)));
            if (targets.contains(FAST)) {
                help.append(" test_fast runs the same tests on the imports as they are.");
            }
        } else if (targets.contains(TEST)) {
            help.append(targets.contains(FAST) ? " test is the full check; test_fast is "
                    + "the same on the imports as they are, without fetching them again."
                    : " test is the full check.");
        }
        List<String> others = new ArrayList<String>(refused.keySet());
        others.remove(TEST);
        if (!others.isEmpty()) {
            help.append(" Also not offered here: ").append(String.join(", ", others))
                    .append('.');
        }
        return help.append(RELOAD).toString();
    }

    /**
     * When nothing is offered: why not, and what to do. Null when there is something to run.
     */
    public String whyNothing() {
        if (!targets.isEmpty()) {
            return null;
        }
        if (!inProcess) {
            return "This project's Makefile declares no targets Build can run.";
        }
        String first = refused.isEmpty() ? "its Makefile has none of the targets the engine "
                + "runs." : refused.values().iterator().next();
        return whyNoContainer + ", and OntoBoard's own engine does not run this project "
                + "either: " + first;
    }

    private static final Pattern PASS =
            Pattern.compile("PASS Rule (?:\\S*[/\\\\])?(\\S+?)(?:-violation)?\\.\\w+: .*");

    /**
     * The checks that passed, by name, read off the lines ROBOT's verify prints:
     * {@code PASS Rule ../sparql/check_labels-violation.sparql: 0 violation(s)}.
     */
    public static List<String> passingChecks(List<String> transcript) {
        List<String> passing = new ArrayList<String>();
        for (String line : transcript) {
            Matcher pass = PASS.matcher(line.trim());
            if (pass.matches()) {
                passing.add(pass.group(1));
            }
        }
        return passing;
    }
}
