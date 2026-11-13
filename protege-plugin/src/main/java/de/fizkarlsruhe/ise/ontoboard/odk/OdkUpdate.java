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
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
    /** What is written on the side while the edit file's imports are tried; removed again. */
    private static final String STAGED_CATALOG = ".ontoboard-update-catalog.xml";
    private static final String STAGED_EDIT = ".ontoboard-update-edit";

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
        private final List<ImportGuard.Undeclared> undeclared;
        private final List<String> notImported;
        private final Set<String> agreed;

        Plan(List<Change> changes, String fromVersion, File editFile, boolean obo,
                List<String> imports, byte[] catalog, Set<String> executable,
                List<String> warnings, List<ImportGuard.Undeclared> undeclared,
                List<String> notImported, Set<String> agreed) {
            this.changes = Collections.unmodifiableList(changes);
            this.fromVersion = fromVersion;
            this.editFile = editFile;
            this.obo = obo;
            this.imports = Collections.unmodifiableList(imports);
            this.catalog = catalog;
            this.executable = executable;
            this.warnings = Collections.unmodifiableList(warnings);
            this.undeclared = Collections.unmodifiableList(undeclared);
            this.notImported = Collections.unmodifiableList(notImported);
            this.agreed = agreed;
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

        /** The edit file ROBOT rewrites; null when the update leaves it alone. */
        public File editFile() {
            return editFile;
        }

        /** What the edit file imports after the update; empty when it is not rewritten. */
        public List<String> imports() {
            return imports;
        }

        /** What ODK's update says at its end that a person has to see to. */
        public List<String> warnings() {
            return warnings;
        }

        /**
         * The imports of the edit file that the configuration does not declare: the update
         * would take them out. Empty where ODK leaves the edit file's imports alone.
         */
        public List<ImportGuard.Undeclared> undeclared() {
            return undeclared;
        }

        /**
         * The same plan, with it agreed that the update takes these imports out - because
         * that is wanted, or because each has been declared and its module takes its place.
         */
        public Plan removing(Collection<String> imports) {
            Set<String> all = new TreeSet<String>(agreed);
            all.addAll(imports);
            return new Plan(changes, fromVersion, editFile, obo, this.imports, catalog,
                    executable, warnings, undeclared, notImported, all);
        }

        /**
         * The modules in the project's imports folder that nothing imports - not the edit
         * file as it is, and not the update for a product the configuration declares.
         * They are named and left alone: neither declared nor deleted. Paths from the
         * project's root, sorted; empty where ODK leaves the edit file's imports alone.
         */
        public List<String> notImported() {
            return notImported;
        }

        /** The imports the update would take out and nobody agreed to. */
        List<String> undecided() {
            List<String> left = new ArrayList<String>();
            for (ImportGuard.Undeclared item : undeclared) {
                if (!agreed.contains(item.iri())) {
                    left.add(item.iri());
                }
            }
            return left;
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
        List<ImportGuard.Undeclared> undeclared = new ArrayList<ImportGuard.Undeclared>();
        List<String> notImported = new ArrayList<String>();
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
            if (editFile.isFile()) {
                try {
                    List<String> now = ImportGuard.importsOf(editFile);
                    undeclared = ImportGuard.undeclared(now, imports, new File(root, CATALOG));
                    notImported = ImportGuard.notImported(root, ontbase(project), imports, now,
                            new File(root, CATALOG));
                } catch (IOException unreadable) {
                    throw new Refused(unreadable.getMessage() + " ODK's update brings its "
                            + "import declarations in line with the configuration, so it "
                            + "has to be read first.");
                }
            }
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
                new TreeSet<String>(installed.executable()), warnings, undeclared,
                notImported, new TreeSet<String>());
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
        String base = ontbase(project);
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

    /** What ODK's Makefile calls ONTBASE: where the project's own files have their IRIs. */
    static String ontbase(PyObject project) {
        Object suffix = project.get("uribase_suffix");
        return Py.str(project.get("uribase")) + "/"
                + Py.str(suffix instanceof PyNone ? project.get("id") : suffix);
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
        if (!plan.undecided().isEmpty()) {
            throw new ImportGuard.Undecided(plan.undecided());
        }
        File root = projectRoot.getAbsoluteFile();
        File ontology = new File(root, ONTOLOGY);
        File stagedCatalog = new File(ontology, STAGED_CATALOG);
        File stagedEdit = new File(ontology, STAGED_EDIT + (plan.obo ? ".obo" : ".ofn"));
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
                    replace(plan.editFile, stagedEdit);
                } catch (IOException notMoved) {
                    throw new UncheckedIOException(plan.editFile + " could not be replaced: "
                            + why(notMoved), notMoved);
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

    // ------------------------------------------------------------------ ODK's own, in its container

    /** What ODK's own update did, run in its container. */
    public static final class Ran {
        private final String failure;
        private final List<String> transcript;
        private final List<File> written;

        Ran(String failure, List<String> transcript, List<File> written) {
            this.failure = failure;
            this.transcript = Collections.unmodifiableList(transcript);
            this.written = Collections.unmodifiableList(written);
        }

        public boolean isOk() {
            return failure == null;
        }

        /** Why the update did not finish, for a person to read; null when it did. */
        public String getFailure() {
            return failure;
        }

        /** The command, and what ODK's update said. */
        public List<String> getTranscript() {
            return transcript;
        }

        /** The files it made or changed. */
        public List<File> getWritten() {
            return written;
        }
    }

    /**
     * Looks for what would stop an update half way - an import of the edit file that cannot be
     * loaded - without writing anything: the edit file is rewritten on the side, as the update
     * will rewrite it, and what was made for that is removed again.
     *
     * @return whether the update would change the edit file
     * @throws Refused when the edit file cannot be read with all its imports
     */
    public static boolean check(File projectRoot, Plan plan) {
        if (plan.editFile == null) {
            return false;
        }
        File ontology = new File(projectRoot.getAbsoluteFile(), ONTOLOGY);
        File stagedCatalog = new File(ontology, STAGED_CATALOG);
        File stagedEdit = new File(ontology, STAGED_EDIT + (plan.obo ? ".obo" : ".ofn"));
        try {
            write(stagedCatalog, plan.catalog);
            rewriteImports(plan, stagedCatalog, stagedEdit);
            return !plan.editFile.isFile()
                    || !Arrays.equals(read(plan.editFile), read(stagedEdit));
        } finally {
            stagedCatalog.delete();
            stagedEdit.delete();
        }
    }

    /**
     * ODK's own update as its container is asked for it: {@code odk.py update} in the
     * project's {@code src/ontology}, which is what ODK's {@code sh run.sh odk.py update} runs.
     */
    public static List<String> containerCommand(String runtime, String image,
            OdkContainer.Host host, File projectRoot) {
        return MakeRun.containerCommandFor(runtime,
                new File(projectRoot.getAbsoluteFile(), ONTOLOGY), image, host,
                Arrays.asList("odk.py", "update"));
    }

    /**
     * Updates the project with ODK's own update, in ODK's container.
     *
     * <p>What would stop ODK's update half way is looked for first ({@link #check}), since
     * ODK's own stops at its last step with every other file written. Then ODK does it all.
     *
     * @param plan what an update would do, made by {@link #plan} just before: it says which
     *             files to look at afterwards
     * @throws Refused when the edit file cannot be read with all its imports; nothing has been
     *                 written then
     */
    public static Ran inContainer(String runtime, String image, OdkContainer.Host host,
            File projectRoot, Plan plan, ProcessRunner.Runner runner) {
        if (!plan.undecided().isEmpty()) {
            throw new ImportGuard.Undecided(plan.undecided());
        }
        check(projectRoot, plan);
        File root = projectRoot.getAbsoluteFile();
        List<File> looked = new ArrayList<File>();
        for (Change change : plan.changes) {
            looked.add(new File(root, change.path().replace('/', File.separatorChar)));
        }
        if (plan.editFile != null) {
            looked.add(plan.editFile);
        }
        List<byte[]> before = new ArrayList<byte[]>();
        for (File file : looked) {
            before.add(file.isFile() ? read(file) : null);
        }
        List<String> command = containerCommand(runtime, image, host, root);
        List<String> transcript = new ArrayList<String>();
        transcript.add("$ " + ReleaseRun.shown(command));
        ProcessRunner.Outcome outcome;
        try {
            outcome = runner.run(new File(root, ONTOLOGY), command, MakeRun.TIMEOUT_MINUTES,
                    null);
        } catch (IOException cannotStart) {
            return new Ran("ODK's own update could not be started: " + cannotStart.getMessage()
                    + ". Nothing was written.", transcript, new ArrayList<File>());
        }
        transcript.addAll(outcome.getOutput());
        List<File> written = new ArrayList<File>();
        for (int i = 0; i < looked.size(); i++) {
            File file = looked.get(i);
            if (file.isFile() && !Arrays.equals(before.get(i), read(file))) {
                written.add(file);
            }
        }
        if (outcome.isSuccess()) {
            return new Ran(null, transcript, written);
        }
        String last = outcome.lastMeaningfulLine();
        return new Ran("ODK's own update stopped" + (last == null || last.trim().isEmpty() ? ""
                : ": " + last.trim()) + (written.isEmpty() ? " Nothing was written."
                : " " + written.size() + (written.size() == 1 ? " file was" : " files were")
                        + " written before it stopped; they are listed, and the log has what "
                        + "ODK said."), transcript, written);
    }

    // ------------------------------------------------------------------ bytes and text

    private static byte[] read(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException unreadable) {
            throw new UncheckedIOException(file + " could not be read: " + why(unreadable),
                    unreadable);
        }
    }

    private static void write(File file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Files.write(file.toPath(), bytes);
        } catch (IOException notWritten) {
            throw new UncheckedIOException(file + " could not be written: " + why(notWritten),
                    notWritten);
        }
    }

    /**
     * Puts the rewritten edit file in the place of the edit file, in one step: the file is the
     * old one or the new one, and never missing. The plain way to replace a file takes the old
     * one away first, and an edit file that then cannot be put there - Windows refuses while
     * another program looks at the new file - would be gone, with work in it nobody committed.
     */
    private static void replace(File editFile, File rewritten) throws IOException {
        try {
            Files.move(rewritten.toPath(), editFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notInOneStep) {
            // A file system that cannot: written over in place, which at least never takes
            // the file away.
            Files.write(editFile.toPath(), Files.readAllBytes(rewritten.toPath()));
        }
    }

    /** What went wrong with a file, without the file's path a second time. */
    private static String why(IOException failure) {
        if (failure instanceof AccessDeniedException) {
            return "access was denied - it may be read-only, open in another program, or a "
                    + "folder";
        }
        if (failure instanceof FileSystemException
                && ((FileSystemException) failure).getReason() != null) {
            return ((FileSystemException) failure).getReason();
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
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
