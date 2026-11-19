package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's board look (1.155.0): kept in the board's sidecar, so it is shared through git with
 * the ontology, and over each person's own.
 */
class ProjectLookTest {

    private static Map<String, String> look() {
        Map<String, String> look = new LinkedHashMap<String, String>();
        look.put("obClass.fill", "#FFEEDD");
        look.put("board.spacing", "roomy");
        return look;
    }

    @Test
    void theSidecarKeepsAProjectsLook(@TempDir Path dir) throws Exception {
        File ontology = dir.resolve("o-edit.owl").toFile();
        CanvasLayout layout = new CanvasLayout();
        layout.look = look();

        CanvasLayoutStore.save(ontology, layout);

        assertEquals(look(), CanvasLayoutStore.load(ontology).look);
    }

    @Test
    void aBoardWithNoLookOfItsOwnWritesNone(@TempDir Path dir) throws Exception {
        File ontology = dir.resolve("o-edit.owl").toFile();

        CanvasLayoutStore.save(ontology, new CanvasLayout());

        String json = new String(Files.readAllBytes(CanvasLayoutStore.sidecarFor(ontology).toPath()),
                StandardCharsets.UTF_8);
        assertFalse(json.contains("\"look\""), "a sidecar of 1.154.0's shape: " + json);
    }

    @Test
    void undoAndCopiesCarryTheLook() {
        CanvasLayout layout = new CanvasLayout();
        layout.look = look();

        CanvasLayout copy = layout.copy();
        CanvasLayout back = new CanvasLayout();
        back.copyFrom(layout);

        assertEquals(look(), copy.look);
        assertEquals(look(), back.look);
        copy.look.put("obClass.fill", "#000000");
        assertTrue(layout.look.get("obClass.fill").equals("#FFEEDD"), "a copy is its own");
    }
}
