package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
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

    // ---------- a project whose ID ranges name nobody (1.155.0) ----------

    /** ODK's seeded project: the edit file and the ranges file allocated to the placeholders. */
    private static File seededProject(File root) throws Exception {
        File ontology = new File(root, "src/ontology");
        assertTrue(ontology.mkdirs());
        java.util.Map<String, byte[]> seed = Goldens.seedTree("eggboil");
        for (String name : new String[] {"eggboil-edit.owl", "eggboil-idranges.owl"}) {
            Files.write(new File(ontology, name).toPath(), seed.get("src/ontology/" + name));
        }
        return new File(ontology, "eggboil-edit.owl");
    }

    private static String ranges(File editFile) throws Exception {
        return new String(Files.readAllBytes(new File(editFile.getParentFile(),
                "eggboil-idranges.owl").toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Protege reads the ranges as it opens the project and asked "No Id Range Found For Current
     * User" - and again each time the edit file was read again, after every pattern imported.
     * Asked here first, before the project opens: the board's question.
     */
    @Test
    void aProjectWhoseRangesNameNobodyIsAskedAboutBeforeItOpens(@TempDir File root)
            throws Exception {
        File edit = seededProject(root);
        java.util.List<String[]> asked = new java.util.ArrayList<String[]>();

        String claimed = ProjectOpener.claimBeforeOpening(edit, "Ada Lovelace", choices -> {
            asked.add(choices);
            return choices[0];
        });

        assertEquals("ONTOLOGY-CREATOR", claimed);
        assertEquals(1, asked.size());
        assertEquals(2, asked.get(0).length);
        assertTrue(asked.get(0)[0].startsWith("ONTOLOGY-CREATOR: 0 to 999999"), asked.get(0)[0]);
        assertTrue(ranges(edit).contains("allocatedto: \"Ada Lovelace\""), ranges(edit));
    }

    @Test
    void cancelledNothingIsWritten(@TempDir File root) throws Exception {
        File edit = seededProject(root);
        String before = ranges(edit);

        assertNull(ProjectOpener.claimBeforeOpening(edit, "Ada Lovelace", choices -> null));
        assertEquals(before, ranges(edit));
    }

    /**
     * A project that chose readable names is not told its new terms are numbered from the range
     * claimed: the board names them from what is typed, and the range is Protege's, for its own
     * Add subclass... (the 1.155.0 review's M11).
     */
    @Test
    void aProjectWithReadableNamesIsNotToldItsTermsAreNumbered() {
        String readable = ProjectOpener.openingQuestion("Ada", "eggboil-idranges.owl", true);
        String numbered = ProjectOpener.openingQuestion("Ada", "eggboil-idranges.owl", false);

        assertFalse(readable.contains("numbered from it"), readable);
        assertTrue(readable.contains("from what you type"), readable);
        assertTrue(numbered.contains("new terms are numbered from it"), numbered);
    }

    /** Once a range names somebody, the project allocates as it does: nothing is asked. */
    @Test
    void aProjectWhoseRangesNameSomebodyIsNotAsked(@TempDir File root) throws Exception {
        File edit = seededProject(root);
        ProjectOpener.claimBeforeOpening(edit, "Grace Hopper", choices -> choices[0]);

        assertNull(ProjectOpener.claimBeforeOpening(edit, "Ada Lovelace", choices -> {
            throw new AssertionError("asked");
        }));
        assertNull(ProjectOpener.claimBeforeOpening(edit, "Grace Hopper", choices -> {
            throw new AssertionError("asked");
        }));
    }
}
