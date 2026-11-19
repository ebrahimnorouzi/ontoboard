package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The board's look as a setting (1.155.0): each kind of box and line has a colour, a shape or a
 * dash, with defaults, and the stylesheet is built from them. The defaults are 1.154.0's board,
 * style for style.
 */
class BoardLookTest {

    /**
     * Every style the board registers, one key and value to a line, sorted; the font family left
     * out, because it is whatever the machine has - Segoe UI here, DejaVu Sans on GitHub's Linux.
     */
    static String dump(mxStylesheet sheet) {
        StringBuilder dump = new StringBuilder();
        for (Map.Entry<String, Map<String, Object>> style
                : new TreeMap<String, Map<String, Object>>(sheet.getStyles()).entrySet()) {
            for (Map.Entry<String, Object> key : new TreeMap<String, Object>(style.getValue()).entrySet()) {
                if (!key.getKey().equals(mxConstants.STYLE_FONTFAMILY)) {
                    Object value = key.getValue();
                    // An object - a perimeter - by its class: its identity changes every run.
                    String text = value instanceof String || value instanceof Number
                            || value instanceof Boolean ? String.valueOf(value)
                                    : "<" + value.getClass().getName() + ">";
                    dump.append(style.getKey()).append('\t').append(key.getKey()).append('\t')
                            .append(text).append('\n');
                }
            }
        }
        return dump.toString();
    }

    private static String golden() throws Exception {
        InputStream in = BoardLookTest.class.getResourceAsStream("/canvas/styles-1.154.0.tsv");
        if (in == null) {
            return null;
        }
        try {
            return new String(readAll(in), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) > 0;) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Test
    void theDefaultLookIsTheBoardOf1154() throws Exception {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph);
        String actual = dump(graph.getStylesheet());
        Files.write(new File("target/styles-actual.tsv").toPath(), actual.getBytes(StandardCharsets.UTF_8));

        assertEquals(golden(), actual, "the stylesheet differs from 1.154.0's - target/styles-actual.tsv");
    }

    @Test
    void theDefaultsInstalledByNameAreTheSameBoard() throws Exception {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph, BoardLook.defaults());

