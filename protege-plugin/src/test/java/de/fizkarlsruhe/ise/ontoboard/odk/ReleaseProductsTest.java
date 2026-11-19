package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release file of an ODK project, opened in Protégé, is known for what it is (1.155.0). The
 * user opened the egg project's root egg.owl, imported two patterns into it - their modules went
 * beside the open file, at the project's root - and released it as an ontology of its own into
 * the project's releases/.
 */
class ReleaseProductsTest {

    @TempDir
    File top;

    private File root;
    private File edit;

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
    }

    @Test
    void theRootOwlIsAProduct() throws Exception {
        ReleaseProducts.Product product = ReleaseProducts.of(file(root, "egg.owl"));

        assertNotNull(product);
        assertEquals("egg", product.id());
        assertEquals(ReleaseProducts.Where.TOP, product.where());
        assertEquals(edit.getCanonicalFile(), product.editFile().getCanonicalFile());
        assertEquals(root.getCanonicalFile(), product.projectRoot().getCanonicalFile());
        assertNull(product.version());
    }

    @Test
    void theVariantsAndFormatsAreProducts() throws Exception {
        for (String name : new String[] {"egg-full.owl", "egg-base.owl", "egg-base.obo",
            "egg-simple.owl", "egg-basic.json", "egg-simple-non-classified.owl", "egg.obo",
            "egg.json", "egg.ttl"}) {
            assertNotNull(ReleaseProducts.of(file(root, name)), name);
        }
    }

    @Test
    void aDatedCopyIsAProduct() throws Exception {
        ReleaseProducts.Product product = ReleaseProducts.of(
                file(root, "releases/2026-11-19/egg.owl"));

        assertNotNull(product);
        assertEquals(ReleaseProducts.Where.RELEASES, product.where());
        assertEquals("2026-11-19", product.version());
        assertEquals(edit.getCanonicalFile(), product.editFile().getCanonicalFile());
    }

    @Test
    void theEditFileIsNot() {
        assertNull(ReleaseProducts.of(edit));
    }

    @Test
    void aStandaloneFileIsNot() throws Exception {
        assertNull(ReleaseProducts.of(file(top, "pizza.owl")));
        assertNull(ReleaseProducts.of(null));
    }

    @Test
    void anotherFileAtTheRootIsNot() throws Exception {
        assertNull(ReleaseProducts.of(file(root, "notes.owl")));
        assertNull(ReleaseProducts.of(file(root, "eggs.owl")));
    }

    @Test
    void aFolderWithoutAnEditFileIsNoProject() throws Exception {
        File other = new File(top, "other");
        file(other, "src/ontology/other-odk.yaml");

        assertNull(ReleaseProducts.of(file(other, "other.owl")),
                "a configuration without an edit file is not a project to send anybody to");
    }

    @Test
    void theRefusalNamesTheEditFileAndWhatWouldBeLost() throws Exception {
        String refusal = ReleaseProducts.refusal(file(root, "egg.owl"), "the pattern's import");

        assertNotNull(refusal);
        assertTrue(refusal.contains("egg.owl"), refusal);
        assertTrue(refusal.contains("src/ontology/egg-edit.owl"), refusal);
        assertTrue(refusal.contains("the pattern's import"), refusal);
        assertTrue(refusal.contains("next release"), refusal);
        assertNull(ReleaseProducts.refusal(edit, "the pattern's import"));
    }

    @Test
    void aPublishedReleaseSaysItIsPublished() throws Exception {
        String refusal = ReleaseProducts.refusal(file(root, "releases/2026-11-19/egg.owl"),
                "a release");

        assertNotNull(refusal);
        assertTrue(refusal.contains("2026-11-19"), refusal);
        assertTrue(refusal.contains("published"), refusal);
    }
}
