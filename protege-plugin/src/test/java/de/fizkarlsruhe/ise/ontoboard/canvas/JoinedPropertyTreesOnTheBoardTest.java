package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mxgraph.model.mxGeometry;
import com.mxgraph.util.mxPoint;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardSections;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.FramedGroup;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Property trees joined by inverse pairs, on the board: laid out by the Outline, framed, and their
 * lines routed as the board routes them.
 *
 * <p>A review of round 3 of the 1.135.1 study fixes measured, on a tree whose eight sub-properties
 * each have an inverse of their own, a partner standing behind a box of the tree's second column -
 * its line 1,938px with four bends - and a property with two inverses whose second line went round
 * the first partner. And on an ontology built with the OWL API, the two trees of a mirrored pair of
 * hierarchies levelled on whichever pair the ontology listed first: adding the tenth unrelated pair
 * reordered that hash set and moved a tree nobody touched.
 */
class JoinedPropertyTreesOnTheBoardTest {

    private static final String NS = "http://example.org/p#";

    private static final class Board {
        final List<CanvasNode> nodes = new ArrayList<CanvasNode>();
        final List<CanvasEdge> edges = new ArrayList<CanvasEdge>();

        Board property(String id, String label) {
            nodes.add(new CanvasNode(NS + id, NodeKind.OBJECT_PROPERTY, label));
            return this;
        }

        Board sub(String child, String parent) {
            edges.add(new CanvasEdge("subprop|" + child + "|" + parent, NS + child, NS + parent,
                    "rdfs:subPropertyOf", CanvasEdge.Kind.SUB_PROPERTY));
            return this;
        }

        /** As the projection draws an inverse pair: from the lesser IRI. */
        Board inverse(String one, String other) {
            String a = NS + one;
            String b = NS + other;
            String from = a.compareTo(b) <= 0 ? a : b;
            String to = from.equals(a) ? b : a;
            edges.add(new CanvasEdge("inv|" + from + "|" + to, from, to, "",
                    CanvasEdge.Kind.INVERSE));
            return this;
        }
    }

