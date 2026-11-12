package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Whether OntoBoard's own engine may run a target of an ODK project - the fallback for a
 * machine where ODK's container cannot run.
 *
 * <p>The engine runs ODK's recipes, and only ODK's. So it may run a target when two things
 * hold, and they are asked for every run:
 *
 * <ol>
 *   <li>The project's {@code Makefile} is the one ODK v1.6.1 writes for the project's
 *       configuration - otherwise the recipes are another ODK's, or older than the
 *       configuration, and <i>Update project files</i> is what brings them up to date.</li>
 *   <li>The project's own {@code <id>.Makefile} changes nothing of what make would run for the
 *       target. That is asked of make's own rules: the Makefile is read as make reads it
 *       ({@link MakeFile}) once as it is and once with the project's own file empty, and the
 *       two runs have to be the same commands. A rule of the project's own that the run
 *       reaches, or a setting of its own that one of ODK's recipes reads, is named with its
 *       line. A target of their own that the run never reaches is no obstacle.</li>
 * </ol>
 *
 * <p>A variable of the run itself - the version, the shape of the version IRI - is given on
 * make's command line and is not a setting of the project's.
 */
public final class FallbackEligibility {

    private static final String MAKEFILE = "Makefile";

    private static final String CONTAINER = " What a project adds to ODK's Makefile is run by "
            + "make, in ODK's container, and not by OntoBoard's own engine: start Docker - or "
            + "Podman - to run this target.";

    private FallbackEligibility() {
    }

    /** Whether the engine may run a target, and what it would run. */
    public static final class Verdict {
        private final String why;
        private final List<MakeFile.Step> plan;

        private Verdict(String why, List<MakeFile.Step> plan) {
            this.why = why;
            this.plan = Collections.unmodifiableList(plan);
        }

        public boolean allowed() {
            return why == null;
        }

        /** Why not, in a sentence that says what to do; null when it may. */
        public String why() {
            return why;
        }

        /**
         * What a run of the target does, as make decides it on the disk as it is now
         * ({@link MakeFile#runPlan}); empty when the engine may not run it.
         */
        public List<MakeFile.Step> plan() {
            return plan;
        }
    }

    /** Why the engine may not run the target, or null when it may. */
    public static String why(File editFile, String goal) {
        return of(editFile, goal, Collections.<String, String>emptyMap()).why();
    }

