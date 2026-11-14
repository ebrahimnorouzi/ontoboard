package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkConfigException;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkProjectModel;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.PyObject;
import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The imports ODK's update would take out of the edit file.
 *
 * <p>ODK's update gives the edit file the import declarations its configuration asks for and no
 * others ({@code robot odk:import --exclusive true}). An import somebody made in Protege, or
 * one the ontology had before the project was ODK's, is not in the configuration: the update
 * takes it out without a word, and the ontology loses whatever it used from there. So the
 * imports an update would take out are found before it ({@link #undeclared}), and it is not
 * made until each of them is declared in the configuration ({@link Reading#declare}) or its
 * removal is agreed to.
 *
 * <p>Declared means: a product in {@code import_group.products} and a module
 * {@code imports/<id>_import.owl} - of the terms the ontology uses, or the whole ontology. The
 * update then puts the module's import in the place of the old one, which is how ODK has it.
 */
public final class ImportGuard {

    private ImportGuard() {
    }

    // ------------------------------------------------------------------ what would go

    /** An import of the edit file that the configuration does not declare. */
    public static final class Undeclared {
        private final String iri;
        private final File localFile;

        Undeclared(String iri, File localFile) {
            this.iri = iri;
            this.localFile = localFile;
        }

        /** The import as the edit file declares it. */
        public String iri() {
            return iri;
        }

        /**
         * The file in the project the catalog serves this import from; null when it is read
         * from where its IRI points.
         */
        public File localFileOrNull() {
            return localFile;
        }

        @Override
        public String toString() {
            return iri;
        }
    }

    /** Why an update is not made: imports it would take out, and nobody said what to do. */
    public static final class Undecided extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final List<String> imports;

        Undecided(List<String> imports) {
            super(sentenceFor(imports));
            this.imports = Collections.unmodifiableList(new ArrayList<String>(imports));
        }

        /** The imports nothing was decided about. */
        public List<String> imports() {
            return imports;
        }
    }

    /** What an update that is not made says about the imports it would take out. */
    public static String sentenceFor(List<String> imports) {
        boolean one = imports.size() == 1;
        return (one ? "The edit file imports " + imports.get(0) + ", which the project's "
                + "configuration does not declare" : "The edit file imports " + imports.size()
                + " ontologies the project's configuration does not declare ("
                + String.join(", ", imports) + ")") + ". ODK's update gives the edit file the "
                + "imports of the configuration and no others, so it would take "
                + (one ? "this one" : "these") + " out. Nothing was written. Say first what is "
                + "to become of " + (one ? "it" : "each") + ": declared in the configuration, "
                + "or taken out.";
    }

    /**
     * The imports of the edit file that an update would take out.
     *
     * @param importedNow the edit file's import declarations ({@link #importsOf})
     * @param declared    the import declarations ODK's update gives the edit file
     * @param catalogFile the project's catalog; it need not be there
     */
    static List<Undeclared> undeclared(List<String> importedNow, Collection<String> declared,
            File catalogFile) throws IOException {
        String catalog = textOf(catalogFile);
        List<Undeclared> found = new ArrayList<Undeclared>();
        for (String iri : importedNow) {
            if (declared.contains(iri)) {
                continue;
            }
            found.add(new Undeclared(iri, servedFrom(catalog, catalogFile, iri)));
        }
        return found;
    }

    /**
     * The modules in the project's imports folder that nothing imports: not the edit file as
     * it is - under the module's own address, or under another that the catalog serves from
     * that file - and not ODK's update for a product the configuration declares. They are
     * named and left alone, neither declared nor deleted: a module somebody keeps for later
     * is theirs.
     *
     * @return their paths from the project's root, with {@code /}, sorted
     */
    static List<String> notImported(File projectRoot, String ontbase,
            Collection<String> declared, List<String> importedNow, File catalogFile)
            throws IOException {
        List<String> unused = new ArrayList<String>();
        File folder = new File(projectRoot, ImportModules.IMPORTS_DIRECTORY);
        String[] names = folder.list();
        if (names == null) {
            return unused;
        }
        String catalog = textOf(catalogFile);
        Set<File> served = new HashSet<File>();
        for (String iri : importedNow) {
            File from = servedFrom(catalog, catalogFile, iri);
            if (from != null) {
                served.add(from.getAbsoluteFile());
            }
        }
        java.util.Arrays.sort(names);
        for (String name : names) {
            if (!name.endsWith("_import.owl")) {
                continue;
            }
            String iri = ontbase + "/imports/" + name;
            if (!declared.contains(iri) && !importedNow.contains(iri)
                    && !served.contains(new File(folder, name).getAbsoluteFile())) {
                unused.add(ImportModules.IMPORTS_DIRECTORY + "/" + name);
            }
        }
        return unused;
    }

    /** The catalog's text; null when there is none. */
    private static String textOf(File catalogFile) throws IOException {
        return catalogFile != null && catalogFile.isFile() ? new String(
                Files.readAllBytes(catalogFile.toPath()), StandardCharsets.UTF_8) : null;
    }

    /** The file of the project the catalog serves an import from; null when from none. */
    private static File servedFrom(String catalog, File catalogFile, String iri) {
        String path = catalog == null ? null : mapped(catalog, iri);
        if (path == null || path.contains(":")) {
            return null;
        }
        File candidate = new File(catalogFile.getParentFile(), path);
        return candidate.isFile() ? candidate : null;
    }

    /** Where the catalog sends an IRI; null when it says nothing of it or cannot be read. */
    private static String mapped(String catalog, String iri) {
        try {
            return Catalog.entryFor(catalog, iri);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The import declarations of an ontology file, sorted, without reading what they import:
     * every import is sent to a file that does not exist, and a missing import is let pass.
     *
     * <p>Read from the file's bytes, not from the file: the OWL API can keep a file it was
     * given open, and this one is about to be replaced.
     */
    static List<String> importsOf(File ontologyFile) throws IOException {
        TreeSet<String> imports = new TreeSet<String>();
        for (OWLImportsDeclaration declaration : alone(ontologyFile)
                .getImportsDeclarations()) {
            imports.add(declaration.getIRI().toString());
        }
        return new ArrayList<String>(imports);
    }

    /**
     * The address of the ontology a file holds; null when it has none. Read as
     * {@link #importsOf} reads: nothing it imports is fetched.
     */
    static String iriOf(File ontologyFile) throws IOException {
        OWLOntology ontology = alone(ontologyFile);
        return ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : null;
    }

    /** The ontology a file holds, by itself: every import is sent to a file that is not there. */
    static OWLOntology alone(File ontologyFile) throws IOException {
        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        final File nowhere = new File(ontologyFile.getAbsoluteFile().getParentFile(),
                ".ontoboard-no-such-import");
        reader.getIRIMappers().add(iri -> IRI.create(nowhere));
        reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        byte[] bytes = Files.readAllBytes(ontologyFile.toPath());
        OWLOntology ontology;
        try {
            ontology = reader.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                    new ByteArrayInputStream(bytes), IRI.create(new File(
                            ontologyFile.getAbsoluteFile().getParentFile(),
                            ".ontoboard-reading-" + ontologyFile.getName()))));
        } catch (Exception unreadable) {
            throw new IOException(ontologyFile.getName() + " could not be read as an ontology: "
                    + unreadable.getMessage(), unreadable);
        }
        return ontology;
    }

    // ------------------------------------------------------------------ what to do about it

    /** What becomes of an undeclared import. */
    public enum Decision {
        /** Declared, its module made of the terms the ontology uses. */
        MODULE,
        /** Declared, its module the whole ontology. */
        WHOLE,
        /** Taken out by the update. */
        REMOVE
    }

    /** What is known of an undeclared import, to decide by. */
    public static final class Finding {
        private final Undeclared item;
        private final String name;
        private final int termsUsed;
        private final String unreadable;

        Finding(Undeclared item, String name, int termsUsed, String unreadable) {
            this.item = item;
            this.name = name;
            this.termsUsed = termsUsed;
            this.unreadable = unreadable;
        }

        public Undeclared item() {
            return item;
        }

        /** The id its product would get, which names its module; null when it cannot be read. */
        public String name() {
            return name;
        }

        /** How many of its terms the ontology's own axioms use. */
        public int termsUsed() {
            return termsUsed;
        }

        /** Whether it could be read, which declaring it needs. */
        public boolean isReadable() {
            return unreadable == null;
        }

        /** Why it could not be read; null when it could. */
        public String whyUnreadable() {
            return unreadable;
        }

        /**
         * Whether ODK keeps the module as it is instead of fetching the import again from
         * its address: so where the import is read from a file - one of the project, which
         * its catalog serves, or one this machine has elsewhere. A file of this machine is
         * an address on no other.
         */
        public boolean isFrozen() {
            return item.localFileOrNull() != null || isOfThisMachine(item.iri());
        }
    }

    /**
     * The edit file read with what it imports, as far as that can be read: what the decisions
     * are made by, and made from.
     */
    public static final class Reading {
        private final File root;
        private final File ontologyDirectory;
        private final File config;
        private final String ontbase;
        private final OWLOntology edit;
        private final Set<OWLEntity> used;

        private Reading(File root, File config, String ontbase, OWLOntology edit) {
            this.root = root;
            this.ontologyDirectory = new File(root, "src/ontology");
            this.config = config;
            this.ontbase = ontbase;
            this.edit = edit;
            this.used = usedBy(edit);
        }

        /** What is known of each import, in the order given. */
        public List<Finding> findings(List<Undeclared> undeclared) {
            List<Finding> findings = new ArrayList<Finding>();
            Set<String> taken = new HashSet<String>();
            for (Undeclared item : undeclared) {
                OWLOntology imported = importedBy(item);
                if (imported == null) {
                    findings.add(new Finding(item, null, 0, "it could not be read"
                            + (item.localFileOrNull() == null ? " from " + item.iri()
                                    : " from " + item.localFileOrNull())));
                    continue;
                }
                String name = nameFor(imported, taken);
                taken.add(name);
                findings.add(new Finding(item, name, termsOf(imported).size(), null));
            }
            return findings;
        }

        /**
         * Declares an import: its product in the configuration, its module, and for a module
         * of the terms used its term list.
         *
         * @return the files written
         * @throws IOException when the import cannot be read or a file cannot be written;
         *                     the configuration is written last, so that a product is never
         *                     declared without its module
         */
        public List<File> declare(Finding finding, Decision decision) throws IOException {
            if (decision == Decision.REMOVE) {
                return new ArrayList<File>();
            }
            if (!finding.isReadable()) {
                throw new IOException(finding.item().iri() + " cannot be declared: "
                        + finding.whyUnreadable() + ".");
            }
            OWLOntology imported = importedBy(finding.item());
            String name = finding.name();
            IRI moduleIri = IRI.create(ontbase + "/imports/" + name + "_import.owl");
            List<File> written = new ArrayList<File>();
            File moduleFile = ImportModules.moduleFileFor(root, name);
            List<IRI> terms = termsOf(imported);
            OWLOntology module;
            try {
                if (decision == Decision.MODULE) {
                    module = ModuleRebuild.extract(imported, terms, TermExtract.DEFAULT_METHOD,
                            moduleIri, Provenance.today()).getModule();
                } else {
                    module = OWLManager.createOWLOntologyManager().createOntology(moduleIri);
                    module.getOWLOntologyManager().addAxioms(module,
                            imported.getAxioms(Imports.INCLUDED));
                    for (OWLAnnotation annotation : imported.getAnnotations()) {
                        module.getOWLOntologyManager().applyChange(
                                new org.semanticweb.owlapi.model.AddOntologyAnnotation(module,
                                        annotation));
                    }
                }
                Files.createDirectories(moduleFile.getParentFile().toPath());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                module.getOWLOntologyManager().saveOntology(module,
                        new FunctionalSyntaxDocumentFormat(), bytes);
                Files.write(moduleFile.toPath(), bytes.toByteArray());
            } catch (IOException notWritten) {
                throw notWritten;
            } catch (Exception notMade) {
                throw new IOException("The module of " + finding.item().iri() + " could not be "
                        + "made: " + notMade.getMessage(), notMade);
            }
            written.add(moduleFile);
            if (decision == Decision.MODULE) {
                Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
                for (IRI term : terms) {
                    for (OWLEntity entity : imported.getEntitiesInSignature(term,
                            Imports.INCLUDED)) {
                        String label = DisplayLabels.forEntity(imported, entity);
                        if (label != null && !label.equals(term.toString())) {
                            labels.put(term, label);
                        }
                    }
                }
                // Where from is said with an address. A path of this machine is none, and
                // goes into no file that is committed.
                written.add(ImportModules.writeTerms(root, name, terms,
                        isOfThisMachine(finding.item().iri()) ? null : finding.item().iri(),
                        null, labels));
            }
            String before = new String(Files.readAllBytes(config.toPath()),
                    StandardCharsets.UTF_8);
            OdkBuildSettings.writeIfUnchanged(config, before, ImportProducts.withProduct(before,
                    productFor(finding, decision)));
            written.add(config);
            return written;
        }

        /** The product's lines in the configuration. */
        private LinkedHashMap<String, String> productFor(Finding finding, Decision decision) {
            if (finding.isFrozen()) {
                // Read from a file, the project's or this machine's: there is no address
                // ODK could fetch it from, so the module is kept as it is - ODK's words for
                // that - and no path is written.
                return ImportProducts.frozen(finding.name());
            }
            if (decision == Decision.MODULE) {
                return ImportProducts.declarationFor(finding.name(), finding.item().iri(),
                        TermExtract.DEFAULT_METHOD).getFields();
            }
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
            fields.put("id", finding.name());
            fields.put("mirror_from", finding.item().iri());
            fields.put("module_type", "mirror");
            return fields;
        }

        private OWLOntology importedBy(Undeclared item) {
            return edit.getOWLOntologyManager().getImportedOntology(
                    edit.getOWLOntologyManager().getOWLDataFactory()
                            .getOWLImportsDeclaration(IRI.create(item.iri())));
        }

        /** The terms of an imported ontology that the edit file's own axioms use, sorted. */
        private List<IRI> termsOf(OWLOntology imported) {
            TreeSet<String> terms = new TreeSet<String>();
            for (OWLEntity entity : used) {
                if (!entity.isBuiltIn() && imported.containsEntityInSignature(entity.getIRI(),
                        Imports.INCLUDED)) {
                    terms.add(entity.getIRI().toString());
                }
            }
            List<IRI> sorted = new ArrayList<IRI>();
            for (String term : terms) {
                sorted.add(IRI.create(term));
            }
            return sorted;
        }

        /**
         * An id for the product: what the ontology is known by, made a word ODK's Makefile
         * can carry, and not one a product, a module or another import of this round has.
         */
        private String nameFor(OWLOntology imported, Set<String> taken) {
            String wanted = TermExtract.recognisableNameOf(imported).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
            if (wanted.isEmpty()) {
                wanted = "import";
            }
            Set<String> has = new HashSet<String>(taken);
            for (ImportProducts.Product product : ImportProducts.declaredIn(ontologyDirectory)) {
                has.add(product.getId());
            }
            String name = wanted;
            for (int n = 2; has.contains(name)
                    || ImportModules.moduleFileFor(root, name).exists()
                    || ImportModules.termsFileFor(root, name).exists(); n++) {
                name = wanted + "_" + n;
            }
            return name;
        }
    }

    /**
     * The same for an ontology that is loaded already, with what it imports in its manager:
     * the content of the project's edit file as somebody has just read it. Nothing is read
     * again - an import fetched from far away is not fetched a second time, and what could
     * be read then can be declared now.
     *
     * @param loaded the ontology the project's edit file holds, read with its imports
     * @throws IOException when the project's configuration cannot be read
     */
    static Reading reading(File projectRoot, OWLOntology loaded) throws IOException {
        File root = projectRoot.getAbsoluteFile();
        File config = configIn(root);
        return new Reading(root, config, OdkUpdate.ontbase(projectOf(config, null)), loaded);
    }

    /** Whether an import's address is a file of this machine and so an address on no other. */
    private static boolean isOfThisMachine(String iri) {
        return iri != null && iri.trim().toLowerCase(Locale.ROOT).startsWith("file:");
    }

    /** The project's one ODK configuration. */
    private static File configIn(File root) throws IOException {
        File config = OdkBuildSettings.yamlIn(new File(root, "src/ontology"));
        if (config == null) {
            throw new IOException("src/ontology holds no ODK configuration (or more than one), "
                    + "so there is nowhere to declare an import.");
        }
        return config;
    }

    /** The configuration as ODK reads it: the file's, or this text in its place. */
    private static PyObject projectOf(File config, String configInstead) throws IOException {
        try {
            return OdkProjectModel.load(configInstead != null ? configInstead
                    : new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
        } catch (OdkConfigException notOdks) {
            throw new IOException(config.getName() + " is not a configuration ODK reads: "
                    + notOdks.getMessage(), notOdks);
        }
    }

    /**
     * Reads the project's edit file with what it imports - through the project's catalog, and
     * past what cannot be read.
     *
     * @throws IOException when the configuration or the edit file cannot be read
     */
    public static Reading read(File projectRoot, File editFile) throws IOException {
        return read(projectRoot, editFile, null);
    }

    /**
     * The same, going by this text as the project's configuration instead of the file -
     * for a configuration that is about to be written: where the project's modules have
     * their addresses is read from it.
     *
     * @param configInstead the configuration's text; null for the file as it is
     */
    public static Reading read(File projectRoot, File editFile, String configInstead)
            throws IOException {
        File root = projectRoot.getAbsoluteFile();
        File ontologyDirectory = new File(root, "src/ontology");
        File config = configIn(root);
        PyObject project = projectOf(config, configInstead);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        File catalog = new File(ontologyDirectory, "catalog-v001.xml");
        if (catalog.isFile()) {
            try {
                manager.getIRIMappers().add(new org.obolibrary.robot.CatalogXmlIRIMapper(
                        catalog));
            } catch (Exception unreadable) {
                throw new IOException("The project's catalog could not be read: "
                        + unreadable.getMessage(), unreadable);
            }
        }
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        OWLOntology edit;
        try {
            edit = manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                    new ByteArrayInputStream(Files.readAllBytes(editFile.toPath())),
                    IRI.create(new File(ontologyDirectory,
                            ".ontoboard-reading-" + editFile.getName()))));
        } catch (Exception unreadable) {
            throw new IOException(editFile.getName() + " could not be read as an ontology: "
                    + unreadable.getMessage(), unreadable);
        }
        return new Reading(root, config, OdkUpdate.ontbase(project), edit);
    }

    /**
     * What an ontology's own axioms mention - not its declarations, which a parser also writes
     * for terms it only met in an import.
     */
    private static Set<OWLEntity> usedBy(OWLOntology ontology) {
        Set<OWLEntity> used = new HashSet<OWLEntity>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            if (axiom instanceof OWLDeclarationAxiom) {
                continue;
            }
            used.addAll(axiom.getSignature());
            if (axiom instanceof OWLAnnotationAssertionAxiom) {
                OWLAnnotationSubject subject = ((OWLAnnotationAssertionAxiom) axiom).getSubject();
                if (subject instanceof IRI) {
                    used.addAll(ontology.getEntitiesInSignature((IRI) subject,
                            Imports.INCLUDED));
                }
            }
        }
        return used;
    }
}
