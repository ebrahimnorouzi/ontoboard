package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key that travels with an exported diagram.
 *
 * <p>Until 1.78.0 an export carried neither the legend nor the prefix colours, so the two things
 * a reader needs in order to decode the picture stayed behind in Protégé. The canvas has drawn
 * both for many releases; {@code mxCellRenderer} renders the graph model, and neither is in the
 * graph model.
 *
 * <p>The layout is geometry, so most of this is checked without a screen. The two tests that
 * actually write a file use {@code BufferedImage} and a DOM, neither of which needs a display.
 */
class ExportedKeyTest {

    private static Map<String, String> prefixes() {
        Map<String, String> colours = new LinkedHashMap<String, String>();
        colours.put("obo", "#7E57C2");
        colours.put("mwo", "#2E7D32");
        return colours;
    }

    private static SchemaGraph aBoard() {
        SchemaGraph graph = new SchemaGraph();
        CanvasNode pizza = new CanvasNode("http://example.org/o#Pizza", NodeKind.CLASS, "Pizza");
        CanvasNode food = new CanvasNode("http://example.org/o#Food", NodeKind.CLASS, "Food");
        graph.render(new Projection(Arrays.asList(pizza, food),
                Arrays.asList(new CanvasEdge("sub|a|b", pizza.getId(), food.getId(), "",
                        CanvasEdge.Kind.SUBCLASS))),
                new de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout());
        return graph;
    }

    // ---------- the layout ----------

    /** A key with nothing in it is nothing, not an empty box. */
    @Test
    void nothingToExplainProducesNoKey() {
        LegendLayout.Key none = LegendLayout.of(null, null, 600);

        assertTrue(none.isEmpty());
        assertEquals(0, none.getHeight());
    }

