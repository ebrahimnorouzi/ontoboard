package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.WebOntology;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * An ontology - one file in any format OWL reads, or a web address, with its earlier versions -
 * turned into an ODK repository: ODK's own seed with the ontology in it, every earlier version
 * as a release and a git tag, its imports as modules ODK's configuration declares, ODK's test
 * run on it, and the current version released as ODK releases one.
 *
 * <p>Nothing here is a project of OntoBoard's own making: the files are ODK's
 * ({@link OdkCreate}), the imports are declared as Update project files declares them
 * ({@link ImportGuard}, {@link OdkUpdate}), the test and the release are targets of ODK's
 * Makefile ({@link OdkRelease}) - run in ODK's container, or by OntoBoard itself where there
 * is none.
 *
 * <p>Asked for: somebody with an ontology and its history who wants an ODK-based repository to
 * publish on GitHub, "with all the previous versions available, imported terms and everything
 * converted", every step visible. So the work is {@link #STEPS} in order, each reported through a
 * {@link Log} as it happens; a step that cannot do something says so and the conversion goes on
 * where it can. Converting the same inputs twice gives the same files and the same git trees.
 *
 * <p>No Swing and no Protégé types: the dialog and the tests drive the same code. Git is the git
 * command, used when it is installed; without it the versions are still written under
 * {@code releases/}, and the log says what was not recorded. A version is released only when
 * ODK's test passes, since ODK's release runs it; the project is made either way.
 */
public final class OdkConversion {

    /** The steps, in order, as the dialog lists them before anything runs. */
    public static final List<String> STEPS = Collections.unmodifiableList(Arrays.asList(
            "Read the ontology",
            "Read the earlier versions",
            "Make the project",
            "Record the earlier versions",
            "Turn imports into modules",
            "Build and test",
            "Record the project in git",
            "Release the current version",
            "Ready to publish"));

    private OdkConversion() {
    }

    /** What happens, as it happens. Called from the converting thread. */
    public interface Log {
        /** Step {@code index} (into {@link #STEPS}) starts. */
        void step(int index);

        /** A line of what it did. */
        void line(String text);

        /** Something it could not do, or that needs looking at; the conversion goes on. */
        void warn(String text);

        /** Step {@code index} is finished; {@code ok} false when it had a warning or failed. */
        void done(int index, boolean ok);
    }

    /** What to convert, and where to. */
    public static final class Request {
        String current;
        final List<String> earlier = new ArrayList<String>();
        File parent;
        String id;
        String title;
        boolean modules = true;
        boolean git = true;
        ProcessRunner.Runner runner = ProcessRunner.real();
        java.util.function.Function<File, OdkRelease.Odk> odk;

        /**
         * How commands are run: the container program's, when ODK seeds the project in its
         * container, and git's for the release. This machine's own unless said otherwise.
         */
        public Request runner(ProcessRunner.Runner runs) {
            this.runner = runs == null ? ProcessRunner.real() : runs;
            return this;
        }

        /**
         * How ODK's targets are run on the new project, given its edit file: in ODK's
         * container ({@link OdkRelease#inContainer}), or - when nothing is said - by
         * OntoBoard itself.
         */
        public Request odk(java.util.function.Function<File, OdkRelease.Odk> runsOdksTargets) {
            this.odk = runsOdksTargets;
            return this;
        }

        public Request current(String source) {
            this.current = source;
            return this;
        }

        /** An earlier version, any order: they are sorted by version. */
        public Request earlier(String source) {
            this.earlier.add(source);
            return this;
        }

        public Request into(File directory) {
            this.parent = directory;
            return this;
        }

        /** The project id; null for one made from the ontology's IRI. */
        public Request id(String projectId) {
            this.id = projectId;
            return this;
        }

        public Request title(String projectTitle) {
            this.title = projectTitle;
            return this;
        }

        public Request modules(boolean turnImportsIntoModules) {
            this.modules = turnImportsIntoModules;
            return this;
        }

        public Request git(boolean recordInGit) {
            this.git = recordInGit;
            return this;
        }
    }

    /** What came out. */
    public static final class Result {
        File root;
        final List<String> versions = new ArrayList<String>();
        final List<String> tags = new ArrayList<String>();
        final List<String> modules = new ArrayList<String>();
        boolean buildPassed;
        final List<String> warnings = new ArrayList<String>();
        String publish = "";

        public File getRoot() {
            return root;
        }

        /**
         * Every version in the repository, oldest first; the current one last, when it was
         * released.
         */
        public List<String> getVersions() {
            return versions;
        }

        public List<String> getTags() {
            return tags;
        }

        public List<String> getModules() {
            return modules;
        }

        /** Whether ODK's test passed on the project. */
        public boolean isBuildPassed() {
            return buildPassed;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        /** What to type, or do, to put it on GitHub. */
        public String getPublish() {
            return publish;
        }
    }

    /** A version read: where from, which version, and the ontology in a manager of its own. */
    static final class Read {
        final String source;
        final OWLOntology ontology;
        final OntologyVersion version;

        Read(String source, OWLOntology ontology, OntologyVersion version) {
            this.source = source;
            this.ontology = ontology;
            this.version = version;
        }
    }

