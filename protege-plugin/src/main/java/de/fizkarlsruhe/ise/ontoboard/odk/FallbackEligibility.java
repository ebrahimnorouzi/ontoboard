package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Whether OntoBoard's own engine may run a target of an ODK project - the fallback for a
 * machine where ODK's container cannot run.
 *
 * <p>The engine may run a target when three things hold, and they are asked for every run:
 *
 * <ol>
 *   <li>The project's {@code Makefile} is the one ODK v1.6.1 writes for the project's
 *       configuration - otherwise the recipes are another ODK's, or older than the
 *       configuration, and <i>Update project files</i> is what brings them up to date.</li>
 *   <li>make's rules can be followed here: the Makefile is read as make reads it
 *       ({@link MakeFile}), with the project's own {@code <id>.Makefile}, which ODK's
 *       includes - its rules, its settings, OntoBoard's rule for the questions among them.</li>
 *   <li>make itself would not stop: what the target needs is there, or something makes it.</li>
 * </ol>
 *
 * <p>Since 1.154.0 a rule of the project's own is no obstacle for being theirs: the engine runs
 * it as make would, when every line of it can be run here - which the engine asks of each line
 * ({@link OdkEngine}), as of ODK's own. A project's own {@code release.sh} stays make's and the
 * container's.
 *
 * <p>A variable of the run itself - the version, the shape of the version IRI - is given on
 * make's command line.
 */
public final class FallbackEligibility {

    private static final String MAKEFILE = "Makefile";

    /**
     * What else runs a target that OntoBoard's own engine does not: said with every refusal,
     * so that a container is one way among others and never the only advice.
     */
    static final String OTHER_WAYS = " It can be run in ODK's container (Docker or Podman "
            + "with ODK's image) or, on Linux and macOS, with ODK's own tools installed on "
            + "the machine; OntoBoard > Check requirements... shows what is there.";

    private FallbackEligibility() {
    }

    /** Whether the engine may run a target, and what it would run. */
    public static final class Verdict {
        private final String why;
        private final List<MakeFile.Step> plan;
        private final String notice;

        private Verdict(String why, List<MakeFile.Step> plan) {
            this(why, plan, null);
        }

        private Verdict(String why, List<MakeFile.Step> plan, String notice) {
            this.why = why;
            this.plan = Collections.unmodifiableList(plan);
            this.notice = notice;
        }

        public boolean allowed() {
            return why == null;
        }

        /**
         * What the run says before its first line: how the project's Makefile differs from
         * what ODK v1.6.1 writes for its configuration, which the engine runs as it is (1.155.0)
         * - or null when the Makefile is ODK's.
         */
        public String notice() {
            return notice;
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
                    + "the Makefile is the one ODK writes for this project, and its own engine "
                    + "does not run it." + OTHER_WAYS);
        }
        // 1.155.0: a Makefile another ODK wrote, or one the project changed, is run as it is - as
        // make in ODK's container runs it - and the run says first how it differs. nfdicore's and
        // MWO's say ODK v1.6, and MWO's misses an import its configuration gained; the user's
        // goal is their development with OntoBoard alone.
        String notice = null;
        try {
            String written = read(makefile);
            String odks = rendered(config);
            if (!lf(written).equals(lf(odks))) {
                notice = howItDiffers(lf(written), lf(odks));
            }
        } catch (IOException unreadable) {
            return refused("The project's Makefile or its configuration could not be read: "
                    + unreadable.getMessage());
        } catch (RuntimeException notRendered) {
            return refused("OntoBoard cannot tell what ODK would write for this project's "
                    + "configuration (" + notRendered.getMessage() + "), so its own engine "
                    + "does not run it." + OTHER_WAYS);
        }

