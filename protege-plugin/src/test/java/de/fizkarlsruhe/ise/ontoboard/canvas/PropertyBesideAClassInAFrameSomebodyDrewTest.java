package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxGeometry;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.PropertiesBeside;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A class that stays in a frame somebody drew has its property beside it, in that frame, which
 * grows to hold it - where nothing of theirs stands in the way.
 *
 * <p>Found by the maintainer on the pizza ontologies (1.135.1): every property stood in a section
 * below the frames. The Outline leaves what stands in a frame where it is; a property whose domain
 * is such a class used to go to the section with the rest.
 */
class PropertyBesideAClassInAFrameSomebodyDrewTest {

    private static final String NS = "http://example.org/drawn#";
    private static final String FRAME = SchemaGraph.FRAME_ID_PREFIX + "drawn";

    /**
     * A frame somebody drew round alpha - and, with {@code inTheWay}, round a class right beside
     * it - beta under the frame, and "has part", whose domain is alpha, in an old section.
     */
    private static SchemaGraph board(boolean inTheWay) {
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout layout = new CanvasLayout();
        nodes.add(new CanvasNode(NS + "alpha", NodeKind.CLASS, "alpha"));
        place(layout, NS + "alpha", 80, 120);
        nodes.add(new CanvasNode(NS + "beta", NodeKind.CLASS, "beta"));
        place(layout, NS + "beta", 80, 400);
        if (inTheWay) {
            nodes.add(new CanvasNode(NS + "gamma", NodeKind.CLASS, "gamma"));
            place(layout, NS + "gamma", 300, 120);
        }
        nodes.add(new CanvasNode(NS + "hasPart", NodeKind.OBJECT_PROPERTY, "has part"));
        place(layout, NS + "hasPart", 900, 600);
        edges.add(new CanvasEdge("domain|" + NS + "hasPart|" + NS + "alpha", NS + "hasPart",
                NS + "alpha", "domain", CanvasEdge.Kind.DOMAIN_RANGE));
        edges.add(new CanvasEdge("range|" + NS + "hasPart|" + NS + "beta", NS + "hasPart",
                NS + "beta", "range", CanvasEdge.Kind.DOMAIN_RANGE));
        CanvasLayout.FrameLayout drawn = new CanvasLayout.FrameLayout();
        drawn.id = FRAME;
        drawn.label = "My classes";
        drawn.x = 40;
        drawn.y = 40;
        drawn.w = inTheWay ? 520 : 280;
        drawn.h = 200;
        layout.frames.add(drawn);
        CanvasLayout.FrameLayout section = new CanvasLayout.FrameLayout();
        section.id = CanvasLayouts.SECTION_PREFIX + "object-properties";
        section.label = "Object properties";
        section.x = 860;
        section.y = 560;
        section.w = 300;
        section.h = 160;
        layout.frames.add(section);
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(nodes, edges), layout);
        return graph;
    }

    private static void place(CanvasLayout layout, String id, double x, double y) {
        CanvasLayout.NodeLayout node = new CanvasLayout.NodeLayout(x, y);
        node.w = 160;
        node.h = 40;
        layout.nodes.put(id, node);
    }

    private static mxGeometry at(SchemaGraph graph, String id) {
        return graph.getModel().getGeometry(graph.getCellForId(id));
    }

    private static boolean inside(mxGeometry frame, mxGeometry box) {
        return box.getX() >= frame.getX() && box.getY() >= frame.getY()
                && box.getX() + box.getWidth() <= frame.getX() + frame.getWidth()
                && box.getY() + box.getHeight() <= frame.getY() + frame.getHeight();
    }

    @Test
    void thePropertyStandsBesideItsClassInTheFrameWhichGrowsToHoldIt() {
        SchemaGraph graph = board(false);

        List<BoardSections.Section> sections = CanvasLayouts.applySections(graph, null, false);

        mxGeometry alpha = at(graph, NS + "alpha");
        mxGeometry hasPart = at(graph, NS + "hasPart");
        mxGeometry frame = at(graph, FRAME);
        assertEquals("80.0,120.0", alpha.getX() + "," + alpha.getY(), "alpha stays in its frame");
        assertEquals((alpha.getX() + alpha.getWidth() + PropertiesBeside.GAP) + "," + alpha.getY(),
                hasPart.getX() + "," + hasPart.getY(), "has part beside alpha");
        assertTrue(inside(frame, hasPart), "has part inside the frame, which is now "
                + frame.getX() + "," + frame.getY() + " " + frame.getWidth() + "x"
                + frame.getHeight());
        assertTrue(inside(frame, alpha), "alpha still inside it");
        assertEquals(0, sections.size(), "no section: its one property stands beside its class");
        assertTrue(at(graph, NS + "beta").getY() >= frame.getY() + frame.getHeight(),
                "beta, arranged by the Outline, under the frame that grew");
    }

    /**
     * Where a class somebody put in the frame stands beside alpha, the property has no room there
     * and stays in its section, and the frame is left as it was drawn.
     */
    @Test
    void whereSomethingInTheFrameStandsInTheWayThePropertyStaysInItsSection() {
        SchemaGraph graph = board(true);

        List<BoardSections.Section> sections = CanvasLayouts.applySections(graph, null, false);

        mxGeometry frame = at(graph, FRAME);
        assertEquals("40.0,40.0 520.0x200.0", frame.getX() + "," + frame.getY() + " "
                + frame.getWidth() + "x" + frame.getHeight(), "the frame as drawn");
        assertEquals(1, sections.size(), "the Object properties section");
        BoardSections.Section section = sections.get(0);
        mxGeometry hasPart = at(graph, NS + "hasPart");
        assertTrue(hasPart.getX() >= section.x && hasPart.getY() >= section.y
                && hasPart.getX() + hasPart.getWidth() <= section.x + section.w
                && hasPart.getY() + hasPart.getHeight() <= section.y + section.h,
                "has part in the section");
        assertEquals("300.0,120.0", at(graph, NS + "gamma").getX() + ","
                + at(graph, NS + "gamma").getY(), "gamma where it was put");
    }
}
