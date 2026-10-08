package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Re-renders an existing project's generated files - ODK's {@code update_repo}.
 *
 * <p>Without this, every capability the plugin gains reaches only <em>newly created</em> projects. A
 * project made last month cannot acquire a corrected Makefile except by being created again from
 * scratch, which means copying the ontology out and back. Four rounds of generator fixes in this
 * plugin - the catalog the build ignored, the release gate weaker than CI, the reasoner accepting
 * equivalences nobody asserted - reached nobody who already had a project.
 *
 * <p><b>It plans before it writes.</b> {@link #plan} renders everything and reports what would
 * change without touching the disk, because this action can destroy work and a user is entitled to
 * see the diff first. {@link #apply} writes only the files that differ.
 *
 * <p><b>What it will not touch</b> is as important as what it renders, and is listed in
 * {@link OdkScaffold#seededFilesFor}: the edit file, the custom Makefile, the YAML it reads, the ID
 * ranges (which hold other editors' allocations) and the catalog (which holds real import mappings
 * the template would delete).
 *
 * <p><b>Line endings are normalised before comparing, never before writing.</b> Git is commonly
 * configured with {@code core.autocrlf=true}, so a checked-out generated file can be CRLF on disk
 * while the blob is LF. Comparing raw bytes there would report every file as changed and rewrite all
 * of them - noise that would teach somebody to stop reading the diff. Writing stays LF, which is
 * what the scaffold has always written.
 *
 * <p>No Protege types and no Swing.
 */
public final class OdkRegenerator {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private OdkRegenerator() {
    }

    /** One generated file, as it is and as it would be. */
    public static final class Change {
        private final String path;
        private final String before;
        private final String after;

        Change(String path, String before, String after) {
            this.path = path;
            this.before = before;
            this.after = after;
        }

        /** Relative to the project root, with {@code /} separators. */
        public String getPath() {
            return path;
        }

        /** Null when the file does not exist yet. */
        public String getBefore() {
            return before;
        }

        public String getAfter() {
            return after;
        }

        public boolean isNew() {
            return before == null;
        }

        /** Whether writing this would change anything, ignoring line endings. */
        public boolean isChanged() {
            return before == null || !normalise(before).equals(normalise(after));
        }

        /** How many lines differ, for a summary that is more useful than "changed". */
        public int changedLines() {
            if (before == null) {
                return normalise(after).split("\n", -1).length;
            }
            String[] was = normalise(before).split("\n", -1);
            String[] now = normalise(after).split("\n", -1);
            int differing = Math.abs(was.length - now.length);
            for (int i = 0; i < Math.min(was.length, now.length); i++) {
                if (!was[i].equals(now[i])) {
                    differing++;
                }
            }
            return differing;
        }
    }

    /** What regenerating would do. */
    public static final class Plan {
        private final List<Change> changes;
        private final String robotVersion;
        private final boolean robotVersionFromProject;

        Plan(List<Change> changes, String robotVersion, boolean robotVersionFromProject) {
            this.changes = Collections.unmodifiableList(changes);
            this.robotVersion = robotVersion;
            this.robotVersionFromProject = robotVersionFromProject;
        }

        /** Every generated file, changed or not. */
        public List<Change> getChanges() {
            return changes;
        }

        /** Only the ones writing would alter. */
        public List<Change> getChanged() {
            List<Change> changed = new ArrayList<Change>();
            for (Change change : changes) {
                if (change.isChanged()) {
                    changed.add(change);
                }
            }
            return changed;
        }

        public boolean isUpToDate() {
            return getChanged().isEmpty();
        }

        public String getRobotVersion() {
            return robotVersion;
        }

        /**
         * Whether the ROBOT version came from the project or from this plugin.
         *
         * <p>False means the project predates {@code robot_version:} in the YAML, and regenerating
         * would write this plugin's version into its CI. That is a decision for the user, not a
         * silent substitution, so a caller must surface it.
         */
        public boolean isRobotVersionFromProject() {
            return robotVersionFromProject;
        }
    }

    /**
     * Why this project must not be regenerated by OntoBoard, or null.
     *
     * <p><b>This guard is the whole reason the operation is safe.</b> Regeneration rewrites the
     * Makefile, the CI workflow, the README and the ignore files from OntoBoard's own templates.
     * On a project OntoBoard scaffolded that is the point. On a real ODK repository it is
     * destruction: MWO's {@code src/ontology/Makefile} is over 750 lines of ODK's own build, and
     * replacing it with the eight-target one this plugin generates would take away every import
     * rule, every release artefact and every quality target the project has.
     *
     * <p>It became reachable in 1.72.0 and not before, which is worth recording. Until then
     * {@code baseIriIn} could not read an OWL functional-syntax edit file, so every real ODK
     * project failed earlier with "records no xml:base" - an accident that happened to protect
     * them. Teaching the reader functional syntax removed that accident, so the protection has
     * to be deliberate instead.
     *
     * <p>Two signals, either of which is conclusive. {@code src/ontology/run.sh} is ODK's Docker
     * wrapper and only ODK puts one there. {@code ODK_VERSION_MAKEFILE} is declared by ODK's
     * generated Makefile and by nothing OntoBoard writes - checked against both: MWO's Makefile
     * declares ODK_VERSION_MAKEFILE and has a run.sh beside it, a scaffolded one has neither.
     *
     * <p>ODK regenerates these files itself, with {@code sh run.sh make update_repo}, so the
     * refusal
     * can name the command that does work rather than leaving the user with nothing. The
     * {@code make} is not optional and 1.72.0 to 1.75.0 left it out: run.sh passes "$@"
     * straight to the container as the command to execute, so {@code sh run.sh update_repo}
     * tries to exec a binary of that name and fails. Checked against a real run.sh, not
     * remembered.
     */
    static String whyNotOurs(File projectRoot) {
        if (projectRoot == null) {
            return null;
        }
        File ontology = new File(new File(projectRoot, "src"), "ontology");
        if (new File(ontology, "run.sh").isFile() || new File(ontology, "run.bat").isFile()) {
            return "This is an ODK repository - it has its own run.sh - and ODK regenerates its "
                    + "files itself. Run 'sh run.sh make update_repo' from "
                    + ontology.getAbsolutePath()
                    + " instead. OntoBoard will not rewrite a Makefile it did not write: its own "
                    + "is eight targets, and ODK's is several hundred.";
        }
        File makefile = new File(ontology, "Makefile");
        if (makefile.isFile()) {
            String text;
            try {
                text = new String(java.nio.file.Files.readAllBytes(makefile.toPath()), "UTF-8");
            } catch (java.io.IOException unreadable) {
                text = "";
            }
            if (text.contains("ODK_VERSION_MAKEFILE")) {
                return "This project's Makefile was generated by ODK itself, not by OntoBoard. "
                        + "ODK regenerates it with 'sh run.sh make update_repo'; rewriting it "
                        + "from "
                        + "here would replace several hundred lines of build with the eight "
                        + "targets OntoBoard generates.";
            }
        }
        return null;
    }

    /**
     * Works out what re-rendering would change. Touches nothing.
     *
     * @throws OdkProjectSettings.UnreadableProjectException if the project cannot be read
     */
    public static Plan plan(File projectRoot) {
        String foreign = whyNotOurs(projectRoot);
        if (foreign != null) {
            throw new OdkProjectSettings.UnreadableProjectException(foreign);
        }
        OdkProjectSettings.Settings settings = OdkProjectSettings.read(projectRoot);
        boolean fromProject = settings.getRobotVersion() != null;
        String robotVersion = fromProject
                ? settings.getRobotVersion()
                : OdkScaffold.ROBOT_VERSION;

        Map<String, String> rendered =
                OdkScaffold.regenerableFiles(settings.getConfig(), robotVersion);
        List<Change> changes = new ArrayList<Change>();
        for (Map.Entry<String, String> file : rendered.entrySet()) {
            File onDisk = new File(projectRoot, file.getKey().replace('/', File.separatorChar));
            String before = onDisk.isFile() ? textOf(onDisk) : null;
            changes.add(new Change(file.getKey(), before, file.getValue()));
        }
        return new Plan(changes, robotVersion, fromProject);
    }

    /**
     * Writes the files that would change, and only those.
     *
     * <p>Only the changed ones, so a project already up to date is left with untouched timestamps -
     * which matters because {@code make} decides what to rebuild from them, and rewriting an
     * identical Makefile would trigger a full rebuild for no reason.
     *
     * @return the files written, in the plan's order
     * @throws OdkRegenerationException if any file cannot be written
     */
    public static List<File> apply(File projectRoot, Plan plan) {
        if (projectRoot == null || plan == null) {
            throw new IllegalArgumentException("a project and a plan are both needed");
        }
        List<File> written = new ArrayList<File>();
        for (Change change : plan.getChanged()) {
            File target = new File(projectRoot,
                    change.getPath().replace('/', File.separatorChar));
            File directory = target.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
                throw new OdkRegenerationException("Could not create "
                        + directory.getAbsolutePath(), written);
            }
            try {
                // LF, as the scaffold has always written. Normalising happens when comparing, not
                // here - rewriting somebody's file with different line endings than the generator
                // has always used would be a change nobody asked for.
                Files.write(target.toPath(), change.getAfter().getBytes(UTF8));
                written.add(target);
            } catch (IOException cannotWrite) {
                throw new OdkRegenerationException("Could not write " + target.getAbsolutePath()
                        + ": " + cannotWrite.getMessage(), written);
            }
        }
        return written;
    }

    /**
     * Signals a regeneration that stopped part way, naming what it had already written.
     *
     * <p>The list matters: a caller has to be able to tell the user which files changed before the
     * failure, because the project is now in a mixed state and "it failed" does not say that.
     */
    public static class OdkRegenerationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final List<File> written;

        OdkRegenerationException(String message, List<File> written) {
            super(message);
            this.written = Collections.unmodifiableList(new ArrayList<File>(written));
        }

        public List<File> getWritten() {
            return written;
        }
    }

    /** CRLF and lone CR to LF, so a checkout's line endings do not read as a change. */
    static String normalise(String text) {
        return text == null ? null : text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String textOf(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), UTF8);
        } catch (IOException cannotRead) {
            throw new OdkProjectSettings.UnreadableProjectException("Could not read "
                    + file.getAbsolutePath() + ": " + cannotRead.getMessage());
        }
    }
}