    /**
     * Converts. Throws only when nothing useful can be made - the ontology cannot be read, or the
     * folder is taken; everything else is a warning in the log and in the result.
     */
    public static Result run(Request request, Log log, WebOntology.Attempt attempt) {
        Result result = new Result();
        WarnCounting warn = new WarnCounting(log, result);

        // 1. The ontology.
        log.step(0);
        Read current = read(request.current, true, warn, attempt);
        if (current == null) {
            throw new IllegalArgumentException("The ontology could not be read, so there is "
                    + "nothing to convert.");
        }
        log.done(0, warn.clean());
        stopIfAsked(attempt, 0, result);

        // 2. Its earlier versions, oldest first.
        log.step(1);
        List<Read> earlier = new ArrayList<Read>();
        for (String source : request.earlier) {
            Read version = read(source, false, warn, attempt);
            if (version == null) {
                continue;
            }
            if (version.version == null) {
                warn.warn(source + ": which version this is could not be told - from its "
                        + "owl:versionInfo, its version IRI or its address - so it is left out.");
                continue;
            }
            if (current.version != null && version.version.compareTo(current.version) >= 0) {
                warn.warn(source + " is version " + version.version.getText() + ", not earlier "
                        + "than the ontology's own " + current.version.getText() + ", so it is "
                        + "left out.");
                continue;
            }
            earlier.add(version);
        }
        Collections.sort(earlier, (a, b) -> a.version.compareTo(b.version));
        List<Read> distinct = new ArrayList<Read>();
        for (Read version : earlier) {
            if (!distinct.isEmpty() && distinct.get(distinct.size() - 1).version.getText()
                    .equals(version.version.getText())) {
                warn.warn(version.source + " is version " + version.version.getText()
                        + " a second time; the first one given is kept.");
                continue;
            }
            distinct.add(version);
        }
        log.line(distinct.isEmpty() ? "No earlier versions."
                : distinct.size() + " earlier version" + (distinct.size() == 1 ? "" : "s") + ": "
                        + describe(distinct));
        log.done(1, warn.clean());
        stopIfAsked(attempt, 1, result);

        // 3. The project: ODK's own seed, with the ontology where the seed's edit file is.
        log.step(2);
        IRI ontologyIri = current.ontology.getOntologyID().getOntologyIRI().isPresent()
                ? current.ontology.getOntologyID().getOntologyIRI().get() : null;
        if (ontologyIri == null) {
            throw new IllegalArgumentException("The ontology has no IRI. An ODK project is named "
                    + "after it and every release is published under it: give the ontology an "
                    + "IRI (Active ontology tab, Ontology IRI) and convert it again.");
        }
        String id = request.id != null && !request.id.trim().isEmpty() ? request.id.trim()
                : idFor(current.ontology, ontologyIri, request.current);
        String title = request.title != null && !request.title.trim().isEmpty()
                ? request.title.trim() : titleOf(current.ontology, id);
        OdkProjectConfig config = new OdkProjectConfig(id, title, descriptionOf(current.ontology),
                ontologyIri.toString(), licenceOf(current.ontology), request.parent).adopting();
        File root = config.getProjectRoot().getAbsoluteFile();
        if (root.exists()) {
            throw new IllegalArgumentException(root.getAbsolutePath() + " is there already. "
                    + "Choose another folder, or another project id.");
        }
        final WebOntology.Attempt asked = attempt;
        BooleanSupplier stopped = new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
                return asked != null && asked.isStopped();
            }
        };
        OWLOntology edit = copyOf(current.ontology, true);
        OdkCreate.Created created = seeded(config, edit, request.runner, stopped);
        result.root = root;
        for (File written : created.written()) {
            log.line("wrote " + under(root, written));
        }
        log.line(created.route() == OdkCreate.Route.CONTAINER
                ? "ODK " + OdkPin.VERSION + " wrote the project, in its container."
                : "It is the project ODK " + OdkPin.VERSION + " writes, made from ODK's "
                        + "templates inside OntoBoard.");
        for (String note : created.notes()) {
            warn.warn(note);
        }
        File editFile = new File(root, "src/ontology/" + id + "-edit.owl");
        log.done(2, warn.clean());
        stopIfAsked(attempt, 2, result);

        // 4. The earlier versions, each a release as an ODK repository keeps one: the file
        // released at the top of the project and under releases/, a commit and a tag.
        log.step(3);
        boolean git = request.git && gitIsInstalled();
        if (request.git && !git) {
            warn.warn("git is not installed, so the versions are written under releases/ but not "
                    + "recorded as commits and tags. Install git and run git init in the project "
                    + "to start a history from here.");
        }
        if (git) {
            git(root, warn, null, "init", "-q");
            git(root, warn, null, "symbolic-ref", "HEAD", "refs/heads/main");
            log.line(knowsWho(root) ? "git: a new repository; the commits are signed with the name "
                    + "and e-mail git knows you by"
                    : "git: a new repository; git has no name and e-mail for you, so the commits "
                            + "are signed OntoBoard");
        }
        File top = new File(root, id + ".owl");
        for (Read version : distinct) {
            String label = version.version.getText();
            try {
                saveEdit(copyOf(version.ontology, true), editFile);
                OWLOntology published = copyOf(version.ontology, false);
                save(published, new File(root, "releases/" + label + "/" + id + ".owl"));
                save(published, top);
                log.line(label + ": the edit file, " + id + ".owl and releases/" + label + "/"
                        + id + ".owl, as published (" + version.source + ")");
                result.versions.add(label);
                if (git) {
                    commitAndTag(root, id, label, "release(" + label + "): " + title + " "
                            + label + ", as published - converted by OntoBoard",
                            dateOf(version), warn, result, log);
                }
            } catch (Exception cannot) {
                warn.warn(label + " could not be written: " + cannot.getMessage());
            }
        }
        if (distinct.isEmpty()) {
            log.line("Nothing to record: no earlier versions were given.");
        }
        log.done(3, warn.clean());
        stopIfAsked(attempt, 3, result);

