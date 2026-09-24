package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

/** The term lists that make an import module rebuildable. */
class ImportModulesTest {

    private static final String IAO_1 = "http://purl.obolibrary.org/obo/IAO_0000115";
    private static final String IAO_2 = "http://purl.obolibrary.org/obo/IAO_0000116";

    private static List<IRI> iris(String... values) {
        IRI[] created = new IRI[values.length];
        for (int i = 0; i < values.length; i++) {
            created[i] = IRI.create(values[i]);
        }
        return Arrays.asList(created);
    }

    @Test
    void atermListRoundTrips(@TempDir File dir) throws Exception {
        File written = ImportModules.writeTerms(dir, "iao", iris(IAO_2, IAO_1),
                "http://purl.obolibrary.org/obo/iao.owl");

        assertEquals("iao_terms.txt", written.getName());
        assertEquals(iris(IAO_1, IAO_2), ImportModules.readTerms(written));
        assertEquals("http://purl.obolibrary.org/obo/iao.owl", ImportModules.sourceIn(written));
    }

    /**
     * Written sorted, so a refresh does not produce a diff in which nothing really moved.
     *
     * <p>Extraction order is not stable, and a term list that reshuffles on every rebuild makes
     * every refresh look like a change - after which nobody reads the diffs.
     */
    @Test
    void thelistIsSortedAndDeduplicated(@TempDir File dir) throws Exception {
        File written = ImportModules.writeTerms(dir, "iao", iris(IAO_2, IAO_1, IAO_2), null);

        String text = new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.indexOf(IAO_1) < text.indexOf(IAO_2), text);
        assertEquals(2, ImportModules.readTerms(written).size(), "the duplicate must be dropped");
    }

    /**
     * The source is recorded, because a term list without it is half a recipe.
     *
     * <p>It says which terms to take and not where from, and the next person has to guess which of
     * forty OBO ontologies owns them.
     */
    @Test
    void theSourceIsRecordedAndReadBack(@TempDir File dir) throws Exception {
        File withSource = ImportModules.writeTerms(dir, "iao", iris(IAO_1), "http://x.org/iao.owl");
        assertEquals("http://x.org/iao.owl", ImportModules.sourceIn(withSource));

        File without = ImportModules.writeTerms(dir, "other", iris(IAO_1), null);
        assertEquals(null, ImportModules.sourceIn(without));
    }

    /**
     * An import with a term list but no module yet is still listed.
     *
     * <p>That is a freshly cloned repository before the build has run - exactly the state this
     * whole class exists to make possible, so it must not be the state that makes it invisible.
     */
    @Test
    void animportWithNoModuleYetIsStillFound(@TempDir File dir) throws Exception {
        ImportModules.writeTerms(dir, "iao", iris(IAO_1), "http://x.org/iao.owl");

        List<ImportModules.Module> modules = ImportModules.modulesIn(dir);

        assertEquals(1, modules.size());
        assertEquals("iao", modules.get(0).getName());
        assertTrue(modules.get(0).isRebuildable(), "it has a term list");
        assertFalse(modules.get(0).hasModule(), "the build has not run yet");
    }

    /** A module with no term list is the state worth reporting: nobody can rebuild it. */
    @Test
    void amoduleWithNoTermListIsFoundAndFlagged(@TempDir File dir) throws Exception {
        File imports = ImportModules.importsDirectoryIn(dir);
        assertTrue(imports.mkdirs());
        Files.write(new File(imports, "chebi_import.owl").toPath(),
                "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        List<ImportModules.Module> modules = ImportModules.modulesIn(dir);

        assertEquals(1, modules.size());
        assertEquals("chebi", modules.get(0).getName());
        assertTrue(modules.get(0).hasModule());
        assertFalse(modules.get(0).isRebuildable(),
                "no term list means nobody can regenerate it, and that is what to report");
    }

    /** Both halves of an import are matched up under one name. */
    @Test
    void amoduleAndItsListAreOneImport(@TempDir File dir) throws Exception {
        ImportModules.writeTerms(dir, "iao", iris(IAO_1), "http://x.org/iao.owl");
        Files.write(ImportModules.moduleFileFor(dir, "iao").toPath(),
                "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        List<ImportModules.Module> modules = ImportModules.modulesIn(dir);

        assertEquals(1, modules.size(), "one import, not two: " + modules.size());
        assertTrue(modules.get(0).hasModule());
        assertTrue(modules.get(0).isRebuildable());
    }

    /**
     * A line that is not an IRI is reported rather than dropped.
     *
     * <p>The file is one a human edits. Losing the other forty terms over one bad line would be
     * the wrong trade, but so would saying nothing about it.
     */
    @Test
    void malformedLinesAreSkippedAndReported(@TempDir File dir) throws Exception {
        File terms = ImportModules.termsFileFor(dir, "iao");
        assertTrue(terms.getParentFile().mkdirs());
        Files.write(terms.toPath(), ("# a comment\n"
                + IAO_1 + "\n"
                + "not an iri at all\n"
                + "\n"
                + IAO_2 + "\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(iris(IAO_1, IAO_2), ImportModules.readTerms(terms));
        assertEquals(Arrays.asList("not an iri at all"), ImportModules.malformedIn(terms));
    }

    @Test
    void aprojectWithNoImportsIsEmptyRatherThanAFailure(@TempDir File dir) throws Exception {
        assertTrue(ImportModules.modulesIn(dir).isEmpty());
        assertTrue(ImportModules.modulesIn(null).isEmpty());
        assertTrue(ImportModules.readTerms(new File(dir, "nope.txt")).isEmpty());
        assertEquals(null, ImportModules.sourceIn(new File(dir, "nope.txt")));
    }

    /**
     * A project with a mirror can refresh offline; one without cannot.
     *
     * <p>That is the whole point of ODK's mirror/ directory, and the question Refresh imports...
     * answers before it offers to rebuild: will this need the network?
     */
    @Test
    void amirrorIsDetectedOnlyWhenThereIsSomethingInIt(@TempDir File dir) throws Exception {
        assertFalse(ImportModules.hasMirror(dir), "nothing mirrored yet");
        assertFalse(ImportModules.hasMirror(null));

        File mirror = ImportModules.mirrorDirectoryIn(dir);
        assertTrue(mirror.mkdirs());
        assertFalse(ImportModules.hasMirror(dir), "an empty mirror directory is not a mirror");

        Files.write(new File(mirror, "notes.txt").toPath(),
                "not an ontology".getBytes(StandardCharsets.UTF_8));
        assertFalse(ImportModules.hasMirror(dir), "a stray text file is not a mirrored ontology");

        Files.write(new File(mirror, "iao.owl").toPath(),
                "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        assertTrue(ImportModules.hasMirror(dir), "now there is something to extract from");
    }

    /** The mirror lives where ODK puts it, beside imports rather than inside it. */
    @Test
    void themirrorIsWhereOdkPutsIt(@TempDir File dir) {
        assertEquals("mirror", ImportModules.mirrorDirectoryIn(dir).getName());
        assertEquals("ontology", ImportModules.mirrorDirectoryIn(dir).getParentFile().getName());
        assertEquals(ImportModules.importsDirectoryIn(dir).getParentFile(),
                ImportModules.mirrorDirectoryIn(dir).getParentFile());
    }
}
