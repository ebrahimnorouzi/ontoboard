package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OdkProjectLoaderTest {

    private static File scaffold(Path dir) {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "Materials Workflow Ontology",
                "", "http://purl.obolibrary.org/obo/mwo.owl", "", dir.toFile());
        OdkScaffold.create(config);
        return config.getProjectRoot();
    }

    @Test
    void detectsAProjectFromItsRepositoryRoot() {
        File root = scaffold(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),
                "odkdetect-" + System.nanoTime()));
        OdkProjectLoader.Detected found = OdkProjectLoader.detect(root);

        assertEquals("mwo", found.getOntologyId());
        assertEquals("mwo-edit.owl", found.getEditFile().getName());
        assertEquals(root.getAbsolutePath(), found.getProjectRoot().getAbsolutePath());
    }

    /** A file chooser pointed at src/ontology is just as reasonable as the repo root. */
    @Test
    void detectsAProjectFromTheOntologyFolderItself(@TempDir Path dir) {
        File root = scaffold(dir);
        File ontology = new File(new File(root, "src"), "ontology");

        OdkProjectLoader.Detected found = OdkProjectLoader.detect(ontology);
        assertEquals("mwo", found.getOntologyId());
        assertEquals(root.getAbsolutePath(), found.getProjectRoot().getAbsolutePath());
    }

    @Test
    void detectsAProjectFromTheSrcFolder(@TempDir Path dir) {
        File root = scaffold(dir);
        OdkProjectLoader.Detected found = OdkProjectLoader.detect(new File(root, "src"));
        assertEquals("mwo", found.getOntologyId());
    }

    /** The title in the ODK YAML is what the user recognises; the id is a fallback. */
    @Test
    void readsTheTitleFromTheOdkYaml(@TempDir Path dir) {
        File root = scaffold(dir);
        assertEquals("Materials Workflow Ontology", OdkProjectLoader.detect(root).getTitle());
    }

    @Test
    void fallsBackToTheIdWhenTheYamlIsMissing(@TempDir Path dir) {
        File root = scaffold(dir);
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(new File(ontology, "mwo-odk.yaml").delete());

        assertEquals("mwo", OdkProjectLoader.detect(root).getTitle());
    }

    /** The YAML is ODK's file, not ours; malformed content must not stop a project opening. */
    @Test
    void aMalformedYamlDoesNotPreventOpening(@TempDir Path dir) throws Exception {
        File root = scaffold(dir);
        File yaml = new File(new File(new File(root, "src"), "ontology"), "mwo-odk.yaml");
        Files.write(yaml.toPath(), "{{{ not yaml ][".getBytes(Charset.forName("UTF-8")));

        assertEquals("mwo", OdkProjectLoader.detect(root).getTitle());
        assertEquals("mwo-edit.owl", OdkProjectLoader.detect(root).getEditFile().getName());
    }

    @Test
    void rejectsAFolderThatIsNotAnOdkProject(@TempDir Path dir) {
        OdkProjectLoader.NotAnOdkProjectException thrown =
                assertThrows(OdkProjectLoader.NotAnOdkProjectException.class,
                        () -> OdkProjectLoader.detect(dir.toFile()));
        assertTrue(thrown.getMessage().contains("src/ontology"),
                "the message must say what was expected: " + thrown.getMessage());
    }

    @Test
    void rejectsAFileRatherThanAFolder(@TempDir Path dir) throws Exception {
        File file = dir.resolve("thing.owl").toFile();
        assertTrue(file.createNewFile());
        assertThrows(OdkProjectLoader.NotAnOdkProjectException.class,
                () -> OdkProjectLoader.detect(file));
    }

    /**
     * Opening the wrong edit file means edits land somewhere the build regenerates, so an
     * ambiguous project must refuse rather than pick one.
     */
    @Test
    void refusesWhenThereIsMoreThanOneEditFile(@TempDir Path dir) throws Exception {
        File root = scaffold(dir);
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(new File(ontology, "other-edit.owl").createNewFile());

        OdkProjectLoader.NotAnOdkProjectException thrown =
                assertThrows(OdkProjectLoader.NotAnOdkProjectException.class,
                        () -> OdkProjectLoader.detect(root));
        assertTrue(thrown.getMessage().contains("mwo-edit.owl"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("other-edit.owl"), thrown.getMessage());
    }

    /** The release .owl is generated; selecting it would silently discard the user's work. */
    @Test
    void doesNotOfferTheGeneratedReleaseFile(@TempDir Path dir) {
        File root = scaffold(dir);
        assertTrue(OdkProjectLoader.detect(root).getEditFile().getName().endsWith("-edit.owl"));
    }

    @Test
    void looksLikeOdkProjectAnswersWithoutThrowing(@TempDir Path dir) {
        assertTrue(!OdkProjectLoader.looksLikeOdkProject(dir.toFile()));
        assertTrue(OdkProjectLoader.looksLikeOdkProject(scaffold(dir)));
    }

    @Test
    void offersMakefileTargetsWhenAMakefileIsPresent(@TempDir Path dir) {
        OdkProjectLoader.Detected found = OdkProjectLoader.detect(scaffold(dir));
        assertTrue(OdkProjectLoader.availableTargets(found).contains("report"));
        assertTrue(OdkProjectLoader.availableTargets(found).contains("reason"));
    }
}
