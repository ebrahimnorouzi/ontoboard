package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxXmlUtils;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.w3c.dom.Document;

/** PNG and SVG export of the current diagram. */
public final class CanvasExport {

    private static final double SCALE = 1.0;
    private static final Color BACKGROUND = Color.WHITE;

    private CanvasExport() {
    }

    /** Writes a PNG at the default scale. Kept so existing callers are unaffected. */
    public static void writePng(SchemaGraph graph, File target) throws IOException {
        writePng(graph, target, SCALE);
    }

    /**
     * Writes a PNG at {@code scale}.
     *
     * <p>A diagram exported at screen size is too small for a paper or a slide. Scaling at render
     * time rather than resampling afterwards keeps text and strokes sharp, which is the entire
     * point of asking for a higher resolution.
     *
     * @param scale must be positive; 1 is screen size
     */
    public static void writePng(SchemaGraph graph, File target, double scale)
            throws IOException {
        if (scale <= 0) {
            throw new IllegalArgumentException("export scale must be positive, got " + scale);
        }
        BufferedImage image = mxCellRenderer.createBufferedImage(
                graph, null, scale, BACKGROUND, true, null);
        if (image == null) {
            // An empty graph has no bounds; emit a 1x1 rather than a corrupt file.
            image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, BACKGROUND.getRGB());
        }
        ImageIO.write(image, "PNG", target);
    }

    public static void writeSvg(SchemaGraph graph, File target) throws IOException {
        Document document = mxCellRenderer.createSvgDocument(
                graph, null, SCALE, BACKGROUND, null);
        String xml = document == null ? "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                : mxXmlUtils.getXml(document.getDocumentElement());
        Files.write(target.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }
}
