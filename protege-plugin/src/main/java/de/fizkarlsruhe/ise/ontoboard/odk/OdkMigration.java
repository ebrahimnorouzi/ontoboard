package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * A project OntoBoard 1.135.x made, turned into an ODK project.
 *
 * <p>Such a project has an ODK configuration that nothing read: its Makefile was OntoBoard's
 * own, written once and calling ROBOT directly. From 1.153.0 a project is ODK's - its Makefile
 * and everything beside it written by ODK from the configuration - and <em>Update project
 * files...</em> carries an older one over in two steps:
 *
 * <ol>
 *   <li>{@link #convert}, which is this class: the configuration becomes one ODK's update can
 *       go by and that says what the project is - the modules the edit file imports declared
 *       in it, its report profile and the report's scope named - the project's own makefile
 *       gets the rule that asks its questions, and the version 1.135 stamped into the edit
 *       file is taken out of it: ODK's release writes each release's own beside it.
 *   <li>ODK's update ({@link OdkUpdate}), as for any ODK project: the Makefile, {@code run.sh},
 *       ODK's own checks and workflows are written, the catalog and {@code .gitignore} merged,
 *       the edit file's imports brought in line with the configuration.
 * </ol>
 *
 * <p>Nothing of the person's own is rewritten or renamed: the ontology's content (but for that
 * stamp, a line of its own taken out where it is one), the README,
 * the pages under {@code docs/}, the releases, the report profile with whatever was relaxed in
 * it, the modules and their term lists - and the test queries. The older layout asked every
 * {@code .rq} file in {@code src/sparql} of the ontology with its imports merged; ODK's own
 * checks read the edit file without them, where such a query changes its answer. So the
 * queries stay what and where they are, and {@link OdkQuestions}' rule, added at the end of
 * the project's own makefile, goes on asking them as they were asked.
 *
 * <p>Looking ({@link #plan}) writes nothing. A conversion that was made and not followed by the
 * update - a container that did not start, a wait given up - leaves a project the next try
 * finds nothing to convert in and goes on from.
 */
public final class OdkMigration {

    private static final String ONTOLOGY = "src/ontology";

    /** The lines under the first one of the header 1.135.x gave a configuration. */
    private static final List<String> OLD_HEADER = Collections.unmodifiableList(Arrays.asList(
            "# This project's Makefile calls robot directly, so it needs make and robot on",
            "# PATH - not Docker. Nothing regenerates the Makefile from this file:",
            "# ODK's `make update_repo` is not part of this project. Put your own",
            "# and never overwrites."));

    /** What the conversion would do: made by looking, without writing. */
    public static final class Plan {
        private final String configPath;
        private final String oldConfig;
        private final String newConfig;
        private final List<String> questions;
        private final String ownMakefilePath;
        private final String oldOwnMakefile;
        private final String newOwnMakefile;
        private final List<String> declared;
        private final List<String> notes;
        private final List<String> warnings;
        private final String editFilePath;
        private final byte[] oldEdit;
        private final Unstamped unstamped;
        private final OdkUpdate.Plan update;

        Plan(String configPath, String oldConfig, String newConfig, List<String> questions,
                String ownMakefilePath, String oldOwnMakefile, String newOwnMakefile,
                List<String> declared, List<String> notes, List<String> warnings,
                String editFilePath, byte[] oldEdit, Unstamped unstamped, OdkUpdate.Plan update) {
            this.configPath = configPath;
            this.oldConfig = oldConfig;
            this.newConfig = newConfig;
            this.questions = Collections.unmodifiableList(questions);
            this.ownMakefilePath = ownMakefilePath;
            this.oldOwnMakefile = oldOwnMakefile;
            this.newOwnMakefile = newOwnMakefile;
            this.declared = Collections.unmodifiableList(declared);
            this.notes = Collections.unmodifiableList(notes);
            this.warnings = Collections.unmodifiableList(warnings);
            this.editFilePath = editFilePath;
            this.oldEdit = oldEdit;
            this.unstamped = unstamped;
            this.update = update;
        }

        /** The configuration, from the project's root, with {@code /}. */
        public String configPath() {
            return configPath;
        }

        /** The configuration as it becomes. */
        public String newConfig() {
            return newConfig;
        }

        /** Whether the configuration changes; not for one that was converted before. */
        public boolean changesTheConfig() {
            return !newConfig.equals(oldConfig);
        }

        /**
         * The project's questions: the {@code .rq} files of {@code src/sparql}, by name, from
         * the project's root. They stay as they are.
         */
        public List<String> questions() {
            return questions;
        }

        /** The project's own makefile, from the project's root, with {@code /}. */
        public String ownMakefilePath() {
            return ownMakefilePath;
        }

        /**
         * Whether the rule that asks the questions is written into the project's own
         * makefile: the project has questions, and the rule is not there as OntoBoard writes
         * it.
         */
        public boolean addsTheQuestionRule() {
            return newOwnMakefile != null;
        }

        /** A sentence for each module the edit file imports that is declared now. */
        public List<String> declared() {
            return declared;
        }

        /**
         * The modules nothing imports: named, and neither declared nor deleted. Paths from
         * the project's root.
         */
        public List<String> leftAlone() {
            return update.notImported();
        }

        /** What else a person may want to know: a sentence each. */
        public List<String> notes() {
            return notes;
        }

        /** What a person has to see to: a sentence each. */
        public List<String> warnings() {
            return warnings;
        }

        /** What ODK's update then does, planned on the configuration as it becomes. */
        public OdkUpdate.Plan update() {
            return update;
        }

        /** The edit file, from the project's root, with {@code /}. */
        public String editFilePath() {
            return editFilePath;
        }

        /**
         * What is taken out of the edit file, each as written - {@code owl:versionInfo
         * "0.1.0"}: the version the ontology carries itself, which ODK's release writes for
         * each release beside any the edit file holds. Empty when there is none.
         */
        public List<String> stampsTakenOut() {
            return unstamped.stamps;
        }
    }

    /** The edit file without the ontology's own {@code owl:versionInfo}: what goes, and the bytes. */
    static final class Unstamped {
        private final List<String> stamps;
        /** The file as it becomes; null when there is nothing to take out. */
        private final byte[] bytes;

        private Unstamped(List<String> stamps, byte[] bytes) {
            this.stamps = Collections.unmodifiableList(stamps);
            this.bytes = bytes;
        }
    }

    private OdkMigration() {
    }

    /**
     * What a project of the older layout is told wherever it asks for something that is
     * ODK's Makefile's to do: what it is, and what makes it an ODK project.
     */
    public static final String CARRY_OVER = "This project was made by OntoBoard 1.135 or "
            + "before: its Makefile is OntoBoard's own, and OntoBoard builds and releases "
            + "ODK projects now, with ODK's own Makefile. "
            + "OntoBoard > Project > Update project files... makes this one an ODK project: "
            + "it shows every change before it makes any, and keeps the ontology, its "
            + "imports and its tests.";

    /**
     * The first line of every Makefile OntoBoard's older layout wrote, whichever version
     * wrote it: what tells such a project from ODK's.
     */
    static final String OLD_MAKEFILE_HEADER =
            "# WARNING: generated by OntoBoard. Do NOT edit by hand.";

    /**
     * Whether a project is one OntoBoard 1.135.x or before made: its Makefile opens with
     * OntoBoard's own first line, as every Makefile that layout ever wrote does.
     */
    public static boolean isOntoBoards1x(File projectRoot) {
        return MakeRun.writtenByOntoBoard(new File(projectRoot, ONTOLOGY));
    }

    // ------------------------------------------------------------------ looking

    /**
     * What the migration of this project would do.
     *
     * @throws OdkUpdate.Refused when the project cannot be carried over as it is, with the
     *                           reason for a person to read; nothing has been written
     */
    public static Plan plan(File projectRoot) {
        File root = projectRoot.getAbsoluteFile();
        File ontology = new File(root, ONTOLOGY);
        File config = OdkBuildSettings.yamlIn(ontology);
        if (config == null) {
            throw new OdkUpdate.Refused("This project has not exactly one ODK configuration: "
                    + ONTOLOGY + " holds no single file whose name ends in -odk.yaml. The "
                    + "update goes by that file, so there is nothing to go by.");
        }
        String old = text(config);
        // A file whose every line break is CRLF is edited as LF and turned back.
        boolean crlf = old.contains("\r\n") && !old.replace("\r\n", "").contains("\n");
        String text = crlf ? old.replace("\r\n", "\n") : old;

        List<String> notes = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();
        List<String> declared = new ArrayList<String>();
        List<String> questions = new ArrayList<String>();
        String id;
        String editPath;
        byte[] editBefore;
        Unstamped unstamped;
        try {
            id = valueOf(text, "id");
            if (id == null || id.trim().isEmpty()) {
                throw new OdkUpdate.Refused(config.getName() + " names no id, so the "
                        + "project's files cannot be found by it.");
            }
            File editFile = new File(ontology, id + "-edit.owl");
            if (!editFile.isFile()) {
                throw new OdkUpdate.Refused("The edit file " + ONTOLOGY + "/" + id
                        + "-edit.owl is not there. The update brings its imports in line "
                        + "with the configuration, so it has to be read first.");
            }
            editPath = ONTOLOGY + "/" + editFile.getName();
            editBefore = Files.readAllBytes(editFile.toPath());
            unstamped = unstamped(editFile, editBefore);
            if (!unstamped.stamps.isEmpty()) {
                notes.add("Taken out of the edit file: " + String.join(", ", unstamped.stamps)
                        + " - " + (unstamped.stamps.equals(Collections.singletonList(
                                "owl:versionInfo \"0.1.0\"")) ? "OntoBoard 1.135 wrote it when "
                                        + "it made the project; " : "")
                        + "ODK's release writes each release's own version, beside any the "
                        + "edit file holds, so every release would say two.");
            }
            String title = valueOf(text, "title");
            text = withHeader(text, title == null ? id : title);
            text = withAddress(text, id, ImportGuard.iriOf(editFile), notes, warnings);
            text = withoutLine(text, "robot_version");
            text = withRepo(text, id);
            text = withProfile(text);
            text = withTheReportsScope(text, new File(ontology, "Makefile"));
            text = withModules(root, text, ImportGuard.importsOf(editFile), declared, notes);
        } catch (OdkYaml.UnreadableException unreadable) {
            throw new OdkUpdate.Refused(config.getName() + " cannot be changed safely: "
                    + unreadable.getMessage());
        } catch (IOException unreadable) {
            throw new OdkUpdate.Refused(unreadable.getMessage());
        }

        // The questions stay as they are; the rule that asks them goes into the project's
        // own makefile, where it is not there as written.
        for (File question : OdkQuestions.filesIn(root)) {
            questions.add("src/sparql/" + question.getName());
            if (!question.getName().matches("[A-Za-z0-9_.+@,=-]+")) {
                warnings.add("src/sparql/" + question.getName() + " has a name make cannot "
                        + "read as one word (a blank, or a sign make or the shell reads as "
                        + "something else). ODK's Makefile is run by make, and the rule that "
                        + "asks the project's questions names every .rq file: rename this one "
                        + "- letters, digits, - _ and . - or `test` stops on it.");
            }
        }
        File own = new File(ontology, id + ".Makefile");
        String ownBefore = own.isFile() ? text(own) : null;
        String ownAfter = questions.isEmpty() ? null : OdkQuestions.with(ownBefore);
        if (ownAfter != null && ownAfter.equals(ownBefore)) {
            ownAfter = null;
        }

        String now = crlf ? text.replace("\n", "\r\n") : text;
        return new Plan(ONTOLOGY + "/" + config.getName(), old, now, questions,
                ONTOLOGY + "/" + own.getName(), ownBefore, ownAfter, declared, notes, warnings,
                editPath, editBefore, unstamped, OdkUpdate.plan(root, now));
    }

    // ------------------------------------------------------------------ the edit file's stamp

    /**
     * Whether an annotation of an ontology is its {@code owl:versionInfo}: what a release
     * writes for itself - ODK's adds one with ROBOT's annotate, beside any there is.
     */
    static boolean isVersionStamp(OWLAnnotation annotation) {
        return OWLRDFVocabulary.OWL_VERSION_INFO.getIRI().equals(annotation.getProperty()
                .getIRI());
    }

    /** An ontology's own version stamps as a person reads them, sorted: {@code owl:versionInfo "0.1.0"}. */
    static List<String> stampsOf(OWLOntology ontology) {
        List<String> said = new ArrayList<String>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (isVersionStamp(annotation)) {
                said.add("owl:versionInfo " + (annotation.getValue().asLiteral().isPresent()
                        ? "\"" + annotation.getValue().asLiteral().get().getLiteral() + "\""
                        : annotation.getValue().toString()));
            }
        }
        Collections.sort(said);
        return said;
    }

    /** How many lines that name it a stamp's line is looked for in, before the OWL API writes. */
    private static final int LINES_TRIED = 3;

    /**
     * The edit file without the ontology's own {@code owl:versionInfo}. Where a stamp has a
     * line of its own, that line is taken out and the rest of the file stays byte for byte -
     * the line is the stamp's when the file without it reads as exactly the ontology without
     * the stamp. Otherwise the OWL API writes the ontology without the stamps, in the syntax
     * it was read in.
     */
    private static Unstamped unstamped(File editFile, byte[] bytes) throws IOException {
        OWLOntology ontology = ImportGuard.alone(bytes, editFile);
        List<OWLAnnotation> stamps = new ArrayList<OWLAnnotation>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (isVersionStamp(annotation)) {
                stamps.add(annotation);
            }
        }
        if (stamps.isEmpty()) {
            return new Unstamped(Collections.<String>emptyList(), null);
        }
        List<String> said = stampsOf(ontology);
        byte[] without = linesTakenOut(editFile, bytes, ontology, stamps);
        return new Unstamped(said, without != null ? without : savedWithout(ontology, stamps));
    }

    /**
     * The file without each stamp's line; null where a stamp has no line of its own among the
     * first few that name {@code versionInfo}.
     */
    private static byte[] linesTakenOut(File editFile, byte[] bytes, OWLOntology ontology,
            List<OWLAnnotation> stamps) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        Set<OWLAnnotation> left = new HashSet<OWLAnnotation>(ontology.getAnnotations());
        for (OWLAnnotation stamp : stamps) {
            left.remove(stamp);
            String without = null;
            int from = 0;
            for (int tried = 0; without == null && tried < LINES_TRIED; tried++) {
                int at = text.indexOf("versionInfo", from);
                if (at < 0) {
                    break;
                }
                int start = text.lastIndexOf('\n', at) + 1;
                int end = text.indexOf('\n', at);
                end = end < 0 ? text.length() : end + 1;
                String candidate = text.substring(0, start) + text.substring(end);
                if (readsAs(candidate, editFile, ontology, left)) {
                    without = candidate;
                }
                from = end;
            }
            if (without == null) {
                return null;
            }
            text = without;
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Whether a text reads as the ontology it was taken from with these annotations, and
     * nothing else changed: its address and version, imports and axioms.
     */
    private static boolean readsAs(String text, File editFile, OWLOntology was,
            Set<OWLAnnotation> annotations) {
        OWLOntology now;
        try {
            now = ImportGuard.alone(text.getBytes(StandardCharsets.UTF_8), editFile);
        } catch (IOException unreadable) {
            return false;
        }
        return now.getOntologyID().getOntologyIRI().equals(was.getOntologyID().getOntologyIRI())
                && now.getOntologyID().getVersionIRI().equals(
                        was.getOntologyID().getVersionIRI())
                && now.getImportsDeclarations().equals(was.getImportsDeclarations())
                && now.getAnnotations().equals(annotations)
                && now.getAxioms().equals(was.getAxioms());
    }

    /** The ontology without the stamps, written by the OWL API in the syntax it was read in. */
    private static byte[] savedWithout(OWLOntology ontology, List<OWLAnnotation> stamps)
            throws IOException {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDocumentFormat format = manager.getOntologyFormat(ontology);
        for (OWLAnnotation stamp : stamps) {
            manager.applyChange(new RemoveOntologyAnnotation(ontology, stamp));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            manager.saveOntology(ontology, format != null ? format
                    : new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(), out);
        } catch (OWLOntologyStorageException notWritten) {
            throw new IOException("The edit file could not be written without its "
                    + "owl:versionInfo: " + notWritten.getMessage(), notWritten);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ the configuration

    /** OntoBoard's header for a configuration ODK reads, in place of the older layout's. */
    private static String withHeader(String text, String title) {
        if (OdkConfigWriter.isOntoBoards(text)) {
            return text;
        }
        List<String> lines = new ArrayList<String>(Arrays.asList(text.split("\n", -1)));
        int from = 0;
        if (!lines.isEmpty() && lines.get(0).startsWith("# ODK configuration for ")) {
            from = 1;
        }
        while (from < lines.size() && isOfTheOldHeader(lines.get(from))) {
            from++;
        }
        return OdkConfigWriter.header(title)
                + String.join("\n", lines.subList(from, lines.size()));
    }

    private static boolean isOfTheOldHeader(String line) {
        String trimmed = line.trim();
        return OLD_HEADER.contains(trimmed)
                || trimmed.matches("# rules in \\S+\\.Makefile, which the build includes");
    }

    /**
     * ODK's {@code uribase} - and {@code uribase_suffix}, where the ontology's address does
     * not end in its id - so that what ODK names under them is where the project's modules
     * and releases already are: the address without {@code .owl}.
     */
    private static String withAddress(String text, String id, String iri, List<String> notes,
            List<String> warnings) {
        String stem = ProjectIri.stemOf(iri);
        if (stem == null) {
            notes.add("The edit file's ontology has no address, so uribase is left as the "
                    + "configuration has it.");
            return text;
        }
        int cut = stem.lastIndexOf('/');
        boolean bare = cut < 0 || stem.startsWith("://", cut - 2);
        String uribase = bare ? stem : stem.substring(0, cut);
        String last = bare ? null : stem.substring(cut + 1);
        String now = valueOf(text, "uribase");
        String changed = text;
        if (now == null) {
            changed = withLineAfter(changed, new String[] {"description", "title", "id"},
                    "uribase: " + OdkConfigWriter.plain(uribase));
        } else if (!now.equals(uribase)) {
            changed = OdkYaml.withValue(changed, "uribase", uribase);
        }
        if (last != null && !last.equals(id)) {
            String suffix = valueOf(changed, "uribase_suffix");
            if (suffix == null) {
                changed = withLineAfter(changed, new String[] {"uribase"},
                        "uribase_suffix: " + OdkConfigWriter.plain(last));
            } else if (!suffix.equals(last)) {
                changed = OdkYaml.withValue(changed, "uribase_suffix", last);
            }
        }
        String odks = uribase + "/" + id + ".owl";
        if (!odks.equals(iri)) {
            // Not a remark: what a release is called is what others import.
            warnings.add("Your ontology keeps its address, " + iri + ", in the edit file. But "
                    + "ODK names the file it releases after the id: " + odks + " - the "
                    + "address up to its last part, then <id>.owl. A release made from here "
                    + "on carries that name, where the releases so far carry the ontology's "
                    + "own, unless " + id + ".Makefile in " + ONTOLOGY + " says otherwise. "
                    + "Look at the first release's file before you publish it.");
        }
        return changed;
    }

    /** {@code repo}, as a new project has it: ODK's own default is the word "noname". */
    private static String withRepo(String text, String id) {
        if (entryAt(text, "repo") != null) {
            return text;
        }
        return withLineAfter(text, new String[] {"license", "uribase_suffix", "uribase", "id"},
                "repo: " + OdkConfigWriter.plain(id));
    }

    /**
     * The report with the project's own profile: {@code custom_profile: TRUE} makes ODK read
     * {@code profile.txt} beside the edit file, where the older layout kept it - with
     * whatever a person relaxed in it.
     */
    private static String withProfile(String text) {
        OdkYaml.Entry report = entryAt(text, "robot_report");
        if (report == null) {
            // What a new project has, for a project somebody took the block out of.
            return text + (text.isEmpty() || text.endsWith("\n") ? "" : "\n")
                    + "robot_report:\n  use_labels: TRUE\n  fail_on: ERROR\n"
                    + "  custom_profile: TRUE\n  report_on:\n    - edit\n";
        }
        if (report.getEditable() != OdkYaml.Editable.MAPPING) {
            throw new OdkYaml.UnreadableException("robot_report in this file is not a block "
                    + "of settings, so the project's report profile cannot be named in it.");
        }
        OdkYaml.Entry profile = entryAt(text, "robot_report.custom_profile");
        if (profile != null) {
            return "true".equalsIgnoreCase(profile.getValue().trim()) ? text
                    : OdkYaml.withValue(text, "robot_report.custom_profile", "TRUE");
        }
        String[] lines = text.split("\n", -1);
        OdkYaml.Entry first = null;
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
            if (entry.getPath().startsWith("robot_report.") && entry.getDepth() == 1) {
                first = entry;
                break;
            }
        }
        String indent = first == null ? "  " : lines[first.getLine() - 1].substring(0,
                lines[first.getLine() - 1].length()
                        - lines[first.getLine() - 1].replaceAll("^\\s+", "").length());
        OdkYaml.Entry after = entryAt(text, "robot_report.fail_on");
        if (after == null || !after.getEditable().isEditable()) {
            after = entryAt(text, "robot_report.use_labels");
        }
        int at = after != null && after.getEditable().isEditable() ? after.getLine()
                : report.getLine();
        return withLineAt(text, at, indent + "custom_profile: TRUE");
    }

    /**
     * The report's scope, as the older layout had it: its Makefile gave {@code robot
     * report} the project's two base addresses ({@code --base-iri <uribase>/<ID>_} and
     * {@code <uribase>/<id>}), so that it reports on the project's own terms. ODK's
     * Makefile gives it the same two where the configuration says {@code use_base_iris}
     * - and without it reports on every term the edit file holds, those of other
     * ontologies included. What the configuration says of it already is left.
     */
    private static String withTheReportsScope(String text, File oldMakefile) {
        if (entryAt(text, "robot_report.use_base_iris") != null
                || !oldMakefile.isFile() || !text(oldMakefile).contains("--base-iri ")) {
            return text;
        }
        OdkYaml.Entry profile = entryAt(text, "robot_report.custom_profile");
        String[] lines = text.split("\n", -1);
        String line = lines[profile.getLine() - 1];
        return withLineAt(text, profile.getLine(), line.substring(0, line.length()
                - line.replaceAll("^\\s+", "").length()) + "use_base_iris: TRUE");
    }

    // ------------------------------------------------------------------ the modules

    /**
     * A product for every module the edit file imports and the configuration does not
     * declare: ODK's update gives the edit file the imports of the configuration and no
     * others. Which module an import is, is read from the catalog - the file it is served
     * from - and not from its address.
     */
    private static String withModules(File root, String text, List<String> imports,
            List<String> declared, List<String> notes) throws IOException {
        File catalogFile = new File(new File(root, ONTOLOGY), "catalog-v001.xml");
        String catalog = catalogFile.isFile() ? text(catalogFile) : null;
        File folder = new File(root, ImportModules.IMPORTS_DIRECTORY).getAbsoluteFile();
        TreeSet<String> names = new TreeSet<String>();
        for (String iri : imports) {
            String path = catalog == null ? null : Catalog.entryFor(catalog, iri);
            File served = path == null || path.contains(":") ? null
                    : new File(catalogFile.getParentFile(), path).getAbsoluteFile();
            if (served == null) {
                // No catalog entry: the module of that name, where the address ends in one.
                String last = iri.substring(iri.lastIndexOf('/') + 1);
                served = iri.endsWith("/imports/" + last) ? new File(folder, last) : null;
            }
            if (served != null && served.isFile() && folder.equals(served.getParentFile())
                    && served.getName().endsWith("_import.owl")) {
                names.add(served.getName().substring(0,
                        served.getName().length() - "_import.owl".length()));
            }
        }
        String changed = text;
        for (String name : names) {
            if (ImportProducts.named(ImportProducts.declaredIn(changed), name) != null) {
                continue;
            }
            File terms = ImportModules.termsFileFor(root, name);
            String pattern = terms.isFile() ? ImportModules.patternIn(terms) : null;
            String source = terms.isFile() ? ImportModules.sourceIn(terms) : null;
            LinkedHashMap<String, String> fields;
            if (pattern != null) {
                fields = ImportProducts.frozen(name);
                declared.add(name + ": declared as kept as it is. It is cut from the pattern "
                        + pattern + " of OntoBoard's library, which nothing refreshes; "
                        + "importing the pattern again is what changes it.");
            } else if (source != null && !source.trim().isEmpty()) {
                ImportProducts.Declaration declaration = ImportProducts.declarationForImport(
                        name, source.trim(), TermExtract.Method.BOT);
                fields = declaration.getFields();
                declared.add(declaration.getCaveat() != null ? declaration.getCaveat()
                        : name + ": declared for ODK to build, from " + source.trim()
                                + " with BOT - how the module was cut is not recorded, and "
                                + "BOT is what OntoBoard and ODK use unless told otherwise.");
            } else {
                fields = ImportProducts.frozen(name);
                declared.add(name + ": declared as kept as it is - no source is recorded for "
                        + "it, so there is nothing for ODK to build it from again.");
            }
            changed = ImportProducts.withProduct(changed, fields);
        }
        for (ImportProducts.Product product : ImportProducts.declaredIn(changed)) {
            String from = product.getMirrorFrom();
            if (from != null && from.trim().toLowerCase(java.util.Locale.ROOT)
                    .startsWith("file:")) {
                notes.add(product.getId() + " is declared with a file of this machine as its "
                        + "source (" + from.trim() + "): ODK's refresh fetches it from there, "
                        + "which works on this machine only.");
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes the configuration, the rule for the questions into the project's own makefile,
     * and the edit file without the stamp 1.135 put in it; ODK's update is the caller's next
     * step.
     *
     * @return the files written, the configuration first and the edit file last; empty when
     *         there was nothing to convert
     * @throws IOException when a file cannot be written, or is no longer what the plan was
     *                     made from
     */
    public static List<File> convert(File projectRoot, Plan plan) throws IOException {
        File root = projectRoot.getAbsoluteFile();
        List<File> written = new ArrayList<File>();
        File own = fileIn(root, plan.ownMakefilePath);
        // Looked at before anything is written: what was looked at is written, or nothing.
        if (plan.addsTheQuestionRule()) {
            String now = own.isFile() ? new String(Files.readAllBytes(own.toPath()),
                    StandardCharsets.UTF_8) : null;
            if (now == null ? plan.oldOwnMakefile != null : !now.equals(plan.oldOwnMakefile)) {
                throw new IOException(own.getName() + " changed after it was looked at, so "
                        + "nothing was written. Run the update again.");
            }
        }
        File edit = plan.unstamped.bytes == null ? null : fileIn(root, plan.editFilePath);
        if (edit != null && !Arrays.equals(edit.isFile() ? Files.readAllBytes(edit.toPath())
                : null, plan.oldEdit)) {
            // Somebody's work, saved from Protege since the look, say.
            throw new IOException(edit.getName() + " changed after it was looked at, so "
                    + "nothing was written. Run the update again.");
        }
        if (plan.changesTheConfig()) {
            File config = fileIn(root, plan.configPath);
            OdkBuildSettings.writeIfUnchanged(config, plan.oldConfig, plan.newConfig);
            written.add(config);
        }
        if (plan.addsTheQuestionRule()) {
            Files.write(own.toPath(), plan.newOwnMakefile.getBytes(StandardCharsets.UTF_8));
            written.add(own);
        }
        if (edit != null) {
            // In one step, as ODK's update replaces it: the old file or the new one, never
            // half of one.
            File staged = new File(edit.getParentFile(), "." + edit.getName()
                    + ".ontoboard-unstamped");
            try {
                Files.write(staged.toPath(), plan.unstamped.bytes);
                OdkUpdate.replace(edit, staged);
            } finally {
                Files.deleteIfExists(staged.toPath());
            }
            written.add(edit);
        }
        return written;
    }

    // ------------------------------------------------------------------ text

    private static File fileIn(File root, String path) {
        return new File(root, path.replace('/', File.separatorChar)).getAbsoluteFile();
    }

    private static String text(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new OdkUpdate.Refused(file.getName() + " could not be read: "
                    + unreadable.getMessage());
        }
    }

    private static OdkYaml.Entry entryAt(String text, String path) {
        for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
            if (entry.getPath().equals(path)) {
                return entry;
            }
        }
        return null;
    }

    /** A top-level scalar's value; null when the key is not there or holds no scalar. */
    private static String valueOf(String text, String key) {
        OdkYaml.Entry entry = entryAt(text, key);
        return entry == null || !entry.getEditable().isEditable() ? null : entry.getValue();
    }

    /** The text without the line of a top-level scalar; as it is when the key is not there. */
    private static String withoutLine(String text, String key) {
        OdkYaml.Entry entry = entryAt(text, key);
        if (entry == null || !entry.getEditable().isEditable()) {
            return text;
        }
        List<String> lines = new ArrayList<String>(Arrays.asList(text.split("\n", -1)));
        lines.remove(entry.getLine() - 1);
        return String.join("\n", lines);
    }

    /** A line of its own after the line of the first of these top-level keys that is there. */
    private static String withLineAfter(String text, String[] keys, String line) {
        for (String key : keys) {
            OdkYaml.Entry entry = entryAt(text, key);
            if (entry != null && entry.getEditable().isEditable()) {
                return withLineAt(text, entry.getLine(), line);
            }
        }
        return line + "\n" + text;
    }

    /** A line put in after the {@code after}-th line (1-based) of the text. */
    private static String withLineAt(String text, int after, String line) {
        List<String> lines = new ArrayList<String>(Arrays.asList(text.split("\n", -1)));
        lines.add(Math.min(after, lines.size()), line);
        return String.join("\n", lines);
    }
}
