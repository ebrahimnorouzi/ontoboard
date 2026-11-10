package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A term list left by an import that Edit &gt; Undo took back is not "imported before" (1.135.1,
 * Windows study rehearsal). A tester imported the wrong IAO term in T4, undid it and imported
 * report: the list still named the wrong term, so the import said "iao is imported already",
 * cut both, and wrote "641 axioms for 2 terms" where the guide says 622 for 1.
 */
class ImportAfterUndoTest {

    @TempDir
    File root;

    private File list;
    private File module;
    private OWLOntology edit;
    private OWLOntologyManager manager;

    @BeforeEach
    void project() throws Exception {
        File imports = new File(root, "src/ontology/imports");
        assertTrue(imports.mkdirs());
        list = new File(imports, "iao_terms.txt");
        module = new File(imports, "iao_import.owl");
        Files.write(list.toPath(), ("# Terms extracted into iao_import.owl\n"
                + "# Source: http://purl.obolibrary.org/obo/iao.owl\n"
                + "http://purl.obolibrary.org/obo/IAO_0000614 # case report textual entity\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(module.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        manager = OWLManager.createOWLOntologyManager();
        edit = manager.createOntology(IRI.create("https://example.org/eggboil/eggboil.owl"));
    }

    @Test
    void aListNothingImportsNowWasLeftByAnImportTakenBack() {
        assertTrue(ImportTermsAction.leftByAnImportTakenBack(edit, list, module));
    }

    @Test
    void aListWhoseModuleIsImportedIsTheImportsOwn() {
        manager.applyChange(new AddImport(edit, manager.getOWLDataFactory()
                .getOWLImportsDeclaration(IRI.create("https://example.org/eggboil/eggboil/imports/iao_import.owl"))));
        assertFalse(ImportTermsAction.leftByAnImportTakenBack(edit, list, module));
    }

    @Test
    void onlyAListOntoBoardWroteForThatModuleCounts() throws Exception {
        Files.write(list.toPath(), "# my own notes\nhttp://purl.obolibrary.org/obo/IAO_0000088\n"
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(ImportTermsAction.leftByAnImportTakenBack(edit, list, module));
        assertFalse(ImportTermsAction.leftByAnImportTakenBack(edit, list,
                new File(module.getParentFile(), "other_import.owl")));
        assertFalse(ImportTermsAction.leftByAnImportTakenBack(null, list, module));
    }

    @Test
    void aListWithoutItsModuleIsNotTouched() throws Exception {
        assertTrue(module.delete());
        assertFalse(ImportTermsAction.leftByAnImportTakenBack(edit, list, module));
    }
}
