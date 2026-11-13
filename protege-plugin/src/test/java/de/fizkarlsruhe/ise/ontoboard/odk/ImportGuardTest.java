package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The imports ODK's update would take out of the edit file are found before it, and it is not
 * made until each is declared or its removal agreed to.
 *
 * <p>The project is the one ODK's own update was recorded on ({@code update/egg-changed}): its
 * edit file imports {@code https://example.invalid/whole.owl}, which its catalog serves from
 * {@code whole.owl} beside it and its configuration does not declare - ODK's update took the
 * import out.
 */
class ImportGuardTest {

    private static final String WHOLE = "https://example.invalid/whole.owl";
    private static final String SPOON = "https://example.invalid/whole#Spoon";
    private static final String FORK = "https://example.invalid/whole#Fork";
    private static final String MODULES = "https://example.org/eggboil/eggboil/imports/";

    @TempDir
    File root;

    private File inOntology(String name) {
        return new File(root, "src/ontology/" + name);
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** The recorded project, its edit file made to use a term of what it imports. */
    private void theProjectUsingTheSpoon() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File edit = inOntology("eggboil-edit.owl");
        String ofn = text(edit);
        int end = ofn.lastIndexOf(')');
        write(edit, ofn.substring(0, end) + "SubClassOf(<https://example.org/eggboil/"
                + "EGGBOIL_0000001> <" + SPOON + ">)\n" + ofn.substring(end));
        // And what it imports holds a class the ontology does not use.
        File whole = inOntology("whole.owl");
        String imported = text(whole);
        int last = imported.lastIndexOf(')');
        write(whole, imported.substring(0, last) + "Declaration(Class(<" + FORK + ">))\n"
                + imported.substring(last));
    }

    private void assertSameTree(Map<String, byte[]> expected) {
        Map<String, byte[]> actual = Goldens.tree(root);
        assertEquals(new TreeSet<String>(expected.keySet()), new TreeSet<String>(actual.keySet()),
                "the files of the project");
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
        }
    }

    private static OWLOntology read(File file) throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new org.semanticweb.owlapi.io.StringDocumentSource(text(file)));
    }

    // ------------------------------------------------------------------ what would go

    @Test
    void anImportTheConfigurationDoesNotDeclareIsListed() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);

        List<ImportGuard.Undeclared> undeclared = OdkUpdate.plan(root).undeclared();

        assertEquals(1, undeclared.size(), undeclared.toString());
        assertEquals(WHOLE, undeclared.get(0).iri());
        assertEquals(inOntology("whole.owl").getAbsoluteFile(),
                undeclared.get(0).localFileOrNull().getAbsoluteFile(),
                "the catalog serves it from a file of the project");
    }

    @Test
    void anImportOfADeclaredProductIsNot() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        OdkUpdate.apply(root, OdkUpdate.plan(root).removing(Collections.singletonList(WHOLE)));

        OdkUpdate.Plan again = OdkUpdate.plan(root);

        assertEquals(2, again.imports().size(), "the edit file imports its two modules now");
        assertEquals(new ArrayList<ImportGuard.Undeclared>(), again.undeclared());
    }

    @Test
    void anImportTheCatalogDoesNotServeHasNoFile() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File catalog = inOntology("catalog-v001.xml");
        String entry = "  <uri id=\"User Entered Import Resolution\" name=\"" + WHOLE
                + "\" uri=\"whole.owl\"/>\n";
        assertTrue(text(catalog).contains(entry), text(catalog));
        write(catalog, text(catalog).replace(entry, ""));

        List<ImportGuard.Undeclared> undeclared = OdkUpdate.plan(root).undeclared();

        assertEquals(WHOLE, undeclared.get(0).iri());
        assertNull(undeclared.get(0).localFileOrNull());
    }

    /** ODK leaves the edit file's imports alone there, so it takes none out. */
    @Test
    void whereOdkDoesNotLookAfterTheImportsNothingIsListed() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File config = inOntology("eggboil-odk.yaml");
        write(config, text(config) + "manage_import_declarations: FALSE\n");

        assertEquals(new ArrayList<ImportGuard.Undeclared>(), OdkUpdate.plan(root).undeclared());
    }

    // ------------------------------------------------------------------ nothing while undecided

    @Test
    void theUpdateIsNotMadeWhileOneIsUndecided() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        final OdkUpdate.Plan plan = OdkUpdate.plan(root);

        ImportGuard.Undecided undecided = assertThrows(ImportGuard.Undecided.class,
                new Executable() {
                    @Override
                    public void execute() {
                        OdkUpdate.apply(root, plan);
                    }
                });

        assertEquals(Collections.singletonList(WHOLE), undecided.imports());
        assertTrue(undecided.getMessage().contains(WHOLE), undecided.getMessage());
        assertSameTree(before);
    }

    /** ODK's own update would take it out without a word: it is not started. */
    @Test
    void odksOwnUpdateIsNotStartedWhileOneIsUndecided() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);
        final OdkUpdate.Plan plan = OdkUpdate.plan(root);
        final List<List<String>> ran = new ArrayList<List<String>>();
        final ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                ran.add(command);
                return new ProcessRunner.Outcome(0, new ArrayList<String>(), false);
            }
        };

        assertThrows(ImportGuard.Undecided.class, new Executable() {
            @Override
            public void execute() {
                OdkUpdate.inContainer("docker", "an-image:v1", OdkContainer.Host.unknown(), root,
                        plan, container);
            }
        });

        assertEquals(new ArrayList<List<String>>(), ran);
        assertSameTree(before);
    }

    @Test
    void anAgreedRemovalLetsTheUpdateTakeItOut() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);

        OdkUpdate.apply(root, OdkUpdate.plan(root).removing(Collections.singletonList(WHOLE)));

        assertEquals(Arrays.asList(MODULES + "ro_import.owl", MODULES + "utensils_import.owl"),
                ImportGuard.importsOf(inOntology("eggboil-edit.owl")));
    }

    // ------------------------------------------------------------------ declaring

    @Test
    void whatIsKnownOfAnImportIsItsNameAndHowMuchOfItIsUsed() throws IOException {
        theProjectUsingTheSpoon();

        ImportGuard.Finding finding = ImportGuard.read(root, inOntology("eggboil-edit.owl"))
                .findings(OdkUpdate.plan(root).undeclared()).get(0);

        assertTrue(finding.isReadable(), finding.whyUnreadable());
        assertEquals("whole", finding.name());
        assertEquals(1, finding.termsUsed());
        assertTrue(finding.isFrozen(), "read from a file of the project: ODK could fetch it "
                + "from nowhere");
    }

    @Test
    void asAModuleItsProductIsDeclaredAndTheModuleHoldsWhatTheOntologyUses() throws Exception {
        theProjectUsingTheSpoon();
        ImportGuard.Reading reading = ImportGuard.read(root, inOntology("eggboil-edit.owl"));
        ImportGuard.Finding finding = reading.findings(OdkUpdate.plan(root).undeclared()).get(0);

        List<File> written = reading.declare(finding, ImportGuard.Decision.MODULE);

        File module = inOntology("imports/whole_import.owl");
        File terms = inOntology("imports/whole_terms.txt");
        File config = inOntology("eggboil-odk.yaml");
        assertEquals(new TreeSet<File>(Arrays.asList(module, terms, config)),
                new TreeSet<File>(written));
        OWLOntology made = read(module);
        assertEquals(MODULES + "whole_import.owl",
                made.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(made.containsEntityInSignature(IRI.create(SPOON), Imports.EXCLUDED));
        assertFalse(made.containsEntityInSignature(IRI.create(FORK), Imports.EXCLUDED),
                "what the ontology does not use is not in a module of what it uses");
        assertTrue(text(module).startsWith("Prefix("), "a module is in functional syntax, as "
                + "ODK's are: " + text(module).substring(0, 40));
        assertTrue(text(terms).contains(SPOON), text(terms));
        ImportProducts.Product product = ImportProducts.named(
                ImportProducts.declaredIn(text(config)), "whole");
        assertEquals("custom", product.getModuleType());
        assertFalse(product.isMirrored(), "kept as it is: nothing is fetched for it");

        // And the update puts the module in the place of the import.
        OdkUpdate.apply(root, OdkUpdate.plan(root).removing(Collections.singletonList(WHOLE)));
        assertEquals(Arrays.asList(MODULES + "ro_import.owl", MODULES + "utensils_import.owl",
                MODULES + "whole_import.owl"),
                ImportGuard.importsOf(inOntology("eggboil-edit.owl")));
        assertTrue(read(module).containsEntityInSignature(IRI.create(SPOON), Imports.EXCLUDED),
                "the update leaves the module that is there");
        assertEquals(new ArrayList<ImportGuard.Undeclared>(), OdkUpdate.plan(root).undeclared());
    }

    @Test
    void asTheWholeOntologyItsModuleIsAllOfIt() throws Exception {
        theProjectUsingTheSpoon();
        ImportGuard.Reading reading = ImportGuard.read(root, inOntology("eggboil-edit.owl"));
        ImportGuard.Finding finding = reading.findings(OdkUpdate.plan(root).undeclared()).get(0);

        List<File> written = reading.declare(finding, ImportGuard.Decision.WHOLE);

        File module = inOntology("imports/whole_import.owl");
        assertEquals(new TreeSet<File>(Arrays.asList(module, inOntology("eggboil-odk.yaml"))),
                new TreeSet<File>(written));
        OWLOntology made = read(module);
        assertEquals(MODULES + "whole_import.owl",
                made.getOntologyID().getOntologyIRI().get().toString());
        assertTrue(made.containsEntityInSignature(IRI.create(SPOON), Imports.EXCLUDED));
        assertTrue(made.containsEntityInSignature(IRI.create(FORK), Imports.EXCLUDED),
                "all of it, used or not");
    }

    /** An import read from its address is one ODK can fetch again: declared with the address. */
    @Test
    void anImportReadFromItsAddressIsDeclaredWithIt() throws Exception {
        theProjectUsingTheSpoon();
        // Outside the project and outside its catalog: read from where its IRI points.
        File elsewhere = new File(root.getParentFile(), root.getName() + "-elsewhere.owl");
        try {
            Files.copy(inOntology("whole.owl").toPath(), elsewhere.toPath());
            String address = elsewhere.toURI().toString();
            File edit = inOntology("eggboil-edit.owl");
            write(edit, text(edit).replace("Import(<" + WHOLE + ">)",
                    "Import(<" + address + ">)"));
            ImportGuard.Reading reading = ImportGuard.read(root, edit);
            ImportGuard.Finding finding = reading.findings(
                    OdkUpdate.plan(root).undeclared()).get(0);
            assertEquals(address, finding.item().iri());
            assertFalse(finding.isFrozen());

            reading.declare(finding, ImportGuard.Decision.MODULE);

            ImportProducts.Product product = ImportProducts.named(ImportProducts.declaredIn(
                    text(inOntology("eggboil-odk.yaml"))), finding.name());
            assertEquals(address, product.getMirrorFrom());
            assertEquals("slme", product.getModuleType());
            assertEquals("BOT", product.getSlmeMethod());
        } finally {
            elsewhere.delete();
        }
    }

    @Test
    void theWholeOfAnImportReadFromItsAddressIsAMirror() throws Exception {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File elsewhere = new File(root.getParentFile(), root.getName() + "-elsewhere.owl");
        try {
            Files.copy(inOntology("whole.owl").toPath(), elsewhere.toPath());
            String address = elsewhere.toURI().toString();
            File edit = inOntology("eggboil-edit.owl");
            write(edit, text(edit).replace("Import(<" + WHOLE + ">)",
                    "Import(<" + address + ">)"));
            ImportGuard.Reading reading = ImportGuard.read(root, edit);
            ImportGuard.Finding finding = reading.findings(
                    OdkUpdate.plan(root).undeclared()).get(0);

            reading.declare(finding, ImportGuard.Decision.WHOLE);

            ImportProducts.Product product = ImportProducts.named(ImportProducts.declaredIn(
                    text(inOntology("eggboil-odk.yaml"))), finding.name());
            assertEquals(address, product.getMirrorFrom());
            assertEquals("mirror", product.getModuleType());
        } finally {
            elsewhere.delete();
        }
    }

    /** What cannot be read cannot be made a module: it can only be taken out. */
    @Test
    void anImportThatCannotBeReadCannotBeDeclared() throws IOException {
        Map<String, byte[]> before = UpdateCases.before("egg-unloadable");
        UpdateCases.write(before, root);
        final ImportGuard.Reading reading = ImportGuard.read(root,
                inOntology("eggboil-edit.owl"));
        final ImportGuard.Finding finding = reading.findings(
                OdkUpdate.plan(root).undeclared()).get(0);

        assertFalse(finding.isReadable());
        assertTrue(finding.whyUnreadable().contains("could not be read"),
                finding.whyUnreadable());
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws IOException {
                reading.declare(finding, ImportGuard.Decision.MODULE);
            }
        });
        assertSameTree(before);
    }

    /** A product, a module or a term list that is there keeps its name. */
    @Test
    void aNameThatIsTakenIsNotGivenAgain() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File list = inOntology("imports/whole_terms.txt");
        Files.createDirectories(list.getParentFile().toPath());
        write(list, "https://example.org/another#Thing\n");

        ImportGuard.Finding finding = ImportGuard.read(root, inOntology("eggboil-edit.owl"))
                .findings(OdkUpdate.plan(root).undeclared()).get(0);

        assertEquals("whole_2", finding.name());
    }

    // ------------------------------------------------------------------ what nothing imports

    /** A module on disk that nothing imports is named and left alone: not declared, not deleted. */
    @Test
    void aModuleNothingImportsIsNamedAndLeftAlone() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File unused = inOntology("imports/unused_import.owl");
        Files.createDirectories(unused.getParentFile().toPath());
        write(unused, "Ontology(<" + MODULES + "unused_import.owl>)\n");
        byte[] before = Files.readAllBytes(unused.toPath());
        // A product's module that is there before the edit file imports it is not one: the
        // configuration declares it, and the update is about to import it.
        write(inOntology("imports/ro_import.owl"), "Ontology(<" + MODULES
                + "ro_import.owl>)\n");

        OdkUpdate.Plan plan = UpdateCases.planAsOdkDoes(root);

        assertEquals(Collections.singletonList("src/ontology/imports/unused_import.owl"),
                plan.notImported());
        OdkUpdate.apply(root, plan);
        assertArrayEquals(before, Files.readAllBytes(unused.toPath()));
        // The update made the modules the configuration declares, and the edit file imports
        // them: of the three in the folder now, the one nothing imports is still the one.
        assertTrue(inOntology("imports/ro_import.owl").isFile());
        assertEquals(Collections.singletonList("src/ontology/imports/unused_import.owl"),
                OdkUpdate.plan(root).notImported());
        assertFalse(text(inOntology("eggboil-odk.yaml")).contains("unused"),
                "it is not declared for them either");
    }

    /**
     * A module the edit file imports without the configuration declaring it is an import to
     * decide about, not a module nothing imports - also when the edit file imports it under
     * another address that the catalog serves from that file.
     */
    @Test
    void aModuleTheEditFileImportsIsNotOneNothingImports() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        // What the edit file imports, kept where modules are kept.
        File module = inOntology("imports/whole_import.owl");
        Files.createDirectories(module.getParentFile().toPath());
        Files.copy(inOntology("whole.owl").toPath(), module.toPath());
        File catalog = inOntology("catalog-v001.xml");
        write(catalog, text(catalog).replace("uri=\"whole.owl\"",
                "uri=\"imports/whole_import.owl\""));

        OdkUpdate.Plan plan = OdkUpdate.plan(root);

        assertEquals(1, plan.undeclared().size());
        assertEquals(new ArrayList<String>(), plan.notImported());

        // And imported under the module's own address, with no product for it.
        File edit = inOntology("eggboil-edit.owl");
        write(edit, text(edit).replace("Import(<" + WHOLE + ">)",
                "Import(<" + MODULES + "whole_import.owl>)"));
        write(catalog, text(catalog).replace("name=\"" + WHOLE + "\"",
                "name=\"https://example.org/elsewhere.owl\""));

        OdkUpdate.Plan own = OdkUpdate.plan(root);

        assertEquals(MODULES + "whole_import.owl", own.undeclared().get(0).iri());
        assertEquals(new ArrayList<String>(), own.notImported());
    }

    /** Where ODK leaves the imports alone, nothing is named either. */
    @Test
    void whereOdkDoesNotLookAfterTheImportsNoModuleIsNamed() throws IOException {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        File unused = inOntology("imports/unused_import.owl");
        Files.createDirectories(unused.getParentFile().toPath());
        write(unused, "Ontology(<" + MODULES + "unused_import.owl>)\n");
        File config = inOntology("eggboil-odk.yaml");
        write(config, text(config) + "manage_import_declarations: FALSE\n");

        assertEquals(new ArrayList<String>(), OdkUpdate.plan(root).notImported());
    }
}
