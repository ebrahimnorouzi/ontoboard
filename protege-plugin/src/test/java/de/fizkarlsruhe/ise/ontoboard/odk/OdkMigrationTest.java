package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A project OntoBoard 1.135.x made becomes an ODK project through <em>Update project
 * files...</em>: its configuration is turned into one ODK reads, its test queries into ODK
 * checks, the modules its edit file imports are declared - and then ODK's update writes the
 * rest. Nothing of the person's own is lost or rewritten on the way.
 *
 * <p>On the project 1.135.2 wrote while its code still existed
 * ({@code src/test/resources/odk/migration/eggboil-1.135.2}, see its README), copied for each
 * test.
 */
class OdkMigrationTest {

    private static final String CONFIG = "src/ontology/eggboil-odk.yaml";
    private static final String EDIT = "src/ontology/eggboil-edit.owl";
    private static final String CATALOG = "src/ontology/catalog-v001.xml";
    private static final String MODULES = "https://example.org/eggboil/eggboil/imports/";

    @TempDir
    File temp;

    private File root;

    @BeforeEach
    void aCopyOfTheProject1352Made() throws IOException {
        root = new File(temp, "eggboil");
        copy(MigrationFixtureTest.fixture("odk/migration/eggboil-1.135.2"), root);
    }

    // ------------------------------------------------------------------ helpers

    static void copy(File from, File into) throws IOException {
        final Path source = from.toPath();
        final Path target = into.toPath();
        try (Stream<Path> all = Files.walk(source)) {
            for (Path one : (Iterable<Path>) all::iterator) {
                Path there = target.resolve(source.relativize(one).toString());
                if (Files.isDirectory(one)) {
                    Files.createDirectories(there);
                } else {
                    Files.createDirectories(there.getParent());
                    Files.copy(one, there);
                }
            }
        }
    }

    /** Every file under a folder: its path with {@code /}, and its bytes. */
    static Map<String, byte[]> tree(File folder) throws IOException {
        Map<String, byte[]> files = new TreeMap<String, byte[]>();
        final Path base = folder.toPath();
        try (Stream<Path> all = Files.walk(base)) {
            for (Path one : (Iterable<Path>) all::iterator) {
                if (Files.isRegularFile(one)) {
                    files.put(base.relativize(one).toString().replace('\\', '/'),
                            Files.readAllBytes(one));
                }
            }
        }
        return files;
    }

    /** The paths whose bytes differ between two trees, or that only one of them has. */
    static Set<String> differing(Map<String, byte[]> before, Map<String, byte[]> after) {
        Set<String> paths = new TreeSet<String>();
        Set<String> all = new TreeSet<String>(before.keySet());
        all.addAll(after.keySet());
        for (String path : all) {
            if (!before.containsKey(path) || !after.containsKey(path)
                    || !Arrays.equals(before.get(path), after.get(path))) {
                paths.add(path);
            }
        }
        return paths;
    }

    private File in(String path) {
        return new File(root, path.replace('/', File.separatorChar));
    }

    private String text(String path) throws IOException {
        return new String(Files.readAllBytes(in(path).toPath()), StandardCharsets.UTF_8);
    }

