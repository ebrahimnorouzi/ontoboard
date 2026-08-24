package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
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
}
