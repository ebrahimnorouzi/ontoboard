package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.canvas.mxGraphics2DCanvas;
import com.mxgraph.shape.mxDefaultTextShape;
import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The logic symbols the board writes into its boxes and onto its arrows are drawn as symbols, on
 * every platform (1.135.1, Windows study rehearsal).
 *
 * <p>On Windows the board's family is Segoe UI, a physical font. Java gives a physical font no
 * per-character fallback there, so every symbol Segoe UI lacks - the {@code ∃} that starts every
 * relation's label, {@code ∀}, {@code ⊔}, {@code ⊓}, {@code ∧}, {@code ▸} - came out as its
 * missing-glyph box: participants read "□ has specified input".
 */
class BoardGlyphsTest {

    /**
     * Every symbol the board writes into a label, and where: OntologyProjection's SOME and ONLY
     * ({@code ∃ ∀}), the counts ({@code ≥ ≤}), a defined class ({@code ≡}), union and intersection
     * nodes ({@code ⊔ ⊓}), rule nodes ({@code ∧ →}), an inverse property ({@code ⁻}), a cut literal
     * ({@code …}), a folded node (SchemaCanvasView, {@code ▸}) and a negation ({@code ¬}).
     */
    static final String BOARD_GLYPHS =
            "∃∀≥≤≡⊔⊓∧→⁻…▸¬";

    /** Unassigned in Unicode, so every font draws its missing-glyph box for it. */
    private static final String NO_GLYPH = "͸";

    private static final NodeKind[] KINDS = {
        NodeKind.CLASS, NodeKind.EXPRESSION, NodeKind.DATATYPE, NodeKind.INDIVIDUAL,
    };

    @Test
    void everyGlyphTheBoardWritesIsDrawnNotBoxed() {
        List<String> boxed = new ArrayList<String>();
        for (NodeKind kind : KINDS) {
            BufferedImage box = render(box(kind, "x" + NO_GLYPH + "x"));
            for (char glyph : BOARD_GLYPHS.toCharArray()) {
                if (samePixels(box, render(box(kind, "x" + glyph + "x")))) {
                    boxed.add(kind + " " + glyph);
                }
            }
        }
        // The symbol alone: two boxes laid out side by side leave a narrow gap, and the ends of a
        // longer label are behind them.
        BufferedImage boxOnArrow = render(arrow(NO_GLYPH));
        for (char glyph : BOARD_GLYPHS.toCharArray()) {
            if (samePixels(boxOnArrow, render(arrow(String.valueOf(glyph))))) {
                boxed.add("arrow " + glyph);
            }
        }
        assertTrue(boxed.isEmpty(), "drawn as the font's missing-glyph box on "
                + System.getProperty("os.name") + ": " + boxed);
    }

