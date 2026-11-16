package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * An ontology with its history, turned into an ODK repository: ODK's own seed with the ontology
 * in it, its earlier versions as releases and tags, its imports as modules ODK knows, ODK's
 * test run on it, and the current version released as ODK releases one - and the same again,
 * to show the repository is reproduced.
 *
 * <p>No container here: ODK's seed is rendered from its templates and its targets are run by
 * OntoBoard's own engine, which other tests hold against ODK's container.
 */
class OdkConversionTest {

    private static final String TURTLE_HEAD = "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
            + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
            + "@prefix dcterms: <http://purl.org/dc/terms/> .\n";

    /**
     * The ontology that is imported, in RDF/XML: the OWL API lets go of a file in that syntax
     * once it is read, and keeps one in any other open until the garbage collector comes by -
     * which on Windows keeps the test's folder from being removed.
     */
    private static File upper(File folder) throws Exception {
        File upper = new File(folder, "upper.owl");
        Files.write(upper.toPath(), ("<?xml version=\"1.0\"?>\n"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n"
                + "     xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"\n"
                + "     xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                + "  <owl:Ontology rdf:about=\"http://example.org/upper\"/>\n"
                + "  <owl:Class rdf:about=\"http://example.org/upper#Entity\">\n"
                + "    <rdfs:label>entity</rdfs:label>\n"
                + "  </owl:Class>\n"
                + "  <owl:Class rdf:about=\"http://example.org/upper#Object\">\n"
                + "    <rdfs:label>object</rdfs:label>\n"
                + "    <rdfs:subClassOf rdf:resource=\"http://example.org/upper#Entity\"/>\n"
                + "  </owl:Class>\n"
                + "  <owl:Class rdf:about=\"http://example.org/upper#Process\">\n"
                + "    <rdfs:label>process</rdfs:label>\n"
                + "  </owl:Class>\n"
                + "</rdf:RDF>\n").getBytes(StandardCharsets.UTF_8));
        return upper;
    }

    /** An upper ontology to import, the current version, and two earlier ones. */
    private static String[] inputs(File folder) throws Exception {
        File upper = upper(folder);
        File current = new File(folder, "shop.ttl");
        Files.write(current.toPath(), (TURTLE_HEAD
                + "<https://example.org/shop.owl> a owl:Ontology ; rdfs:label \"Shop\" ;\n"
                + "  owl:versionInfo \"1.0.0\" ; owl:imports <" + upper.toURI() + "> .\n"
                + "<https://example.org/shop#Pizza> a owl:Class ; rdfs:label \"pizza\" ;\n"
                + "  rdfs:subClassOf <http://example.org/upper#Object> .\n"
                + "<https://example.org/shop#Base> a owl:Class ; rdfs:label \"base\" ;\n"
                + "  rdfs:subClassOf <http://example.org/upper#Object> .\n")
                .getBytes(StandardCharsets.UTF_8));
        String first = earlier(folder, "0.1.0", "2026-01-15", new RDFXMLDocumentFormat(),
                "shop-0.1.0.owl");
        String second = earlier(folder, "0.2.0", "2026-03-01", new OWLXMLDocumentFormat(),
                "shop-0.2.0.owx");
        return new String[] {current.getAbsolutePath(), second, first};
    }

