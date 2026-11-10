package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * A click answers at once, and the lines it shows follow a moment later, routed: none is drawn
 * straight through the boxes while it waits.
 *
 * <p>Found by a review of 1.135.1: every selection routed what it showed on the Swing thread, with
 * the two seconds the whole board's routing gets. On a board of 150 boxes whose properties' domains
 * and ranges meet at two upper classes, as in a BFO-based application ontology, a click on one of
 * them held the board for 2 seconds and still left 3 of its 19 lines drawn straight through 8
 * boxes; selecting every term held it for 2.2 seconds.
 */
class ClickRoutingTimeTest {

    private static final String NS = "urn:hub#";

    /** Longer than this and a click, or the routing that follows it, is felt as the board stuck. */
    private static final long FELT = 1000;

    /**
     * 6 frames of 20 classes, 30 relations, and a section of 30 properties under them, two thirds
     * of whose domains are the first class of the first frame and two thirds of whose ranges are the
     * first class of the second.
     */
    static SchemaGraph board() {
        java.util.Random random = new java.util.Random(1351L);
        int frames = 6;
        int each = 20;
        String[] words = {"process", "quality", "material", "device", "specimen", "data set",
            "report", "role", "function", "value", "unit", "property", "measurement", "setting",
            "sample", "plan", "site", "agent", "event", "document"};
        SchemaGraph graph = new SchemaGraph();
        List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        CanvasLayout row = new CanvasLayout();
        List<FramedGroup.Placement> planned = new ArrayList<FramedGroup.Placement>();
        for (int f = 0; f < frames; f++) {
            List<String> ids = new ArrayList<String>();
            Map<String, double[]> sizes = new HashMap<String, double[]>();
            Map<String, String> names = new HashMap<String, String>();
            Map<String, String> parents = new HashMap<String, String>();
            for (int t = 0; t < each; t++) {
                String id = NS + "f" + f + "t" + t;
                String label = words[random.nextInt(words.length)] + " "
                        + words[random.nextInt(words.length)] + " " + f + "." + t;
                ids.add(id);
                nodes.add(new CanvasNode(id, NodeKind.CLASS, label));
                sizes.put(id, graph.fittedSize(label, SchemaStyles.CLASS));
                names.put(id, label);
                if (t > 0 && random.nextInt(5) < 3) {
                    String parent = NS + "f" + f + "t" + random.nextInt(t);
                    parents.put(id, parent);
                    edges.add(new CanvasEdge("sub|" + id, id, parent, "", CanvasEdge.Kind.SUBCLASS));
                }
            }
            FramedGroup.Placement placement = FramedGroup.plan("Frame " + f, ids, row, sizes,
                    parents, names);
            FramedGroup.withId(placement.getFrame(), SchemaGraph.FRAME_ID_PREFIX + f);
            row.frames.add(placement.getFrame());
            row.nodes.putAll(placement.getNodes());
            planned.add(placement);
        }
        FramedGroup.wrap(planned);
        String[] verbs = {"has part", "is about", "has input", "has output", "realizes",
            "has quality", "has value", "located in", "participates in", "concretizes"};
        for (int r = 0; r < 30; r++) {
            int from = random.nextInt(frames);
            int to = random.nextInt(10) < 7 ? from : random.nextInt(frames);
            String source = NS + "f" + from + "t" + random.nextInt(each);
            String target = NS + "f" + to + "t" + random.nextInt(each);
            if (!source.equals(target)) {
                edges.add(new CanvasEdge("rel|" + r, source, target,
                        OntologyProjection.SOME + verbs[random.nextInt(verbs.length)],
                        CanvasEdge.Kind.OBJECT_PROPERTY));
            }
        }
        double bottom = 0;
        for (CanvasLayout.FrameLayout frame : row.frames) {
            bottom = Math.max(bottom, frame.y + frame.h);
        }
        double top = bottom + 120;
        int properties = 30;
        int perRow = 6;
        CanvasLayout.FrameLayout section = new CanvasLayout.FrameLayout();
        section.id = CanvasLayouts.SECTION_PREFIX + "object-properties";
        section.label = "Object properties";
        section.x = 40;
        section.y = top;
        section.w = perRow * 300 + 40;
        section.h = (properties + perRow - 1) / perRow * 100 + 80;
        section.stroke = "#B35C00";
        row.frames.add(section);
        for (int p = 0; p < properties; p++) {
            String id = NS + "p" + p;
            nodes.add(new CanvasNode(id, NodeKind.OBJECT_PROPERTY, verbs[p % verbs.length] + " " + p));
            CanvasLayout.NodeLayout at = new CanvasLayout.NodeLayout(80 + (p % perRow) * 300,
                    top + 44 + (p / perRow) * 100);
            at.w = 220;
            at.h = 60;
            row.nodes.put(id, at);
            String domain = random.nextInt(3) < 2 ? NS + "f0t0"
                    : NS + "f" + random.nextInt(frames) + "t" + random.nextInt(each);
            String range = random.nextInt(3) < 2 ? NS + "f1t0"
                    : NS + "f" + random.nextInt(frames) + "t" + random.nextInt(each);
            edges.add(new CanvasEdge("domain|" + id + "|" + domain, id, domain, "domain",
                    CanvasEdge.Kind.DOMAIN_RANGE));
            if (!range.equals(domain)) {
                edges.add(new CanvasEdge("range|" + id + "|" + range, id, range, "range",
                        CanvasEdge.Kind.DOMAIN_RANGE));
            }
        }
        graph.render(new Projection(nodes, edges), row);
        return graph;
    }