    @Test
    void theLabelFontOfEveryStyleCanDisplayEveryBoardGlyph() {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph);
        List<String> missing = new ArrayList<String>();
        // 100%, Fit on a big board, and a 300% screen.
        for (double scale : new double[] {1.0, 0.26, 3.0}) {
            for (Map.Entry<String, Map<String, Object>> style
                    : graph.getStylesheet().getStyles().entrySet()) {
                Font font = FallbackTextShape.fontFor(style.getValue(), scale);
                int at = font.canDisplayUpTo(BOARD_GLYPHS);
                if (at >= 0) {
                    missing.add(style.getKey() + " at " + scale + ": " + font.getFontName()
                            + " has no " + BOARD_GLYPHS.charAt(at));
                }
            }
        }
        assertTrue(missing.isEmpty(), missing.toString());
    }

    /**
     * Only the missing symbols change. The family is still the first font and gives every metric,
     * so a board of Latin labels comes out as it did - and with it every box size, wrap and route
     * the study rehearsals measured.
     */
    @Test
    void labelsTheFamilyCanDrawComeOutPixelForPixelAsBefore() {
        Projection latin = new Projection(Arrays.asList(
                new CanvasNode("http://example.org/glyphs#A", NodeKind.CLASS, "egg boiling"),
                new CanvasNode("http://example.org/glyphs#B", NodeKind.CLASS,
                        "an egg boiling report about the test kitchen"),
                new CanvasNode("http://example.org/glyphs#C", NodeKind.EXPRESSION, "has part some data"),
                new CanvasNode("http://example.org/glyphs#D", NodeKind.DATATYPE, "xsd:decimal"),
                new CanvasNode("http://example.org/glyphs#E", NodeKind.INDIVIDUAL, "Masse café 1"),
                new CanvasNode("http://example.org/glyphs#F", NodeKind.LITERAL, "\"58 g\"")),
                Arrays.asList(
                        new CanvasEdge("e1", "http://example.org/glyphs#A", "http://example.org/glyphs#B",
                                "is about", CanvasEdge.Kind.OBJECT_PROPERTY),
                        new CanvasEdge("e2", "http://example.org/glyphs#A", "http://example.org/glyphs#C",
                                "", CanvasEdge.Kind.SUBCLASS),
                        new CanvasEdge("e3", "http://example.org/glyphs#E", "http://example.org/glyphs#F",
                                "has value", CanvasEdge.Kind.DATA_PROPERTY)));
        BufferedImage before;
        mxGraphics2DCanvas.putTextShape(mxGraphics2DCanvas.TEXT_SHAPE_DEFAULT, new mxDefaultTextShape());
        try {
            before = render(latin);
        } finally {
            mxGraphics2DCanvas.putTextShape(mxGraphics2DCanvas.TEXT_SHAPE_DEFAULT,
                    new FallbackTextShape());
        }
        assertTrue(samePixels(before, render(latin)),
                "Latin labels look different through the fallback font");
    }

    /**
     * An SVG names each label's font the CSS way, with a generic family after it: a viewer without
     * Segoe UI keeps a sans face instead of its serif default, and a browser takes {@code ∃} from
     * whichever font has it. Java's logical names mean nothing to CSS.
     */
    @Test
    void svgNamesTheBoardFontWithAGenericFallback(@TempDir Path dir) throws Exception {
        File svg = dir.resolve("board.svg").toFile();
        SchemaGraph graph = new SchemaGraph();
        graph.render(arrow("∃ has part"), new CanvasLayout());
        CanvasExport.writeSvg(graph, svg);
        NodeList texts = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg)
                .getElementsByTagName("text");
        assertTrue(texts.getLength() > 0, "no text in the SVG");
        List<String> bare = new ArrayList<String>();
        for (int i = 0; i < texts.getLength(); i++) {
            String family = ((Element) texts.item(i)).getAttribute("font-family");
            boolean generic = family.equals("sans-serif") || family.equals("serif")
                    || family.equals("monospace");
            if (!generic && !family.endsWith(", sans-serif")) {
                bare.add(family);
            }
        }
        assertTrue(bare.isEmpty(), "font-family without a generic fallback: " + bare);
    }

    @Test
    void cssFamilyNamesJavaLogicalFontsByTheirGenericFamily() {
        assertEquals("'Segoe UI', sans-serif", CanvasExport.cssFamily("Segoe UI"));
        assertEquals("sans-serif", CanvasExport.cssFamily("SansSerif"));
        assertEquals("sans-serif", CanvasExport.cssFamily("Dialog"));
        assertEquals("serif", CanvasExport.cssFamily("Serif"));
        assertEquals("monospace", CanvasExport.cssFamily("Monospaced"));
        assertEquals("monospace", CanvasExport.cssFamily("DialogInput"));
        assertEquals("sans-serif", CanvasExport.cssFamily("sans-serif"));
        assertEquals("'Segoe UI', Arial, sans-serif", CanvasExport.cssFamily("'Segoe UI', Arial, sans-serif"));
    }

    /**
     * The family is the look, and is never swapped for one that has more symbols: those come from
     * the fallback, glyph by glyph. A PC that also has DejaVu Sans, like the study's, keeps Segoe UI.
     */
    @Test
    void theLookIsChosenByFamilyNotByGlyph() {
        assertEquals("Segoe UI", SchemaStyles.resolveFont(new HashSet<String>(Arrays.asList(
                "DejaVu Sans", "Segoe UI", "Arial"))));
        assertEquals("Roboto", SchemaStyles.resolveFont(new HashSet<String>(Arrays.asList(
                "Liberation Sans", "Roboto"))));
        assertEquals(Font.SANS_SERIF, SchemaStyles.resolveFont(new HashSet<String>()));
    }

    static Projection box(NodeKind kind, String label) {
        return new Projection(Collections.singletonList(
                new CanvasNode("http://example.org/glyphs#A", kind, label)),
                Collections.<CanvasEdge>emptyList());
    }

    static Projection arrow(String label) {
        return new Projection(Arrays.asList(
                new CanvasNode("http://example.org/glyphs#A", NodeKind.CLASS, "egg boiling"),
                new CanvasNode("http://example.org/glyphs#B", NodeKind.CLASS, "raw egg")),
                Collections.singletonList(new CanvasEdge("e1", "http://example.org/glyphs#A",
                        "http://example.org/glyphs#B", label, CanvasEdge.Kind.OBJECT_PROPERTY)));
    }

    /** The PNG export's path, which reads the same label painter as the board. */
    static BufferedImage render(Projection projection) {
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection, new CanvasLayout());
        return mxCellRenderer.createBufferedImage(graph, null, 2.0, Color.WHITE, true, null);
    }

    static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        int[] left = a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth());
        int[] right = b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth());
        return Arrays.equals(left, right);
    }
}