    /**
     * @param commandLine the variables of this run, as they would stand on make's command line
     */
    public static Verdict of(File editFile, String goal, Map<String, String> commandLine) {
        File ontology = editFile == null ? null : editFile.getAbsoluteFile().getParentFile();
        File makefile = ontology == null ? null : new File(ontology, MAKEFILE);
        if (makefile == null || !makefile.isFile()) {
            return refused("There is no Makefile beside "
                    + (editFile == null ? "this ontology" : editFile.getName())
                    + ", so this is not an ODK project and it has no target '" + goal + "'.");
        }
        File config = OdkBuildSettings.yamlIn(ontology);
        if (config == null || !config.isFile()) {
            return refused("There is no ODK configuration (<id>-odk.yaml) beside the Makefile "
                    + "in " + ontology.getAbsolutePath() + ", so OntoBoard cannot tell whether "
                    + "the Makefile is the one ODK writes for this project. It runs in ODK's "
                    + "container only: start Docker - or Podman.");
        }
        String own;
        try {
            String written = read(makefile);
            String odks = new String(OdkInstaller.renderSeed(read(config)).files()
                    .get("src/ontology/" + MAKEFILE), StandardCharsets.UTF_8);
            if (!lf(written).equals(lf(odks))) {
                return refused(whyNotOdks(written));
            }
            own = OdkRelease.Facts.of(editFile).id() + ".Makefile";
        } catch (IOException unreadable) {
            return refused("The project's Makefile or its configuration could not be read: "
                    + unreadable.getMessage());
        } catch (RuntimeException notRendered) {
            return refused("OntoBoard cannot tell what ODK would write for this project's "
                    + "configuration (" + notRendered.getMessage() + "), so its own engine "
                    + "does not run it. It runs in ODK's container only: start Docker - or "
                    + "Podman.");
        }

        // The reader takes Windows line endings out itself: a checkout that has them is
        // the same makefiles, though make in the container could not read them.
        MakeFile.Disk disk = MakeFile.onDisk(ontology);
        MakeFile make;
        List<MakeFile.Step> plan;
        try {
            make = MakeFile.read(MAKEFILE, disk, commandLine);
            plan = make.runPlan(goal);
        } catch (MakeFile.MakeException notRead) {
            if (notRead.isMakesOwn()) {
                return refused("make itself stops for '" + goal + "': " + notRead.getMessage()
                        + " It would stop in ODK's container as well: the project's makefiles "
                        + "name something that is not there and that nothing makes.");
            }
            return refused("OntoBoard's own engine cannot follow this project's Makefile for '"
                    + goal + "': " + notRead.getMessage() + ". make can, in ODK's container: "
                    + "start Docker - or Podman - to run this target.");
        }
        MakeFile.Step theirs = firstOfTheirs(plan);
        if (theirs != null) {
            return refused(theirs.file() + ":" + ruleLine(theirs, make) + " has a rule of the "
                    + "project's own for " + theirs.target()
                    + (theirs.target().equals(goal) ? "" : ", which '" + goal + "' runs")
                    + "." + CONTAINER);
        }
        List<MakeFile.Step> odksPlan;
        try {
            odksPlan = MakeFile.read(MAKEFILE, without(disk, own), commandLine).runPlan(goal);
        } catch (MakeFile.MakeException onlyWithTheirs) {
            return refused("'" + goal + "' can be made only with the project's own " + own
                    + " (without it: " + onlyWithTheirs.getMessage() + ")" + CONTAINER);
        }
        MakeFile.Step[] apart = firstDifference(plan, odksPlan);
        if (apart != null) {
            return refused(whyApart(apart[0], apart[1], make, own, goal));
        }
        return new Verdict(null, plan);
    }

    private static Verdict refused(String why) {
        return new Verdict(why, Collections.<MakeFile.Step>emptyList());
    }

    // ------------------------------------------------------------------ whose Makefile it is

    /** Why a Makefile that is not the bundled render is not run here. */
    private static String whyNotOdks(String written) {
        String version = OdkImage.versionOf(written);
        if (version != null && !version.equals(OdkPin.VERSION)) {
            return "This project's Makefile was written by ODK " + version + ", and OntoBoard's "
                    + "own engine runs what ODK " + OdkPin.VERSION + " writes. It runs in ODK's "
                    + "container: start Docker - or Podman. To bring the project to ODK "
                    + OdkPin.VERSION + ", use "
                    + "OntoBoard > Project > Update project files...";
        }
        return "This project's Makefile is not the one ODK " + OdkPin.VERSION + " writes for "
                + "its configuration: the configuration was changed after the Makefile was "
                + "written, or the Makefile was edited. Write it again with "
                + "OntoBoard > Project > Update project files..."
                + " - until then this target runs in ODK's container only.";
    }

    // ------------------------------------------------------------------ what is theirs

    /** The first step of a run that is not from ODK's Makefile, sub-makes included; or null. */
    private static MakeFile.Step firstOfTheirs(List<MakeFile.Step> plan) {
        for (MakeFile.Step step : plan) {
            if (step.kind() != MakeFile.Step.Kind.MESSAGE && step.file() != null
                    && !step.file().equals(MAKEFILE)) {
                return step;
            }
            MakeFile.Step inside = firstOfTheirs(step.steps());
            if (inside != null) {
                return inside;
            }
        }
        return null;
    }

    /** The line of the rule a step's recipe line belongs to; the recipe line's own if unknown. */
    private static int ruleLine(MakeFile.Step step, MakeFile make) {
        for (MakeFile.Rule rule : make.rules()) {
            if (!rule.file().equals(step.file())) {
                continue;
            }
            for (MakeFile.Line line : rule.recipe()) {
                if (line.number() == step.line()) {
                    return rule.line();
                }
            }
        }
        return step.line();
    }

