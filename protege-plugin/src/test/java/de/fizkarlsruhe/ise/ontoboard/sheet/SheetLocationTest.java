package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a project's templates live.
 *
 * <p>So that opening the sheet editor does not begin with a file chooser and knowing the
 * answer already. An ODK project has one obvious place, and every real project to hand uses
 * it: {@code src/templates} beside {@code src/ontology}.
 */
class SheetLocationTest {

    @TempDir
    File dir;

    private File odkEditFile() throws Exception {
        File ontology = new File(new File(dir, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        File edit = new File(ontology, "mwo-edit.owl");
        Files.write(edit.toPath(), "Ontology(<http://example.org/mwo>)"
                .getBytes(Charset.forName("UTF-8")));
        return edit;
    }

    @Test
    void anOdkProjectKeepsThemBesideTheOntologyDirectory() throws Exception {
        File edit = odkEditFile();

        File templates = SheetLocation.forOntologyFile(edit);

        assertNotNull(templates);
        assertEquals("templates", templates.getName());
        assertEquals(new File(dir, "src"), templates.getParentFile(),
                "src/templates, which is where ODK's own Makefile reads them from");
        assertTrue(SheetLocation.isOdkLayout(edit));
    }

    /**
     * An ontology that is not in an ODK layout has no such convention, so the answer is a
     * folder beside it: still predictable, still in the repository.
     */
    @Test
    void anOntologyOutsideAnOdkLayoutGetsAFolderBesideIt() throws Exception {
        File loose = new File(dir, "pizza.owl");
        Files.write(loose.toPath(), "Ontology(<http://example.org/pizza>)"
                .getBytes(Charset.forName("UTF-8")));

        File templates = SheetLocation.forOntologyFile(loose);

        assertEquals(new File(dir, "templates"), templates);
        assertFalse(SheetLocation.isOdkLayout(loose));
    }

    /** A folder called ontology that is not under src is not an ODK layout. */
    @Test
    void theShapeHasToBeSrcOntologyAndNotJustOntology() throws Exception {
        File ontology = new File(dir, "ontology");
        assertTrue(ontology.mkdirs());
        File edit = new File(ontology, "x-edit.owl");
        Files.write(edit.toPath(), "Ontology(<http://example.org/x>)"
                .getBytes(Charset.forName("UTF-8")));

        assertFalse(SheetLocation.isOdkLayout(edit));
        assertEquals(new File(ontology, "templates"), SheetLocation.forOntologyFile(edit));
    }

    /** Nothing is created by asking where things go. */
    @Test
    void askingWhereTheyGoDoesNotCreateAnything() throws Exception {
        File edit = odkEditFile();

        File templates = SheetLocation.forOntologyFile(edit);

        assertFalse(templates.exists(),
                "opening an editor must not put a directory into somebody's repository");
    }

    @Test
    void creatingItIsSeparateAndRepeatable() throws Exception {
        File templates = SheetLocation.forOntologyFile(odkEditFile());

        assertNotNull(SheetLocation.create(templates));
        assertTrue(templates.isDirectory());
        assertNotNull(SheetLocation.create(templates), "creating it twice is not an error");
        assertNull(SheetLocation.create(null));
    }

    @Test
    void sheetsAreCountedAndOtherFilesAreNot() throws Exception {
        File templates = SheetLocation.create(SheetLocation.forOntologyFile(odkEditFile()));
        write(new File(templates, "terms.tsv"), "a");
        write(new File(templates, "more.csv"), "b");
        write(new File(templates, "notes.md"), "c");
        write(new File(templates, "sheet.xlsx"), "d");

        assertEquals(2, SheetLocation.sheetsIn(templates));
        assertEquals(0, SheetLocation.sheetsIn(new File(dir, "nope")));
        assertEquals(0, SheetLocation.sheetsIn(null));
    }

    @Test
    void aNewSheetNeverOverwritesOneThatIsThere() throws Exception {
        File templates = SheetLocation.create(SheetLocation.forOntologyFile(odkEditFile()));

        File first = SheetLocation.freeNameIn(templates, "terms");
        assertEquals("terms.tsv", first.getName());
        write(first, "x");

        assertEquals("terms-2.tsv", SheetLocation.freeNameIn(templates, "terms").getName());
        write(new File(templates, "terms-2.tsv"), "x");
        assertEquals("terms-3.tsv", SheetLocation.freeNameIn(templates, "terms").getName());
    }

    @Test
    void nullsAreSurvivable() {
        assertNull(SheetLocation.forOntologyFile(null));
        assertFalse(SheetLocation.isOdkLayout(null));
        assertNull(SheetLocation.freeNameIn(null, "terms"));
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(Charset.forName("UTF-8")));
    }
}
