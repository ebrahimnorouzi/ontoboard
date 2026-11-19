package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release file opened in Protégé is said for what it is, once for each file (1.155.0: the user
 * asked what happens when they open egg-full.owl or another of the release's files).
 */
class ReleaseProductNoticeTest {

    @TempDir
    File top;

    private File root;
    private File edit;
    private final Set<File> told = new HashSet<File>();

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
    void toldOnceAFile() throws Exception {
        File full = file(root, "egg-full.owl");

        String said = ReleaseProductNotice.decide(full, told);

        assertNotNull(said);
        assertTrue(said.contains("egg-full.owl"), said);
        assertTrue(said.contains("src/ontology/egg-edit.owl"), said);
        assertTrue(said.contains("the next release writes it again"), said);
        assertNull(ReleaseProductNotice.decide(full, told), "told once");
        assertNotNull(ReleaseProductNotice.decide(file(root, "egg.ttl"), told),
                "another release file is told of in its turn");
    }

    @Test
    void aPublishedReleaseIsSaidToBeOne() throws Exception {
        String said = ReleaseProductNotice.decide(file(root, "releases/1.0.0/egg.owl"), told);

        assertTrue(said.contains("1.0.0"), said);
        assertTrue(said.contains("published"), said);
    }

    @Test
    void notToldForTheEditFile() {
        assertNull(ReleaseProductNotice.decide(edit, told));
    }

    @Test
    void notToldForAFileOfNoProject() throws Exception {
        assertNull(ReleaseProductNotice.decide(file(top, "elsewhere/pizza.owl"), told));
        assertNull(ReleaseProductNotice.decide(null, told));
    }
}