    /**
     * Where two runs first part: the step of the one and of the other, either null where its
     * run has ended; null when they are the same run.
     */
    private static MakeFile.Step[] firstDifference(List<MakeFile.Step> one,
            List<MakeFile.Step> other) {
        for (int i = 0; i < Math.max(one.size(), other.size()); i++) {
            MakeFile.Step a = i < one.size() ? one.get(i) : null;
            MakeFile.Step b = i < other.size() ? other.get(i) : null;
            if (a == null || b == null || !same(a, b)) {
                return new MakeFile.Step[] {a, b};
            }
            MakeFile.Step[] inside = firstDifference(a.steps(), b.steps());
            if (inside != null) {
                return inside;
            }
        }
        return null;
    }

    private static boolean same(MakeFile.Step a, MakeFile.Step b) {
        return a.kind() == b.kind() && a.command().equals(b.command())
                && String.valueOf(a.target()).equals(String.valueOf(b.target()))
                && String.valueOf(a.file()).equals(String.valueOf(b.file()))
                && a.line() == b.line() && a.silent() == b.silent()
                && a.ignoreErrors() == b.ignoreErrors();
    }

    /**
     * Why the run with the project's own makefile is not the run without it, though every
     * recipe is ODK's: a setting of theirs that a recipe reads - named, with its line - or
     * something that changes which recipes run.
     */
    private static String whyApart(MakeFile.Step with, MakeFile.Step without, MakeFile make,
            String own, String goal) {
        MakeFile.Step at = with != null ? with : without;
        String of = at.target() == null ? "" : " for " + at.target();
        if (with != null && without != null && with.line() == without.line()
                && String.valueOf(with.file()).equals(String.valueOf(without.file()))) {
            for (String name : make.variablesReadBy(recipeLine(with, make))) {
                MakeFile.Variable variable = make.variables().get(name);
                for (MakeFile.Line assignment : variable == null
                        ? Collections.<MakeFile.Line>emptyList() : variable.assignments()) {
                    if (!assignment.file().equals(MAKEFILE)) {
                        return assignment.file() + ":" + assignment.number() + " sets " + name
                                + ", which ODK's recipe" + of + " reads"
                                + (goal.equals(at.target()) ? "" : " when '" + goal + "' runs")
                                + "." + CONTAINER;
                    }
                }
            }
        }
        return "With the project's own " + own + ", make runs something else for '" + goal
                + "' than ODK's Makefile alone does"
                + (at.target() == null || at.target().equals(goal) ? "" : " (at " + at.target()
                        + ")") + "." + CONTAINER;
    }

    /** The recipe line a step was expanded from, as written; the command itself if unknown. */
    private static String recipeLine(MakeFile.Step step, MakeFile make) {
        for (MakeFile.Rule rule : make.rules()) {
            if (!rule.file().equals(step.file())) {
                continue;
            }
            for (MakeFile.Line line : rule.recipe()) {
                if (line.number() == step.line()) {
                    return line.text();
                }
            }
        }
        return step.command();
    }

    // ------------------------------------------------------------------ the disk make sees

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }

    /** The same folder, with the project's own makefile holding nothing. */
    private static MakeFile.Disk without(final MakeFile.Disk disk, final String own) {
        return new Forwarding(disk) {
            @Override
            public String read(String path) {
                return path.equals(own) || path.equals("./" + own)
                        ? "# (the project's own makefile, left out)\n" : disk.read(path);
            }
        };
    }

    /** A disk that answers as another does, for the one above to change one answer. */
    private abstract static class Forwarding implements MakeFile.Disk {
        private final MakeFile.Disk disk;

        Forwarding(MakeFile.Disk disk) {
            this.disk = disk;
        }

        @Override
        public boolean exists(String path) {
            return disk.exists(path);
        }

        @Override
        public long modified(String path) {
            return disk.modified(path);
        }

        @Override
        public List<String> list(String folder) {
            return new ArrayList<String>(disk.list(folder));
        }

        @Override
        public String folder() {
            return disk.folder();
        }
    }
}