    private void write(String path, String text) throws IOException {
        Files.createDirectories(in(path).getParentFile().toPath());
        Files.write(in(path).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private void replace(String path, String old, String now) throws IOException {
        String before = text(path);
        assertTrue(before.contains(old), path + " holds " + old);
        write(path, before.replace(old, now));
    }

    /** The ontology a file holds, by itself: its imports are named and not fetched. */
    private static OWLOntology byItself(File file) throws Exception {
        OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
        final File nowhere = new File(file.getAbsoluteFile().getParentFile(), ".no-such-import");
        reader.getIRIMappers().add(iri -> IRI.create(nowhere));
        reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return reader.loadOntologyFromOntologyDocument(new FileDocumentSource(file));
    }

    private static Set<String> imported(OWLOntology ontology) {
        Set<String> imports = new TreeSet<String>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            imports.add(declaration.getIRI().toString());
        }
        return imports;
    }

    private static ImportProducts.Product product(String config, String id) {
        return ImportProducts.named(ImportProducts.declaredIn(config), id);
    }

    /** The conversion, and then ODK's update as OntoBoard itself makes it. */
    private void migrate() throws IOException {
        OdkMigration.convert(root, OdkMigration.plan(root));
        OdkUpdate.apply(root, OdkUpdate.plan(root));
    }

    // ------------------------------------------------------------------ which projects

    @Test
    void aProjectOfTheOlderLayoutIsKnownByItsMakefile() throws IOException {
        assertTrue(OdkMigration.isOntoBoards1x(root));

        File seed = new File(temp, "seed");
        copy(MigrationFixtureTest.fixture("odk/v1.6.1/seed/eggboil"), seed);
        assertFalse(OdkMigration.isOntoBoards1x(seed), "ODK's own Makefile");
        assertFalse(OdkMigration.isOntoBoards1x(new File(temp, "nothing-here")));
    }

    // ------------------------------------------------------------------ the configuration

    /**
     * OntoBoard's header for ODK; {@code robot_version} gone, which ODK does not read;
     * {@code repo} as a new project has it; the report with the project's own profile and
     * the scope it had; the pattern's module declared as kept as it is. Everything else as
     * it was, line for line - and no list of checks: ODK then runs its own four.
     */
    @Test
    void theConfigBecomesThis() throws IOException {
        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(CONFIG, plan.configPath());
        assertEquals(new String(Files.readAllBytes(MigrationFixtureTest.fixture(
                "odk/migration/expected/eggboil-odk.yaml").toPath()), StandardCharsets.UTF_8),
                plan.newConfig());
        assertTrue(plan.changesTheConfig());
    }

    @Test
    void lookingWritesNothing() throws IOException {
        Map<String, byte[]> before = tree(root);

        OdkMigration.plan(root);

        assertEquals(new TreeSet<String>(), differing(before, tree(root)));
    }

    /** What a person wrote above the keys, and a key of their own, stay. */
    @Test
    void whatSomebodyAddedToTheConfigurationStays() throws IOException {
        replace(CONFIG, "id: eggboil\n", "# Ask Ada before changing the licence.\nid: eggboil\n");
        replace(CONFIG, "primary_release: full\n", "primary_release: full\ngit_main_branch: trunk\n");

        String config = OdkMigration.plan(root).newConfig();

        assertTrue(config.startsWith("# ODK configuration for Test kitchen ontology, written by "
                + "OntoBoard for ODK v1.6.1.\n# After changing it, run "), config);
        assertTrue(config.contains("update_repo).\n# Ask Ada before changing the licence.\n"
                + "id: eggboil\n"), config);
        assertTrue(config.contains("primary_release: full\ngit_main_branch: trunk\n"), config);
        assertFalse(config.contains("calls robot directly"), "the old header is gone: " + config);
        assertFalse(config.contains("robot_version"), config);
    }

    /**
     * The older layout's Makefile gave its report the project's own two base addresses,
     * so that it reports on the project's terms and not on those of other ontologies the
     * edit file holds. ODK's Makefile does the same where the configuration says
     * use_base_iris - and without it reports on every term: a project that passed its
     * report would fail it on the day it was carried over.
     */
    @Test
    void theReportKeepsItsScope() throws IOException {
        assertTrue(text("src/ontology/Makefile").contains(
                "--base-iri $(URIBASE)/EGGBOIL_ --base-iri $(URIBASE)/$(ONT)"),
                "what 1.135.2 gave the report");

        String config = OdkMigration.plan(root).newConfig();

        assertTrue(config.contains("  custom_profile: TRUE\n  use_base_iris: TRUE\n"), config);
        migrate();
        assertTrue(text("src/ontology/Makefile").contains(
                "--base-iri $(URIBASE)/EGGBOIL_ --base-iri $(URIBASE)/eggboil "),
                "and what ODK's Makefile gives it now");
    }

    /** A Makefile that gave the report no base addresses asks for none. */
    @Test
    void aReportThatHadNoBaseAddressesGetsNone() throws IOException {
        replace("src/ontology/Makefile",
                "\t  --base-iri $(URIBASE)/EGGBOIL_ --base-iri $(URIBASE)/$(ONT) \\\n", "");

        assertFalse(OdkMigration.plan(root).newConfig().contains("use_base_iris"));
    }

    /** What the configuration says of it already is the person's, and stays. */
    @Test
    void aSettingOfTheirOwnForTheReportsScopeStays() throws IOException {
        replace(CONFIG, "  fail_on: ERROR\n", "  fail_on: ERROR\n  use_base_iris: FALSE\n");

        String config = OdkMigration.plan(root).newConfig();

        assertTrue(config.contains("  use_base_iris: FALSE\n"), config);
        assertFalse(config.contains("use_base_iris: TRUE"), config);
    }

    // ------------------------------------------------------------------ the queries

    private static final String OWN = "src/ontology/eggboil.Makefile";

    /**
     * The older layout asked every {@code .rq} in src/sparql of the ontology with its imports
     * merged. ODK's own checks read the edit file without its imports, where a query about
     * an imported term changes its answer - the project's "every dataset has part" passes
     * with its import and names two terms without it. So the queries stay what and where
     * they are, and the rule that asks them as they were asked is added to the project's own
     * makefile.
     */
    @Test
    void theirQueriesStayAndGetTheRuleThatAsksThem() throws IOException {
        byte[] question = Files.readAllBytes(in("src/sparql/every_dataset_has_part.rq").toPath());
        byte[] labels = Files.readAllBytes(in("src/sparql/check_labels.rq").toPath());
        String own = text(OWN);

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(Arrays.asList("src/sparql/check_labels.rq",
                "src/sparql/every_dataset_has_part.rq"), plan.questions());
        assertEquals(OWN, plan.ownMakefilePath());
        assertTrue(plan.addsTheQuestionRule());
        assertEquals(new ArrayList<String>(), plan.warnings());

        OdkMigration.convert(root, plan);

        assertArrayEquals(question, Files.readAllBytes(in("src/sparql/every_dataset_has_part.rq")
                .toPath()));
        assertArrayEquals(labels, Files.readAllBytes(in("src/sparql/check_labels.rq").toPath()));
        assertEquals(OdkQuestions.with(own), text(OWN));
        assertTrue(text(OWN).startsWith(own.replaceAll("\n+$", "")),
                "what was in their own makefile stays: " + text(OWN));
        assertTrue(OdkQuestions.isKeptIn(root));
        assertFalse(plan.newConfig().contains("custom_sparql_checks"),
                "none of them is made an ODK check: " + plan.newConfig());
    }

    /** A rule that is there as written is not written again. */
    @Test
    void aRuleThatIsThereAlreadyIsLeft() throws IOException {
        write(OWN, OdkQuestions.with(text(OWN)));

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertFalse(plan.addsTheQuestionRule());
        assertEquals(Arrays.asList(in(CONFIG).getAbsoluteFile()),
                OdkMigration.convert(root, plan));
    }

    /** A project without a query gets no rule: there is nothing to ask. */
    @Test
    void aProjectWithoutQueriesGetsNoRule() throws IOException {
        Files.delete(in("src/sparql/check_labels.rq").toPath());
        Files.delete(in("src/sparql/every_dataset_has_part.rq").toPath());
        String own = text(OWN);

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(new ArrayList<String>(), plan.questions());
        assertFalse(plan.addsTheQuestionRule());
        OdkMigration.convert(root, plan);
        assertEquals(own, text(OWN));
    }

    /** A project whose own makefile is gone gets one with the rule. */
    @Test
    void aProjectWithoutItsOwnMakefileGetsOneForTheRule() throws IOException {
        Files.delete(in(OWN).toPath());

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertTrue(plan.addsTheQuestionRule());
        OdkMigration.convert(root, plan);
        assertEquals(OdkQuestions.rule(), text(OWN));
    }

    /**
     * make reads a file name as words: a query whose name has a blank would stop the rule
     * that names every query. Said, as something to see to; the file is not renamed for them.
     */
    @Test
    void aQueryWhoseNameMakeCannotReadIsWarnedAbout() throws IOException {
        write("src/sparql/My Query.rq", "SELECT ?x WHERE { ?x a ?y } LIMIT 0\n");

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(3, plan.questions().size());
        assertEquals(1, plan.warnings().size(), plan.warnings().toString());
        assertTrue(plan.warnings().get(0).contains("src/sparql/My Query.rq"),
                plan.warnings().get(0));
        assertTrue(plan.warnings().get(0).contains("rename"), plan.warnings().get(0));
        assertTrue(in("src/sparql/My Query.rq").isFile());
    }

    /**
     * The two files of a conversion are written or neither: their own makefile is looked at
     * again before the configuration is written.
     */
    @Test
    void aMakefileOfTheirsThatChangedSinceTheLookStopsTheConversionBeforeItWrites()
            throws IOException {
        OdkMigration.Plan plan = OdkMigration.plan(root);
        String config = text(CONFIG);
        write(OWN, text(OWN) + "mine:\n\techo mine\n");
        String own = text(OWN);

        try {
            OdkMigration.convert(root, plan);
            org.junit.jupiter.api.Assertions.fail("written although the file had changed");
        } catch (IOException changed) {
            assertTrue(changed.getMessage().contains("eggboil.Makefile"), changed.getMessage());
        }
        assertEquals(config, text(CONFIG));
        assertEquals(own, text(OWN));
    }

    // ------------------------------------------------------------------ the modules

    @Test
    void aPatternsModuleIsDeclaredAsKeptAsItIs() throws IOException {
        OdkMigration.Plan plan = OdkMigration.plan(root);

        ImportProducts.Product measurement = product(plan.newConfig(), "measurement");
        assertNotNull(measurement);
        assertEquals("custom", measurement.getModuleType());
        assertFalse(measurement.isMirrored());
        assertEquals(1, plan.declared().size(), plan.declared().toString());
        assertTrue(plan.declared().get(0).startsWith("measurement"), plan.declared().get(0));
        assertTrue(plan.declared().get(0).contains("pattern"), plan.declared().get(0));
    }

    /** What was declared stays as it was declared. */
    @Test
    void aDeclaredProductIsNotTouched() throws IOException {
        ImportProducts.Product mini = product(OdkMigration.plan(root).newConfig(), "mini");

        assertEquals("https://example.org/mini.owl", mini.getMirrorFrom());
        assertEquals("slme", mini.getModuleType());
        assertEquals("BOT", mini.getSlmeMethod());
    }

    /** Imported, not declared, and its term list says where it is from: ODK builds it. */
    @Test
    void aModuleWithARecordedSourceIsDeclaredForOdkToBuild() throws IOException {
        replace(CONFIG, "  products:\n    - id: mini\n      mirror_from: https://example.org/"
                + "mini.owl\n      module_type: slme\n      module_type_slme: BOT\n",
                "  products: []\n");

        OdkMigration.Plan plan = OdkMigration.plan(root);

        ImportProducts.Product mini = product(plan.newConfig(), "mini");
        assertNotNull(mini, plan.newConfig());
        assertEquals("https://example.org/mini.owl", mini.getMirrorFrom());
        assertEquals("slme", mini.getModuleType());
        assertEquals("BOT", mini.getSlmeMethod());
        assertNotNull(product(plan.newConfig(), "measurement"));
        assertEquals(2, plan.declared().size(), plan.declared().toString());
    }

    /** Imported, not declared, and nothing says where it is from: kept as it is. */
    @Test
    void aModuleWithNoRecordIsDeclaredAsKeptAsItIs() throws IOException {
        replace(CONFIG, "  products:\n    - id: mini\n      mirror_from: https://example.org/"
                + "mini.owl\n      module_type: slme\n      module_type_slme: BOT\n",
                "  products: []\n");
        replace("src/ontology/imports/mini_terms.txt", "# Source: https://example.org/mini.owl\n",
                "");

        OdkMigration.Plan plan = OdkMigration.plan(root);

        ImportProducts.Product mini = product(plan.newConfig(), "mini");
        assertEquals("custom", mini.getModuleType());
        assertFalse(mini.isMirrored());
        assertNull(mini.getMirrorFrom());
        assertTrue(String.join("\n", plan.declared()).contains("no source is recorded"),
                plan.declared().toString());
    }

    @Test
    void theUnusedModuleIsLeftAloneAndSaidSo() throws IOException {
        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(Arrays.asList("src/ontology/imports/unused_import.owl"), plan.leftAlone());
        assertNull(product(plan.newConfig(), "unused"));
    }

    // ------------------------------------------------------------------ the conversion

    @Test
    void theConversionWritesTheConfigurationAndTheRuleAndNothingElse() throws IOException {
        Map<String, byte[]> before = tree(root);
        OdkMigration.Plan plan = OdkMigration.plan(root);

        List<File> written = OdkMigration.convert(root, plan);

        assertEquals(new TreeSet<String>(Arrays.asList(CONFIG, OWN)),
                differing(before, tree(root)));
        assertEquals(plan.newConfig(), text(CONFIG));
        assertEquals(Arrays.asList(in(CONFIG).getAbsoluteFile(), in(OWN).getAbsoluteFile()),
                written);
    }

    /**
     * Stopped between the conversion and ODK's update - a container that did not start, a
     * wait given up: the next try has nothing to convert and goes on with the update.
     */
    @Test
    void aMigrationThatStoppedAfterTheConversionGoesOn() throws IOException {
        OdkMigration.convert(root, OdkMigration.plan(root));
        assertTrue(OdkMigration.isOntoBoards1x(root), "the Makefile is still OntoBoard's");
        Map<String, byte[]> converted = tree(root);

        OdkMigration.Plan again = OdkMigration.plan(root);

        assertFalse(again.changesTheConfig());
        assertEquals(text(CONFIG), again.newConfig());
        assertFalse(again.addsTheQuestionRule());
        assertEquals(new ArrayList<String>(), again.declared());
        assertEquals(new ArrayList<File>(), OdkMigration.convert(root, again));
        assertEquals(new TreeSet<String>(), differing(converted, tree(root)));
    }

    // ------------------------------------------------------------------ after ODK's update

    @Test
    void theRelaxedProfileIsKeptByteForByte() throws IOException {
        byte[] profile = Files.readAllBytes(in("src/ontology/profile.txt").toPath());
        assertTrue(new String(profile, StandardCharsets.UTF_8).contains("INFO\tmissing_definition"),
                "relaxed by hand in the fixture");

        migrate();

        assertArrayEquals(profile, Files.readAllBytes(in("src/ontology/profile.txt").toPath()));
    }

    @Test
    void theirOwnFilesAreNotTouched() throws IOException {
        Map<String, byte[]> before = tree(root);

        migrate();

        Map<String, byte[]> after = tree(root);
        for (String path : new String[] {"README.md", "mkdocs.yaml", "docs/index.md",
                "docs/editing.md", "docs/release.md", ".gitattributes", "eggboil.owl",
                "releases/1.0.0/eggboil.owl", "src/sparql/check_labels.rq",
                "src/sparql/every_dataset_has_part.rq",
                "src/ontology/eggboil-idranges.owl", "src/ontology/profile.txt",
                "src/ontology/upstream/mini.owl", "src/ontology/imports/mini_import.owl",
                "src/ontology/imports/mini_terms.txt",
                "src/ontology/imports/measurement_import.owl",
                "src/ontology/imports/measurement_terms.txt",
                "src/ontology/imports/unused_import.owl",
                "src/ontology/imports/unused_terms.txt"}) {
            assertArrayEquals(before.get(path), after.get(path), path);
        }
        String own = new String(after.get(OWN), StandardCharsets.UTF_8);
        assertEquals(OdkQuestions.with(new String(before.get(OWN), StandardCharsets.UTF_8)),
                own, "their own makefile, with the rule for their queries at its end");
        String ignore = new String(after.get(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(ignore.contains("\nnotes/\n"), "their own line: " + ignore);
        assertTrue(ignore.contains("src/ontology/mirror/"), "their rules are kept: " + ignore);
    }

    @Test
    void theEditFileKeepsEveryAxiomAndImportsItsTwoModules() throws Exception {
        OWLOntology before = byItself(in(EDIT));

        migrate();

        OWLOntology after = byItself(in(EDIT));
        assertTrue(after.getAxioms().containsAll(before.getAxioms()), "every axiom is kept");
        Set<org.semanticweb.owlapi.model.OWLAxiom> added =
                new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>(after.getAxioms());
        added.removeAll(before.getAxioms());
        // ODK's OWL API (4.5.29) declares in the edit file a datatype that only its imports
        // use, as ODK's own update does (theResultIsWhatOdksUpdateGives); the older one adds
        // nothing at all.
        assertEquals(new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>(),
                RunComparisons.onOdksOwlApi() ? withoutDatatypeDeclarations(added) : added,
                "and none is added");
        assertEquals(before.getAnnotations(), after.getAnnotations());
        assertEquals(before.getOntologyID(), after.getOntologyID());
        assertEquals(new TreeSet<String>(Arrays.asList(MODULES + "measurement_import.owl",
                MODULES + "mini_import.owl")), imported(after));
        assertTrue(text(EDIT).startsWith("Prefix("), "in functional syntax, as ODK keeps it");
    }

    @Test
    void afterItTheProjectIsAnOdkProjectAndASecondUpdateChangesNothing() throws IOException {
        migrate();

        assertFalse(OdkMigration.isOntoBoards1x(root));
        Map<String, byte[]> migrated = tree(root);
        OdkUpdate.Plan second = OdkUpdate.plan(root);
        for (OdkUpdate.Change change : second.changes()) {
            assertFalse(change.isChanged(), change.path());
        }
        assertFalse(OdkUpdate.check(root, second), "the edit file is as the update leaves it");
        assertEquals(new ArrayList<ImportGuard.Undeclared>(), second.undeclared());
        assertEquals(new TreeSet<String>(), differing(migrated, tree(root)));
    }

    /**
     * The whole of it, held against ODK itself. ODK's own update was run in ODK's image on the
     * project as the conversion leaves it ({@code migration/expected}, recorded by
     * {@code generate.py migration}); here the conversion is made again - it leaves the very
     * files the recording started from - and then OntoBoard's own update: every file is what
     * ODK's update left, byte for byte, and the edit file the same ontology, in functional
     * syntax.
     */
    @Test
    void theResultIsWhatOdksUpdateGives() throws Exception {
        File expected = MigrationFixtureTest.fixture("odk/migration/expected");
        OdkMigration.convert(root, OdkMigration.plan(root));

        Map<String, byte[]> converted = tree(root);
        Map<String, String> recorded = new TreeMap<String, String>();
        for (String line : new String(Files.readAllBytes(new File(expected, "converted.sha256")
                .toPath()), StandardCharsets.UTF_8).split("\n")) {
            if (!line.trim().isEmpty()) {
                recorded.put(line.substring(line.indexOf("  ") + 2).trim(),
                        line.substring(0, line.indexOf("  ")));
            }
        }
        assertEquals(recorded.keySet(), converted.keySet(), "the files ODK's update started from");
        for (Map.Entry<String, byte[]> file : converted.entrySet()) {
            assertEquals(recorded.get(file.getKey()), sha256(file.getValue()), file.getKey());
        }

        OdkUpdate.apply(root, OdkUpdate.plan(root));

        Map<String, byte[]> theirs = new TreeMap<String, byte[]>(converted);
        theirs.putAll(tree(new File(expected, "after/files")));
        for (String gone : new String(Files.readAllBytes(new File(expected, "after/gone.txt")
                .toPath()), StandardCharsets.UTF_8).split("\n")) {
            if (!gone.trim().isEmpty()) {
                theirs.remove(gone.trim());
            }
        }
        Map<String, byte[]> ours = tree(root);
        assertEquals(theirs.keySet(), ours.keySet(), "the files of the project");
        for (Map.Entry<String, byte[]> file : theirs.entrySet()) {
            if (file.getKey().equals(EDIT)) {
                File odks = new File(temp, "odks-edit.owl");
                Files.write(odks.toPath(), file.getValue());
                OWLOntology one = byItself(odks);
                OWLOntology other = byItself(in(EDIT));
                assertEquals(one.getOntologyID(), other.getOntologyID());
                assertEquals(imported(one), imported(other));
                assertSame(one.getAnnotations(), other.getAnnotations(), "the ontology's "
                        + "own annotations");
                // ODK's OWL API (4.5.29) declares in the edit file a datatype that only its
                // imports use - xsd:date, of the day a module's stamp names. The older one,
                // which Protege 5.5.0 has and this runs on by default, does not. Nothing
                // else may differ, and on ODK's own (-Dowlapi.version=4.5.29) nothing does.
                assertSame(RunComparisons.onOdksOwlApi() ? one.getAxioms()
                        : withoutDatatypeDeclarations(one.getAxioms()),
                        RunComparisons.onOdksOwlApi() ? other.getAxioms()
                                : withoutDatatypeDeclarations(other.getAxioms()),
                        "the edit file's axioms");
                assertTrue(new String(file.getValue(), StandardCharsets.UTF_8)
                        .startsWith("Prefix("), "ODK's is in functional syntax");
                assertTrue(text(EDIT).startsWith("Prefix("), "and so is OntoBoard's");
            } else {
                assertEquals(new String(file.getValue(), StandardCharsets.UTF_8),
                        new String(ours.get(file.getKey()), StandardCharsets.UTF_8),
                        file.getKey());
                assertArrayEquals(file.getValue(), ours.get(file.getKey()), file.getKey());
            }
        }
        assertEquals("identical\n", new String(Files.readAllBytes(new File(expected,
                "second-run.txt").toPath()), StandardCharsets.UTF_8),
                "ODK's own second update changed nothing either");
    }

    private static Set<org.semanticweb.owlapi.model.OWLAxiom> withoutDatatypeDeclarations(
            Set<org.semanticweb.owlapi.model.OWLAxiom> axioms) {
        Set<org.semanticweb.owlapi.model.OWLAxiom> kept =
                new java.util.HashSet<org.semanticweb.owlapi.model.OWLAxiom>();
        for (org.semanticweb.owlapi.model.OWLAxiom axiom : axioms) {
            if (!(axiom instanceof org.semanticweb.owlapi.model.OWLDeclarationAxiom
                    && ((org.semanticweb.owlapi.model.OWLDeclarationAxiom) axiom).getEntity()
                            .isOWLDatatype())) {
                kept.add(axiom);
            }
        }
        return kept;
    }

    /** Two sets the same; where they are not, what only one of them holds is said. */
    private static void assertSame(Set<?> odks, Set<?> ours, String what) {
        Set<String> onlyOdks = new TreeSet<String>();
        for (Object one : odks) {
            if (!ours.contains(one)) {
                onlyOdks.add(one.toString());
            }
        }
        Set<String> onlyOurs = new TreeSet<String>();
        for (Object one : ours) {
            if (!odks.contains(one)) {
                onlyOurs.add(one.toString());
            }
        }
        assertTrue(onlyOdks.isEmpty() && onlyOurs.isEmpty(), what + " - only in ODK's: "
                + onlyOdks + "; only in OntoBoard's: " + onlyOurs);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------ other addresses

    /**
     * 1.135.x let an ontology's address end in any name. Its modules and releases were named
     * under that address without {@code .owl}; ODK names them under
     * {@code <uribase>/<uribase_suffix>}, so the suffix keeps them where they are. What ODK
     * calls the file it releases is said: it is not the address the ontology has.
     */
    @Test
    void anOntologyWhoseAddressIsNotTheIdsKeepsItsNamespace() throws IOException {
        replace(EDIT, "https://example.org/eggboil/eggboil", "https://example.org/kitchen/egg");
        replace(CATALOG, "https://example.org/eggboil/eggboil", "https://example.org/kitchen/egg");
        replace(CONFIG, "uribase: https://example.org/eggboil\n",
                "uribase: https://example.org/kitchen\n");

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertTrue(plan.newConfig().contains("uribase: https://example.org/kitchen\n"
                + "uribase_suffix: egg\n"), plan.newConfig());
        assertEquals(new ArrayList<ImportGuard.Undeclared>(), plan.update().undeclared());
        assertEquals(Arrays.asList("https://example.org/kitchen/egg/imports/measurement_import.owl",
                "https://example.org/kitchen/egg/imports/mini_import.owl"),
                new ArrayList<String>(new TreeSet<String>(plan.update().imports())));
        assertEquals(1, plan.warnings().size(), plan.warnings().toString());
        String warned = plan.warnings().get(0);
        assertTrue(warned.contains("https://example.org/kitchen/egg.owl"), warned);
        assertTrue(warned.contains("https://example.org/kitchen/eggboil.owl"), warned);
    }

    /** The usual address needs neither a suffix nor a remark. */
    @Test
    void theUsualAddressNeedsNoSuffixAndNoRemark() throws IOException {
        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertFalse(plan.newConfig().contains("uribase_suffix"), plan.newConfig());
        assertEquals(new ArrayList<String>(), plan.warnings());
    }

    /**
     * An import the catalog serves from the module file of a declared product is that module,
     * whatever address the edit file gives it: nothing to ask about, and ODK's update writes
     * ODK's address for it.
     */
    @Test
    void anImportUnderAnotherAddressThanOdksIsStillThatModule() throws Exception {
        replace(EDIT, MODULES + "mini_import.owl", "https://elsewhere.example/modules/mini.owl");
        replace(CATALOG, MODULES + "mini_import.owl",
                "https://elsewhere.example/modules/mini.owl");

        OdkMigration.Plan plan = OdkMigration.plan(root);

        assertEquals(new ArrayList<ImportGuard.Undeclared>(), plan.update().undeclared());
        assertEquals(Arrays.asList("https://elsewhere.example/modules/mini.owl"),
                plan.update().sameModules());
        assertNotNull(product(plan.newConfig(), "mini"));

        migrate();
        assertEquals(new TreeSet<String>(Arrays.asList(MODULES + "measurement_import.owl",
                MODULES + "mini_import.owl")), imported(byItself(in(EDIT))));
    }
}