    private static boolean waits(SchemaGraph graph, Object edge) {
        String style = graph.getModel().getStyle(edge);
        String kind = style == null ? "" : style.split(";", 2)[0];
        return SchemaStyles.DISJOINT.equals(kind) || SchemaStyles.DOMAIN_RANGE.equals(kind)
                || SchemaStyles.RULE_PART.equals(kind);
    }

    private static List<Object> edges(SchemaGraph graph) {
        List<Object> edges = new ArrayList<Object>();
        Object parent = graph.getDefaultParent();
        for (int i = 0; i < graph.getModel().getChildCount(parent); i++) {
            Object cell = graph.getModel().getChildAt(parent, i);
            if (graph.getModel().isEdge(cell)) {
                edges.add(cell);
            }
        }
        return edges;
    }

    private static boolean touches(SchemaGraph graph, Object edge, Collection<String> focus) {
        return focus.contains(graph.getIdForCell(graph.getModel().getTerminal(edge, true)))
                || focus.contains(graph.getIdForCell(graph.getModel().getTerminal(edge, false)));
    }

    /** The lines the selection shows that are drawn without a route: straight, through boxes. */
    private static Set<String> drawnPlain(SchemaGraph graph) {
        Set<String> plain = new LinkedHashSet<String>();
        for (Object edge : edges(graph)) {
            if (waits(graph, edge) && graph.isCellVisible(edge) && graph.routeOf(edge) == null) {
                plain.add(graph.getIdForCell(edge));
            }
        }
        return plain;
    }

    /** The lines the selection shows that are not yet drawn with a route. */
    private static Set<String> notYetRouted(SchemaGraph graph, Collection<String> focus) {
        Set<String> left = new LinkedHashSet<String>();
        for (Object edge : edges(graph)) {
            if (waits(graph, edge) && touches(graph, edge, focus)
                    && (!graph.isCellVisible(edge) || graph.routeOf(edge) == null)) {
                left.add(graph.getIdForCell(edge));
            }
        }
        return left;
    }

