package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxXmlUtils;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.imageio.ImageIO;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * PNG and SVG export of the current diagram, with its key.
 *
 * <p><b>The key travels with the picture now, and until 1.78.0 it did not.</b> An exported
 * diagram carried no legend and no prefix colours, so the two things a reader needs in order to
 * decode it - what a dashed arrow means, what a term's outline colour says about its namespace -
 * stayed behind in Protégé with the person who made it. The canvas has drawn both since 1.53.0
 * and 1.63.0; the export never saw them, because {@code mxCellRenderer} renders the graph model
 * and neither the legend nor the prefix key is in the graph model.
 *
 * <p>That is the same reason note badges, peer cursors and alignment guides do not appear in an
 * export, and for those it is the right answer - they are transient, or they belong to a
 * session. A key is not transient. It is the part of the diagram that makes the rest of it
 * mean something.
 */
public final class CanvasExport {

    private static final double SCALE = 1.0;
    private static final Color BACKGROUND = Color.WHITE;

    /**
     * Breathing room around the diagram, in graph units.
     *
     * <p>Without it the image is sized to the graph bounds exactly, and a node sitting at the
     * leftmost or topmost position has its border drawn half outside the picture. Measured on
     * the rendered design proof before this existed: column x=0 carried 101 non-background
     * pixels, rising to 331 by x=3 - a node outline running off the edge of the file.
     *
     * <p>Eight units rather than two, because the stroke is not the only thing at the boundary:
     * a selected node is drawn with a wider outline, and a frame's dashed border sits outside
     * the shape it encloses.
     */
    static final double MARGIN = 8;

    /**
     * The graph's own bounds, widened by {@link #MARGIN} on every side.
     *
     * <p>Null when the graph is empty, which both renderers take to mean "work it out", and
     * for an empty graph there is nothing to work out.
     */
    static com.mxgraph.util.mxRectangle framed(com.mxgraph.view.mxGraph graph, double scale) {
        com.mxgraph.util.mxRectangle bounds = graph.getGraphBounds();
        if (bounds == null || bounds.getWidth() <= 0 || bounds.getHeight() <= 0) {
            return null;
        }
        return new com.mxgraph.util.mxRectangle(
                (bounds.getX() - MARGIN) * scale,
                (bounds.getY() - MARGIN) * scale,
                (bounds.getWidth() + 2 * MARGIN) * scale,
                (bounds.getHeight() + 2 * MARGIN) * scale);
    }

    /** How wide the key is drawn when the diagram is narrower than it needs. */
    private static final int MINIMUM_KEY_WIDTH = 420;

    private CanvasExport() {
    }

    /** Writes a PNG at the default scale, with no key. Kept so existing callers are unaffected. */
    public static void writePng(SchemaGraph graph, File target) throws IOException {
        writePng(graph, target, SCALE);
    }

    /** Writes a PNG at {@code scale}, with no key. */
    public static void writePng(SchemaGraph graph, File target, double scale)
            throws IOException {
        writePng(graph, target, scale, null, null);
    }

    /**
     * Writes a PNG at {@code scale}, with the key beneath the diagram.
     *
     * <p>A diagram exported at screen size is too small for a paper or a slide. Scaling at
     * render time rather than resampling afterwards keeps text and strokes sharp, which is the
     * entire point of asking for a higher resolution - and the key is drawn at the same scale,
     * so it stays legible rather than becoming a smudge under a crisp diagram.
     *
     * <p>Beneath rather than beside, because a board is usually wider than it is tall and a key
     * in the margin would stretch the image sideways into whitespace.
     *
     * @param scale must be positive; 1 is screen size
     * @param entries the shape and line key, or null for none
     * @param namespaces the prefix colours, or null for none
     */
    public static void writePng(SchemaGraph graph, File target, double scale,
            List<CanvasLegend.Entry> entries, List<CanvasLegend.Namespace> namespaces)
            throws IOException {
        if (scale <= 0) {
            throw new IllegalArgumentException("export scale must be positive, got " + scale);
        }
        BufferedImage diagram = mxCellRenderer.createBufferedImage(
                graph, null, scale, BACKGROUND, true, framed(graph, scale));
        if (diagram == null) {
            // An empty graph has no bounds; emit a 1x1 rather than a corrupt file.
            diagram = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            diagram.setRGB(0, 0, BACKGROUND.getRGB());
        }

        LegendLayout.Key key = LegendLayout.of(entries, namespaces,
                keyWidthFor(diagram.getWidth(), scale));
        ImageIO.write(key.isEmpty() ? diagram : withKeyBeneath(diagram, key, scale),
                "PNG", target);
    }

