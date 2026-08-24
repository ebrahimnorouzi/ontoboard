package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanvasLayoutStoreTest {

    @Test
    void sidecarNameAppendsToTheFullOntologyFileName(@TempDir Path dir) {
        File ontology = dir.resolve("myont-edit.owl").toFile();
        assertEquals("myont-edit.owl.ontoboard.json",
                CanvasLayoutStore.sidecarFor(ontology).getName());
    }

    @Test
    void missingSidecarYieldsAnEmptyLayoutRatherThanFailing(@TempDir Path dir) {
        CanvasLayout layout = CanvasLayoutStore.load(dir.resolve("absent.owl").toFile());
        assertEquals(CanvasLayout.CURRENT_VERSION, layout.version);
        assertTrue(layout.onCanvas.isEmpty());
        assertTrue(layout.nodes.isEmpty());
    }

    @Test
    void roundTripsAllPresentationState(@TempDir Path dir) {
        File ontology = dir.resolve("round.owl").toFile();

        CanvasLayout original = new CanvasLayout();
        original.ontologyIri = "http://example.org/tiny";
        original.onCanvas.add("http://example.org/tiny#Person");
        original.nodes.put("http://example.org/tiny#Person", new CanvasLayout.NodeLayout(120, 40));
        original.prefixColors.put("ex", "#4A90D9");

        CanvasLayoutStore.save(ontology, original);
        CanvasLayout reloaded = CanvasLayoutStore.load(ontology);

        assertEquals("http://example.org/tiny", reloaded.ontologyIri);
        assertEquals(1, reloaded.onCanvas.size());
        assertEquals("http://example.org/tiny#Person", reloaded.onCanvas.get(0));
        assertEquals(120.0, reloaded.nodes.get("http://example.org/tiny#Person").x);
        assertEquals(40.0, reloaded.nodes.get("http://example.org/tiny#Person").y);
        assertEquals("#4A90D9", reloaded.prefixColors.get("ex"));
    }

    /**
     * Jackson is embedded via robot-core, not imported from Protege (see Global
     * Constraints). If Embed-Transitive is ever dropped, this fails with
     * NoClassDefFoundError rather than the sidecar silently breaking at runtime.
     */
    @Test
    void jacksonIsActuallyReachableOnTheClasspath() {
        assertEquals("com.fasterxml.jackson.databind.ObjectMapper",
                com.fasterxml.jackson.databind.ObjectMapper.class.getName());
    }

    @Test
    void unknownVersionFailsLoudlyInsteadOfSilentlyLosingLayout(@TempDir Path dir) throws Exception {
        File ontology = dir.resolve("future.owl").toFile();
        Files.write(CanvasLayoutStore.sidecarFor(ontology).toPath(),
                "{\"version\":999}".getBytes(StandardCharsets.UTF_8));

        assertThrows(UnsupportedLayoutVersionException.class,
                () -> CanvasLayoutStore.load(ontology));
    }
}
