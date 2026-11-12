package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.CatalogMerge;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.GitignoreMerge;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkConfigException;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Py;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.PyNone;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.PyObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Update project files, without ODK's container: what {@code odk.py update} of ODK v1.6.1 does in
 * a project's {@code src/ontology}, done here.
 *
 * <p>ODK's update renders its templates for the project's configuration and writes them by three
 * rules ({@link OdkInstaller#updatePolicies}): the Makefile, {@code run.sh}, the SPARQL checks,
 * the workflow documentation and the CI workflows the configuration names are written again;
 * the files a person writes are never touched; everything else is added when it is missing. Two
 * files are merged with what the project has ({@link GitignoreMerge}, {@link CatalogMerge}), and
 * at the end ROBOT brings the edit file's import declarations in line with the configuration.
 *
 * <p>Two things are done otherwise than ODK does them, both on purpose:
 * <ul>
 *   <li><b>The last step is tried first.</b> ODK's update stops at that last step when an import
 *       of the edit file cannot be loaded, with every other file written already. Here the edit
 *       file is rewritten on the side before anything else is written, and when that fails
 *       nothing is written at all.</li>
 *   <li><b>A file that differs only in its line endings is left alone.</b> A checkout on Windows
 *       has every file with Windows line endings; writing ODK's again would change every one of
 *       them for nothing.</li>
 * </ul>
 */
public final class OdkUpdate {

    private static final String ONTOLOGY = "src/ontology";
    private static final String GITIGNORE = ".gitignore";
    private static final String CATALOG = ONTOLOGY + "/catalog-v001.xml";
    private static final String SUFFIX = "-odk.yaml";

    private OdkUpdate() {
    }

    /** Why the update is not made; the message is the reason, for a person to read. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /** One file the update writes. */
    public static final class Change {
        private final String path;
        private final byte[] before;
        private final byte[] after;

        Change(String path, byte[] before, byte[] after) {
            this.path = path;
            this.before = before;
            this.after = after;
        }

        /** From the project's root, with {@code /}. */
        public String path() {
            return path;
        }

        /** The file as the project has it; null when it has none. */
        public byte[] before() {
            return before == null ? null : before.clone();
        }

        public byte[] after() {
            return after.clone();
        }

        public boolean isNew() {
            return before == null;
        }

        /** Whether the update changes the file: line endings alone are no change. */
        public boolean isChanged() {
            return before == null || !Arrays.equals(unix(before), unix(after));
        }
    }

    /** What an update would do: made by looking, without writing. */
    public static final class Plan {
        private final List<Change> changes;
        private final String fromVersion;
        private final File editFile;
        private final boolean obo;
        private final List<String> imports;
        private final byte[] catalog;
        private final Set<String> executable;
        private final List<String> warnings;

        Plan(List<Change> changes, String fromVersion, File editFile, boolean obo,
                List<String> imports, byte[] catalog, Set<String> executable,
                List<String> warnings) {
            this.changes = Collections.unmodifiableList(changes);
            this.fromVersion = fromVersion;
            this.editFile = editFile;
            this.obo = obo;
            this.imports = Collections.unmodifiableList(imports);
            this.catalog = catalog;
            this.executable = executable;
            this.warnings = Collections.unmodifiableList(warnings);
        }

        /** Every file ODK's update writes for this project, changed by it or not, in its order. */
        public List<Change> changes() {
            return changes;
        }

        /** The ODK that wrote the project's Makefile, or null when the Makefile does not say. */
        public String fromVersion() {
            return fromVersion;
        }

        /** The ODK whose files the update writes. */
        public String toVersion() {
            return OdkPin.VERSION;
        }

        /**
         * Whether ROBOT rewrites the edit file, so that it imports what the configuration
         * declares and nothing else. Not for a project that looks after its imports itself.
         */
        public boolean editFileWillBeRewritten() {
            return editFile != null;
        }

        /** What the edit file imports after the update; empty when it is not rewritten. */
        public List<String> imports() {
            return imports;
        }

        /** What ODK's update says at its end that a person has to see to. */
        public List<String> warnings() {
            return warnings;
        }
    }

    // ------------------------------------------------------------------ looking

    /**
     * What an update of this project would do.
     *
     * @throws Refused when the project has not exactly one configuration, the configuration is
     *                 not one ODK reads, the project's files are of a newer ODK, or its catalog
     *                 cannot be merged
     */
    public static Plan plan(File projectRoot) {
        final File root = projectRoot.getAbsoluteFile();
        File ontology = new File(root, ONTOLOGY);
        File config = theConfiguration(ontology);
        PyObject project;
        try {
            project = OdkProjectModel.load(text(read(config)));
        } catch (OdkConfigException notOdks) {
            throw new Refused(config.getName() + " is not a configuration ODK's update can go "
                    + "by: " + notOdks.getMessage());
        }
        File makefile = new File(ontology, "Makefile");
        String from = makefile.isFile() ? OdkImage.versionOf(text(read(makefile))) : null;
        if (from != null && isNewer(from, OdkPin.VERSION)) {
            throw new Refused("This project's files were written by ODK " + from + ", which is "
                    + "newer than the ODK OntoBoard brings (" + OdkPin.VERSION + "). An update "
                    + "here would move them back. Update with that ODK itself, or with an "
                    + "OntoBoard that brings it.");
        }

        List<Change> changes = new ArrayList<Change>();
        OdkInstaller.Rendered installed;
        Map<String, byte[]> templates;
        try {
            installed = OdkInstaller.install(project, OdkInstaller.updatePolicies(project),
                    new Predicate<String>() {
                        @Override
                        public boolean test(String path) {
                            return new File(root, path).exists();
                        }
                    });
            templates = OdkInstaller.renderAll(project).files();
        } catch (OdkConfigException notRendered) {
            throw new Refused(notRendered.getMessage());
        }
        for (Map.Entry<String, byte[]> file : installed.files().entrySet()) {
            changes.add(change(root, file.getKey(), file.getValue()));
        }
        changes.add(change(root, GITIGNORE, utf8(GitignoreMerge.merge(
                text(templates.get(GITIGNORE)), textOrNull(new File(root, GITIGNORE))))));

        List<String> warnings = new ArrayList<String>();
        File editFile = null;
        boolean obo = false;
        List<String> imports = new ArrayList<String>();
        byte[] catalog = null;
        if (Py.truthy(project.get("manage_import_declarations"))) {
            try {
                catalog = utf8(CatalogMerge.merge(text(templates.get(CATALOG)),
                        textOrNull(new File(root, CATALOG))));
            } catch (IllegalArgumentException notMerged) {
                throw new Refused(notMerged.getMessage());
            }
            changes.add(change(root, CATALOG, catalog));
            String format = Py.str(project.get("edit_format"));
            obo = !format.equals("owl");
            editFile = new File(ontology, Py.str(project.get("id")) + "-edit." + format);
            imports = importsOf(project);
        } else {
            warnings.add("You may need to update the -edit file and the XML catalog if you have "
                    + "added, removed or modified any import or component.");
        }
        warnings.add("This file should be manually migrated: mkdocs.yaml");
        if (Py.contains(project.get("ci"), "github_actions")
                && !Py.contains(project.get("workflows"), "qc")) {
            warnings.add("Your QC workflows have not been updated automatically. Please update "
                    + "the ODK version number in .github/workflows/qc.yml");
        }
        return new Plan(changes, from, editFile, obo, imports, catalog,
                new TreeSet<String>(installed.executable()), warnings);
    }

    /** The one {@code *-odk.yaml} of the folder ODK's update runs in. */
    private static File theConfiguration(File ontology) {
        List<String> found = new ArrayList<String>();
        String[] names = ontology.list();
        for (String name : names == null ? new String[0] : names) {
            if (name.endsWith(SUFFIX) && new File(ontology, name).isFile()) {
                found.add(name);
            }
        }
        Collections.sort(found);
        if (found.isEmpty()) {
            throw new Refused("This project has no ODK configuration: " + ONTOLOGY + " holds no "
                    + "file whose name ends in " + SUFFIX + ". The update brings a project's "
                    + "files in line with that file, so there is nothing to go by.");
        }
        if (found.size() > 1) {
            throw new Refused(ONTOLOGY + " holds more than one ODK configuration ("
                    + String.join(", ", found) + "). ODK's update stops there too: keep the one "
                    + "the project is built from.");
        }
        return new File(ontology, found.get(0));
    }

    /**
     * The import declarations ODK's update gives the edit file: one for each import module (or
     * the merged one), each component, and the pattern files.
     */
    private static List<String> importsOf(PyObject project) {
        Object suffix = project.get("uribase_suffix");
        String base = Py.str(project.get("uribase")) + "/"
                + Py.str(suffix instanceof PyNone ? project.get("id") : suffix);
        List<String> imports = new ArrayList<String>();
        Object group = project.get("import_group");
        if (group instanceof PyObject) {
            if (Py.truthy(((PyObject) group).get("use_base_merging"))) {
                imports.add(base + "/imports/merged_import.owl");
            } else {
                for (Object product : productsOf((PyObject) group)) {
                    imports.add(base + "/imports/" + Py.str(((PyObject) product).get("id"))
                            + "_import.owl");
                }
            }
        }
        Object components = project.get("components");
        if (components instanceof PyObject) {
            for (Object component : productsOf((PyObject) components)) {
                imports.add(base + "/components/"
                        + Py.str(((PyObject) component).get("filename")));
            }
        }
        if (Py.truthy(project.get("use_dosdps"))) {
            imports.add(base + "/patterns/definitions.owl");
            if (Py.truthy(project.get("import_pattern_ontology"))) {
                imports.add(base + "/patterns/pattern.owl");
            }
        }
        return imports;
    }

    private static Iterable<Object> productsOf(PyObject group) {
        Object products = group.get("products");
        return products instanceof PyNone ? Collections.<Object>emptyList()
                : Py.iterate(products);
    }

    private static Change change(File root, String path, byte[] after) {
        File file = new File(root, path);
        return new Change(path, file.isFile() ? read(file) : null, after);
    }

    /** Whether an ODK version is later than another: by the numbers they start with. */
    static boolean isNewer(String version, String than) {
        List<Integer> one = numbersOf(version);
        List<Integer> other = numbersOf(than);
        for (int i = 0; i < Math.max(one.size(), other.size()); i++) {
            int a = i < one.size() ? one.get(i) : 0;
            int b = i < other.size() ? other.get(i) : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static List<Integer> numbersOf(String version) {
        List<Integer> numbers = new ArrayList<Integer>();
        Matcher digits = Pattern.compile("^v?(\\d+(?:\\.\\d+)*)").matcher(version);
        if (digits.find()) {
            for (String part : digits.group(1).split("\\.")) {
                numbers.add(part.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(part));
            }
        }
        return numbers;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Makes the update a plan describes.
     *
     * @return the files written: those the update changed or added, in ODK's order, the edit
     *         file last
     * @throws Refused when the edit file cannot be read with all its imports; nothing has been
     *                 written then
     */
    public static List<File> apply(File projectRoot, Plan plan) {
        File root = projectRoot.getAbsoluteFile();
        File ontology = new File(root, ONTOLOGY);
        File stagedCatalog = new File(ontology, ".ontoboard-update-catalog.xml");
        File stagedEdit = new File(ontology, ".ontoboard-update-edit"
                + (plan.obo ? ".obo" : ".ofn"));
        try {
            boolean editChanges = false;
            if (plan.editFile != null) {
                // ODK's last step, first and on the side: when it fails, nothing is written.
                write(stagedCatalog, plan.catalog);
                rewriteImports(plan, stagedCatalog, stagedEdit);
                editChanges = !plan.editFile.isFile()
                        || !Arrays.equals(read(plan.editFile), read(stagedEdit));
            }
            List<File> written = new ArrayList<File>();
            for (Change change : plan.changes) {
                if (change.isChanged()) {
                    File target = new File(root, change.path().replace('/', File.separatorChar));
                    write(target, change.after);
                    if (plan.executable.contains(change.path())) {
                        target.setExecutable(true, false);
                    }
                    written.add(target);
                }
            }
            if (editChanges) {
                try {
                    Files.move(stagedEdit.toPath(), plan.editFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException notMoved) {
                    throw new UncheckedIOException("The edit file could not be replaced: "
                            + notMoved.getMessage(), notMoved);
                }
                written.add(plan.editFile);
            }
            return written;
        } finally {
            stagedCatalog.delete();
            stagedEdit.delete();
        }
    }

    /**
     * {@code robot odk:import -i <edit> --exclusive true --add ... convert ...}, as ODK's update
     * runs it, reading with the catalog the update is about to write and writing beside the
     * edit file.
     */
    private static void rewriteImports(Plan plan, File catalog, File output) {
        List<String> line = new ArrayList<String>(Arrays.asList("--catalog",
                catalog.getAbsolutePath(), "odk:import", "-i", plan.editFile.getAbsolutePath(),
                "--exclusive", "true"));
        for (String iri : plan.imports) {
            line.add("--add");
            line.add(iri);
        }
        line.add("convert");
        if (plan.obo) {
            line.addAll(Arrays.asList("--check", "false"));
        } else {
            line.addAll(Arrays.asList("-f", "ofn"));
        }
        line.addAll(Arrays.asList("-o", output.getAbsolutePath()));
        String failure;
        try {
            OdkRobot.Result result = OdkRobot.run(line);
            failure = result.ok() ? null : result.failure();
        } catch (IllegalArgumentException refused) {
            failure = refused.getMessage();
        }
        if (failure != null) {
            throw new Refused("ODK's update brings the edit file's imports in line with the "
                    + "configuration, and for that " + plan.editFile.getName() + " has to be "
                    + "read with everything it imports. That did not work: " + failure
                    + " Nothing was written. Put the import right - the file its catalog entry "
                    + "names may be missing - or take it out of the edit file, and update again. "
                    + "(ODK's own update stops at the same place, with its other files written "
                    + "already.)");
        }
    }

    // ------------------------------------------------------------------ bytes and text

    private static byte[] read(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            throw new UncheckedIOException(file + " could not be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    private static void write(File file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), bytes);
        } catch (IOException notWritten) {
            throw new UncheckedIOException(file + " could not be written: "
                    + notWritten.getMessage(), notWritten);
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String textOrNull(File file) {
        return file.isFile() ? text(read(file)) : null;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The bytes with every Windows line ending made a Unix one. */
    private static byte[] unix(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') {
                continue;
            }
            out.write(bytes[i]);
        }
        return out.toByteArray();
    }
}
