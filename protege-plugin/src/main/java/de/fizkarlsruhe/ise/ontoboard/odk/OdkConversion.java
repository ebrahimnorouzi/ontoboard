package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * An ontology - one file in any format OWL reads, or a web address, with its earlier versions -
 * turned into an ODK repository: the project, every earlier version as a release and a git tag,
 * its imports as modules of the terms it uses, a build that passes or says why, and the current
 * version released.
 *
 * <p>Asked for: somebody with an ontology and its history who wants an ODK-based repository to
 * publish on GitHub, "with all the previous versions available, imported terms and everything
 * converted", every step visible. So the work is {@link #STEPS} in order, each reported through a
 * {@link Log} as it happens; a step that cannot do something says so and the conversion goes on
 * where it can. Converting the same inputs twice gives the same files and the same git trees.
 *
 * <p>No Swing and no Protégé types: the dialog and the tests drive the same code. Git is the git
 * command, used when it is installed; without it the versions are still written under
 * {@code releases/}, and the log says what was not recorded.
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
            "Release the current version",
            "Record it in git",
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

        /** Every version in the repository, oldest first, the current one last. */
        public List<String> getVersions() {
            return versions;
        }

        public List<String> getTags() {
            return tags;
        }

        public List<String> getModules() {
            return modules;
        }

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

        // 3. The project.
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
        File root = config.getProjectRoot();
        if (root.exists()) {
            throw new IllegalArgumentException(root.getAbsolutePath() + " is there already. "
                    + "Choose another folder, or another project id.");
        }
        result.root = root;
        for (File written : OdkScaffold.create(config)) {
            log.line("wrote " + root.toPath().relativize(written.toPath()));
        }
        File editFile = new File(new File(root, "src/ontology"), id + "-edit.owl");
        log.done(2, warn.clean());
        stopIfAsked(attempt, 2, result);

        // 4. The earlier versions, each a release, a commit and a tag.
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
        for (Read version : distinct) {
            String label = version.version.getText();
            try {
                save(copyOf(version.ontology, true), editFile);
                save(copyOf(version.ontology, false), Release.releaseFile(root, id, label));
                log.line(label + ": the edit file and releases/" + label + "/" + id + ".owl, as "
                        + "published (" + version.source + ")");
                result.versions.add(label);
                if (git) {
                    commitAndTag(root, label, "release(" + label + "): " + title + " " + label
                            + ", as published - converted by OntoBoard", dateOf(version), warn,
                            result, log);
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

        // 5. Imports, as modules of the terms the ontology uses.
        log.step(4);
        OWLOntology edit = copyOf(current.ontology, true);
        if (request.modules) {
            modules(current.ontology, edit, root, id, ontologyIri, warn, log, result);
        } else {
            log.line("Left as they are: the ontology imports what it imported.");
        }
        metadata(edit, title, config, log);
        try {
            save(edit, editFile);
            log.line("wrote src/ontology/" + editFile.getName());
        } catch (Exception cannot) {
            throw new IllegalStateException("Could not write the edit file: "
                    + cannot.getMessage(), cannot);
        }
        log.done(4, warn.clean());
        stopIfAsked(attempt, 4, result);

        // 6. Build and test, as Build and release > Build... > test does.
        log.step(5);
        try {
            OWLOntologyManager building = OWLManager.createOWLOntologyManager();
            building.getIRIMappers().add(new org.obolibrary.robot.CatalogXmlIRIMapper(
                    new File(editFile.getParentFile(), "catalog-v001.xml")));
            OWLOntology loaded = building.loadOntologyFromOntologyDocument(editFile);
            InProcessTargets.Outcome outcome = InProcessTargets.run(editFile, loaded,
                    Reasoners.Choice.ELK.newFactory(), "test");
            for (String line : outcome.getTranscript()) {
                log.line(line);
            }
            result.buildPassed = outcome.isOk();
            if (!outcome.isOk()) {
                warn.warn("The build found problems: " + outcome.getFailure() + " The project is "
                        + "made all the same; Build and release > Build... shows them in Protégé.");
            }
        } catch (Exception cannot) {
            warn.warn("The build could not run: " + cannot.getMessage());
        }
        log.done(5, warn.clean());
        stopIfAsked(attempt, 5, result);

        // 7. The current version, released.
        log.step(6);
        String label = current.version != null ? current.version.getText() : Provenance.today();
        if (current.version == null) {
            warn.warn("The ontology does not say which version it is, so it is released as "
                    + label + ", today's date.");
        }
        try {
            release(root, id, edit, ontologyIri, OntologyVersion.of(label),
                    distinct.isEmpty() ? null : distinct.get(distinct.size() - 1).version, log);
            result.versions.add(label);
        } catch (Exception cannot) {
            warn.warn("The release could not be made: " + cannot.getMessage());
        }
        log.done(6, warn.clean());
        stopIfAsked(attempt, 6, result);

        // 8. Recorded.
        log.step(7);
        if (git) {
            writeReadmeHistory(root, title, result.versions);
            commitAndTag(root, label, "release(" + label + "): " + title + " " + label
                    + ", converted to an ODK repository by OntoBoard", null, warn, result, log);
        } else {
            log.line("Not recorded: no git.");
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
            return manager.loadOntology(address);
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
                rdf.copyPrefixesFrom(format.asPrefixOWLOntologyFormat());
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
        ontology.getOWLOntologyManager().saveOntology(ontology,
                format instanceof RDFXMLDocumentFormat ? format : new RDFXMLDocumentFormat(),
                IRI.create(file.toURI()));
    }

    /**
     * Each import, as an ODK module of the terms the ontology uses from it: extracted (BOT), its
     * term list with labels, a catalog entry, the import pointed at the module, and the product
     * declared with the import's address as its mirror.
     */
    private static void modules(OWLOntology current, OWLOntology edit, File root, String id,
            IRI ontologyIri, WarnCounting warn, Log log, Result result) {
        OWLOntologyManager manager = current.getOWLOntologyManager();
        OWLDataFactory df = edit.getOWLOntologyManager().getOWLDataFactory();
        // What the ontology's own axioms mention - not its declarations, which a parser also
        // writes for terms it only met in an import.
        Set<OWLEntity> used = new java.util.HashSet<OWLEntity>();
        for (org.semanticweb.owlapi.model.OWLAxiom axiom : current.getAxioms()) {
            if (axiom instanceof org.semanticweb.owlapi.model.OWLDeclarationAxiom) {
                continue;
            }
            used.addAll(axiom.getSignature());
            if (axiom instanceof org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom) {
                org.semanticweb.owlapi.model.OWLAnnotationSubject subject =
                        ((org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom) axiom)
                                .getSubject();
                if (subject instanceof IRI) {
                    used.addAll(current.getEntitiesInSignature((IRI) subject, Imports.INCLUDED));
                }
            }
        }
        if (current.getImportsDeclarations().isEmpty()) {
            log.line("The ontology imports nothing.");
            return;
        }
        File yaml = OdkBuildSettings.yamlIn(new File(root, "src/ontology"));
        for (OWLImportsDeclaration declaration : current.getImportsDeclarations()) {
            OWLOntology imported = manager.getImportedOntology(declaration);
            if (imported == null) {
                warn.warn(declaration.getIRI() + " could not be read, so it stays an import of "
                        + "the whole ontology. Turn it into a module later with "
                        + "OntoBoard > Reuse > Imported terms... once it can be read.");
                continue;
            }
            List<IRI> terms = new ArrayList<IRI>();
            for (OWLEntity entity : used) {
                if (!entity.isBuiltIn() && imported.containsEntityInSignature(entity.getIRI(),
                        Imports.INCLUDED)) {
                    terms.add(entity.getIRI());
                }
            }
            Collections.sort(terms, (a, b) -> a.toString().compareTo(b.toString()));
            if (terms.isEmpty()) {
                warn.warn(declaration.getIRI() + " is imported but none of its terms is used, so "
                        + "it stays as it was.");
                continue;
            }
            try {
                String name = TermExtract.shortNameOf(imported);
                IRI moduleIri = TermExtract.moduleIriIn(ontologyIri, imported);
                TermExtract.Result extracted = ModuleRebuild.extract(imported, terms,
                        TermExtract.DEFAULT_METHOD, moduleIri, Provenance.today());
                File moduleFile = ImportModules.moduleFileFor(root, name);
                save(extracted.getModule(), moduleFile);
                ImportModules.writeTerms(root, name, new ArrayList<IRI>(),
                        declaration.getIRI().toString());
                File list = ImportModules.termsFileFor(root, name);
                Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
                for (IRI term : terms) {
                    String label = null;
                    for (OWLEntity entity : imported.getEntitiesInSignature(term,
                            Imports.INCLUDED)) {
                        label = DisplayLabels.forEntity(imported, entity);
                    }
                    labels.put(term, label);
                }
                String listText = new String(Files.readAllBytes(list.toPath()),
                        StandardCharsets.UTF_8);
                StringBuilder written = new StringBuilder(listText);
                for (Map.Entry<IRI, String> term : labels.entrySet()) {
                    written.append(term.getKey()).append(term.getValue() == null
                            || term.getValue().equals(term.getKey().toString()) ? ""
                                    : " # " + term.getValue()).append('\n');
                }
                Files.write(list.toPath(), written.toString().getBytes(StandardCharsets.UTF_8));
                Catalog.addEntry(new File(root, "src/ontology/catalog-v001.xml"),
                        moduleIri.toString(), "imports/" + moduleFile.getName());
                edit.getOWLOntologyManager().applyChange(
                        new org.semanticweb.owlapi.model.RemoveImport(edit, declaration));
                edit.getOWLOntologyManager().applyChange(new AddImport(edit,
                        df.getOWLImportsDeclaration(moduleIri)));
                if (yaml != null) {
                    String before = new String(Files.readAllBytes(yaml.toPath()),
                            StandardCharsets.UTF_8);
                    ImportProducts.Declaration product = ImportProducts.declarationFor(name,
                            declaration.getIRI().toString(), TermExtract.DEFAULT_METHOD);
                    OdkBuildSettings.writeIfUnchanged(yaml, before,
                            OdkYaml.appendBlockTo(before, "import_group.products",
                                    product.getFields()));
                }
                result.modules.add(name);
                log.line(declaration.getIRI() + " -> imports/" + moduleFile.getName() + ": "
                        + terms.size() + " terms the ontology uses, a module of "
                        + extracted.getModule().getAxiomCount() + " axioms"
                        + (extracted.getMissing().isEmpty() ? ""
                                : "; not found in it: " + extracted.getMissing()));
            } catch (Exception cannot) {
                warn.warn(declaration.getIRI() + " could not be made a module ("
                        + cannot.getMessage() + "), so it stays an import of the whole ontology.");
            }
        }
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
            log.line("added dcterms:" + each[0] + " - ODK's quality report requires it, and "
                    + "the ontology did not say: " + value);
        }
    }

    /** As Release... cuts one: imports merged, reasoned with ELK, versioned, kept and published. */
    private static void release(File root, String id, OWLOntology edit, IRI ontologyIri,
            OntologyVersion version, OntologyVersion previous, Log log) throws Exception {
        OWLOntologyManager loading = OWLManager.createOWLOntologyManager();
        loading.getIRIMappers().add(new org.obolibrary.robot.CatalogXmlIRIMapper(
                new File(root, "src/ontology/catalog-v001.xml")));
        OWLOntology withImports = loading.loadOntologyFromOntologyDocument(
                new File(root, "src/ontology/" + id + "-edit.owl"));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology release = manager.createOntology(ontologyIri);
        manager.addAxioms(release, withImports.getAxioms(Imports.INCLUDED));
        for (OWLAnnotation annotation : withImports.getAnnotations()) {
            manager.applyChange(new AddOntologyAnnotation(release, annotation));
        }
        RobotTransform.Diff inferred = RobotTransform.preview(release,
                RobotTransform.Kind.REASON, Reasoners.Choice.ELK.newFactory());
        manager.applyChanges(inferred.getChanges());
        manager.applyChanges(Release.stamp(release, version,
                previous != null && previous.getScheme() == version.getScheme() ? previous
                        : null));
        File kept = Release.releaseFile(root, id, version.getText());
        kept.getParentFile().mkdirs();
        manager.saveOntology(release, new RDFXMLDocumentFormat(), IRI.create(kept.toURI()));
        manager.saveOntology(release, new RDFXMLDocumentFormat(),
                IRI.create(new File(root, id + ".owl").toURI()));
        manager.saveOntology(release, new TurtleDocumentFormat(),
                IRI.create(new File(root, id + ".ttl").toURI()));
        log.line(version.getText() + ": releases/" + version.getText() + "/" + id + ".owl, "
                + id + ".owl and " + id + ".ttl - " + release.getAxiomCount() + " axioms, "
                + inferred.getAdded() + " inferred, version IRI " + Release.versionIriOf(release));
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

    private static void commitAndTag(File root, String label, String message, String date,
            WarnCounting warn, Result result, Log log) {
        git(root, warn, date, "add", "-A");
        if (git(root, warn, date, "commit", "-q", "-m", message) == 0) {
            String tag = "v" + label;
            git(root, warn, date, "tag", "-a", tag, "-m", label);
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

    private static void writeReadmeHistory(File root, String title, List<String> versions) {
        File readme = new File(root, "README.md");
        try {
            String text = readme.isFile() ? new String(Files.readAllBytes(readme.toPath()),
                    StandardCharsets.UTF_8) : "# " + title + "\n";
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