    private static List<String> terms(SchemaGraph graph) {
        List<String> ids = new ArrayList<String>();
        for (Object cell : graph.getChildVertices(graph.getDefaultParent())) {
            String id = graph.getIdForCell(cell);
            if (id != null && !SchemaGraph.isAnnotationId(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Every line the selection shows that is drawn with a route, and the points it is drawn at. */
    private static Map<String, String> drawnRouted(SchemaGraph graph) {
        Map<String, String> drawn = new HashMap<String, String>();
        for (Object edge : edges(graph)) {
            if (waits(graph, edge) && graph.isCellVisible(edge) && graph.routeOf(edge) != null) {
                StringBuilder points = new StringBuilder();
                for (com.mxgraph.util.mxPoint p : graph.getView().getState(edge).getAbsolutePoints()) {
                    points.append(p.getX()).append(',').append(p.getY()).append(' ');
                }
                drawn.put(graph.getIdForCell(edge), points.toString());
            }
        }
        return drawn;
    }

    /**
     * Selects {@code ids} on the Swing thread, as a click does, and follows what happens after:
     * how long the click took, how long the board was busy at the longest while the lines came,
     * every line drawn without a route meanwhile, and every line that moved once it was drawn.
     */
    private static void clickAndFollow(SchemaGraph graph, List<String> ids, String what)
            throws Exception {
        long[] took = new long[1];
        SwingUtilities.invokeAndWait(() -> {
            long started = System.nanoTime();
            graph.setFocus(ids);
            took[0] = (System.nanoTime() - started) / 1_000_000;
            graph.getView().validate();
        });
        Set<String> plain = new LinkedHashSet<String>();
        Set<String> left = new LinkedHashSet<String>();
        Map<String, String> first = new HashMap<String, String>();
        Set<String> moved = new LinkedHashSet<String>();
        long busiest = 0;
        long started = System.nanoTime();
        long until = started + 60_000_000_000L;
        while (System.nanoTime() < until) {
            long asked = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                graph.getView().validate();
                plain.addAll(drawnPlain(graph));
                for (Map.Entry<String, String> line : drawnRouted(graph).entrySet()) {
                    String before = first.get(line.getKey());
                    if (before == null) {
                        first.put(line.getKey(), line.getValue());
                    } else if (!before.equals(line.getValue())) {
                        moved.add(line.getKey());
                    }
                }
                left.clear();
                left.addAll(notYetRouted(graph, ids));
            });
            busiest = Math.max(busiest, (System.nanoTime() - asked) / 1_000_000);
            if (left.isEmpty()) {
                break;
            }
            Thread.sleep(15);
        }
        long followed = (System.nanoTime() - started) / 1_000_000;
        System.out.println(what + ": the click took " + took[0] + "ms; its lines were all routed "
                + followed + "ms later, the board busy for " + busiest + "ms at the longest; "
                + "drawn without a route meanwhile: " + plain + "; left: " + left);
        assertTrue(took[0] < FELT, what + ": the click held the board for " + took[0] + "ms");
        assertEquals(Collections.emptySet(), plain,
                what + ": lines drawn straight, through the boxes, before they were routed");
        assertEquals(Collections.emptySet(), left, what + ": lines never routed");
        assertEquals(Collections.emptySet(), moved,
                what + ": lines drawn that moved while the others came");
        assertTrue(busiest < FELT, what + ": the routing after the click held the board for "
                + busiest + "ms");
        BoardMeasures measured = new BoardMeasures(what, graph);
        List<String> through = new ArrayList<String>();
        for (String one : measured.through) {
            if (one.startsWith("domain ") || one.startsWith("range ")) {
                through.add(one);
            }
        }
        assertEquals(new ArrayList<String>(), through, what + ": revealed lines through boxes");
    }

    @Test
    void aClickOnAClassManyLinesMeetAtAnswersAtOnceAndItsLinesFollowRouted() throws Exception {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.getView().validate();
        assertTrue(!graph.isDense() && graph.isFullyRouted(), "a board the router routes in full");
        Set<String> base = new HashSet<String>();
        for (Object edge : edges(graph)) {
            if (!waits(graph, edge)) {
                base.add(graph.getIdForCell(edge));
            }
        }
        assertTrue(base.size() > 90 && base.size() < SchemaGraph.DENSE, base.size() + " lines");

        clickAndFollow(graph, Collections.singletonList(NS + "f1t0"), "the range class");
        clickAndFollow(graph, Collections.singletonList(NS + "f0t0"), "the domain class");
    }

    @Test
    void selectingEveryTermAnswersAtOnceAndTheLinesFollowRouted() throws Exception {
        SchemaGraph graph = board();
        graph.routeEdges();
        graph.getView().validate();
        clickAndFollow(graph, terms(graph), "every term");
    }
}
