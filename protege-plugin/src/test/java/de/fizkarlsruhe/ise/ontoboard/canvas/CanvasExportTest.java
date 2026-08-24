package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanvasExportTest {

    private static SchemaGraph oneNodeGraph() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(
                Collections.singletonList(new CanvasNode(
                        "http://example.org/tiny#Person", NodeKind.CLASS, "Person")),
                Collections.emptyList()), new CanvasLayout());
        return graph;
    }

    @Test
    void writesANonEmptyPng(@TempDir Path dir) throws Exception {
        File png = dir.resolve("diagram.png").toFile();
        CanvasExport.writePng(oneNodeGraph(), png);
        assertTrue(png.length() > 0, "PNG should not be empty");
    }

    @Test
    void writesSvgContainingTheNodeLabel(@TempDir Path dir) throws Exception {
        File svg = dir.resolve("diagram.svg").toFile();
        CanvasExport.writeSvg(oneNodeGraph(), svg);

        String content = new String(Files.readAllBytes(svg.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("<svg"), "expected an SVG root element");
        assertTrue(content.contains("Person"), "expected the node label in the SVG");
    }

    /**
     * {@code mxCellRenderer.createBufferedImage} returns {@code null} for a graph with no
     * cells, since there are no bounds to render - the only case
     * {@link CanvasExport#writePng}'s null check exists to handle. Without a test that
     * actually exercises an empty graph, deleting that check would still pass every other
     * test here while leaving a user who clicks "Export PNG" on an empty canvas with an
     * NPE. Reading the bytes back with {@code ImageIO} (not just checking the file's
     * length) also catches a corrupt-but-non-empty file.
     */
    @Test
    void writesAReadablePngForAnEmptyGraph(@TempDir Path dir) throws Exception {
        File png = dir.resolve("empty.png").toFile();
        CanvasExport.writePng(new SchemaGraph(), png);

        assertTrue(png.length() > 0, "PNG for an empty graph should not be empty");
        BufferedImage image = ImageIO.read(png);
        assertNotNull(image, "PNG bytes for an empty graph must decode as a real image");
    }

    /**
     * Mirrors {@link #writesAReadablePngForAnEmptyGraph} for the SVG side:
     * {@code mxCellRenderer.createSvgDocument} returns {@code null} for an empty graph, and
     * {@link CanvasExport#writeSvg} falls back to a literal {@code <svg/>} document. Only
     * an empty-graph test can exercise that fallback branch.
     */
    @Test
    void writesSvgFallbackRootForAnEmptyGraph(@TempDir Path dir) throws Exception {
        File svg = dir.resolve("empty.svg").toFile();
        CanvasExport.writeSvg(new SchemaGraph(), svg);

        String content = new String(Files.readAllBytes(svg.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("<svg"), "expected an SVG root element even for an empty graph");
    }
}
