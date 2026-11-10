package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxConstants;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * A picture exported right after a click has every line the click shows, each routed round the
 * boxes: it is the picture the board gives a moment later, once they have all come.
 *
 * <p>Found by a review of 1.135.1: a click on a class many lines meet routes what it shows for a
 * moment and the rest a slice at a time, each line hidden until it is routed, and Export image...
 * drew the board as it stood - so a picture taken in that moment left out every line still waiting.
 * Here, exported straight after a click on the class most ranges meet: 14 of its 19 lines, and the
 * picture was 76 by 101 px smaller than the board they were drawn on a moment later.
 */
class ExportRightAfterAClickTest {

    /** The class two thirds of the properties' ranges meet at, on ClickRoutingTimeTest's board. */
    private static final String BUSY = "urn:hub#f1t0";

    /** One of the two ways Export image... writes the board. */
    private interface Export {
        void write(SchemaGraph graph, File target) throws IOException;
    }

    @Test
    void anSvgExportedRightAfterAClickOnABusyClassHasEveryLineItShowsRoutedRoundTheBoxes(
            @TempDir Path dir) throws Exception {
        SchemaGraph graph = routedBoard();
        File now = dir.resolve("right-after-the-click.svg").toFile();
        File later = dir.resolve("a-moment-later.svg").toFile();

        int[] counted = clickAndExport(graph, CanvasExport::writeSvg, now);
        untilEveryLineHasCome(graph);
        List<String> unrouted = new ArrayList<String>();
        List<String> through = new ArrayList<String>();
        SwingUtilities.invokeAndWait(() -> {
            graph.getView().validate();
            export(CanvasExport::writeSvg, graph, later);
            for (Object edge : shown(graph)) {
                if (graph.routeOf(edge) == null) {
                    unrouted.add(graph.getIdForCell(edge));
                }
            }
            for (String one : new BoardMeasures("a moment later", graph).through) {
                if (one.startsWith("domain ") || one.startsWith("range ")) {
                    through.add(one);
                }
            }
        });

        assertTrue(counted[1] > 0, "the click routed all " + counted[0]
                + " lines it shows at once: this board no longer shows the case");
        String colour = lineColour(graph);
        List<String> drawnLater = linesIn(later, colour);
        assertEquals(counted[0], drawnLater.size(),
                "a moment later, the picture has every line the click shows");
        List<String> drawnNow = linesIn(now, colour);
        System.out.println("right after the click: " + counted[1] + " of its " + counted[0]
                + " lines still waiting; in the picture then " + drawnNow.size() + ", a moment later "
                + drawnLater.size());
        assertEquals(drawnLater.size(), drawnNow.size(),
                "lines the click shows in the picture taken right after it");
        assertEquals(drawnLater, drawnNow, "each drawn where it is drawn a moment later");
        assertTrue(Arrays.equals(Files.readAllBytes(later.toPath()), Files.readAllBytes(now.toPath())),
                "the picture taken right after the click is the one taken a moment later");
        assertEquals(new ArrayList<String>(), unrouted,
                "lines the click shows drawn without a route of their own");
        assertEquals(new ArrayList<String>(), through, "lines the click shows through boxes");
    }

    @Test
    void aPngExportedRightAfterAClickOnABusyClassHasEveryLineItShows(@TempDir Path dir)
            throws Exception {
        SchemaGraph graph = routedBoard();
        // Where a reviewer can look at it, as CanvasDesignProofTest leaves its pictures.
        File directory = new File("target/design");
        directory.mkdirs();
        File now = new File(directory, "export-right-after-a-click.png");
        File later = dir.resolve("a-moment-later.png").toFile();

        int[] counted = clickAndExport(graph, CanvasExport::writePng, now);
        untilEveryLineHasCome(graph);
        SwingUtilities.invokeAndWait(() -> {
            graph.getView().validate();
            export(CanvasExport::writePng, graph, later);
        });

        assertTrue(counted[1] > 0, "the click routed all " + counted[0]
                + " lines it shows at once: this board no longer shows the case");
        BufferedImage right = ImageIO.read(now);
        BufferedImage moment = ImageIO.read(later);
        assertEquals(moment.getWidth() + "x" + moment.getHeight(),
                right.getWidth() + "x" + right.getHeight(),
                "the size of the picture taken right after the click");
        assertEquals(0, differing(right, moment), "pixels of the picture taken right after the "
                + "click unlike those of one taken once every line had come");
    }

