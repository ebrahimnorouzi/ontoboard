package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * A defined class has a badge, drawn as strokes, beside its label's {@code ≡}.
 *
 * <p>Found by a tester: at 100% on an ordinary screen a defined {@code ≡ publication} read as
 * {@code = publication}. The character is three faint bars in the board's font at 13 px.
 */
class DefinedClassBadgeTest {

    @Test
    void aDefinedClassIsDrawnWithTheBadgeAndAnOrdinaryOneWithout() {
        assertTrue(SchemaGraph.styleForTesting(new CanvasNode("http://example.org/a",
                NodeKind.CLASS, "≡ publication")).contains("shape=" + DefinedClassShape.NAME));
        assertFalse(SchemaGraph.styleForTesting(new CanvasNode("http://example.org/b",
                NodeKind.CLASS, "publication")).contains(DefinedClassShape.NAME));
        assertFalse(SchemaGraph.styleForTesting(new CanvasNode("http://example.org/c",
                NodeKind.INDIVIDUAL, "≡ not a class")).contains(DefinedClassShape.NAME));
    }

    @Test
    void theBadgeIsInAnExportedImage() {
        SchemaGraph graph = new SchemaGraph();
        Object box = graph.insertVertex(graph.getDefaultParent(), "http://example.org/a",
                "≡ publication\nobo:IAO_0000311", 20, 20, 200, 40,
                SchemaGraph.styleForTesting(new CanvasNode("http://example.org/a",
                        NodeKind.CLASS, "≡ publication")));
        mxCellState state = graph.getView().getState(box);
        BufferedImage image = mxCellRenderer.createBufferedImage(graph, null, 1, Color.WHITE, false,
                null);
        // The image starts at the board's painted bounds. Looked at: the disc's centre column,
        // between its bottom bar and its edge.
        com.mxgraph.util.mxRectangle bounds = graph.getPaintBounds(new Object[] {box});
        int x = (int) Math.round(state.getX() + DefinedClassShape.INSET
                + DefinedClassShape.SIZE / 2 - bounds.getX());
        int y = (int) Math.round(state.getCenterY() + DefinedClassShape.SIZE * 0.35
                - bounds.getY());
        Color below = new Color(image.getRGB(x, y));
        // Between the bottom bar and the disc's edge: the box's border colour, not its white fill.
        Color border = com.mxgraph.util.mxUtils.getColor(state.getStyle(),
                com.mxgraph.util.mxConstants.STYLE_STROKECOLOR);
        assertEquals(border.getRed(), below.getRed(), 40, "the disc at " + x + "," + y + " is "
                + below + ", the border " + border);
        assertEquals(border.getGreen(), below.getGreen(), 40, "the disc is " + below);
        assertEquals(border.getBlue(), below.getBlue(), 40, "the disc is " + below);
    }
}
