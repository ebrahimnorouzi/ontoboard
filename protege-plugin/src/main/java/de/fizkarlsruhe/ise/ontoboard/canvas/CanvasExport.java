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

    public static void writePng(SchemaGraph graph, File target) throws IOException {
        BufferedImage image = mxCellRenderer.createBufferedImage(
                graph, null, SCALE, BACKGROUND, true, null);
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