    /** The shapes and lines are in there, each with its label. */
    @Test
    void theKeyCarriesTheLegendRows() {
        LegendLayout.Key key = LegendLayout.of(CanvasLegend.entries(), null, 600);

        assertFalse(key.isEmpty());
        String text = textOf(key);
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            assertTrue(text.contains(entry.getLabel()),
                    "the key lost the row for " + entry.getLabel());
        }
    }

    /**
     * The namespace colours are in there too, which is the half most easily forgotten.
     *
     * <p>The outline colour carries more information than any other channel on the board, and a
     * key that omits it invites a confident misreading - the exact defect the legend dialog was
     * extended to fix in 1.63.0, reproduced in the export by leaving the export out of the fix.
     */
    @Test
    void theKeyCarriesTheNamespaceColours() {
        LegendLayout.Key key = LegendLayout.of(null,
                CanvasLegend.namespaces(prefixes()), 600);

        String text = textOf(key);
        assertTrue(text.contains("Namespaces"), text);
        assertTrue(text.contains("obo"), text);
        assertTrue(text.contains("mwo"), text);
        assertTrue(colours(key).contains("#7E57C2"), colours(key).toString());
    }

    /**
     * Each section heading appears once.
     *
     * <p>The on-screen legend printed "Relationships" twice, because it emitted a heading
     * whenever the row kind changed and the two inferred rows sit after the modifier rows. This
     * layout groups by form instead, so the same mistake cannot be made twice.
     */
    @Test
    void eachHeadingAppearsExactlyOnce() {
        LegendLayout.Key key = LegendLayout.of(CanvasLegend.entries(),
                CanvasLegend.namespaces(prefixes()), 600);

        for (String heading : Arrays.asList("Terms", "Relationships", "Markers", "Namespaces")) {
            assertEquals(1, occurrences(key, heading), heading + " should appear once");
        }
    }

    /** Every shape sits inside the box the key claims to need. */
    @Test
    void nothingIsDrawnOutsideTheKeysOwnBounds() {
        LegendLayout.Key key = LegendLayout.of(CanvasLegend.entries(),
                CanvasLegend.namespaces(prefixes()), 600);

        for (LegendLayout.Shape shape : key.getShapes()) {
            assertTrue(shape.getY() <= key.getHeight(),
                    shape + " is below the key, which would be clipped");
            assertTrue(shape.getX() >= 0 && shape.getY() >= 0, shape + " is off the top-left");
        }
    }

    /** A narrow diagram still gets a key wide enough to read. */
    @Test
    void theKeyHasAFloorAndACeiling() {
        assertTrue(CanvasExport.keyWidthFor(40, 1.0) >= 420, "a tiny board still needs a key");
        assertTrue(CanvasExport.keyWidthFor(40000, 1.0) <= 1400, "and a huge one does not");
        assertEquals(CanvasExport.keyWidthFor(1200, 1.0), CanvasExport.keyWidthFor(2400, 2.0),
                "the key is laid out at scale 1 whatever the export scale");
    }

    // ---------- the files ----------

    /** A PNG with the key is taller than the same PNG without it, and no narrower. */
    @Test
    void thePngGrowsToHoldTheKey(@TempDir File directory) throws Exception {
        SchemaGraph graph = aBoard();
        File plain = new File(directory, "plain.png");
        File keyed = new File(directory, "keyed.png");

        CanvasExport.writePng(graph, plain, 1.0);
        CanvasExport.writePng(graph, keyed, 1.0, CanvasLegend.entries(),
                CanvasLegend.namespaces(prefixes()));

        BufferedImage without = ImageIO.read(plain);
        BufferedImage with = ImageIO.read(keyed);
        assertTrue(with.getHeight() > without.getHeight(),
                "the key has to be somewhere: " + without.getHeight() + " -> "
                        + with.getHeight());
        assertTrue(with.getWidth() >= without.getWidth(), "and the diagram is not cropped");
    }

    /** The SVG carries the key as elements, and grows its viewport to show them. */
    @Test
    void theSvgCarriesTheKeyAsText(@TempDir File directory) throws Exception {
        File target = new File(directory, "board.svg");

        CanvasExport.writeSvg(aBoard(), target, CanvasLegend.entries(),
                CanvasLegend.namespaces(prefixes()));

        String svg = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertTrue(svg.contains(">Key<"), "the key's title is missing from the SVG");
        assertTrue(svg.contains(">obo<"), "the namespace rows are missing from the SVG");
        assertTrue(svg.contains("<text"), "the key must be text, not a pasted bitmap");
        assertFalse(svg.contains("<image"), "an embedded bitmap would blur when enlarged");
    }

    /** Without a key, both formats are exactly what they always were. */
    @Test
    void anExportWithNoKeyIsUnchanged(@TempDir File directory) throws Exception {
        File png = new File(directory, "none.png");
        File svg = new File(directory, "none.svg");

        CanvasExport.writePng(aBoard(), png, 1.0, null, null);
        CanvasExport.writeSvg(aBoard(), svg, null, null);

        assertTrue(png.isFile() && png.length() > 0);
        String text = new String(Files.readAllBytes(svg.toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains(">Key<"), "no key was asked for");
    }

    private static String textOf(LegendLayout.Key key) {
        StringBuilder all = new StringBuilder();
        for (LegendLayout.Shape shape : key.getShapes()) {
            if (shape.getText() != null) {
                all.append(shape.getText()).append('\n');
            }
        }
        return all.toString();
    }

    private static List<String> colours(LegendLayout.Key key) {
        List<String> found = new java.util.ArrayList<String>();
        for (LegendLayout.Shape shape : key.getShapes()) {
            if (shape.getStroke() != null) {
                found.add(shape.getStroke());
            }
        }
        return found;
    }

    private static int occurrences(LegendLayout.Key key, String text) {
        int count = 0;
        for (LegendLayout.Shape shape : key.getShapes()) {
            if (text.equals(shape.getText())) {
                count++;
            }
        }
        return count;
    }
}
