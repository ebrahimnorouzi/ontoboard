package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Opening a project that is open in this window already. Protégé listed the eleven ontologies it
 * had ("They have not been replaced/overwritten") and OntoBoard then said "Project opened",
 * though nothing was opened (Phase 7, nfdicore in the probe host, 2026-11-20).
 */
class ProjectOpenerTest {

    private static File ontologyFile(File folder, String name) throws Exception {
        File file = new File(folder, name);
        Files.write(file.toPath(), ("Ontology(<https://example.org/" + name + ">\n"
                + "Declaration(Class(<https://example.org/A>))\n)\n")
                .getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void theProjectOpenInThisWindowIsFoundByItsEditFile(@TempDir File folder) throws Exception {
        File edit = ontologyFile(folder, "proj-edit.owl");
        File other = ontologyFile(folder, "other-edit.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology open = manager.loadOntologyFromOntologyDocument(edit);

        assertSame(open, ProjectOpener.openFrom(manager, edit));
        assertSame(open, ProjectOpener.openFrom(manager,
                new File(new File(folder, "."), "proj-edit.owl")), "the same file, spelt otherwise");
        assertNull(ProjectOpener.openFrom(manager, other));
    }

    @Test
    void whatIsSaidNamesTheProjectAndHowToReadItAgain(@TempDir File folder) throws Exception {
        File edit = ontologyFile(folder, "proj-edit.owl");

        String said = ProjectOpener.alreadyOpen("Project Ontology", edit);

        assertTrue(said.contains("Project Ontology"), said);
        assertTrue(said.contains("already open in this window"), said);
        assertTrue(said.contains(edit.getAbsolutePath()), said);
        assertTrue(said.contains("Nothing was read again"), said);
        assertTrue(said.contains("File > Reload"), said);
    }
}
