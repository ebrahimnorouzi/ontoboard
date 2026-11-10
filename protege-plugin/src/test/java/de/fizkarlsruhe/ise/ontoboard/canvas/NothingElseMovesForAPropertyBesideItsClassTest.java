package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A property that comes to stand beside its class moves, and nothing else does: the classes, the
 * individuals, a note and a frame somebody drew, with what is in it, stand where they stood on the
 * same board whose property has no domain.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): every property stood in a section
 * of its own, joined to nothing. Where it stands now is decided for it alone.
 */
class NothingElseMovesForAPropertyBesideItsClassTest {

    private static final String NS = "http://example.org/zoo#";
    private static final String FRAME = SchemaGraph.FRAME_ID_PREFIX + "garden";
    private static final String NOTE = SchemaGraph.NOTE_ID_PREFIX + "remember";

    /** The board, "houses" with its domain zoo or with none, arranged by the Outline. */
    private static SchemaGraph arranged(boolean domain) {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        for (String cls : new String[] {"animal", "cat", "dog", "zoo", "plant"}) {
            nodes.add(new CanvasNode(NS + cls, NodeKind.CLASS, cls));
        }
        nodes.add(new CanvasNode(NS + "tom", NodeKind.INDIVIDUAL, "tom"));
        nodes.add(new CanvasNode(NS + "houses", NodeKind.OBJECT_PROPERTY, "houses"));
        edges.add(new CanvasEdge("sub|cat", NS + "cat", NS + "animal", "",
                CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("sub|dog", NS + "dog", NS + "animal", "",
                CanvasEdge.Kind.SUBCLASS));
        edges.add(new CanvasEdge("type|tom", NS + "tom", NS + "cat", "", CanvasEdge.Kind.TYPE));
        if (domain) {
            edges.add(new CanvasEdge("domain|" + NS + "houses|" + NS + "zoo", NS + "houses",
                    NS + "zoo", "domain", CanvasEdge.Kind.DOMAIN_RANGE));
        }
        CanvasLayout layout = new CanvasLayout();
        CanvasLayout.NodeLayout plant = new CanvasLayout.NodeLayout(80, 120);
        plant.w = 160;
        plant.h = 40;
        layout.nodes.put(NS + "plant", plant);
        CanvasLayout.FrameLayout garden = new CanvasLayout.FrameLayout();
        garden.id = FRAME;
        garden.label = "garden";
        garden.x = 40;
        garden.y = 40;
        garden.w = 240;
        garden.h = 160;
        layout.frames.add(garden);
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = NOTE;
        note.text = "remember the zoo";
        note.x = 600;
        note.y = 60;
        note.w = 180;
        note.h = 120;
        layout.notes.add(note);
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        CanvasLayouts.applySections(graph, null, false);
        return graph;
    }

    private static Map<String, String> everythingButTheProperty(SchemaGraph graph) {
        Map<String, String> where = new LinkedHashMap<String, String>();
        for (String id : new String[] {NS + "animal", NS + "cat", NS + "dog", NS + "zoo",
            NS + "plant", NS + "tom", NOTE, FRAME}) {
            mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(id));
            where.put(id, at.getX() + "," + at.getY() + " " + at.getWidth() + "x"
                    + at.getHeight());
        }
        return where;
    }

    @Test
    void onlyThePropertyMovesWhenItsDomainPutsItBesideItsClass() {
        SchemaGraph besideZoo = arranged(true);
        SchemaGraph inItsSection = arranged(false);

        mxGeometry zoo = besideZoo.getModel().getGeometry(besideZoo.getCellForId(NS + "zoo"));
        mxGeometry houses = besideZoo.getModel().getGeometry(
                besideZoo.getCellForId(NS + "houses"));
        assertEquals((zoo.getX() + zoo.getWidth() + PropertiesBeside.GAP) + "," + zoo.getY(),
                houses.getX() + "," + houses.getY(), "houses beside zoo");
        assertEquals(everythingButTheProperty(inItsSection), everythingButTheProperty(besideZoo),
                "everything else where it stands when houses is in its section");
    }
}
