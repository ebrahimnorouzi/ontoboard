package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Renders the canvas to a PNG so its appearance can be looked at.
 *
 * <p>Every claim this project has made about how the canvas <em>looks</em> has rested on somebody
 * launching Prot&eacute;g&eacute;, opening a tab and squinting. That is why the receipts keep saying
 * "nothing here looks at a pixel": the in-host self-test proves the views construct, and stops there.
 *
 * <p>It does not have to stop there. {@code mxCellRenderer} draws from the graph model and never touches
 * a component - which is why an overlay like the note badge is absent from an export - so the whole
 * diagram can be rendered headlessly, at any scale, from the same {@code SchemaStyles} the canvas
 * installs. This writes those images to {@code target/design/}, where a reviewer, or the author of a
 * style change, can open them.
 *
 * <p>What it asserts is deliberately weak: that an image was produced, that it is not blank, and that it
 * is not one flat colour. A test cannot tell handsome from ugly. What it can do is make the difference
 * visible without a twenty-minute round trip through two Prot&eacute;g&eacute; installs, and fail loudly
 * if a style change makes the diagram render as nothing at all - which a stylesheet typo does.
 *
 * <p>The board is representative on purpose: every node kind, every edge kind, a term with a note, an
 * unsatisfiable class, an imported term, a sticky note and a frame. A design reviewed on three white
 * boxes is a design reviewed on nothing.
 */
class CanvasDesignProofTest {

    private static final String PIZZA = "http://www.co-ode.org/ontologies/pizza/pizza.owl#";
    private static final String OBO = "http://purl.obolibrary.org/obo/";

    /** Where the images go. Under target, so it is never committed and never stale. */
    private static File designDirectory() {
        File directory = new File("target/design");
        directory.mkdirs();
        return directory;
    }

    /**
     * Renders on the colour the canvas actually is, not the colour an export is.
     *
     * <p>{@code CanvasExport} fills with white, correctly: an exported diagram goes into a paper or a
     * slide, and a grey wash behind it there would be somebody else's design decision. But reviewing the
     * canvas on white is reviewing something the user never sees - every judgement about whether a stroke
     * or a fill reads is a judgement against the wrong backdrop, and the difference between #FFFFFF and
     * #F7F8FA is exactly the sort of thing a pale fill disappears into.
     *
     * <p>Two fidelity gaps remain and are not worth faking. {@code mxCellRenderer} draws from the model,
     * so the grid the real canvas shows is absent, and so is anything painted as a component overlay -
     * the peer cursors and the note badge. Where a review depends on those, it has to happen in
     * Prot&eacute;g&eacute;.
     */
    private static void render(SchemaGraph graph, File target, double scale) throws Exception {
        BufferedImage image = com.mxgraph.util.mxCellRenderer.createBufferedImage(
                graph, null, scale, java.awt.Color.decode(SchemaStyles.CANVAS_BACKGROUND),
                true, null);
        assertTrue(image != null, "nothing rendered - the graph has no bounds");
        ImageIO.write(image, "PNG", target);
    }

    private static CanvasNode cls(String localName, String label) {
        return new CanvasNode(PIZZA + localName, NodeKind.CLASS, label);
    }

