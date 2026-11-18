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

    /** A real licence: ROBOT reports a missing ontology licence as an ERROR, and
     * the generated CI fails on errors, so a project without one fails its own build. */
    private static final String CC0 =
            "https://creativecommons.org/publicdomain/zero/1.0/";

    /** A project as New ODK project makes it: ODK's seed. */
    private static File scaffold(Path dir) {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "Materials Workflow Ontology",
                "", "http://purl.obolibrary.org/obo/mwo.owl", CC0, dir.toFile());
        SeededProject.create(config);
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
        // No configuration to say which is ODK's (with one, it says: below).
        assertTrue(new File(ontology, "mwo-odk.yaml").delete());

        OdkProjectLoader.NotAnOdkProjectException thrown =
                assertThrows(OdkProjectLoader.NotAnOdkProjectException.class,
                        () -> OdkProjectLoader.detect(root));
        assertTrue(thrown.getMessage().contains("mwo-edit.owl"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("other-edit.owl"), thrown.getMessage());
    }

    /**
     * Ruling 17: the configuration says which file is the edit file - ODK's Makefile builds
     * from {@code $(ONT)-edit.$(EDIT_FORMAT)} and from nothing else - so another file named
     * like one is not a second candidate.
     */
    @Test
    void theConfigurationSaysWhichEditFileIsOdks(@TempDir Path dir) throws Exception {
        File root = scaffold(dir);
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(new File(ontology, "other-edit.owl").createNewFile());

        assertEquals("mwo-edit.owl", OdkProjectLoader.detect(root).getEditFile().getName());
        assertEquals(new File(ontology, "mwo-edit.owl").getAbsoluteFile(),
                OdkProjectLoader.editFileOf(ontology).getAbsoluteFile());
    }

    /**
     * Ruling 17: an ODK project edits in the format its configuration names - 15 of ODK's 79
     * samples in OBO, one in Turtle - and it opens at that file, under its own id.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"tests__test-edit-obo, eto, eto-edit.obo",
        "examples__chr, chr, chr-edit.ttl"})
    void aProjectThatEditsInAnotherFormatOpensAtItsEditFile(String sample, String id,
            String editFile, @TempDir Path dir) throws Exception {
        File edit = SeededProject.ofCorpus(dir.toFile(), sample);
        assertTrue(edit.isFile(), edit + " seeded");
        File root = dir.resolve(sample).toFile();

        OdkProjectLoader.Detected found = OdkProjectLoader.detect(root);

        assertEquals(id, found.getOntologyId());
        assertEquals(editFile, found.getEditFile().getName());
        assertEquals(edit.getAbsoluteFile(), OdkProjectLoader.editFileOf(
                edit.getParentFile()).getAbsoluteFile());
    }

    /** Ruling 17: a name is an edit file's in every format ODK writes one in, and only then. */
    @Test
    void anEditFileIsNamedSoInEveryFormatOdkWrites() {
        for (String name : new String[] {"x-edit.owl", "x-edit.obo", "x-edit.ofn",
            "x-edit.ttl"}) {
            assertTrue(OdkProjectLoader.isEditFileName(name), name);
        }
        for (String name : new String[] {"x.owl", "x-edit.owl.bak", "x-edit.txt",
            "x-editor.owl", "-edit.owl"}) {
            org.junit.jupiter.api.Assertions.assertFalse(OdkProjectLoader.isEditFileName(name),
                    name);
        }
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

    /** The targets are read from the project's Makefile, which is ODK's: its names. */
    @Test
    void offersMakefileTargetsWhenAMakefileIsPresent(@TempDir Path dir) {
        OdkProjectLoader.Detected found = OdkProjectLoader.detect(scaffold(dir));
        assertTrue(OdkProjectLoader.availableTargets(found).contains("test"));
        assertTrue(OdkProjectLoader.availableTargets(found).contains("prepare_release"));
        assertTrue(!OdkProjectLoader.availableTargets(found).contains("reason"),
                "a target of the Makefile OntoBoard wrote up to 1.135, not of ODK's");
    }
}
