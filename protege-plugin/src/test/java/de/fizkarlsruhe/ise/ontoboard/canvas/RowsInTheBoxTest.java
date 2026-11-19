package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A class's box drawn UML-like (1.155.0): its name, a rule, and its rows below - data properties
 * "name : datatype [n..m]" and keys - and a box tall enough to hold them.
 */
class RowsInTheBoxTest {

    private static final String U = "http://example.org/u#";
    private static final List<String> ROWS = Arrays.asList("age : integer [0..1]", "name : string [1]",
            "key: id");

    @Test
    void aClassBoxHoldsItsRowsBelowItsName() throws Exception {
        SchemaGraph graph = new SchemaGraph();
        CanvasNode person = new CanvasNode(U + "Person", NodeKind.CLASS, "Person").withRows(ROWS);
        CanvasNode pizza = new CanvasNode(U + "Pizza", NodeKind.CLASS, "Pizza");
        CanvasNode defined = new CanvasNode(U + "Margherita", NodeKind.CLASS, "≡ Margherita")
                .withRows(Collections.singletonList("kind = \"classic\""));
        graph.render(new Projection(Arrays.asList(person, pizza, defined),
                Collections.<CanvasEdge>emptyList()), new CanvasLayout());

        Object box = graph.getCellForId(U + "Person");
        assertTrue(graph.getModel().getStyle(box).contains("shape=" + RowsShape.NAME),
                graph.getModel().getStyle(box));
        assertEquals(ROWS, graph.rowsOf(box));
        assertEquals("Person", graph.getModel().getValue(box), "the label stays the name");
        assertTrue(graph.getModel().getStyle(graph.getCellForId(U + "Margherita"))
                .contains("shape=" + RowsShape.DEFINED), "a defined class keeps its badge");
        mxGeometry withRows = graph.getModel().getGeometry(box);
        mxGeometry without = graph.getModel().getGeometry(graph.getCellForId(U + "Pizza"));
        assertTrue(withRows.getHeight() >= without.getHeight() + 3 * 15,
                "room for three rows: " + withRows.getHeight() + " against " + without.getHeight());
        assertTrue(graph.rowsOf(graph.getCellForId(U + "Pizza")).isEmpty());

        java.awt.image.BufferedImage picture = com.mxgraph.util.mxCellRenderer.createBufferedImage(graph,
                null, 1, java.awt.Color.WHITE, true, null);
        File png = new File("target/rows-in-the-box.png");
        javax.imageio.ImageIO.write(picture, "PNG", png);
        assertTrue(png.length() > 0);
    }

    @Test
    void aBoxGrowsWhenItsRowsDo() throws Exception {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        graph.render(new Projection(Collections.singletonList(new CanvasNode(U + "Person", NodeKind.CLASS,
                "Person")), Collections.<CanvasEdge>emptyList()), layout);
        double before = graph.getModel().getGeometry(graph.getCellForId(U + "Person")).getHeight();

        graph.render(new Projection(Collections.singletonList(new CanvasNode(U + "Person", NodeKind.CLASS,
                "Person").withRows(ROWS)), Collections.<CanvasEdge>emptyList()), layout);

        assertTrue(graph.getModel().getGeometry(graph.getCellForId(U + "Person")).getHeight() > before);
    }

    /**
     * A board saved before its rows were drawn - every board of 1.154.0 - stores a box the size
     * its name needed. The box grows to hold its rows, stays grown at the next drawing, which reads
     * the stored size again, and goes back when the rows go.
     */
    @Test
    void aStoredBoxHoldsItsRowsAtEveryDrawing() throws Exception {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        CanvasLayout.NodeLayout stored = new CanvasLayout.NodeLayout(100, 100);
        stored.w = 160;
        stored.h = 40;
        layout.nodes.put(U + "Person", stored);
        Projection withRows = new Projection(Collections.singletonList(new CanvasNode(U + "Person",
                NodeKind.CLASS, "Person").withRows(ROWS)), Collections.<CanvasEdge>emptyList());

        graph.render(withRows, layout);
        double grown = heightOf(graph, U + "Person");
        graph.render(withRows, layout);

        assertTrue(grown >= 40 + 3 * 15, "grown to hold three rows: " + grown);
        assertEquals(grown, heightOf(graph, U + "Person"), "the same at the next drawing");
        graph.render(new Projection(Collections.singletonList(new CanvasNode(U + "Person", NodeKind.CLASS,
                "Person")), Collections.<CanvasEdge>emptyList()), layout);
        assertEquals(40, heightOf(graph, U + "Person"), "back to the size its name needs");
        graph.render(new Projection(Collections.singletonList(new CanvasNode(U + "Person", NodeKind.CLASS,
                "Person")), Collections.<CanvasEdge>emptyList()), layout);
        assertEquals(40, heightOf(graph, U + "Person"), "and stays there");
    }

    /** The same for a name: a box renamed to a longer name keeps the size it was given. */
    @Test
    void aStoredBoxKeepsTheSizeANewNameGaveIt() throws Exception {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        CanvasLayout.NodeLayout stored = new CanvasLayout.NodeLayout(100, 100);
        stored.w = 160;
        stored.h = 40;
        layout.nodes.put(U + "Person", stored);
        graph.render(new Projection(Collections.singletonList(new CanvasNode(U + "Person", NodeKind.CLASS,
                "Person")), Collections.<CanvasEdge>emptyList()), layout);
        Projection renamed = new Projection(Collections.singletonList(new CanvasNode(U + "Person",
                NodeKind.CLASS, "a person somebody named at very great length")),
                Collections.<CanvasEdge>emptyList());

        graph.render(renamed, layout);
        double wider = graph.getModel().getGeometry(graph.getCellForId(U + "Person")).getWidth();
        graph.render(renamed, layout);

        assertTrue(wider > 160, "wider for its new name: " + wider);
        assertEquals(wider, graph.getModel().getGeometry(graph.getCellForId(U + "Person")).getWidth(),
                "the same at the next drawing");
    }

    private static double heightOf(SchemaGraph graph, String id) {
        return graph.getModel().getGeometry(graph.getCellForId(id)).getHeight();
    }
}