    private static String earlier(File folder, String version, String date,
            org.semanticweb.owlapi.model.OWLDocumentFormat format, String name) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("https://example.org/shop.owl"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getOWLVersionInfo(), df.getOWLLiteral(version))));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(o,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(
                        IRI.create("http://purl.org/dc/terms/date")), df.getOWLLiteral(date))));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(df.getOWLClass(
                IRI.create("https://example.org/shop#Pizza"))));
        File file = new File(folder, name);
        manager.saveOntology(o, format, IRI.create(file.toURI()));
        return file.getAbsolutePath();
    }

    /**
     * An ontology by itself: no import, no earlier version; its three classes under its own
     * address, as {@code <address without .owl>#Pizza}.
     */
    private static File alone(File folder, String iri, String more) throws Exception {
        String terms = (iri.endsWith(".owl") ? iri.substring(0, iri.length() - 4) : iri) + "#";
        File file = new File(folder, "alone.ttl");
        Files.write(file.toPath(), (TURTLE_HEAD
                + "<" + iri + "> a owl:Ontology ; rdfs:label \"Shop\" ;\n"
                + "  owl:versionInfo \"1.0.0\" .\n"
                + "<" + terms + "Pizza> a owl:Class ; rdfs:label \"pizza\" .\n"
                + "<" + terms + "Base> a owl:Class ; rdfs:label \"base\" .\n"
                + "<" + terms + "Topping> a owl:Class ; rdfs:label \"topping\" .\n"
                + more).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** A machine with git, as this one has it, and no program that runs containers. */
    private static ProcessRunner.Runner gitAndNothingElse() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!command.isEmpty() && command.get(0).equals("git")) {
                    return ProcessRunner.real().run(workingDirectory, command, timeoutMinutes,
                            sink);
                }
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
        };
    }

    private static OdkConversion.Result convert(OdkConversion.Request request,
            final List<String> log) {
        return OdkConversion.run(request.runner(gitAndNothingElse()), new OdkConversion.Log() {
            @Override
            public void step(int index) {
                log.add("STEP " + OdkConversion.STEPS.get(index));
            }

            @Override
            public void line(String text) {
                log.add(text);
            }

            @Override
            public void warn(String text) {
                log.add("WARN " + text);
            }

            @Override
            public void done(int index, boolean ok) {
                log.add("DONE " + index + " " + ok);
            }
        }, null);
    }

    private static OdkConversion.Result convert(String[] in, File into, List<String> log) {
        return convert(new OdkConversion.Request().current(in[0]).earlier(in[1]).earlier(in[2])
                .into(into), log);
    }

    private static String text(File root, String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the whole of it

    @Test
    void anOntologyAndItsHistoryBecomeAnOdkRepositoryAndAreReproduced(@TempDir File folder)
            throws Exception {
        String[] in = inputs(folder);
        List<String> log = new ArrayList<String>();

        OdkConversion.Result one = convert(in, new File(folder, "one"), log);

        File root = one.getRoot();
        assertEquals("shop", root.getName());
        assertEquals(Arrays.asList("upper"), one.getModules(), log.toString());
        assertTrue(one.isBuildPassed(), log.toString());
        assertEquals(9, log.stream().filter(l -> l.startsWith("STEP ")).count(),
                "every step said");
        assertEquals(Arrays.asList("0.1.0", "0.2.0", "1.0.0"), one.getVersions(),
                "oldest first, whatever order they were given in: " + log);
        for (String version : one.getVersions()) {
            assertTrue(new File(root, "releases/" + version + "/shop.owl").isFile(), version);
        }
        assertTrue(text(root, "src/ontology/shop-edit.owl").startsWith("Prefix("),
                "the edit file is in functional syntax, as ODK keeps one");
        // What ODK's update would make of it now: nothing.
        OdkUpdate.Plan again = OdkUpdate.plan(root);
        for (OdkUpdate.Change change : again.changes()) {
            assertFalse(change.isChanged(), change.path() + " is what ODK's update writes");
        }
        assertFalse(OdkUpdate.check(root, again), "and so is the edit file");
        assertEquals(new ArrayList<ImportGuard.Undeclared>(), again.undeclared());

        assumeTrue(OdkConversion.gitIsInstalled(), "git is not installed here");
        assertEquals(Arrays.asList("v0.1.0", "v0.2.0", "v1.0.0"), one.getTags());
        assertEquals(Arrays.asList("v0.1.0", "v0.2.0", "v1.0.0"), git(root, "tag"));
        assertTrue(text(root, "README.md").contains("## History"));
        assertTrue(text(root, "README.md").contains("| 1.0.0 | `v1.0.0` |"));
        assertEquals(new ArrayList<String>(), git(root, "status", "--porcelain"),
                "everything is recorded: " + log);
        List<String> signer = OdkConversion.knowsWho(root) ? git(root, "config", "user.name")
                : Arrays.asList("OntoBoard");
        assertEquals(signer, git(root, "log", "-1", "--format=%an", "v0.1.0"),
                "signed by whoever git knows, as any commit of yours would be");
        assertEquals(signer, git(root, "log", "-1", "--format=%an", "v1.0.0"),
                "the release's commit too");

        // Again, into another folder: the same repository.
        OdkConversion.Result two = convert(in, new File(folder, "two"), new ArrayList<String>());
        Map<String, String> differences = differences(tracked(root), tracked(two.getRoot()));
        assertTrue(differences.isEmpty(), "the same files, dates aside: " + differences);
        for (String tag : Arrays.asList("v0.1.0", "v0.2.0")) {
            assertEquals(git(root, "rev-parse", tag + "^{tree}"),
                    git(two.getRoot(), "rev-parse", tag + "^{tree}"), tag + ": the same tree");
        }
        assertEquals(git(root, "rev-parse", "v0.2.0^{commit}"),
                git(two.getRoot(), "rev-parse", "v0.2.0^{commit}"),
                "an earlier version's commit carries its own date, so it is the same commit");
        assertEquals(Arrays.asList("2026-01-15"), git(root, "log", "-1", "--format=%ad",
                "--date=short", "v0.1.0"));
    }

    // ------------------------------------------------------------------ the seed

    /**
     * The project is the one ODK's seed makes for its configuration, file for file, with the
     * ontology where the seed's edit file would be - not a project of OntoBoard's own making.
     */
    @Test
    void theConvertedProjectIsTheSeedWithTheirOntology(@TempDir File folder) throws Exception {
        File mine = alone(folder, "https://example.org/shop.owl", "");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")).git(false), log);

        File root = made.getRoot();
        String config = text(root, "src/ontology/shop-odk.yaml");
        assertTrue(OdkConfigWriter.isOntoBoards(config), config);
        Map<String, byte[]> seed = OdkInstaller.renderSeed(config).files();
        assertTrue(seed.containsKey("src/ontology/Makefile") && seed.size() > 30,
                seed.keySet().toString());
        // Two files of a seed ODK's own update writes its own way the first time it runs - the
        // last line of .gitignore, the first two of the catalog. The conversion has run it.
        Set<String> theUpdates = new TreeSet<String>(Arrays.asList(".gitignore",
                "src/ontology/catalog-v001.xml"));
        for (Map.Entry<String, byte[]> file : seed.entrySet()) {
            if (file.getKey().equals("src/ontology/shop-edit.owl")
                    || theUpdates.contains(file.getKey())) {
                continue;
            }
            assertArrayEquals(file.getValue(),
                    Files.readAllBytes(new File(root, file.getKey()).toPath()), file.getKey());
        }
        OdkUpdate.Plan again = OdkUpdate.plan(root);
        for (OdkUpdate.Change change : again.changes()) {
            assertFalse(change.isChanged(), change.path() + " is what ODK's update writes: "
                    + "the first Update project files finds nothing to do");
        }
        assertFalse(OdkUpdate.check(root, again), "and so is the edit file");
        assertEquals(new String(seed.get("README.md"), StandardCharsets.UTF_8),
                text(root, "README.md"), "ODK's README, as it is where no history is recorded");
        assertFalse(OdkMigration.isOntoBoards1x(root), "ODK's Makefile, not OntoBoard's");
        assertTrue(new File(root, "mkdocs.yaml").isFile(), "and OntoBoard's extras beside it");

        // Their ontology, under its own address, in the syntax ODK keeps an edit file in.
        String edit = text(root, "src/ontology/shop-edit.owl");
        assertTrue(edit.startsWith("Prefix("), edit);
        OWLOntology read = ImportGuard.alone(new File(root, "src/ontology/shop-edit.owl"));
        assertEquals("https://example.org/shop.owl",
                read.getOntologyID().getOntologyIRI().get().toString());
        assertFalse(read.getOntologyID().getVersionIRI().isPresent(),
                "an edit file has no version IRI: a release gives it one");
        assertEquals(3, read.getClassesInSignature().size(), edit);
        assertEquals(new ArrayList<String>(), made.getWarnings(), log.toString());
        // What was added for ODK's report is said as it reads - on Protege 5.6.9 the log named
        // a Java object in its place (1.153.0, live run).
        assertTrue(log.contains("added dcterms:title - ODK's quality report requires it, and "
                + "the ontology did not say: Shop"), log.toString());
        assertTrue(log.contains("added dcterms:license - ODK's quality report requires it, and "
                + "the ontology did not say: https://creativecommons.org/licenses/by/4.0/"),
                log.toString());
    }

    /**
     * OWL's own vocabulary is nobody's default prefix. OWL/XML has it as its XML namespace, and
     * the OWL API of Protege 5.6 hands that on as the ontology's default prefix: an earlier
     * version given in OWL/XML was recorded with {@code <Ontology>} for {@code <owl:Ontology>},
     * and an edit file made of one begins {@code Prefix(:=<http://www.w3.org/2002/07/owl#>)}.
     * Here the source says so itself, which every OWL API reads alike.
     */
    @Test
    void owlsOwnVocabularyIsNotMadeTheDefaultPrefix(@TempDir File folder) throws Exception {
        String head = "@prefix : <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n";
        File earlier = new File(folder, "shop-0.1.0.ttl");
        Files.write(earlier.toPath(), (head
                + "<https://example.org/shop.owl> a :Ontology ; :versionInfo \"0.1.0\" .\n"
                + "<https://example.org/shop#Pizza> a :Class ; rdfs:label \"pizza\" .\n")
                .getBytes(StandardCharsets.UTF_8));
        File mine = new File(folder, "shop.ttl");
        Files.write(mine.toPath(), (head
                + "<https://example.org/shop.owl> a :Ontology ; rdfs:label \"Shop\" ;\n"
                + "  :versionInfo \"1.0.0\" .\n"
                + "<https://example.org/shop#Pizza> a :Class ; rdfs:label \"pizza\" .\n"
                + "<https://example.org/shop#Base> a :Class ; rdfs:label \"base\" .\n")
                .getBytes(StandardCharsets.UTF_8));
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).earlier(earlier.getAbsolutePath())
                .into(new File(folder, "out")).git(false), log);

        File root = made.getRoot();
        String edit = text(root, "src/ontology/shop-edit.owl");
        assertFalse(edit.contains("Prefix(:=<http://www.w3.org/2002/07/owl#>)"), edit);
        assertTrue(edit.contains("Prefix(owl:=<http://www.w3.org/2002/07/owl#>)"), edit);
        String published = text(root, "releases/0.1.0/shop.owl");
        assertFalse(published.contains("xmlns=\"http://www.w3.org/2002/07/owl#\""), published);
        assertTrue(published.contains("<owl:Ontology rdf:about=\"https://example.org/shop.owl\">"),
                published);
        assertTrue(published.contains("<owl:versionInfo>0.1.0</owl:versionInfo>"), published);
        assertEquals(Arrays.asList("0.1.0", "1.0.0"), made.getVersions(), log.toString());
    }

    /**
     * F1's cause in a converted ontology: ODK's release adds owl:versionInfo with ROBOT's
     * annotate, beside any the edit file holds. So the edit file is made without it, as it is
     * made without the version IRI; what was released keeps its own.
     */
    @Test
    void theEditFileIsMadeWithoutTheVersionEachReleaseWrites(@TempDir File folder)
            throws Exception {
        File mine = new File(folder, "shop.ttl");
        Files.write(mine.toPath(), ("@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
                + "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n"
                + "<https://example.org/shop.owl> a owl:Ontology ; rdfs:label \"Shop\" ;\n"
                + "  owl:versionInfo \"1.0.0\" .\n"
                + "<https://example.org/shop#Pizza> a owl:Class ; rdfs:label \"pizza\" .\n")
                .getBytes(StandardCharsets.UTF_8));
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")).git(false), log);

        File root = made.getRoot();
        String edit = text(root, "src/ontology/shop-edit.owl");
        assertFalse(edit.contains("versionInfo"), edit);
        assertTrue(edit.contains("rdfs:label"), "the rest of the head stays: " + edit);
        assertTrue(text(root, "releases/1.0.0/shop.owl").contains(
                "<owl:versionInfo>1.0.0</owl:versionInfo>"), "the release keeps its own");
        assertTrue(String.join("\n", log).contains("owl:versionInfo \"1.0.0\""), log.toString());
    }

    // ------------------------------------------------------------------ earlier versions

    /**
     * An earlier version is a release as ODK's repositories keep one: a commit with a tag, the
     * released file at the top of the project and under releases/, the edit file as it was
     * then.
     */
    @Test
    void earlierVersionsAreTagsAndFolders(@TempDir File folder) throws Exception {
        assumeTrue(OdkConversion.gitIsInstalled(), "git is not installed here");
        String[] in = inputs(folder);
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(in, new File(folder, "out"), log);

        File root = made.getRoot();
        for (String version : Arrays.asList("0.1.0", "0.2.0")) {
            String tag = "v" + version;
            List<String> released = git(root, "show", tag + ":releases/" + version
                    + "/shop.owl");
            assertTrue(String.join("\n", released).contains("<owl:versionInfo>" + version
                    + "</owl:versionInfo>"), tag + ": " + released);
            assertEquals(released, git(root, "show", tag + ":shop.owl"),
                    tag + ": the released file is at the top too, as ODK puts it");
            assertTrue(git(root, "show", tag + ":src/ontology/shop-edit.owl").get(0)
                    .startsWith("Prefix("), tag + ": the edit file as it was then");
            assertEquals(Arrays.asList("shop " + version), git(root, "tag", "-l",
                    "--format=%(contents:subject)", tag), "the tag says what a release's says");
            assertEquals(Arrays.asList("release(" + version + "): Shop " + version
                    + ", as published - converted by OntoBoard"),
                    git(root, "log", "-1", "--format=%s", tag));
        }
        assertEquals(Arrays.asList("2026-03-01"), git(root, "log", "-1", "--format=%ad",
                "--date=short", "v0.2.0"));
        // The project itself is there from the first of them: ODK's Makefile at that tag.
        assertTrue(String.join("\n", git(root, "show", "v0.1.0:src/ontology/Makefile"))
                .contains("ODK_VERSION_MAKEFILE"), "ODK's Makefile at the first tag");
    }

    // ------------------------------------------------------------------ imports

    /**
     * An import becomes a product ODK's configuration declares, with its module of the terms
     * the ontology uses - what Update project files' question about an undeclared import
     * makes of one - so that ODK's own update leaves it in.
     */
    @Test
    void importsBecomeDeclaredModules(@TempDir File folder) throws Exception {
        String[] in = inputs(folder);
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request().current(in[0])
                .into(new File(folder, "out")).git(false), log);

        File root = made.getRoot();
        assertEquals(Arrays.asList("upper"), made.getModules(), log.toString());
        String config = text(root, "src/ontology/shop-odk.yaml");
        assertTrue(config.contains("import_group:\n  products:\n    - id: upper\n"), config);
        // Read from a file of this machine: there is no address ODK could fetch it from.
        assertTrue(config.contains("      module_type: custom\n      mirror_type: no_mirror\n"),
                config);
        assertFalse(config.contains(folder.getName()), "and no path of this machine: " + config);
        String edit = text(root, "src/ontology/shop-edit.owl");
        assertTrue(edit.contains("Import(<https://example.org/shop/imports/upper_import.owl>)"),
                "the import now names the module: " + edit);
        assertFalse(edit.contains("upper.owl"), edit);
        assertTrue(text(root, "src/ontology/imports/upper_import.owl").startsWith("Prefix("));
        String terms = text(root, "src/ontology/imports/upper_terms.txt");
        assertTrue(terms.contains("http://example.org/upper#Object # object"), terms);
        assertFalse(terms.contains("Process"), "only the terms the ontology uses: " + terms);
        assertTrue(text(root, "src/ontology/catalog-v001.xml").contains(
                "https://example.org/shop/imports/upper_import.owl"));
        assertTrue(text(root, "src/ontology/Makefile").contains("IMPORTS =  upper"),
                "ODK's Makefile knows the module");
    }

    @Test
    void importsAreLeftAsTheyAreWhenAskedSo(@TempDir File folder) throws Exception {
        String[] in = inputs(folder);
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request().current(in[0])
                .into(new File(folder, "out")).git(false).modules(false), log);

        File root = made.getRoot();
        assertEquals(new ArrayList<String>(), made.getModules());
        assertTrue(text(root, "src/ontology/shop-edit.owl").contains("upper.owl"),
                "it imports what it imported");
        assertFalse(text(root, "src/ontology/shop-odk.yaml").contains("import_group"));
        assertTrue(log.toString().contains("Update project files"),
                "and the log says who asks about them later: " + log);
    }

    /**
     * What cannot be read cannot be made a module, and ODK keeps no import its configuration
     * does not declare: such an import is taken out, said as a warning, and the rest goes on.
     */
    @Test
    void anImportThatCannotBeReadIsTakenOutAndSaid(@TempDir File folder) throws Exception {
        File mine = alone(folder, "https://example.org/shop.owl",
                "<https://example.org/shop.owl> owl:imports "
                        + "<https://example.invalid/nowhere.owl> .\n");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")).git(false), log);

        File root = made.getRoot();
        String warnings = made.getWarnings().toString();
        assertTrue(warnings.contains("https://example.invalid/nowhere.owl could not be read"),
                warnings);
        assertTrue(warnings.contains("taken out of the edit file"), warnings);
        assertFalse(text(root, "src/ontology/shop-edit.owl").contains("nowhere.owl"),
                text(root, "src/ontology/shop-edit.owl"));
        assertEquals(new ArrayList<String>(), made.getModules());
        assertTrue(made.isBuildPassed(), "and the rest goes on: " + log);
        assertEquals(Arrays.asList("1.0.0"), made.getVersions(), log.toString());
    }

    // ------------------------------------------------------------------ the build

    /** The build is ODK's own test - its Makefile's target, not a test of OntoBoard's making. */
    @Test
    void theBuildStepIsOdksTest(@TempDir File folder) throws Exception {
        File mine = alone(folder, "https://example.org/shop.owl", "");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")).git(false), log);

        assertTrue(made.isBuildPassed(), log.toString());
        String said = String.join("\n", log);
        assertTrue(said.contains("$ make test_fast"), said);
        assertTrue(said.contains("reason --input"), "ODK's reason_test ran: " + said);
        assertTrue(said.contains("--fail-on ERROR"), "and its report, on ODK's terms: " + said);
        assertTrue(said.contains("PASS Rule ../sparql/check_labels-violation.sparql"),
                "and its checks: " + said);
        // And the release is ODK's too: its products, at the top and under releases/.
        assertEquals(Arrays.asList("1.0.0"), made.getVersions(), said);
        assertTrue(said.contains("$ make prepare_release_fast"), said);
        File root = made.getRoot();
        for (String product : Arrays.asList("shop.owl", "shop.obo", "shop-base.owl",
                "shop-full.owl")) {
            assertTrue(new File(root, product).isFile(), product);
            assertArrayEquals(Files.readAllBytes(new File(root, product).toPath()),
                    Files.readAllBytes(new File(root, "releases/1.0.0/" + product).toPath()),
                    product);
        }
        assertTrue(text(root, "shop.owl").contains("<owl:versionIRI rdf:resource=\"https://"
                + "example.org/shop/releases/1.0.0/shop.owl\"/>"), "ODK's version IRI, with the "
                        + "version released and not the day: "
                        + text(root, "shop.owl").substring(0, 900));
        // ODK's base release - the file others import - holds the ontology's own terms: the
        // configuration says where they are. It held none (1.153.0, live run).
        assertTrue(text(root, "src/ontology/shop-odk.yaml").contains("namespaces:\n"
                + "  - https://example.org/shop\n  - https://example.org/SHOP_\n"),
                text(root, "src/ontology/shop-odk.yaml"));
        for (String term : Arrays.asList("Pizza", "Base", "Topping")) {
            assertTrue(text(root, "shop-base.owl").contains("<owl:Class rdf:about=\"https://"
                    + "example.org/shop#" + term + "\""), term + " in the base release");
        }
    }

    /**
     * An ontology ODK's test does not pass is still made a project, with its history; it is
     * not released, since ODK's release runs the same test, and the result says what to do.
     */
    @Test
    void whenOdksTestFailsTheProjectIsMadeAndNotReleased(@TempDir File folder)
            throws Exception {
        File mine = alone(folder, "https://example.org/shop.owl",
                "<https://example.org/shop#Base> owl:disjointWith "
                        + "<https://example.org/shop#Topping> .\n"
                        + "<https://example.org/shop#Calzone> a owl:Class ; "
                        + "rdfs:label \"calzone\" ;\n"
                        + "  rdfs:subClassOf <https://example.org/shop#Base> , "
                        + "<https://example.org/shop#Topping> .\n");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")), log);

        File root = made.getRoot();
        assertFalse(made.isBuildPassed(), log.toString());
        assertEquals(new ArrayList<String>(), made.getVersions(), "nothing was released");
        assertEquals(new ArrayList<String>(), made.getTags());
        assertFalse(new File(root, "releases").exists(), "no release folder");
        String warnings = made.getWarnings().toString();
        assertTrue(warnings.contains("unsatisfiable"), warnings);
        assertTrue(warnings.contains("OntoBoard > Build and release > Release..."),
                "what to do once it passes: " + warnings);
        assertFalse(text(root, "README.md").contains("| 1.0.0 |"),
                "the history names no release that was not made");
        assumeTrue(OdkConversion.gitIsInstalled(), "git is not installed here");
        assertEquals(new ArrayList<String>(), git(root, "tag"));
        assertEquals(1, git(root, "log", "--format=%s").size(), "the project is recorded");
        assertEquals(new ArrayList<String>(), git(root, "status", "--porcelain"));
    }

    /**
     * Where nobody runs ODK's targets - the setting asks for a container and there is none -
     * the project is made all the same, and the result says why it was neither tested nor
     * released.
     */
    @Test
    void whenNobodyRunsOdksTargetsTheProjectIsMadeAndSaysWhy(@TempDir File folder)
            throws Exception {
        File mine = alone(folder, "https://example.org/shop.owl", "");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).into(new File(folder, "out")).git(false)
                .odk(new java.util.function.Function<File, OdkRelease.Odk>() {
                    @Override
                    public OdkRelease.Odk apply(File editFile) {
                        throw new IllegalStateException("No container can be run here, and "
                                + "the setting is 'Always in a container'.");
                    }
                }), log);

        assertFalse(made.isBuildPassed());
        assertEquals(new ArrayList<String>(), made.getVersions());
        String warnings = made.getWarnings().toString();
        assertTrue(warnings.contains("ODK's test could not be run: No container can be run "
                + "here, and the setting is 'Always in a container'."), warnings);
        assertTrue(warnings.contains("is not released"), warnings);
        assertTrue(new File(made.getRoot(), "src/ontology/Makefile").isFile(),
                "the project is there");
        assertEquals(9, log.stream().filter(l -> l.startsWith("STEP ")).count(),
                "and every step was gone through: " + log);
    }

    // ------------------------------------------------------------------ an address of another shape

    /**
     * ODK names what it releases {@code <uribase>/<id>.owl}. An ontology with another address
     * keeps it, the project's own files live under it, and the result says what ODK's release
     * will call the ontology.
     */
    @Test
    void anIriOdkWouldRenameIsWarnedAbout(@TempDir File folder) throws Exception {
        File mine = alone(folder, "https://example.org/vocab/ontology", "");
        List<String> log = new ArrayList<String>();

        OdkConversion.Result made = convert(new OdkConversion.Request()
                .current(mine.getAbsolutePath()).id("shopcore").into(new File(folder, "out"))
                .git(false), log);

        File root = made.getRoot();
        assertEquals("shopcore", root.getName());
        String warnings = made.getWarnings().toString();
        assertTrue(warnings.contains("keeps its address, https://example.org/vocab/ontology"),
                warnings);
        assertTrue(warnings.contains("https://example.org/vocab/shopcore.owl"), warnings);
        assertTrue(warnings.contains("shopcore.Makefile"), warnings);
        String config = text(root, "src/ontology/shopcore-odk.yaml");
        assertTrue(config.contains("uribase: https://example.org/vocab\n"
                + "uribase_suffix: ontology\n"), config);
        assertEquals("https://example.org/vocab/ontology", ImportGuard.iriOf(
                new File(root, "src/ontology/shopcore-edit.owl")));
        // What ODK names under the project's base is under the ontology's own address.
        assertTrue(text(root, "shopcore.owl").contains("<owl:versionIRI rdf:resource=\"https://"
                + "example.org/vocab/ontology/releases/1.0.0/shopcore.owl\"/>"),
                text(root, "shopcore.owl").substring(0, 1200));
        // And the ontology's own terms, which are under that address, are in the base release.
        assertTrue(text(root, "shopcore-base.owl").contains("<owl:Class rdf:about=\"https://"
                + "example.org/vocab/ontology#Pizza\""), "the base release holds its terms");
        // Said once before the project is made and once of the release that was made.
        assertTrue(warnings.contains("The release names the ontology https://example.org/vocab/"
                + "shopcore.owl, as ODK names what it releases; the edit file says https://"
                + "example.org/vocab/ontology. Look at shopcore.owl before you publish it."),
                warnings);
        assertEquals(2, made.getWarnings().size(), "both warnings are about the address: "
                + warnings);
    }

    // ------------------------------------------------------------------ strings

    /**
     * The line Linux CI wrote the first time and the line it wrote the second, for the same
     * dcterms:date of the same version: the OWL API returns either form of an equal string
     * literal, whichever its shared cache holds. Written canonically they are one line.
     */
    @Test
    void aStringLiteralIsWrittenOneWayWhicheverFormTheOwlApiHandsBack() {
        String typed = "<terms:date rdf:datatype=\"http://www.w3.org/2001/XMLSchema#string\">"
                + "2026-03-01</terms:date>";
        String simple = "<terms:date>2026-03-01</terms:date>";

        assertEquals(simple, OdkConversion.simpleStrings(typed));
        assertEquals(simple, OdkConversion.simpleStrings(simple));
        assertEquals("ex:a ex:p \"x\", \"\"\"two\nlines\"\"\" .", OdkConversion.simpleStrings(
                "ex:a ex:p \"x\"^^xsd:string, \"\"\"two\nlines\"\"\"^^"
                        + "<http://www.w3.org/2001/XMLSchema#string> ."));
        assertEquals("\"a\\\"b\"", OdkConversion.simpleStrings("\"a\\\"b\"^^xsd:string"),
                "an escaped quote inside the literal");
        assertEquals("\"5\"^^xsd:integer", OdkConversion.simpleStrings("\"5\"^^xsd:integer"),
                "every other datatype stays");
    }

    /** Through the OWL API, both formats: no explicit xsd:string, and read back the same. */
    @Test
    void aReleaseIsWrittenInTheSimpleFormAndReadsBackTheSame(@TempDir File folder)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/shop.owl"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(
                        IRI.create("http://purl.org/dc/terms/date")), df.getOWLLiteral(
                                "2026-03-01", df.getOWLDatatype(
                                        org.semanticweb.owlapi.vocab.XSDVocabulary.STRING
                                                .getIRI())))));
        File rdfXml = new File(folder, "shop.owl");
        File turtle = new File(folder, "shop.ttl");

        OdkConversion.writeCanonically(ontology, new RDFXMLDocumentFormat(), rdfXml);
        OdkConversion.writeCanonically(ontology,
                new org.semanticweb.owlapi.formats.TurtleDocumentFormat(), turtle);

        String written = new String(Files.readAllBytes(rdfXml.toPath()), StandardCharsets.UTF_8)
                + new String(Files.readAllBytes(turtle.toPath()), StandardCharsets.UTF_8);
        assertTrue(written.contains("2026-03-01"), written);
        assertTrue(!written.contains("XMLSchema#string") && !written.contains("xsd:string"),
                written);
        // RDF/XML, which the OWL API's first parser reads, so no handle is left on Windows.
        OWLOntology back = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(rdfXml);
        assertEquals(ontology.getAnnotations(), back.getAnnotations(),
                "the same literal: RDF 1.1 and the OWL API both say so");
    }

    // ------------------------------------------------------------------ looking

    /**
     * Every file git has, its text with any date and time of day taken out: a release says the
     * day it was made, and ODK's OBO products the minute.
     */
    private static Map<String, String> tracked(File root) throws Exception {
        Map<String, String> files = new TreeMap<String, String>();
        for (String path : git(root, "ls-files")) {
            files.put(path, new String(Files.readAllBytes(new File(root, path).toPath()),
                    StandardCharsets.UTF_8)
                    .replaceAll("\\d{2}:\\d{2}:\\d{4} \\d{2}:\\d{2}", "DATE")
                    .replaceAll("\\d{4}-\\d{2}-\\d{2}", "DATE"));
        }
        assertTrue(files.size() > 40, "the files of a project: " + files.keySet());
        return files;
    }

    /**
     * Each path whose text differs, with the first line where it does - not two whole
     * repositories side by side, which is what a failure on Linux CI printed, cut off long
     * before the difference.
     */
    private static Map<String, String> differences(Map<String, String> one,
            Map<String, String> two) {
        Map<String, String> differ = new TreeMap<String, String>();
        Set<String> paths = new TreeSet<String>(one.keySet());
        paths.addAll(two.keySet());
        for (String path : paths) {
            String a = one.get(path);
            String b = two.get(path);
            if (a == null || b == null) {
                differ.put(path, a == null ? "only in the second" : "only in the first");
            } else if (!a.equals(b)) {
                String[] linesA = a.split("\n", -1);
                String[] linesB = b.split("\n", -1);
                int line = 0;
                while (line < linesA.length && line < linesB.length
                        && linesA[line].equals(linesB[line])) {
                    line++;
                }
                differ.put(path, "line " + (line + 1) + ": ["
                        + (line < linesA.length ? linesA[line] : "(end)") + "] then ["
                        + (line < linesB.length ? linesB[line] : "(end)") + "]");
            }
        }
        return differ;
    }

    private static List<String> git(File root, String... arguments) throws Exception {
        List<String> command = new ArrayList<String>(Arrays.asList("git"));
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).directory(root).start();
        List<String> lines = new ArrayList<String>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(line.trim());
            }
        }
        process.waitFor();
        return lines;
    }
}