        // 5. Imports, as modules ODK's configuration declares.
        log.step(4);
        metadata(edit, title, config, log);
        try {
            saveEdit(edit, editFile);
            log.line("wrote src/ontology/" + editFile.getName());
        } catch (IOException cannot) {
            throw new IllegalStateException("Could not write the edit file: "
                    + cannot.getMessage(), cannot);
        }
        if (current.ontology.getImportsDeclarations().isEmpty()) {
            log.line("The ontology imports nothing.");
            try {
                odksUpdate(root, editFile, Collections.<String>emptyList(), log);
            } catch (IOException | RuntimeException cannot) {
                warn.warn("ODK's update could not be made (" + cannot.getMessage() + "). "
                        + "OntoBoard > Project > Update project files... makes it.");
            }
        } else if (request.modules) {
            modules(current.ontology, edit, root, editFile, warn, log, result);
        } else {
            log.line("Left as they are: the ontology imports what it imported. ODK's update "
                    + "takes out an import its configuration does not declare; "
                    + "OntoBoard > Project > Update project files... asks about each one "
                    + "before it does.");
        }
        log.done(4, warn.clean());
        stopIfAsked(attempt, 4, result);

        // 6. ODK's own test, on the modules as they are: its Makefile's test_fast.
        log.step(5);
        OdkRelease.Odk odk = null;
        try {
            // Who runs ODK's targets here is found out now: the project is there to ask about.
            odk = request.odk != null ? request.odk.apply(editFile)
                    : OdkRelease.byOntoBoardItself(editFile, stopped);
            List<String> test = Arrays.asList("make", "test_fast");
            log.line("$ " + odk.shown(test, false));
            ProcessRunner.Outcome tested = odk.run(test, false);
            for (String line : tested.getOutput()) {
                log.line(line);
            }
            result.buildPassed = tested.isSuccess();
            if (!tested.isSuccess()) {
                warn.warn("ODK's test did not pass: " + MakeRun.explain("test_fast", tested)
                        + " The project is made all the same; "
                        + "OntoBoard > Build and release > Build... runs the test again.");
            }
        } catch (IOException | RuntimeException cannot) {
            warn.warn("ODK's test could not be run: " + cannot.getMessage());
        }
        log.done(5, warn.clean());
        stopIfAsked(attempt, 5, result);

        // 7. The project as it is now, a commit of its own: ODK's release commits what git
        // knows of, and of the project itself git knows nothing yet.
        log.step(6);
        File readme = new File(root, "README.md");
        String readmeOfOdk = textOf(readme);
        if (git) {
            writeHistory(readme, readmeOfOdk, title, result.versions);
            if (git(root, warn, null, "add", "-A") == 0 && git(root, warn, null, "commit", "-q",
                    "-m", title + ": the ODK repository, converted by OntoBoard") == 0) {
                log.line("git: committed the project");
            }
        } else {
            log.line("Not recorded: no git.");
        }
        log.done(6, warn.clean());
        stopIfAsked(attempt, 6, result);

        // 8. The current version, released as Release... releases one: ODK's own
        // prepare_release on the modules as they are, its products at the top of the
        // project and under releases/, a commit and a tag.
        log.step(7);
        String label = current.version != null ? current.version.getText() : Provenance.today();
        if (current.version == null) {
            warn.warn("The ontology does not say which version it is, so it is released as "
                    + label + ", today's date.");
        }
        if (!result.buildPassed || odk == null) {
            warn.warn("Version " + label + " is not released: ODK's release runs its test "
                    + "first, and the test did not pass here. Once it does, "
                    + "OntoBoard > Build and release > Release... makes the release.");
        } else {
            ProcessRunner.Runner recording = signing(request.runner, root);
            if (git) {
                List<String> withThisOne = new ArrayList<String>(result.versions);
                withThisOne.add(label);
                writeHistory(readme, readmeOfOdk, title, withThisOne);
            }
            OdkRelease.Outcome released = OdkRelease.run(new OdkRelease.Request(editFile, label,
                    OdkRelease.proposedShape(editFile, recording), false, false), odk, recording,
                    git ? new OdkRelease.Asks() {
                        @Override
                        public boolean commit(ReleaseRun.Staging staging) {
                            return true;
                        }
                    } : null);
            for (String line : released.getTranscript()) {
                log.line(line);
            }
            for (String warning : released.getWarnings()) {
                // Without git there is no repository, and that was chosen, or said already.
                if (git || !warning.contains("is not a git repository")) {
                    warn.warn(warning);
                }
            }
            if (released.isOk()) {
                if (!OdkConfigWriter.odkIri(config).equals(config.getBaseIri())) {
                    warn.warn("The release names the ontology " + OdkConfigWriter.odkIri(config)
                            + ", as ODK names what it releases; the edit file says "
                            + config.getBaseIri() + ". Look at " + id + ".owl before you "
                            + "publish " + (released.isCommitted() ? "the tag "
                                    + released.getTag() : "it") + ".");
                }
                result.versions.add(label);
                if (released.isCommitted()) {
                    result.tags.add(released.getTag());
                }
                log.line(git ? released.getSummary() : "Released " + id + " " + label + ": "
                        + "ODK's products at the top of the project, a copy under releases/"
                        + label + ".");
            } else {
                if (git) {
                    writeHistory(readme, readmeOfOdk, title, result.versions);
                }
                warn.warn("Version " + label + " could not be released: " + released.getFailure());
            }
        }
        log.done(7, warn.clean());
        stopIfAsked(attempt, 7, result);