        // The reader takes Windows line endings out itself: a checkout that has them is
        // the same makefiles, though make in the container could not read them.
        List<MakeFile.Step> plan;
        try {
            plan = MakeFile.read(MAKEFILE, MakeFile.onDisk(ontology), commandLine).runPlan(goal);
        } catch (MakeFile.MakeException notRead) {
            if (notRead.isMakesOwn()) {
                return refused("make itself stops for '" + goal + "': " + notRead.getMessage()
                        + " It would stop in ODK's container as well: the project's makefiles "
                        + "name something that is not there and that nothing makes.");
            }
            return refused("OntoBoard's own engine cannot follow this project's Makefile for '"
                    + goal + "': " + notRead.getMessage() + ". make can." + OTHER_WAYS);
        }
        return new Verdict(null, plan, notice);
    }

    /** How many differing lines of each side a notice quotes. */
    private static final int QUOTED = 3;

    /**
     * How a project's Makefile differs from ODK's render of its configuration, in words: only
     * in the version ODK wrote into it, or in which lines.
     */
    static String howItDiffers(String written, String odks) {
        String version = OdkImage.versionOf(written);
        boolean otherVersion = version != null && !version.equals(OdkPin.VERSION);
        if (otherVersion && withPinnedVersion(written, version).equals(odks)) {
            return "This project's Makefile was written by ODK " + version + "; its rules are "
                    + "the ones ODK " + OdkPin.VERSION + " writes for its configuration, so "
                    + "OntoBoard runs it as " + OdkPin.VERSION + "'s. OntoBoard > Project > "
                    + "Update project files... writes " + OdkPin.VERSION + " into it.";
        }
        List<String> theirs = onlyIn(written, odks);
        List<String> ours = onlyIn(odks, written);
        StringBuilder said = new StringBuilder("This project's Makefile is not the one ODK "
                + OdkPin.VERSION + " writes for its configuration"
                + (otherVersion ? " (ODK " + version + " wrote it)" : "") + ": "
                + (theirs.size() + ours.size()) + " lines differ");
        if (!theirs.isEmpty()) {
            said.append("; it has ").append(quoted(theirs));
        }
        if (!ours.isEmpty()) {
            said.append("; ODK's has ").append(quoted(ours));
        }
        said.append(". OntoBoard runs it as it is, as make in ODK's container does; OntoBoard > "
                + "Project > Update project files... writes ODK's again.");
        return said.toString();
    }

    private static String withPinnedVersion(String makefile, String version) {
        return makefile.replace("# ODK Version: " + version + "\n",
                "# ODK Version: " + OdkPin.VERSION + "\n").replaceAll(
                "(?m)^(ODK_VERSION_MAKEFILE =\\s+)" + java.util.regex.Pattern.quote(version) + "$",
                "$1" + java.util.regex.Matcher.quoteReplacement(OdkPin.VERSION));
    }

    /** The lines of {@code one}, not blank, that {@code other} does not have, in order. */
    private static List<String> onlyIn(String one, String other) {
        Map<String, Integer> left = new java.util.HashMap<String, Integer>();
        for (String line : other.split("\n", -1)) {
            left.merge(line, 1, Integer::sum);
        }
        List<String> only = new java.util.ArrayList<String>();
        for (String line : one.split("\n", -1)) {
            Integer count = left.get(line);
            if (count != null && count > 0) {
                left.put(line, count - 1);
            } else if (!line.trim().isEmpty()) {
                only.add(line.trim());
            }
        }
        return only;
    }

    private static String quoted(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(QUOTED, lines.size()); i++) {
            text.append(i == 0 ? "" : ", ").append('"').append(lines.get(i)).append('"');
        }
        if (lines.size() > QUOTED) {
            text.append(" and ").append(lines.size() - QUOTED).append(" more");
        }
        return text.toString();
    }

    private static Verdict refused(String why) {
        return new Verdict(why, Collections.<MakeFile.Step>emptyList());
    }

    // ------------------------------------------------------------------ whose Makefile it is

    /** How many times ODK's seed was rendered here; for a test to count. */
    private static final java.util.concurrent.atomic.AtomicInteger RENDERS =
            new java.util.concurrent.atomic.AtomicInteger();

    static int rendersSoFar() {
        return RENDERS.get();
    }

    /** How many configurations' Makefiles are kept: the ones used last. */
    private static final int RENDERS_KEPT = 16;

    /**
     * The Makefile ODK writes, per configuration text (minor 9): the Build and Release forms
     * ask about every target they offer before they open, and each ask rendered the whole seed
     * again - seconds on a click. A configuration that changed is another text, and rendered.
     */
    private static final Map<String, String> RENDERED = Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(RENDERS_KEPT, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > RENDERS_KEPT;
                }
            });

    /** The Makefile ODK writes for a configuration: the bundled templates' render of it. */
    private static String rendered(File config) throws IOException {
        String text = read(config);
        String kept = RENDERED.get(text);
        if (kept != null) {
            return kept;
        }
        RENDERS.incrementAndGet();
        String makefile = new String(OdkInstaller.renderSeed(text).files()
                .get("src/ontology/" + MAKEFILE), StandardCharsets.UTF_8);
        RENDERED.put(text, makefile);
        return makefile;
    }

    /**
     * F5: why a project's Makefile is not the one ODK writes for its configuration, said where
     * make runs it anyway - in ODK's container, or with ODK's tools on the machine - as it is,
     * while the engine refuses it ({@link #of}). Null when it is ODK's render; when there is no
     * configuration to render; when another ODK wrote it, which the image's own note says; and
     * when it cannot be read or rendered here.
     */
    public static String notOdksMakefile(File editFile) {
        File ontology = editFile == null ? null : editFile.getAbsoluteFile().getParentFile();
        File makefile = ontology == null ? null : new File(ontology, MAKEFILE);
        File config = ontology == null ? null : OdkBuildSettings.yamlIn(ontology);
        if (makefile == null || !makefile.isFile() || config == null || !config.isFile()) {
            return null;
        }
        try {
            String written = read(makefile);
            String version = OdkImage.versionOf(written);
            if (version != null && !version.equals(OdkPin.VERSION)
                    || lf(written).equals(lf(rendered(config)))) {
                return null;
            }
        } catch (IOException | RuntimeException cannotTell) {
            return null;
        }
        return "This project's Makefile is not the one ODK " + OdkPin.VERSION + " writes for "
                + "its configuration: the configuration was changed after the Makefile was "
                + "written, or the Makefile was edited. make runs it as it is, so this run may "
                + "differ from what ODK writes for the configuration, and OntoBoard's own engine "
                + "would not run it. OntoBoard > Project > Update project files... writes it "
                + "again.";
    }

    // ------------------------------------------------------------------ the disk make sees

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }
}
