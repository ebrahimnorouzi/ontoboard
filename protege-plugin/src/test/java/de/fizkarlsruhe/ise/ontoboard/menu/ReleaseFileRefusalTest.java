package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseProducts;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No writing beside a release file (1.155.0). The user opened the egg project's root egg.owl -
 * a file ODK's release writes, and the next release writes again - imported two patterns into it,
 * their modules landing at the project's root, and released it as an ontology of its own. Each
 * action that writes beside the open file is refused there before it asks or writes anything,
 * says where to work instead, and offers the edit file.
 */
class ReleaseFileRefusalTest {

    @TempDir
    File top;

    private File root;
    private File edit;
    private File released;
    private File kept;

    private static File file(File folder, String path) throws Exception {
        File file = new File(folder, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @BeforeEach
    void theEggProject() throws Exception {
        root = new File(top, "egg");
        edit = file(root, "src/ontology/egg-edit.owl");
        file(root, "src/ontology/egg-odk.yaml");
        released = file(root, "egg.owl");
        kept = file(root, "releases/2026-11-19/egg.owl");
    }

    /** Refused in both kinds of release file, never in the edit file, and nothing written. */
    private void refused(String work) throws Exception {
        refused(work, file -> ReleaseProducts.refusal(file, work));
    }

    private void refused(String work, java.util.function.Function<File, String> said) throws Exception {
        assertNotNull(work, "the action says what it would write beside the file");
        long before = count(root);

        assertEquals(said.apply(released), ReleaseFileRefusal.of(released, work));
        assertEquals(said.apply(kept), ReleaseFileRefusal.of(kept, work));
        assertNotNull(ReleaseFileRefusal.of(released, work));
        assertNotNull(ReleaseFileRefusal.of(kept, work));
        assertTrue(ReleaseFileRefusal.of(released, work).contains("edit file"),
                "and where to work instead: " + ReleaseFileRefusal.of(released, work));
        assertNull(ReleaseFileRefusal.of(edit, work), "the edit file is where the work goes");
        assertEquals(before, count(root), "nothing written in the project");
    }

    private static long count(File folder) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> all = Files.walk(folder.toPath())) {
            return all.count();
        }
    }

    @Test
    void aPatternIsNotImportedIntoARelease() throws Exception {
        refused(PatternLibraryAction.WORK);
    }

    @Test
    void termsAreNotImportedIntoARelease() throws Exception {
        refused(new ImportTermsAction().worksBesideTheFile());
    }

    @Test
    void importsAreNotRefreshedInARelease() throws Exception {
        refused(new RefreshImportsAction().worksBesideTheFile());
    }

    /** Releasing one is refused in a sentence of its own: a release is made from the edit file. */
    @Test
    void aReleaseFileIsNotReleased() throws Exception {
        refused(new ReleaseAction().worksBesideTheFile(), ReleaseProducts::releaseRefusal);
        assertTrue(ReleaseProducts.releaseRefusal(released).contains("release from there"),
                ReleaseProducts.releaseRefusal(released));
    }

    @Test
    void anOntologyInNoProjectIsWorkedOnAsBefore() throws Exception {
        File alone = file(top, "alone/pizza.owl");

        assertNull(ReleaseFileRefusal.of(alone, new ReleaseAction().worksBesideTheFile()));
    }
}
