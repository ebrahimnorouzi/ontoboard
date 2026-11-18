package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportGuard;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.UpdateCases;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.obolibrary.robot.CatalogXmlIRIMapper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Check imports in an ODK project, and the button on each import ODK's update would take out:
 * one the configuration does not declare is declared as the update's own question declares it,
 * and one that cannot be read either - which stops the update (ruling 102 of 1.153.0) - is
 * taken out of what is open, as by hand in Protege.
 */
class ImportFixesTest {

    private static final String EDIT = "src/ontology/eggboil-edit.owl";
    private static final String WHOLE = "https://example.invalid/whole.owl";
    private static final String SPARE = "https://example.invalid/spare.owl";
    private static final String NONE = "Neither Docker nor Podman answers on this machine";

    @TempDir
    File top;

    private File project(String folder, String recorded) {
        File root = new File(top, folder);
        UpdateCases.write(UpdateCases.before(recorded), root);
        return root;
    }

    /** The edit file as Protege has it open: read with its imports, through the catalog. */
    private static OWLOntology opened(File root) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(new CatalogXmlIRIMapper(
                new File(root, "src/ontology/catalog-v001.xml")));
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        File edit = new File(root, EDIT);
        return manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                new ByteArrayInputStream(Files.readAllBytes(edit.toPath())),
                IRI.create(edit)));
    }

    private static OdkUpdateForm.Way itself() {
        return OdkUpdateForm.way(OdkRoute.Use.AUTOMATIC, null, null, null, NONE, null);
    }

    private static ProcessRunner.Runner noContainer() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("no container was to be started: " + command);
            }
        };
    }

    /** Whoever has the project open: answers each question with these, and keeps what it was asked. */
    private static final class Asks implements OdkUpdateForm.Session {
        final List<List<ImportGuard.Finding>> asked = new ArrayList<List<ImportGuard.Finding>>();
        /** null: the question's own first answer for each. */
        private final ImportGuard.Decision answer;

        Asks(ImportGuard.Decision answer) {
            this.answer = answer;
        }

        @Override
        public boolean stopped() {
            return false;
        }

        @Override
        public boolean isOpen(File editFile) {
            return false;
        }

        @Override
        public String readAgain(File editFile, Map<String, File> imports) {
            return null;
        }

        @Override
        public void writtenAsItWas(File editFile) {
        }

        @Override
        public List<ImportGuard.Decision> decide(List<ImportGuard.Finding> findings) {
            asked.add(findings);
            List<ImportGuard.Decision> decided = new ArrayList<ImportGuard.Decision>();
            for (ImportGuard.Finding finding : findings) {
                decided.add(answer != null ? answer
                        : OdkUpdateForm.choicesFor(finding).values().iterator().next());
            }
            return decided;
        }
    }

    private static String text(File root, String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ declared

    @Test
    void anImportTheConfigurationDoesNotDeclareIsToBeDeclared() throws Exception {
        File root = project("egg", "egg-changed");

        List<ImportGuard.Finding> found = ImportFixes.undeclared(root, new File(root, EDIT),
                opened(root));

        assertEquals(1, found.size());
        ImportGuard.Finding finding = found.get(0);
        assertEquals(WHOLE, finding.item().iri());
        assertEquals("Declare it", ImportFixes.labelFor(finding));
        String warning = ImportFixes.warningFor(finding);
        assertTrue(warning.contains(WHOLE), warning);
        assertTrue(warning.contains("does not declare it"), warning);
        assertTrue(warning.contains("would take it out"), warning);
        assertTrue(warning.contains("the product whole"), "what declaring it makes: " + warning);
    }

    /**
     * Declared by its button, the project is as the update's own question leaves it when its
     * first answer is taken - the configuration, the module and the edit file, byte for byte -
     * and the result names the files.
     */
    @Test
    void declaredByItsButtonTheProjectIsAsTheUpdatesQuestionLeavesIt() throws Exception {
        File asked = project("asked", "egg-changed");
        File fixed = project("fixed", "egg-changed");
        OdkUpdateForm.run("Update project files", asked, true, itself(), noContainer(),
                new Asks(null));
        Asks nobody = new Asks(ImportGuard.Decision.REMOVE);

        OperationResult result = ImportFixes.declare("Declare an import", fixed, WHOLE,
                itself(), noContainer(), nobody);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(new ArrayList<List<ImportGuard.Finding>>(), nobody.asked,
                "the import whose button it is is not asked about again");
        Map<String, byte[]> theirs = Goldens.tree(asked);
        Map<String, byte[]> ours = Goldens.tree(fixed);
        assertEquals(new TreeSet<String>(theirs.keySet()), new TreeSet<String>(ours.keySet()));
        for (Map.Entry<String, byte[]> file : theirs.entrySet()) {
            assertArrayEquals(file.getValue(), ours.get(file.getKey()), file.getKey());
        }
        for (String path : new String[] {"src/ontology/eggboil-odk.yaml",
            "src/ontology/imports/whole_import.owl", EDIT}) {
            assertTrue(result.getFiles().contains(new File(fixed, path).getAbsoluteFile()),
                    path + " in " + result.getFiles());
        }
        assertTrue(text(fixed, EDIT).contains("Import(<https://example.org/eggboil/eggboil/"
                + "imports/whole_import.owl>)"), "its module in its place");
        assertFalse(text(fixed, EDIT).contains("Import(<" + WHOLE + ">)"));
        assertEquals(Collections.<ImportGuard.Finding>emptyList(),
                ImportFixes.undeclared(fixed, new File(fixed, EDIT), opened(fixed)),
                "nothing left to put right");
    }

    /** Another import the update would take out is asked about as always - and only it. */
    @Test
    void anotherImportTheUpdateWouldTakeOutIsStillAskedAbout() throws Exception {
        File root = project("two", "egg-changed");
        Files.write(new File(root, "src/ontology/spare.owl").toPath(), ("Prefix(:=<"
                + "https://example.invalid/spare#>)\nOntology(<" + SPARE + ">\nDeclaration("
                + "Class(:Fork))\n)\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "src/ontology/catalog-v001.xml").toPath(),
                text(root, "src/ontology/catalog-v001.xml").replace("</catalog>",
                        "  <uri name=\"" + SPARE + "\" uri=\"spare.owl\"/>\n</catalog>")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, EDIT).toPath(), text(root, EDIT).replace("Import(<" + WHOLE
                + ">)", "Import(<" + WHOLE + ">)\nImport(<" + SPARE + ">)")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals(2, ImportFixes.undeclared(root, new File(root, EDIT), opened(root)).size());
        Asks theOther = new Asks(ImportGuard.Decision.REMOVE);

        OperationResult result = ImportFixes.declare("Declare an import", root, WHOLE,
                itself(), noContainer(), theOther);

        assertTrue(result.isSuccess(), result.getSummary());
        assertEquals(1, theOther.asked.size());
        assertEquals(1, theOther.asked.get(0).size());
        assertEquals(SPARE, theOther.asked.get(0).get(0).item().iri());
        assertTrue(text(root, EDIT).contains("imports/whole_import.owl>)"), text(root, EDIT));
        assertFalse(text(root, EDIT).contains(SPARE), "taken out, as was answered");
    }

    /** What the update would take out is the edit file's: another file of the project has none. */
    @Test
    void anotherFileOfTheProjectHasNoneToPutRight() throws Exception {
        File root = project("module", "egg-changed");

        assertEquals(Collections.<ImportGuard.Finding>emptyList(), ImportFixes.undeclared(root,
                new File(root, "src/ontology/whole.owl"), opened(root)));
    }

    // ------------------------------------------------------------------ taken out

    /** One that cannot be read cannot be declared: its button takes it out of what is open. */
    @Test
    void anImportThatCannotBeReadIsToBeTakenOut() throws Exception {
        File root = project("broken", "egg-unloadable");
        OWLOntology open = opened(root);

        List<ImportGuard.Finding> found = ImportFixes.undeclared(root, new File(root, EDIT), open);

        assertEquals(1, found.size());
        ImportGuard.Finding finding = found.get(0);
        assertFalse(finding.isReadable());
        assertEquals("Take it out", ImportFixes.labelFor(finding));
        String warning = ImportFixes.warningFor(finding);
        assertTrue(warning.contains(finding.item().iri()), warning);
        assertTrue(warning.contains("cannot be read"), warning);
        assertTrue(warning.contains("taken out"), warning);

        open.getOWLOntologyManager().applyChanges(
                ImportFixes.takingOut(open, finding.item().iri()));
        for (OWLImportsDeclaration left : open.getImportsDeclarations()) {
            assertFalse(left.getIRI().toString().equals(finding.item().iri()), left.toString());
        }
    }
}