        // 9. What publishing takes.
        log.step(8);
        result.publish = publishing(root, id, git);
        for (String line : result.publish.split("\n")) {
            log.line(line);
        }
        log.done(8, true);
        return result;
    }

    /** A file of the project as a path from its top. */
    private static String under(File root, File file) {
        return root.getAbsoluteFile().toPath().relativize(file.getAbsoluteFile().toPath())
                .toString();
    }

    /**
     * ODK's seed for the project, with the ontology as its edit file: what New ODK project
     * makes for somebody who starts from an ontology ({@link OdkCreate}) - in ODK's container
     * where one runs and the image is there, else from ODK's templates inside OntoBoard. Git
     * is the conversion's own to start: the first commit is the oldest version, not ODK's
     * "initial commit".
     */
    private static OdkCreate.Created seeded(OdkProjectConfig config, OWLOntology edit,
            ProcessRunner.Runner runner, BooleanSupplier stopped) {
        File adopted = null;
        try {
            adopted = File.createTempFile("ontoboard-convert-", ".ofn");
            saveEdit(edit, adopted);
            return OdkCreate.create(config, adopted, runner, false, stopped);
        } catch (IOException cannot) {
            throw new IllegalStateException("The ontology could not be written for the project: "
                    + cannot.getMessage(), cannot);
        } finally {
            if (adopted != null) {
                adopted.delete();
            }
        }
    }

    /**
     * git as the release runs it, signed OntoBoard where git knows nobody - a git that knows
     * nobody refuses to commit, and the versions before were signed the same way.
     */
    private static ProcessRunner.Runner signing(final ProcessRunner.Runner runner, File root) {
        if (knowsWho(root)) {
            return runner;
        }
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (command.size() > 1 && command.get(0).equals("git")
                        && (command.get(1).equals("commit") || command.get(1).equals("tag"))) {
                    List<String> signed = new ArrayList<String>(Arrays.asList("git", "-c",
                            "user.name=OntoBoard", "-c", "user.email=ontoboard@localhost"));
                    signed.addAll(command.subList(1, command.size()));
                    return runner.run(workingDirectory, signed, timeoutMinutes, sink);
                }
                return runner.run(workingDirectory, command, timeoutMinutes, sink);
            }
        };
    }

    private static void stopIfAsked(WebOntology.Attempt attempt, int step, Result result) {
        if (attempt != null && attempt.isStopped()) {
            throw new IllegalStateException("Stopped after step " + (step + 1) + ", "
                    + STEPS.get(step).toLowerCase(Locale.ROOT) + "."
                    + (result.root == null ? "" : " What is written so far stays in "
                            + result.root.getAbsolutePath() + "."));
        }
    }

    // ---------------------------------------------------------------- reading

    private static Read read(String source, boolean withImports, WarnCounting warn,
            WebOntology.Attempt attempt) {
        List<String> notes = new ArrayList<String>();
        OWLOntology ontology;
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            ontology = withImports
                    ? OntologySource.loadLeavingOutMissingImports(manager,
                            OntologySource.toIri(source), attempt, null, notes)
                    : loadAlone(manager, source, attempt, notes);
        } catch (RuntimeException cannot) {
            warn.warn(source + " could not be read: " + cannot.getMessage());
            return null;
        }
        for (String note : notes) {
            warn.log.line(note);
        }
        OntologyVersion version = versionOf(ontology, source);
        org.semanticweb.owlapi.model.OWLDocumentFormat format =
                ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        warn.log.line(source + ": " + (format == null ? "?" : format.getKey()) + ", "
                + (ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get() : "no IRI")
                + (version == null ? "" : ", version " + version.getText()) + " - "
                + ontology.getClassesInSignature(Imports.EXCLUDED).size() + " classes, "
                + ontology.getObjectPropertiesInSignature(Imports.EXCLUDED).size()
                + " object properties, "
                + ontology.getIndividualsInSignature(Imports.EXCLUDED).size() + " individuals, "
                + ontology.getAxiomCount() + " axioms"
                + (ontology.getImportsDeclarations().isEmpty() ? ""
                        : ", imports " + ontology.getImportsDeclarations().size()));
        if (withImports) {
            String missing = OntologySource.describeMissingImports(
                    OntologySource.missingImportsOf(ontology));
            if (missing != null) {
                warn.warn(missing + ".");
            }
        }
        return new Read(source, ontology, version);
    }

    /** An earlier version on its own: what it imported is history too, not something to fetch. */
    private static OWLOntology loadAlone(OWLOntologyManager manager, String source,
            WebOntology.Attempt attempt, List<String> notes) {
        final File nowhere = new File(System.getProperty("java.io.tmpdir"),
                ".ontoboard-earlier-version-import");
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(
                        org.semanticweb.owlapi.model.MissingImportHandlingStrategy.SILENT));
        IRI address = OntologySource.toIri(source);
        if (WebOntology.isWebAddress(address)) {
            return WebOntology.load(manager, address, attempt, null, notes);
        }
        manager.getIRIMappers().add(iri -> iri.equals(address) ? null : IRI.create(nowhere));
        try {
            // A file read through OntologySource.loadFile is let go of afterwards; handed to
            // loadOntology, an earlier version in any format but RDF/XML stayed open until the
            // garbage collector closed it, and Windows will not delete or replace an open file.
            File local = OntologySource.localFileFor(manager, address);
            return local != null ? OntologySource.loadFile(manager, local)
                    : manager.loadOntology(address);
        } catch (Exception cannot) {
            throw new IllegalStateException(cannot.getMessage(), cannot);
        }
    }

    private static final Pattern VERSION_IN_TEXT = Pattern.compile(
            "(?<![0-9.])v?(\\d{4}-\\d{2}-\\d{2}|\\d+\\.\\d+(?:\\.\\d+)?)(?![0-9])");

    /**
     * Which version an ontology is: its {@code owl:versionInfo}, else a version in its version IRI,
     * else one in the address it was read from - {@code .../v3.0.4/nfdicore.ttl}. Null when none
     * of them says.
     */
    static OntologyVersion versionOf(OWLOntology ontology, String source) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString()
                    .equals("http://www.w3.org/2002/07/owl#versionInfo")
                    && annotation.getValue().asLiteral().isPresent()) {
                OntologyVersion said = OntologyVersion.parseOrNull(
                        annotation.getValue().asLiteral().get().getLiteral());
                if (said != null) {
                    return said;
                }
            }
        }
        List<String> places = new ArrayList<String>();
        if (ontology.getOntologyID().getVersionIRI().isPresent()) {
            places.add(ontology.getOntologyID().getVersionIRI().get().toString());
        }
        places.add(source);
        for (String place : places) {
            Matcher found = VERSION_IN_TEXT.matcher(place);
            OntologyVersion last = null;
            while (found.find()) {
                OntologyVersion parsed = OntologyVersion.parseOrNull(found.group(1));
                last = parsed != null ? parsed : last;
            }
            if (last != null) {
                return last;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- writing

    /**
     * The ontology's own content in a manager of its own - axioms, annotations and imports - for
     * the edit file without its version IRI (a release adds that), or as published with it.
     */
    static OWLOntology copyOf(OWLOntology source, boolean asEditFile) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntologyID id = source.getOntologyID();
            OWLOntologyID kept = asEditFile && id.getOntologyIRI().isPresent()
                    ? new OWLOntologyID(id.getOntologyIRI(),
                            com.google.common.base.Optional.<IRI>absent())
                    : id;
            OWLOntology copy = manager.createOntology(kept);
            manager.addAxioms(copy, source.getAxioms());
            for (OWLAnnotation annotation : source.getAnnotations()) {
                manager.applyChange(new AddOntologyAnnotation(copy, annotation));
            }
            for (OWLImportsDeclaration declaration : source.getImportsDeclarations()) {
                manager.applyChange(new AddImport(copy, declaration));
            }
            org.semanticweb.owlapi.model.OWLDocumentFormat format =
                    source.getOWLOntologyManager().getOntologyFormat(source);
            if (format != null && format.isPrefixOWLOntologyFormat()) {
                RDFXMLDocumentFormat rdf = new RDFXMLDocumentFormat();
                copyPrefixes(format, rdf);
                manager.setOntologyFormat(copy, rdf);
            }
            return copy;
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException cannot) {
            throw new IllegalStateException(cannot.getMessage(), cannot);
        }
    }

    private static void save(OWLOntology ontology, File file) throws Exception {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        org.semanticweb.owlapi.model.OWLDocumentFormat format =
                ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        writeCanonically(ontology,
                format instanceof RDFXMLDocumentFormat ? format : new RDFXMLDocumentFormat(), file);
    }

    /** OWL's and RDF's own vocabularies: nobody's default prefix. */
    private static final Set<String> STANDARD_VOCABULARIES = new java.util.HashSet<String>(
            Arrays.asList("http://www.w3.org/2002/07/owl#",
                    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                    "http://www.w3.org/2000/01/rdf-schema#",
                    "http://www.w3.org/2001/XMLSchema#",
                    "http://www.w3.org/XML/1998/namespace"));

    /**
     * The prefixes an ontology was read with, for writing it again - all but a default prefix
     * that is OWL's own vocabulary. OWL/XML has that as its XML namespace, and the OWL API of
     * Protege 5.6 hands it on as the ontology's default prefix: a version read from OWL/XML was
     * written with {@code <Ontology>} and {@code <Class>} for {@code <owl:Ontology>} and
     * {@code <owl:Class>}, and its edit file began with OWL's namespace under no name.
     */
    private static void copyPrefixes(org.semanticweb.owlapi.model.OWLDocumentFormat from,
            org.semanticweb.owlapi.formats.PrefixDocumentFormat into) {
        if (from == null || !from.isPrefixOWLOntologyFormat()) {
            return;
        }
        for (Map.Entry<String, String> prefix : from.asPrefixOWLOntologyFormat()
                .getPrefixName2PrefixMap().entrySet()) {
            if (prefix.getKey().equals(":") && STANDARD_VOCABULARIES.contains(prefix.getValue())) {
                continue;
            }
            into.setPrefix(prefix.getKey(), prefix.getValue());
        }
    }

    /**
     * An edit file as ODK keeps one: functional syntax, under the prefixes the ontology was
     * read with, a string always in its simple form ({@link SimpleStrings}).
     */
    private static void saveEdit(OWLOntology ontology, File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat syntax =
                new org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat();
        org.semanticweb.owlapi.model.OWLDocumentFormat had =
                ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        copyPrefixes(had, syntax);
        try {
            java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
            ontology.getOWLOntologyManager().saveOntology(ontology, syntax, written);
            Files.write(file.toPath(), SimpleStrings.simplified(
                    new String(written.toByteArray(), StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (org.semanticweb.owlapi.model.OWLOntologyStorageException cannot) {
            throw new IOException(cannot.getMessage(), cannot);
        }
    }

    /**
     * Writes the ontology as the OWL API would, except that a string literal is always written in
     * its simple form - "x", never "x"^^xsd:string.
     *
     * <p>RDF 1.1 makes the two the same literal, and the OWL API agrees: they are equal, with one
     * hash code. So its data factory - one per process, shared by every manager - hands back
     * whichever of the two it has cached, annotations included: asked for dcterms:date
     * "2026-03-01", it returns the "2026-03-01"^^xsd:string an earlier load created, until a
     * background eviction drops it. Which form a release file shows then depends on what else the
     * process did and on when the cache last cleared. Measured on Linux CI: one version converted
     * twice in one run was written with rdf:datatype the first time and without it the second, so
     * the conversion was not reproducible; on the Mac it never happened. Writing the simple form
     * always makes it so, and nothing that reads the file can tell the difference.
     */
    static void writeCanonically(OWLOntology ontology,
            org.semanticweb.owlapi.model.OWLDocumentFormat format, File file) throws Exception {
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(ontology, format, written);
        Files.write(file.toPath(), simpleStrings(
                new String(written.toByteArray(), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
    }

    /** A quoted Turtle literal - long or short - followed by an explicit xsd:string. */
    private static final Pattern TYPED_STRING = Pattern.compile("(\"\"\"[\\s\\S]*?\"\"\""
            + "|\"(?:[^\"\\\\\\n]|\\\\.)*\")\\^\\^(?:xsd:string|<http://www\\.w3\\.org/2001/"
            + "XMLSchema#string>)");

    /**
     * "x"^^xsd:string as "x", in RDF/XML and in Turtle; nothing else changes. Only the datatype is
     * ever removed, so a match in an unexpected place still changes nothing but that.
     */
    static String simpleStrings(String written) {
        String rdfXml = written
                .replace(" rdf:datatype=\"http://www.w3.org/2001/XMLSchema#string\"", "")
                .replace(" rdf:datatype=\"&xsd;string\"", "");
        return TYPED_STRING.matcher(rdfXml).replaceAll("$1");
    }

    /**
     * Each import, as a product ODK's configuration declares: its module of the terms the
     * ontology uses and its term list, and - by ODK's own update - the import pointed at the
     * module, the catalog's entry and the Makefile's targets for it. It is what Update
     * project files' question about an import the configuration does not declare makes of
     * one ({@link ImportGuard}), answered here without asking: a module of the terms used;
     * the whole of an import none of whose terms is used; out with one that cannot be read,
     * since what cannot be read cannot be declared and ODK keeps no import it does not know.
     *
     * @param current the ontology as it was read, with what it imports: nothing is fetched
     *                a second time
     * @param edit    what the edit file holds
     */
    private static void modules(OWLOntology current, OWLOntology edit, File root, File editFile,
            WarnCounting warn, Log log, Result result) {
        try {
            ImportGuard.Reading reading = ImportGuard.reading(root, current);
            List<String> declared = new ArrayList<String>();
            boolean takenOut = false;
            for (ImportGuard.Finding finding : reading.findings(
                    OdkUpdate.plan(root).undeclared())) {
                String iri = finding.item().iri();
                if (!finding.isReadable()) {
                    edit.getOWLOntologyManager().applyChange(
                            new org.semanticweb.owlapi.model.RemoveImport(edit,
                                    edit.getOWLOntologyManager().getOWLDataFactory()
                                            .getOWLImportsDeclaration(IRI.create(iri))));
                    takenOut = true;
                    warn.warn(iri + " could not be read, so no module can be made of it, and "
                            + "ODK keeps no import its configuration does not declare: it is "
                            + "taken out of the edit file. Once it can be read, "
                            + "OntoBoard > Reuse > Import terms from a list... brings its "
                            + "terms back.");
                    continue;
                }
                boolean used = finding.termsUsed() > 0;
                for (File written : reading.declare(finding, used
                        ? ImportGuard.Decision.MODULE : ImportGuard.Decision.WHOLE)) {
                    if (written.getName().endsWith("_import.owl")) {
                        SimpleStrings.simplify(written);
                    }
                }
                declared.add(iri);
                result.modules.add(finding.name());
                log.line(iri + " -> imports/" + finding.name() + "_import.owl"
                        + ", declared as the product " + finding.name() + ": "
                        + (used ? "a module of the " + finding.termsUsed()
                                + (finding.termsUsed() == 1 ? " term" : " terms")
                                + " the ontology uses"
                                : "the whole of it, since the ontology's own axioms use none "
                                        + "of its terms")
                        + (finding.isFrozen() ? ". ODK keeps the module as it is: it was read "
                                + "from a file, so there is no address to fetch it from again."
                                : ". ODK fetches it again from its address when the imports "
                                        + "are refreshed."));
            }
            if (takenOut) {
                saveEdit(edit, editFile);
            }
            odksUpdate(root, editFile, declared, log);
        } catch (IOException | RuntimeException cannot) {
            warn.warn("The imports could not be made modules (" + cannot.getMessage()
                    + "), so the ontology imports what it imported. "
                    + "OntoBoard > Project > Update project files... asks about each import "
                    + "the configuration does not declare.");
        }
    }

    /**
     * ODK's own update, as after any change of the configuration: the Makefile, the catalog
     * and the edit file's import declarations follow what the configuration declares. Made
     * also where nothing was declared: the first time ODK's update runs on a seed it writes
     * two of the seed's files its own way - the last line of .gitignore, the first two of
     * the catalog - and the first Update project files on a converted project would show
     * that as two files to change.
     *
     * @param declared the imports that were just declared, which the update replaces by
     *                 their modules
     */
    private static void odksUpdate(File root, File editFile, List<String> declared, Log log)
            throws IOException {
        for (File written : OdkUpdate.apply(root, OdkUpdate.plan(root).removing(declared))) {
            log.line("ODK's update wrote " + under(root, written));
        }
        SimpleStrings.simplify(editFile);
    }

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    /**
     * The title, description and licence ODK's quality report requires of an ontology, added
     * where the ontology does not state them - without them every build fails with three errors
     * about metadata nobody was asked for.
     */
    private static void metadata(OWLOntology edit, String title, OdkProjectConfig config,
            Log log) {
        OWLDataFactory df = edit.getOWLOntologyManager().getOWLDataFactory();
        Set<String> stated = new java.util.HashSet<String>();
        for (OWLAnnotation annotation : edit.getAnnotations()) {
            stated.add(annotation.getProperty().getIRI().toString());
        }
        String description = descriptionOf(edit);
        Object[][] wanted = {
            {"title", df.getOWLLiteral(title)},
            {"description", df.getOWLLiteral(description.isEmpty() ? title : description)},
            {"license", IRI.create(licenceOf(edit))}};
        for (Object[] each : wanted) {
            String property = DCTERMS + each[0];
            if (stated.contains(property)) {
                continue;
            }
            org.semanticweb.owlapi.model.OWLAnnotationValue value =
                    (org.semanticweb.owlapi.model.OWLAnnotationValue) each[1];
            edit.getOWLOntologyManager().applyChange(new AddOntologyAnnotation(edit,
                    df.getOWLAnnotation(df.getOWLAnnotationProperty(IRI.create(property)),
                            value)));
            // As it reads: what an annotation value prints as is the OWL API's own business -
            // "Shop"^^xsd:string on one version, the name of a Java object on another.
            log.line("added dcterms:" + each[0] + " - ODK's quality report requires it, and "
                    + "the ontology did not say: " + (value.asLiteral().isPresent()
                            ? value.asLiteral().get().getLiteral() : each[1].toString()));
        }
    }

    // ---------------------------------------------------------------- git

    /** Whether the git command runs here. */
    public static boolean gitIsInstalled() {
        try {
            Process git = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            // Read to the end: closing it first can make git die of a broken pipe, and then git
            // looks absent when it is not.
            try (java.io.InputStream out = git.getInputStream()) {
                while (out.read() >= 0) {
                    continue;
                }
            }
            return git.waitFor() == 0;
        } catch (Exception notThere) {
            return false;
        }
    }

    /**
     * A version, as a release is recorded: everything written, the file released at the top
     * of the project with it - ODK's .gitignore keeps a release's files out of git, and a
     * release takes them in all the same, as ODK's own instructions do with git add -f - a
     * commit, and the tag a release has.
     */
    private static void commitAndTag(File root, String id, String label, String message,
            String date, WarnCounting warn, Result result, Log log) {
        git(root, warn, date, "add", "-A");
        git(root, warn, date, "add", "-f", "--", id + ".owl");
        if (git(root, warn, date, "commit", "-q", "-m", message) == 0) {
            String tag = GitTags.tagFor(label);
            git(root, warn, date, "tag", "-a", tag, "-m", id + " " + label);
            result.tags.add(tag);
            log.line("git: committed and tagged " + tag);
        }
    }

    /**
     * Runs git in the project. With {@code date}, the commit carries it as both author and
     * committer date, so a version's commit says when the version was made, and two conversions
     * of the same history give the same commits.
     */
    private static int git(File root, WarnCounting warn, String date, String... arguments) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(root)
                .redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        if (date != null) {
            env.put("GIT_AUTHOR_DATE", date + "T12:00:00+00:00");
            env.put("GIT_COMMITTER_DATE", date + "T12:00:00+00:00");
        }
        // Signed as any commit of yours would be. Only a git that knows nobody, and so refuses to
        // commit, signs them OntoBoard.
        if (("commit".equals(arguments[0]) || "tag".equals(arguments[0])) && !knowsWho(root)) {
            env.putIfAbsent("GIT_AUTHOR_NAME", "OntoBoard");
            env.putIfAbsent("GIT_AUTHOR_EMAIL", "ontoboard@localhost");
            env.putIfAbsent("GIT_COMMITTER_NAME", "OntoBoard");
            env.putIfAbsent("GIT_COMMITTER_EMAIL", "ontoboard@localhost");
        }
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                warn.warn("git " + String.join(" ", arguments) + " failed: "
                        + output.toString().trim());
            }
            return exit;
        } catch (Exception cannot) {
            warn.warn("git " + String.join(" ", arguments) + " could not run: "
                    + cannot.getMessage());
            return -1;
        }
    }

    /** Whether git has a name and an e-mail to sign commits in {@code root} with. */
    static boolean knowsWho(File root) {
        for (String key : new String[] {"user.name", "user.email"}) {
            try {
                Process config = new ProcessBuilder("git", "config", key).directory(root)
                        .redirectErrorStream(true).start();
                StringBuilder value = new StringBuilder();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        config.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        value.append(line);
                    }
                }
                if (config.waitFor() != 0 || value.toString().trim().isEmpty()) {
                    return false;
                }
            } catch (Exception cannot) {
                return false;
            }
        }
        return true;
    }

    /** The date a version says it was made, {@code YYYY-MM-DD}, or null. */
    static String dateOf(Read version) {
        if (version.version.getScheme() == OntologyVersion.Scheme.DATE) {
            return version.version.getText();
        }
        for (OWLAnnotation annotation : version.ontology.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString();
            if ((property.endsWith("/date") || property.endsWith("/issued")
                    || property.endsWith("/created") || property.endsWith("/modified"))
                    && annotation.getValue().asLiteral().isPresent()) {
                Matcher date = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(
                        annotation.getValue().asLiteral().get().getLiteral());
                if (date.find()) {
                    return date.group();
                }
            }
        }
        return null;
    }

    /** A file's text; empty when it is not there or cannot be read. */
    private static String textOf(File file) {
        try {
            return file.isFile() ? new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8) : "";
        } catch (IOException unreadable) {
            return "";
        }
    }

    /**
     * ODK's README with the history after it: every version that is a release and a tag,
     * newest first. With no version, ODK's README as it is.
     *
     * @param readmeOfOdk the README as the seed wrote it
     */
    private static void writeHistory(File readme, String readmeOfOdk, String title,
            List<String> versions) {
        try {
            if (versions.isEmpty()) {
                if (!readmeOfOdk.isEmpty()) {
                    Files.write(readme.toPath(), readmeOfOdk.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            String text = readmeOfOdk.isEmpty() ? "# " + title + "\n" : readmeOfOdk;
            StringBuilder history = new StringBuilder("\n## History\n\nConverted to an ODK "
                    + "repository by OntoBoard. Every version is a release under `releases/` and a "
                    + "git tag:\n\n| Version | Tag |\n|---|---|\n");
            List<String> newestFirst = new ArrayList<String>(versions);
            Collections.reverse(newestFirst);
            for (String version : newestFirst) {
                history.append("| ").append(version).append(" | `v").append(version)
                        .append("` |\n");
            }
            Files.write(readme.toPath(), (text.trim() + "\n" + history)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException cannot) {
            // The README is a courtesy; the releases and tags are the record.
        }
    }

    static String publishing(File root, String id, boolean git) {
        if (!git) {
            return "To publish it, install git, then in " + root.getAbsolutePath() + ":\n"
                    + "  git init && git add -A && git commit -m \"The ODK repository\"\n"
                    + "and push it to a repository you create on GitHub.";
        }
        return "To publish it on GitHub, create an empty repository there (no README), then in "
                + root.getAbsolutePath() + ":\n"
                + "  git remote add origin https://github.com/<you>/" + id + ".git\n"
                + "  git push -u origin main --tags\n"
                + "or, with the GitHub CLI: gh repo create " + id + " --source . --push, then "
                + "git push --tags";
    }

    // ---------------------------------------------------------------- small things

    /** Words an IRI or a file is often named that say nothing about which ontology it is. */
    private static final Set<String> GENERIC = new java.util.HashSet<String>(Arrays.asList(
            "ontology", "ontologies", "index", "owl", "core", "main", "release", "edit", "full",
            "base", "simple", "vocab", "vocabulary", "schema", "o"));

    /**
     * The project id: the ontology's preferred prefix ({@code vann:preferredNamespacePrefix}),
     * else its IRI's last name, else the name of the file it was read from - whichever is first
     * not a generic word. NFDIcore's IRI ends in {@code /ontology}, and a project called
     * "ontology" says nothing; its file is {@code nfdicore.ttl}.
     */
    static String idFor(OWLOntology ontology, IRI ontologyIri, String source) {
        List<String> candidates = new ArrayList<String>();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString()
                    .equals("http://purl.org/vocab/vann/preferredNamespacePrefix")
                    && annotation.getValue().asLiteral().isPresent()) {
                candidates.add(annotation.getValue().asLiteral().get().getLiteral());
            }
        }
        candidates.add(ontologyIri.toString());
        if (source != null) {
            candidates.add(source.replaceAll("[?#].*$", ""));
        }
        for (String candidate : candidates) {
            String id = ProjectWizard.idFrom(candidate);
            if (id != null && !id.isEmpty() && !GENERIC.contains(id)) {
                return id;
            }
        }
        return ProjectWizard.idFrom(ontologyIri.toString());
    }

    private static String titleOf(OWLOntology ontology, String id) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString();
            if ((annotation.getProperty().isLabel() || property.endsWith("/title"))
                    && annotation.getValue().asLiteral().isPresent()) {
                return annotation.getValue().asLiteral().get().getLiteral().trim();
            }
        }
        return id;
    }

    private static String descriptionOf(OWLOntology ontology) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString();
            if ((property.endsWith("/description") || property.endsWith("#comment"))
                    && annotation.getValue().asLiteral().isPresent()) {
                String text = annotation.getValue().asLiteral().get().getLiteral()
                        .replaceAll("\\s+", " ").trim();
                return text.length() > 300 ? text.substring(0, 297) + "..." : text;
            }
        }
        return "";
    }

    private static String licenceOf(OWLOntology ontology) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            String property = annotation.getProperty().getIRI().toString().toLowerCase(
                    Locale.ROOT);
            if (property.endsWith("/license") || property.endsWith("/licence")
                    || property.endsWith("/rights")) {
                if (annotation.getValue().asIRI().isPresent()) {
                    return annotation.getValue().asIRI().get().toString();
                }
                if (annotation.getValue().asLiteral().isPresent()
                        && annotation.getValue().asLiteral().get().getLiteral()
                                .startsWith("http")) {
                    return annotation.getValue().asLiteral().get().getLiteral().trim();
                }
            }
        }
        return "https://creativecommons.org/licenses/by/4.0/";
    }

    private static String describe(List<Read> versions) {
        Set<String> labels = new LinkedHashSet<String>();
        for (Read version : versions) {
            labels.add(version.version.getText());
        }
        return String.join(", ", labels);
    }

    /** The log, counting the warnings of the current step. */
    private static final class WarnCounting {
        final Log log;
        final Result result;
        int warned;

        WarnCounting(Log log, Result result) {
            this.log = log;
            this.result = result;
        }

        void warn(String text) {
            warned++;
            result.warnings.add(text);
            log.warn(text);
        }

        boolean clean() {
            boolean clean = warned == 0;
            warned = 0;
            return clean;
        }
    }
}
