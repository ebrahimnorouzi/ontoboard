package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.pattern.DesignPattern;
import de.fizkarlsruhe.ise.ontoboard.pattern.NamesAsLabels;
import de.fizkarlsruhe.ise.ontoboard.pattern.PatternLibrary;
import de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance;
import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.obolibrary.robot.CatalogXmlIRIMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Writes the 1.135.2 project that {@link MigrationFixtureTest} reads and the migration tests start
 * from - with the code that wrote projects in 1.135.2, while that code still exists.
 *
 * <p>Runs only when asked, with {@code -Dontoboard.writeFixture=<folder>}: the folder must be new
 * or empty, and the project is written into it. Otherwise this is skipped, so the suite never
 * rewrites a committed fixture behind anybody's back. The fixture's {@code README.md} says which
 * commit wrote it.
 *
 * <p>Every file is what a 1.135.2 user's actions leave, made by the calls those actions make:
 * {@link OdkScaffold#create} for the project; {@link TermMinter} for three classes, minted from the
 * first editor's range as the board mints them; an import module of two terms built step by step
 * as {@code OdkConversion.modules} builds one (term list with its {@code # Source:} line and
 * labels, catalog entry, import, product declared); a pattern module cut and saved as
 * <i>Import terms...</i> saves a library pattern's, imported and not declared; a module nothing
 * imports; a question written as <i>Write a test from a question...</i> writes it; a report rule
 * relaxed; a release cut as <i>Release...</i> cuts one (merged, reasoned with ELK, stamped
 * {@code 1.0.0}, written in the project's export formats). Only the three edits a person makes by
 * hand - the relaxed rule, a paragraph in the README, a line in .gitignore - are text edits here.
 *
 * <p>Every date passed in is {@value #DATE}, and the scaffold's first range goes to the name it
 * gives when the account has none ({@code FirstEditor}), so writing the fixture twice gives the
 * same bytes on any machine.
 */
class MigrationFixtureWriter {

    /** The one date every call that takes one is given. */
    static final String DATE = "2026-11-11";

    static final String ID = "eggboil";
    static final String BASE_IRI = "https://example.org/eggboil/eggboil.owl";
    static final String LICENCE = "https://creativecommons.org/licenses/by/4.0/";

    static final String MINI = "https://example.org/mini.owl";
    static final String MINI_TERMS = "https://example.org/mini/MINI_";
    static final String UNUSED = "https://example.org/unused.owl";

    /** The library's id for the pattern the {@code measurement} module is cut from. */
    static final String PATTERN = "pmdco-measurement-and-setpoint";

    private static final IRI DEFINITION = IRI.create("http://purl.obolibrary.org/obo/IAO_0000115");

    @Test
    void writesThe1352ProjectWhenAsked(@TempDir File scratch) throws Exception {
        String into = System.getProperty("ontoboard.writeFixture");
        assumeTrue(into != null && !into.trim().isEmpty(),
                "Writes the migration fixture only with -Dontoboard.writeFixture=<folder>.");
        File target = new File(into.trim()).getAbsoluteFile();
        String[] there = target.list();
        assertTrue(there == null || there.length == 0,
                target + " already holds files; the fixture is written into a new or empty folder.");

        File root = write(scratch);
        copyTree(root.toPath(), target.toPath());
        assertTrue(new File(target, "src/ontology/Makefile").isFile());
    }

    /** The project, in {@code parent/eggboil}. */
    static File write(File parent) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig(ID, "Test kitchen ontology",
                "Application ontology for boiling eggs in a test kitchen", BASE_IRI, LICENCE,
                parent);
        // The scaffold allocates its one range to the OS account. With the account blank it uses
        // its own fallback name, so the fixture does not carry whoever wrote it.
        String account = System.getProperty("user.name");
        System.setProperty("user.name", "");
        try {
            OdkScaffold.create(config);
        } finally {
            System.setProperty("user.name", account == null ? "" : account);
        }
        File root = config.getProjectRoot();
        File ontologyDirectory = new File(root, "src/ontology");
        File editFile = config.getEditFile();
        File catalog = new File(ontologyDirectory, "catalog-v001.xml");
        IRI projectIri = IRI.create(BASE_IRI);

        File upstream = new File(ontologyDirectory, "upstream/mini.owl");
        writeMini(upstream);

        OWLOntologyManager editing = OWLManager.createOWLOntologyManager();
        OWLOntology edit = editing.loadOntologyFromOntologyDocument(editFile);
        OWLDataFactory df = editing.getOWLDataFactory();

        // Three classes, as the board makes them: numbered from the first editor's range, the
        // typed name as the label.
        TermMinter minter = TermMinter.forOntologyFile(editFile, "FirstEditor");
        assertTrue(minter.isNumeric(), "the scaffold's ranges were not found");
        List<IRI> classes = new ArrayList<IRI>();
        for (String label : new String[] {"egg", "boiling run", "boiling record"}) {
            IRI iri = minter.mintFor(edit, label);
            editing.applyChanges(minter.declare(edit, iri, EntityFactory.Kind.CLASS, label));
            classes.add(iri);
        }
        IRI dataset = IRI.create(MINI_TERMS + "0000005");
        IRI hasPart = IRI.create(MINI_TERMS + "0000101");
        // The boiling record is a dataset: the edit file uses a term the mini module brings.
        editing.addAxiom(edit, df.getOWLSubClassOfAxiom(df.getOWLClass(classes.get(2)),
                df.getOWLClass(dataset)));

        // The import module "mini", as OdkConversion.modules builds one.
        OWLOntology mini = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(upstream);
        List<IRI> miniTerms = new ArrayList<IRI>(Arrays.asList(dataset, hasPart));
        Collections.sort(miniTerms, (a, b) -> a.toString().compareTo(b.toString()));
        String miniName = TermExtract.shortNameOf(mini);
        assertEquals("mini", miniName);
        IRI miniModule = TermExtract.moduleIriIn(projectIri, mini);
        TermExtract.Result cut = ModuleRebuild.extract(mini, miniTerms,
                TermExtract.DEFAULT_METHOD, miniModule, DATE);
        File miniFile = ImportModules.moduleFileFor(root, miniName);
        saveAsConversionSaves(cut.getModule(), miniFile);
        writeListAsConversionWrites(root, miniName, MINI, mini, miniTerms);
        Catalog.addEntry(catalog, miniModule.toString(), "imports/" + miniFile.getName());
        editing.applyChange(new AddImport(edit, df.getOWLImportsDeclaration(miniModule)));
        File yaml = OdkBuildSettings.yamlIn(ontologyDirectory);
        assertNotNull(yaml, "the scaffold wrote no -odk.yaml");
        String before = text(yaml);
        ImportProducts.Declaration product = ImportProducts.declarationFor(miniName, MINI,
                TermExtract.DEFAULT_METHOD);
        OdkBuildSettings.writeIfUnchanged(yaml, before,
                OdkYaml.appendBlockTo(before, "import_group.products", product.getFields()));

        // The pattern module "measurement", cut and saved as Import terms... saves a library
        // pattern's: imported, with a catalog entry, and not declared.
        DesignPattern pattern = PatternLibrary.find(PATTERN);
        assertNotNull(pattern, PATTERN + " is not in the library");
        OWLOntology patternOntology = PatternLibrary.load(pattern);
        String patternIri = patternOntology.getOntologyID().getOntologyIRI().get().toString();
        List<IRI> measurementTerms = Arrays.asList(
                IRI.create("http://purl.obolibrary.org/obo/OBI_0000070"),
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000109"),
                IRI.create("http://purl.obolibrary.org/obo/BFO_0000019"));
        IRI measurementModule = TermExtract.moduleIriIn(projectIri, patternOntology,
                "measurement");
        TermExtract.Result measured = TermExtract.run(patternOntology, measurementTerms,
                TermExtract.DEFAULT_METHOD, measurementModule);
        assertTrue(measured.getMissing().isEmpty(), "not in the pattern: " + measured.getMissing());
        OWLOntology measurement = measured.getModule();
        measurement.getOWLOntologyManager().applyChanges(
                ImportProvenance.stamp(measurement, patternOntology, DATE));
        NamesAsLabels.labelTheUnlabelled(measurement, edit);
        File measurementFile = ImportModules.moduleFileFor(root, "measurement");
        measurement.getOWLOntologyManager().saveOntology(measurement,
                IRI.create(measurementFile.toURI()));
        ImportModules.writeTerms(root, "measurement", measurementTerms, patternIri, PATTERN);
        Catalog.addEntry(catalog, measurementModule.toString(),
                "imports/" + measurementFile.getName());
        editing.applyChange(new AddImport(edit, df.getOWLImportsDeclaration(measurementModule)));

        // The module "unused": on disk, with its source recorded, and nothing imports it.
        OWLOntology unused = unusedSource();
        List<IRI> unusedTerms = new ArrayList<IRI>();
        for (OWLClass cls : unused.getClassesInSignature()) {
            unusedTerms.add(cls.getIRI());
        }
        Collections.sort(unusedTerms, (a, b) -> a.toString().compareTo(b.toString()));
        String unusedName = TermExtract.shortNameOf(unused);
        assertEquals("unused", unusedName);
        TermExtract.Result spare = ModuleRebuild.extract(unused, unusedTerms,
                TermExtract.DEFAULT_METHOD, TermExtract.moduleIriIn(projectIri, unused), DATE);
        saveAsConversionSaves(spare.getModule(), ImportModules.moduleFileFor(root, unusedName));
        writeListAsConversionWrites(root, unusedName, UNUSED, unused, unusedTerms);

        // Saved, as Protege saves it: in the format it was read in.
        editing.saveOntology(edit, editing.getOntologyFormat(edit),
                IRI.create(editFile.toURI()));

        // A question, as Write a test from a question... writes it.
        String question = "Every dataset has part";
        File test = new File(root, "src/sparql/" + QuestionTests.fileNameFor(question));
        assertEquals("every_dataset_has_part.rq", test.getName());
        Files.write(test.toPath(), QuestionTests.query(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, question, dataset, hasPart, null,
                0).getBytes(StandardCharsets.UTF_8));

        // The project relaxes one rule, as the Makefile tells it to: by changing its level.
        File profile = new File(ontologyDirectory, "profile.txt");
        String rules = text(profile);
        assertTrue(rules.contains("WARN\tmissing_definition\n"), "profile.txt has changed");
        write(profile, rules.replace("WARN\tmissing_definition\n", "INFO\tmissing_definition\n"));

        release(root, ontologyDirectory, editFile, catalog);

        // And two edits of the kind people make to files the scaffold wrote.
        File readme = new File(root, "README.md");
        write(readme, text(readme) + "\nThe kitchen's notes on each boiling run are kept in"
                + " `notes/`, which is not committed.\n");
        File gitignore = new File(root, ".gitignore");
        String ignored = text(gitignore);
        write(gitignore, ignored + (ignored.endsWith("\n") ? "" : "\n") + "notes/\n");
        return root;
    }

    /**
     * Release 1.0.0, as Release... cuts it with its defaults on a project that imports: the
     * imports merged in, the inferences written down by ELK, stamped, and written in the
     * project's export formats under releases/1.0.0 and at the root.
     */
    private static void release(File root, File ontologyDirectory, File editFile, File catalog)
            throws Exception {
        OWLOntologyManager loading = OWLManager.createOWLOntologyManager();
        loading.getIRIMappers().add(new CatalogXmlIRIMapper(catalog));
        OWLOntology edit = loading.loadOntologyFromOntologyDocument(editFile);
        assertEquals(2, edit.getImportsDeclarations().size());
        assertEquals(2, edit.getImports().size(), "an import did not resolve through the catalog");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology release = manager.createOntology(
                new HashSet<OWLAxiom>(edit.getAxioms(Imports.INCLUDED)),
                edit.getOntologyID().getOntologyIRI().get());
        for (OWLAnnotation annotation : edit.getAnnotations()) {
            manager.applyChange(new AddOntologyAnnotation(release, annotation));
        }
        RobotTransform.Diff inferred = RobotTransform.preview(release, RobotTransform.Kind.REASON,
                Reasoners.Choice.ELK.newFactory());
        manager.applyChanges(inferred.getChanges());
        manager.applyChanges(Release.stamp(release, OntologyVersion.of("1.0.0"), null));

        File dated = Release.releaseFile(root, ID, "1.0.0");
        assertTrue(dated.getParentFile().mkdirs());
        File published = new File(root, ID + ".owl");
        ExportFormats.Wanted formats = ExportFormats.wantedBy(ontologyDirectory);
        for (ExportFormats.Format format : formats.getWritable()) {
            manager.saveOntology(release, format.newWriter(),
                    IRI.create(ExportFormats.named(dated, format).toURI()));
            manager.saveOntology(release, format.newWriter(),
                    IRI.create(ExportFormats.named(published, format).toURI()));
        }
    }

    /** {@code OdkConversion.save}: RDF/XML, through the canonical writer. */
    private static void saveAsConversionSaves(OWLOntology module, File file) throws Exception {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory()) {
            assertTrue(parent.mkdirs());
        }
        OWLDocumentFormat format = module.getOWLOntologyManager().getOntologyFormat(module);
        OdkConversion.writeCanonically(module,
                format instanceof RDFXMLDocumentFormat ? format : new RDFXMLDocumentFormat(), file);
    }

    /**
     * {@code OdkConversion.modules}' term list: written with its source and no terms, then each
     * term appended with its label as a comment.
     */
    private static void writeListAsConversionWrites(File root, String name, String source,
            OWLOntology from, List<IRI> terms) throws IOException {
        ImportModules.writeTerms(root, name, new ArrayList<IRI>(), source);
        File list = ImportModules.termsFileFor(root, name);
        Map<IRI, String> labels = new LinkedHashMap<IRI, String>();
        for (IRI term : terms) {
            String label = null;
            for (OWLEntity entity : from.getEntitiesInSignature(term, Imports.INCLUDED)) {
                label = DisplayLabels.forEntity(from, entity);
            }
            labels.put(term, label);
        }
        StringBuilder written = new StringBuilder(text(list));
        for (Map.Entry<IRI, String> term : labels.entrySet()) {
            written.append(term.getKey()).append(term.getValue() == null
                    || term.getValue().equals(term.getKey().toString()) ? ""
                            : " # " + term.getValue()).append('\n');
        }
        write(list, written.toString());
    }

    /**
     * {@code upstream/mini.owl}: ten classes with labels and definitions, and the one property the
     * question asks about. A dataset has some datum as a part, so the question about datasets is
     * answered by the module that carries the dataset.
     */
    private static void writeMini(File file) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology mini = manager.createOntology(IRI.create(MINI));
        manager.addAxiom(mini, df.getOWLDeclarationAxiom(df.getOWLAnnotationProperty(DEFINITION)));
        manager.addAxiom(mini, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), DEFINITION,
                df.getOWLLiteral("definition", "en")));
        String[][] classes = {
            {"0000001", "entity", "Anything that exists or has existed.", null},
            {"0000002", "material entity", "An entity that has some portion of matter as a part.",
                "0000001"},
            {"0000003", "process", "An entity that happens, unfolding in time.", "0000001"},
            {"0000004", "information content entity", "An entity that is about something else.",
                "0000001"},
            {"0000005", "dataset", "An information content entity that collects data.",
                "0000004"},
            {"0000006", "datum", "An information content entity that records one value.",
                "0000004"},
            {"0000007", "device", "A material entity made to perform a function.", "0000002"},
            {"0000008", "measurement", "A process that produces a datum about a quality.",
                "0000003"},
            {"0000009", "temperature", "How hot or cold something is.", "0000001"},
            {"0000010", "duration", "How long a process lasts.", "0000001"}};
        for (String[] each : classes) {
            IRI iri = IRI.create(MINI_TERMS + each[0]);
            manager.addAxiom(mini, df.getOWLDeclarationAxiom(df.getOWLClass(iri)));
            manager.addAxiom(mini, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                    df.getOWLLiteral(each[1], "en")));
            manager.addAxiom(mini, df.getOWLAnnotationAssertionAxiom(
                    df.getOWLAnnotationProperty(DEFINITION), iri, df.getOWLLiteral(each[2], "en")));
            if (each[3] != null) {
                manager.addAxiom(mini, df.getOWLSubClassOfAxiom(df.getOWLClass(iri),
                        df.getOWLClass(IRI.create(MINI_TERMS + each[3]))));
            }
        }
        IRI hasPart = IRI.create(MINI_TERMS + "0000101");
        manager.addAxiom(mini, df.getOWLDeclarationAxiom(df.getOWLObjectProperty(hasPart)));
        manager.addAxiom(mini, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), hasPart,
                df.getOWLLiteral("has part", "en")));
        manager.addAxiom(mini, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(DEFINITION), hasPart,
                df.getOWLLiteral("Relates a whole to one of its parts.", "en")));
        manager.addAxiom(mini, df.getOWLSubClassOfAxiom(
                df.getOWLClass(IRI.create(MINI_TERMS + "0000005")),
                df.getOWLObjectSomeValuesFrom(df.getOWLObjectProperty(hasPart),
                        df.getOWLClass(IRI.create(MINI_TERMS + "0000006")))));
        assertTrue(file.getParentFile().mkdirs());
        OdkConversion.writeCanonically(mini, new RDFXMLDocumentFormat(), file);
    }

    /** What the module nothing imports was cut from: two labelled classes, read from nowhere. */
    private static OWLOntology unusedSource() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology unused = manager.createOntology(IRI.create(UNUSED));
        String[][] classes = {{"UNUSED_0000001", "stove"}, {"UNUSED_0000002", "pan"}};
        for (String[] each : classes) {
            IRI iri = IRI.create("https://example.org/unused/" + each[0]);
            manager.addAxiom(unused, df.getOWLDeclarationAxiom(df.getOWLClass(iri)));
            manager.addAxiom(unused, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                    df.getOWLLiteral(each[1], "en")));
        }
        return unused;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(from)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            Path copy = to.resolve(from.relativize(file).toString());
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
        }
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