    /**
     * A board with one of everything, laid out as the canvas lays it out.
     *
     * <p>Positions come from the real hierarchical layout rather than from hand-picked coordinates, so
     * what is rendered is what a user gets after pressing <em>Arrange</em>.
     */
    private static SchemaGraph representativeBoard() {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>(Arrays.asList(
                cls("Pizza", "Pizza"),
                cls("NamedPizza", "Named pizza"),
                cls("Margherita", "Margherita"),
                cls("AmericanHot", "American Hot"),
                cls("PizzaTopping", "Pizza topping"),
                cls("MozzarellaTopping", "Mozzarella"),
                cls("JalapenoPepperTopping", "Jalapeño pepper"),
                new CanvasNode(PIZZA + "CheeseyVegetableTopping", NodeKind.CLASS,
                        "Cheesey vegetable topping").asUnsatisfiable(),
                new CanvasNode(PIZZA + "Country", NodeKind.CLASS, "Country",
                        Collections.singletonList("Only the four we actually use - see issue 412.")),
                new CanvasNode(PIZZA + "Italy", NodeKind.INDIVIDUAL, "Italy"),
                new CanvasNode(PIZZA + "hasTopping", NodeKind.OBJECT_PROPERTY, "has topping"),
                new CanvasNode(PIZZA + "hasCalorificContentValue", NodeKind.DATA_PROPERTY,
                        "has calorific content"),
                new CanvasNode("http://www.w3.org/2001/XMLSchema#integer", NodeKind.DATATYPE,
                        "xsd:integer"),
                new CanvasNode(OBO + "BFO_0000002", NodeKind.CLASS, "continuant").asImported()));

        List<CanvasEdge> edges = new ArrayList<CanvasEdge>(Arrays.asList(
                new CanvasEdge("sub|1", PIZZA + "NamedPizza", PIZZA + "Pizza", "",
                        CanvasEdge.Kind.SUBCLASS),
                new CanvasEdge("sub|2", PIZZA + "Margherita", PIZZA + "NamedPizza", "",
                        CanvasEdge.Kind.SUBCLASS),
                new CanvasEdge("sub|3", PIZZA + "AmericanHot", PIZZA + "NamedPizza", "",
                        CanvasEdge.Kind.SUBCLASS),
                new CanvasEdge("sub|4", PIZZA + "MozzarellaTopping", PIZZA + "PizzaTopping", "",
                        CanvasEdge.Kind.SUBCLASS),
                new CanvasEdge("sub|5", PIZZA + "JalapenoPepperTopping", PIZZA + "PizzaTopping", "",
                        CanvasEdge.Kind.SUBCLASS),
                new CanvasEdge("rest|1", PIZZA + "Margherita", PIZZA + "MozzarellaTopping",
                        "has topping", CanvasEdge.Kind.OBJECT_PROPERTY),
                new CanvasEdge("rest|2", PIZZA + "AmericanHot", PIZZA + "JalapenoPepperTopping",
                        "has topping", CanvasEdge.Kind.OBJECT_PROPERTY),
                new CanvasEdge("data|1", PIZZA + "Pizza",
                        "http://www.w3.org/2001/XMLSchema#integer", "has calorific content",
                        CanvasEdge.Kind.DATA_PROPERTY),
                new CanvasEdge("type|1", PIZZA + "Italy", PIZZA + "Country", "",
                        CanvasEdge.Kind.TYPE),
                new CanvasEdge("inf-sub|1", PIZZA + "Margherita", PIZZA + "Pizza", "",
                        CanvasEdge.Kind.INFERRED_SUBCLASS),
                new CanvasEdge("inf-type|1", PIZZA + "Italy", PIZZA + "Pizza", "",
                        CanvasEdge.Kind.INFERRED_TYPE)));

        CanvasLayout layout = new CanvasLayout();
        layout.prefixColors.put(PIZZA, "#4A90D9");
        layout.prefixColors.put(OBO, "#7B61A8");

        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = SchemaGraph.NOTE_ID_PREFIX + "design";
        note.text = "Toppings still need the vegetarian axioms. Ask Maria before the release.";
        note.x = 40;
        note.y = 620;
        layout.notes.add(note);

        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = SchemaGraph.FRAME_ID_PREFIX + "design";
        frame.label = "Toppings";
        frame.x = 300;
        frame.y = 600;
        frame.w = 520;
        frame.h = 220;
        layout.frames.add(frame);

        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);
        return graph;
    }

    /** True when the image has more than a handful of distinct colours - so it drew something. */
    private static int distinctColours(BufferedImage image) {
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (int x = 0; x < image.getWidth(); x += 3) {
            for (int y = 0; y < image.getHeight(); y += 3) {
                seen.add(image.getRGB(x, y));
                if (seen.size() > 64) {
                    return seen.size();
                }
            }
        }
        return seen.size();
    }

    @Test
    void theBoardRendersToAnImageSomebodyCanLookAt() throws Exception {
        File png = new File(designDirectory(), "board.png");

        render(representativeBoard(), png, 2.0);

        assertTrue(png.isFile() && png.length() > 4096,
                "no usable image at " + png.getAbsolutePath() + " (" + png.length() + " bytes)");
        BufferedImage image = ImageIO.read(png);
        assertTrue(image.getWidth() > 400 && image.getHeight() > 300,
                image.getWidth() + "x" + image.getHeight() + " is not a diagram");
        // A stylesheet typo that makes every shape transparent renders a flat rectangle, which is the
        // failure this catches: the file exists, the size is right, and there is nothing on it.
        assertTrue(distinctColours(image) > 12,
                "the diagram rendered as " + distinctColours(image) + " colours - it is blank");
    }

    /** The same board as SVG, which is what goes into a paper. */
    @Test
    void theBoardRendersToSvg() throws Exception {
        File svg = new File(designDirectory(), "board.svg");

        CanvasExport.writeSvg(representativeBoard(), svg);

        String content = new String(java.nio.file.Files.readAllBytes(svg.toPath()), "UTF-8");
        assertTrue(content.contains("<svg"), content.substring(0, Math.min(200, content.length())));
        assertTrue(content.length() > 2000, "an SVG of one node is not the board: " + content.length());
    }

    /**
     * A wall of terms, which is what the ontologies this plugin is for actually look like.
     *
     * <p>Thirty classes in a hierarchy, rendered at screen scale. A design that reads well on a dozen
     * nodes and turns into noise on thirty is a design that has not been reviewed on real work - and
     * "Add all" on the pizza ontology produces a hundred.
     */
    @Test
    void aFullBoardRendersToo() throws Exception {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        nodes.add(cls("Thing", "Thing"));
        for (int i = 0; i < 30; i++) {
            nodes.add(cls("Term" + i, "Term " + i));
            edges.add(new CanvasEdge("sub|" + i, PIZZA + "Term" + i,
                    i < 4 ? PIZZA + "Thing" : PIZZA + "Term" + (i / 4), "",
                    CanvasEdge.Kind.SUBCLASS));
        }
        CanvasLayout layout = new CanvasLayout();
        layout.prefixColors.put(PIZZA, "#4A90D9");
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        File png = new File(designDirectory(), "board-large.png");
        render(graph, png, 1.0);

        assertTrue(png.isFile() && png.length() > 4096, png.getAbsolutePath());
    }

    // ===================================================================== the panels around it

    /**
     * Paints a Swing panel to a PNG, without a display.
     *
     * <p>Lightweight Swing components can be built and painted in a headless JVM - only a {@code Window}
     * cannot exist - so the panels this plugin puts around the canvas can be reviewed the same way the
     * diagram is. That covers the half of "how it looks" that {@code mxCellRenderer} cannot show, and it
     * is the half nobody has ever looked at outside a running Prot&eacute;g&eacute;.
     *
     * <p>The panel is sized to its preferred size unless one is given, then laid out explicitly: without
     * {@code doLayout} a component that has never been shown has every child at 0x0, and the image comes
     * out blank in a way that looks like a rendering bug rather than a missing call.
     */
    private static void paintPanel(javax.swing.JComponent panel, File target, int width, int height)
            throws Exception {
        java.awt.Dimension preferred = panel.getPreferredSize();
        int w = width > 0 ? width : Math.max(320, preferred.width);
        int h = height > 0 ? height : Math.max(200, preferred.height);
        panel.setSize(w, h);
        panel.doLayout();
        layOutChildren(panel);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, w, h);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "PNG", target);
    }

    /** Lays out every descendant, which one doLayout on the root does not do. */
    private static void layOutChildren(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container) {
                layOutChildren((java.awt.Container) child);
            }
        }
    }

    /**
     * The legend, with the namespace rows a real board produces.
     *
     * <p>It is the plugin's only explanation of what the diagram's shapes and colours mean, it opens in a
     * dialog, and its appearance has never been reviewed anywhere but by opening it.
     */
    @Test
    void theLegendRendersToAnImageSomebodyCanLookAt() throws Exception {
        java.util.Map<String, String> prefixes = new java.util.LinkedHashMap<String, String>();
        prefixes.put(PIZZA, "#4A90D9");
        prefixes.put(OBO, "#7B61A8");
        prefixes.put("http://www.w3.org/2002/07/owl#", "#3E8E5A");

        File png = new File(designDirectory(), "legend.png");
        paintPanel(new LegendPanel(prefixes), png, 0, 0);

        assertTrue(png.isFile() && png.length() > 2048, png.getAbsolutePath()
                + " (" + png.length() + " bytes)");
        assertTrue(distinctColours(ImageIO.read(png)) > 8, "the legend rendered blank");
    }

    /**
     * The empty state, which is the first thing a new user sees.
     *
     * <p>The three actions are wired to runnables the view supplies; here they do nothing, because what is
     * being looked at is the layout and the words.
     */
    @Test
    void theEmptyStateRendersToAnImageSomebodyCanLookAt() throws Exception {
        Runnable nothing = new Runnable() {
            @Override
            public void run() {
            }
        };

        File png = new File(designDirectory(), "empty-state.png");
        paintPanel(new StartPanel(nothing, nothing, nothing, nothing), png, 900, 560);

        assertTrue(png.isFile() && png.length() > 2048, png.getAbsolutePath()
                + " (" + png.length() + " bytes)");
        assertTrue(distinctColours(ImageIO.read(png)) > 4, "the empty state rendered blank");
    }
}