    /** Laid out by the Outline, its sections framed as the board frames them, and routed. */
    private static SchemaGraph drawn(Board board, List<BoardSections.Section> sections) {
        Projection projection = new Projection(board.nodes, board.edges);
        SchemaGraph graph = new SchemaGraph();
        graph.render(projection, new CanvasLayout());
        sections.addAll(CanvasLayouts.applySections(graph, null, false));
        CanvasLayout framed = new CanvasLayout();
        for (CanvasNode node : board.nodes) {
            mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(node.getId()));
            CanvasLayout.NodeLayout placed = new CanvasLayout.NodeLayout(at.getX(), at.getY());
            placed.w = at.getWidth();
            placed.h = at.getHeight();
            framed.nodes.put(node.getId(), placed);
        }
        for (BoardSections.Section section : sections) {
            CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
            frame.id = CanvasLayouts.SECTION_PREFIX
                    + section.label.toLowerCase(Locale.ROOT).replace(' ', '-');
            frame.label = section.label;
            frame.x = section.x;
            frame.y = section.y;
            frame.w = section.w;
            frame.h = section.h;
            frame.stroke = "#B35C00";
            framed.frames.add(frame);
        }
        graph.render(projection, framed);
        graph.routeEdges();
        graph.getView().validate();
        return graph;
    }

    private static double[] rect(SchemaGraph graph, Object cell) {
        mxCellState state = graph.getView().getState(cell);
        return new double[] {state.getX(), state.getY(), state.getWidth(), state.getHeight()};
    }

    private static String name(SchemaGraph graph, Object cell) {
        return String.valueOf(graph.getModel().getValue(cell)).split("\n")[0];
    }

    /**
     * What is wrong with an inverse line: more than the two bends a straight line between two
     * boxes is drawn with, longer than the gap between its boxes, through a box, out of its frame.
     */
    private static List<String> wrongWithTheInverseLines(SchemaGraph graph,
            BoardSections.Section frame) {
        return wrongWithTheInverseLines(graph, frame, true);
    }

    /**
     * The same, the bends and length looked at only when {@code straight}: a sub-property in the
     * second column of one tree has the first column of the other between it and its inverse, and
     * its line goes round that.
     */
    private static List<String> wrongWithTheInverseLines(SchemaGraph graph,
            BoardSections.Section frame, boolean straight) {
        List<String> wrong = new ArrayList<String>();
        for (Object edge : com.mxgraph.model.mxGraphModel.getChildCells(graph.getModel(),
                graph.getDefaultParent(), false, true)) {
            String style = graph.getModel().getStyle(edge);
            if (style == null || !style.startsWith(SchemaStyles.INVERSE)) {
                continue;
            }
            Object from = graph.getModel().getTerminal(edge, true);
            Object to = graph.getModel().getTerminal(edge, false);
            String names = name(graph, from) + " / " + name(graph, to);
            List<mxPoint> points = graph.getView().getState(edge).getAbsolutePoints();
            double[][] line = new double[points.size()][];
            double length = 0;
            for (int i = 0; i < line.length; i++) {
                line[i] = new double[] {points.get(i).getX(), points.get(i).getY()};
                if (i > 0) {
                    length += Math.abs(line[i][0] - line[i - 1][0])
                            + Math.abs(line[i][1] - line[i - 1][1]);
                }
            }
            double[] a = rect(graph, from);
            double[] b = rect(graph, to);
            double gap = Math.max(0, Math.max(a[0], b[0]) - Math.min(a[0] + a[2], b[0] + b[2]))
                    + Math.max(0, Math.max(a[1], b[1]) - Math.min(a[1] + a[3], b[1] + b[3]));
            if (straight && (line.length > 4 || length > gap + 10)) {
                wrong.add(names + ": " + (line.length - 2) + " bends, " + Math.round(length)
                        + "px for a gap of " + Math.round(gap) + "px");
            }
            for (Object box : graph.getChildVertices(graph.getDefaultParent())) {
                String boxStyle = graph.getModel().getStyle(box);
                if (box != from && box != to
                        && (boxStyle == null || !boxStyle.startsWith(SchemaStyles.FRAME))
                        && BoardMeasures.crosses(line, rect(graph, box))) {
                    wrong.add(names + ": through " + name(graph, box));
                }
            }
            for (double[] point : line) {
                if (point[0] < frame.x - 1 || point[0] > frame.x + frame.w + 1
                        || point[1] < frame.y + FramedGroup.LABEL_BAND - 1
                        || point[1] > frame.y + frame.h + 1) {
                    wrong.add(names + ": out of the frame's inside at " + Math.round(point[0])
                            + "," + Math.round(point[1]));
                    break;
                }
            }
        }
        return wrong;
    }

    /**
     * "has characteristic" with eight sub-properties, in two columns, each with its inverse a tree
     * of its own: every partner level with its property, and every line straight, short and in the
     * frame - none round a box of the second column.
     */
    @Test
    void everySubPropertysInverseLineIsShortAndStraightWhereTheTreeHasTwoColumns() {
        Board board = new Board().property("HC", "has characteristic");
        String[] kinds = {"quality", "role", "disposition", "function", "capability", "tendency",
            "power", "potential"};
        for (int i = 0; i < kinds.length; i++) {
            board.property("has" + i, "has " + kinds[i]).property("of" + i, kinds[i] + " of")
                    .sub("has" + i, "HC").inverse("has" + i, "of" + i);
        }
        for (int i = 0; i < 10; i++) {
            board.property("x" + i, "m other " + i);
        }
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        SchemaGraph graph = drawn(board, sections);

        assertEquals(new ArrayList<String>(), wrongWithTheInverseLines(graph, sections.get(0)));
    }

    /** A property with two inverses: both lines short and straight, neither round the other box. */
    @Test
    void bothLinesOfAPropertyWithTwoInversesAreShortAndStraight() {
        Board board = new Board().property("P", "a has part").property("Q", "b part of")
                .property("R", "c is part of").inverse("P", "Q").inverse("P", "R");
        for (int i = 0; i < 6; i++) {
            board.property("x" + i, "m other " + i);
        }
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        SchemaGraph graph = drawn(board, sections);

        assertEquals(new ArrayList<String>(), wrongWithTheInverseLines(graph, sections.get(0)));
    }

    /**
     * Two hierarchies that mirror each other - "has parameter" with eight sub-properties, "is
     * parameter of" with their inverses - their labels as long as the library's aquatic resource
     * observation pattern's: the two roots level, and every inverse line in the frame and through
     * no box. A review of round 4 found that pattern's "is parameter of" in a band under "has
     * parameter", because the two side by side were wider than a block of their size in a screen's
     * shape: every sub-property's line went round, and lines left the frame.
     */
    @Test
    void mirroredHierarchiesWithLongLabelsStandLevelAndTheirLinesStayInTheFrame() {
        assertEquals(new ArrayList<String>(), wrongWithAMirror(true));
    }

    /**
     * The same where only the sub-properties are each other's inverse, as the pattern reaches a
     * board without the ontology it imports, where its roots' pair is declared.
     */
    @Test
    void mirroredHierarchiesWhoseRootsAreNoPairStandLevelAndTheirLinesStayInTheFrame() {
        assertEquals(new ArrayList<String>(), wrongWithAMirror(false));
    }

    /** The mirror laid out, framed and routed: what is wrong with its roots and inverse lines. */
    private static List<String> wrongWithAMirror(boolean roots) {
        String[] stems = {"BottomType", "ClimaticZone", "DepthZone", "GeoForm", "HorizontalDist",
            "Species", "StdAbundance", "StdExploitRate"};
        Board board = new Board().property("HP", "has parameter").property("IP", "is parameter of");
        if (roots) {
            board.inverse("HP", "IP");
        }
        for (int i = 0; i < stems.length; i++) {
            board.property("of" + i, "aquaticresourceobservation:of" + stems[i])
                    .property("inv" + i, "aquaticresourceobservation:"
                            + Character.toLowerCase(stems[i].charAt(0)) + stems[i].substring(1)
                            + "OfObservation")
                    .sub("of" + i, "HP").sub("inv" + i, "IP").inverse("of" + i, "inv" + i);
        }
        board.property("HO", "has observation").property("IO", "is observation of")
                .inverse("HO", "IO");
        List<BoardSections.Section> sections = new ArrayList<BoardSections.Section>();
        SchemaGraph graph = drawn(board, sections);

        List<String> wrong = wrongWithTheInverseLines(graph, sections.get(0), false);
        mxGeometry hp = graph.getModel().getGeometry(graph.getCellForId(NS + "HP"));
        mxGeometry ip = graph.getModel().getGeometry(graph.getCellForId(NS + "IP"));
        if (hp.getY() != ip.getY()) {
            wrong.add(0, "has parameter at y " + Math.round(hp.getY()) + ", is parameter of at y "
                    + Math.round(ip.getY()));
        }
        return wrong;
    }

    private static final String O = "http://example.org/parts#";

    private static OWLObjectProperty declared(OWLOntologyManager manager, OWLOntology ontology,
            String name, String label, Set<String> on) {
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty property = df.getOWLObjectProperty(IRI.create(O + name));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(property));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                property.getIRI(), df.getOWLLiteral(label)));
        on.add(property.getIRI().toString());
        return property;
    }

    /**
     * Mirrored hierarchies - has part with has alpha part and has zeta part, part of with their
     * inverses, whose labels sort the other way - with no, one, ... twelve unrelated inverse pairs
     * beside them: the same six positions relative to has part every time, and the two trees level
     * on the pair of their roots. The OWL API lists inverse axioms in the order of a hash set, which
     * its tenth unrelated pair reorders; round 3 levelled the trees on the first pair it listed.
     */
    @Test
    void anOntologyIsLaidOutTheSameWhateverOrderItsInverseAxiomsComeIn() throws Exception {
        String[] six = {"hasPart", "hasAlphaPart", "hasZetaPart", "partOf", "alphaPartOf",
            "zetaPartOf"};
        String first = null;
        List<String> moved = new ArrayList<String>();
        for (int n = 0; n <= 12; n++) {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();
            OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/parts"));
            Set<String> on = new HashSet<String>();
            OWLObjectProperty hasPart = declared(manager, ontology, "hasPart", "has part", on);
            OWLObjectProperty hasAlpha = declared(manager, ontology, "hasAlphaPart",
                    "has alpha part", on);
            OWLObjectProperty hasZeta = declared(manager, ontology, "hasZetaPart",
                    "has zeta part", on);
            OWLObjectProperty partOf = declared(manager, ontology, "partOf", "part of", on);
            OWLObjectProperty alphaOf = declared(manager, ontology, "alphaPartOf",
                    "z alpha part of", on);
            OWLObjectProperty zetaOf = declared(manager, ontology, "zetaPartOf",
                    "a zeta part of", on);
            manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(hasAlpha, hasPart));
            manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(hasZeta, hasPart));
            manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(alphaOf, partOf));
            manager.addAxiom(ontology, df.getOWLSubObjectPropertyOfAxiom(zetaOf, partOf));
            manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(hasPart, partOf));
            manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(hasAlpha, alphaOf));
            manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(hasZeta, zetaOf));
            for (int i = 0; i < n; i++) {
                manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(
                        declared(manager, ontology, "u" + i, "unrelated " + i, on),
                        declared(manager, ontology, "v" + i, "unrelated " + i + " of", on)));
            }
            Projection projection = OntologyProjection.project(ontology, on, false);
            SchemaGraph graph = new SchemaGraph();
            graph.render(projection, new CanvasLayout());
            CanvasLayouts.applySections(graph, null, false);
            Map<String, String> relative = new LinkedHashMap<String, String>();
            mxGeometry origin = graph.getModel().getGeometry(graph.getCellForId(O + "hasPart"));
            for (String name : six) {
                mxGeometry at = graph.getModel().getGeometry(graph.getCellForId(O + name));
                relative.put(name, Math.round(at.getX() - origin.getX()) + ","
                        + Math.round(at.getY() - origin.getY()));
            }
            String now = relative.toString();
            if (first == null) {
                first = now;
                assertEquals("0", relative.get("partOf").split(",")[1],
                        "the two trees level on the pair of their roots: " + now);
            } else if (!now.equals(first)) {
                moved.add(n + " unrelated pairs: " + now);
            }
        }
        assertEquals(new ArrayList<String>(), moved, "with none: " + first);
    }
}