    private static SchemaGraph routedBoard() {
        SchemaGraph graph = ClickRoutingTimeTest.board();
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    /**
     * Clicks the busy class on the Swing thread, as a person does, and exports the board in the
     * same moment, before a slice has run. Returns how many lines the click shows, and how many of
     * them were still waiting when the export began.
     */
    private static int[] clickAndExport(SchemaGraph graph, Export export, File target)
            throws Exception {
        int[] counted = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            graph.setFocus(Collections.singletonList(BUSY));
            graph.getView().validate();
            for (Object edge : shown(graph)) {
                counted[0]++;
                if (!graph.isCellVisible(edge)) {
                    counted[1]++;
                }
            }
            export(export, graph, target);
        });
        return counted;
    }

    /** Waits, as a person at the board does, until no line the click shows waits to be routed. */
    private static void untilEveryLineHasCome(SchemaGraph graph) throws Exception {
        long until = System.nanoTime() + 60_000_000_000L;
        boolean[] waiting = {true};
        while (waiting[0] && System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> {
                waiting[0] = false;
                for (Object edge : shown(graph)) {
                    waiting[0] |= !graph.isCellVisible(edge);
                }
            });
            if (waiting[0]) {
                Thread.sleep(15);
            }
        }
        assertTrue(!waiting[0], "lines the click shows still waiting a minute later");
    }

    private static void export(Export export, SchemaGraph graph, File target) {
        try {
            export.write(graph, target);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** The lines a click on the busy class shows: those of a kind that waits, at that class. */
    private static List<Object> shown(SchemaGraph graph) {
        List<Object> lines = new ArrayList<Object>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            String kind = String.valueOf(graph.getModel().getStyle(cell)).split(";", 2)[0];
            boolean waits = SchemaStyles.DISJOINT.equals(kind)
                    || SchemaStyles.DOMAIN_RANGE.equals(kind) || SchemaStyles.RULE_PART.equals(kind);
            if (graph.getModel().isEdge(cell) && waits
                    && (BUSY.equals(graph.getIdForCell(graph.getModel().getTerminal(cell, true)))
                    || BUSY.equals(graph.getIdForCell(graph.getModel().getTerminal(cell, false))))) {
                lines.add(cell);
            }
        }
        return lines;
    }

    /** The colour a domain or range line is drawn in: on this board, the only lines that wait. */
    private static String lineColour(SchemaGraph graph) {
        return String.valueOf(graph.getStylesheet().getStyles().get(SchemaStyles.DOMAIN_RANGE)
                .get(mxConstants.STYLE_STROKECOLOR));
    }

    /**
     * The domain and range lines in an SVG as JGraphX writes it, by the points they are drawn
     * through: a dashed path in their colour. Each one's arrowhead is a path of that colour too, but
     * not dashed.
     */
    private static List<String> linesIn(File svg, String colour) throws Exception {
        NodeList paths = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg)
                .getElementsByTagName("path");
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < paths.getLength(); i++) {
            Element path = (Element) paths.item(i);
            if (colour.equalsIgnoreCase(path.getAttribute("stroke"))
                    && path.hasAttribute("stroke-dasharray")) {
                lines.add(path.getAttribute("d"));
            }
        }
        return lines;
    }

    private static int differing(BufferedImage one, BufferedImage other) {
        int differ = 0;
        for (int y = 0; y < one.getHeight(); y++) {
            for (int x = 0; x < one.getWidth(); x++) {
                if (one.getRGB(x, y) != other.getRGB(x, y)) {
                    differ++;
                }
            }
        }
        return differ;
    }
}