    /** Writes an SVG with no key. Kept so existing callers are unaffected. */
    public static void writeSvg(SchemaGraph graph, File target) throws IOException {
        writeSvg(graph, target, null, null);
    }

    /**
     * Writes an SVG with the key beneath the diagram, as elements.
     *
     * <p>Elements rather than an embedded bitmap: half the reason to choose SVG is that it does
     * not blur when somebody enlarges it for a poster, and a key pasted in as a PNG would be the
     * one part that did.
     */
    public static void writeSvg(SchemaGraph graph, File target,
            List<CanvasLegend.Entry> entries, List<CanvasLegend.Namespace> namespaces)
            throws IOException {
        Document document = mxCellRenderer.createSvgDocument(
                graph, null, SCALE, BACKGROUND, framed(graph, SCALE));
        if (document == null) {
            Files.write(target.toPath(),
                    "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(
                            StandardCharsets.UTF_8));
            return;
        }
        Element root = document.getDocumentElement();
        LegendLayout.Key key = LegendLayout.of(entries, namespaces,
                keyWidthFor(widthOf(root), 1.0));
        if (!key.isEmpty()) {
            int diagramHeight = heightOf(root);
            LegendRender.appendTo(document, root, key, 0, diagramHeight + LegendLayout.PADDING);
            // The viewport has to grow or the key is simply outside it - present in the file and
            // invisible in every viewer, which is the worst of both.
            root.setAttribute("width",
                    String.valueOf(Math.max(widthOf(root), key.getWidth())));
            root.setAttribute("height",
                    String.valueOf(diagramHeight + LegendLayout.PADDING + key.getHeight()));
            root.removeAttribute("viewBox");
        }
        Files.write(target.toPath(),
                mxXmlUtils.getXml(root).getBytes(StandardCharsets.UTF_8));
    }

    /** The diagram with the key under it, on one canvas. */
    private static BufferedImage withKeyBeneath(BufferedImage diagram, LegendLayout.Key key,
            double scale) {
        int keyWidth = (int) Math.ceil(key.getWidth() * scale);
        int keyHeight = (int) Math.ceil(key.getHeight() * scale);
        int gap = (int) Math.ceil(LegendLayout.PADDING * scale);

        int width = Math.max(diagram.getWidth(), keyWidth);
        int height = diagram.getHeight() + gap + keyHeight;

        BufferedImage composed = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = composed.createGraphics();
        try {
            graphics.setColor(BACKGROUND);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(diagram, 0, 0, null);
            LegendRender.paint(graphics, key, 0, diagram.getHeight() + gap, scale);
        } finally {
            graphics.dispose();
        }
        return composed;
    }

    /** The key is as wide as the diagram, within reason, so the picture stays rectangular. */
    static int keyWidthFor(int diagramWidth, double scale) {
        int atScaleOne = (int) Math.round(diagramWidth / (scale <= 0 ? 1 : scale));
        return Math.max(MINIMUM_KEY_WIDTH, Math.min(atScaleOne, 1400));
    }

    private static int widthOf(Element root) {
        return numberIn(root.getAttribute("width"), 800);
    }

    private static int heightOf(Element root) {
        return numberIn(root.getAttribute("height"), 600);
    }

    /** An SVG length may carry a unit; only the leading number is wanted. */
    private static int numberIn(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        StringBuilder digits = new StringBuilder();
        for (int at = 0; at < value.length(); at++) {
            char character = value.charAt(at);
            if (Character.isDigit(character)) {
                digits.append(character);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException tooLarge) {
            return fallback;
        }
    }
}