        assertEquals(golden(), dump(graph.getStylesheet()));
    }

    @Test
    void aChangedColourOrLineReachesTheCellStyle() {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph, BoardLook.defaults().with("obClass.fill", "#FFEEDD")
                .with("obSubClass.colour", "#112233").with("obSubClass.line", "8 4")
                .with("obOnly.line", ""));

        Map<String, Object> cls = graph.getStylesheet().getStyles().get(SchemaStyles.CLASS);
        Map<String, Object> subclass = graph.getStylesheet().getStyles().get(SchemaStyles.SUBCLASS);
        Map<String, Object> only = graph.getStylesheet().getStyles().get(SchemaStyles.ONLY);
        assertEquals("#FFEEDD", cls.get(mxConstants.STYLE_FILLCOLOR));
        assertEquals("#112233", subclass.get(mxConstants.STYLE_STROKECOLOR));
        assertEquals(Boolean.TRUE, subclass.get(mxConstants.STYLE_DASHED));
        assertEquals("8 4", subclass.get(mxConstants.STYLE_DASH_PATTERN));
        assertEquals(null, only.get(mxConstants.STYLE_DASHED), "solid: no dash");
        assertEquals("#FFEEDD", graph.getStylesheet().getStyles().get(SchemaStyles.NOTED)
                .get(mxConstants.STYLE_FILLCOLOR), "the legend's marked classes follow the class");
    }

    @Test
    void aBoxTakesOneOfTheFourShapesABoardDraws() {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph, BoardLook.defaults().with("obClass.shape", "hexagon")
                .with("obObjectPropertyNode.shape", "rounded").with("obIndividual.shape", "rectangle")
                .with("obLiteral.shape", "ellipse"));
        Map<String, Map<String, Object>> styles = graph.getStylesheet().getStyles();

        assertEquals(mxConstants.SHAPE_HEXAGON, styles.get(SchemaStyles.CLASS).get(mxConstants.STYLE_SHAPE));
        assertEquals(22, styles.get(SchemaStyles.CLASS).get(mxConstants.STYLE_SPACING_LEFT),
                "room for the label inside the taper");
        assertEquals(mxConstants.SHAPE_RECTANGLE,
                styles.get(SchemaStyles.OBJECT_PROPERTY_NODE).get(mxConstants.STYLE_SHAPE));
        assertEquals(Boolean.TRUE, styles.get(SchemaStyles.OBJECT_PROPERTY_NODE).get(mxConstants.STYLE_ROUNDED));
        assertEquals(null, styles.get(SchemaStyles.OBJECT_PROPERTY_NODE).get(mxConstants.STYLE_SPACING_LEFT));
        assertEquals(Boolean.FALSE, styles.get(SchemaStyles.INDIVIDUAL).get(mxConstants.STYLE_ROUNDED));
        assertEquals(mxConstants.SHAPE_ELLIPSE, styles.get(SchemaStyles.LITERAL).get(mxConstants.STYLE_SHAPE));
    }

    @Test
    void anImportedTermIsAsFadedAsTheLookSays() {
        SchemaGraph graph = new SchemaGraph();
        SchemaStyles.install(graph, BoardLook.defaults().with("obImported.opacity", "70"));
        de.fizkarlsruhe.ise.ontoboard.model.CanvasNode imported = new de.fizkarlsruhe.ise.ontoboard.model.CanvasNode(
                "http://example.org/o#Agent", de.fizkarlsruhe.ise.ontoboard.model.NodeKind.CLASS, "Agent")
                .asImported();

        graph.render(new de.fizkarlsruhe.ise.ontoboard.model.Projection(java.util.Collections.singletonList(imported),
                java.util.Collections.<de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge>emptyList()),
                new de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout());

        String style = graph.getModel().getStyle(graph.getCellForId("http://example.org/o#Agent"));
        assertEquals(true, style.contains("opacity=70"), style);
    }

    /**
     * The board's look is the defaults, a person's own over them, and a project's over both
     * (decided 2026-11-19: "Per user, project can override").
     */
    @Test
    void aProjectsLookIsOverThePersonsOverTheDefaults() {
        Map<String, String> person = new java.util.LinkedHashMap<String, String>();
        person.put("obClass.fill", "#111111");
        person.put("obSubClass.colour", "#222222");
        Map<String, String> project = java.util.Collections.singletonMap("obClass.fill", "#333333");

        BoardLook look = BoardLook.effective(person, project);

        assertEquals("#333333", look.get("obClass.fill"), "the project's");
        assertEquals("#222222", look.get("obSubClass.colour"), "the person's, where the project says nothing");
        assertEquals(BoardLook.defaults().get("obOnly.colour"), look.get("obOnly.colour"), "the default");
        assertEquals(BoardLook.defaults(), BoardLook.effective(null, null));
    }

    /** A person's look is kept in Protégé's preferences as text: a key and a value to a line. */
    @Test
    void aLookIsWrittenAsTextAndReadBack() {
        Map<String, String> changes = BoardLook.defaults().with("obClass.fill", "#FFEEDD")
                .with(BoardLook.SPACING, "roomy").changes();

        String text = BoardLook.format(changes);

        assertEquals("obClass.fill=#FFEEDD\nboard.spacing=roomy\n", text);
        assertEquals(changes, BoardLook.parse(text));
        assertEquals(changes, BoardLook.parse("# a comment\n\nobClass.fill=#FFEEDD\r\nnonsense\n"
                + "board.spacing=roomy\n"), "a line that is no key=value is skipped");
        assertEquals(java.util.Collections.emptyMap(), BoardLook.parse(null));
    }

    @Test
    void aLookIsKeptAsWhatDiffersFromTheDefaults() {
        BoardLook look = BoardLook.defaults().with("obClass.fill", "#FFEEDD").with("obClass.shape", "rounded");

        assertEquals(java.util.Collections.singletonMap("obClass.fill", "#FFEEDD"), look.changes(),
                "the class is rounded already");
        assertEquals(look.changes(), BoardLook.defaults().withChanges(look.changes()).changes());
        Map<String, String> wrong = new java.util.LinkedHashMap<String, String>();
        wrong.put("obNothing.fill", "#000000");
        wrong.put("obClass.fill", "red");
        wrong.put("obClass.shape", "star");
        wrong.put("obSubClass.line", "dashed");
        wrong.put("obImported.opacity", "5");
        assertEquals(java.util.Collections.emptyMap(), BoardLook.defaults().withChanges(wrong).changes(),
                "a key no kind has, or a value no board can draw, is left out");
        assertEquals("70", BoardLook.defaults().with("obImported.opacity", "70").changes()
                .get("obImported.opacity"));
    }
}
