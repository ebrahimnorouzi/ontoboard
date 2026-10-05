package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The board as GraphViz source.
 *
 * <p>Taken from OntoGraf. PNG and SVG hand somebody a picture; DOT hands them the graph, which
 * is what a LaTeX pipeline or a house style actually needs - a figure has to match the rest of
 * the document, and an exported bitmap never will.
 *
 * <p>What is checked here is what would break a reader: the file has to parse. Everything else
 * is taste, and taste is checked by looking at a rendering.
 */
class DotExportTest {

    private static final String NS = "http://example.org/o#";

    private static CanvasNode cls(String name) {
        return new CanvasNode(NS + name, NodeKind.CLASS, name);
    }

    private static Projection board(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        return new Projection(nodes, edges);
    }

    /** A graph with a node and an edge produces a digraph containing both. */
    @Test
    void aBoardBecomesADigraph() {
        CanvasNode pizza = cls("Pizza");
        CanvasNode food = cls("Food");
        String dot = DotExport.of(board(Arrays.asList(pizza, food),
                Arrays.asList(new CanvasEdge("sub|a|b", pizza.getId(), food.getId(), "",
                        CanvasEdge.Kind.SUBCLASS))), "pizza");

        assertTrue(dot.startsWith("//"), "a generated file says so on its first line");
        assertTrue(dot.contains("digraph \"pizza\" {"), dot);
        assertTrue(dot.contains("n0 -> n1"), dot);
        assertTrue(dot.trim().endsWith("}"), dot);
        assertEquals(countOf(dot, "{"), countOf(dot, "}"), "braces must balance or dot refuses");
    }

    /**
     * Identifiers are sequential, and the IRI travels as a tooltip.
     *
     * <p>A DOT identifier may not contain most of what an IRI contains. Quoting around that
     * produces a file half the tooling rejects, so the IRI goes where it is safe.
     */
    @Test
    void iriesDoNotBecomeIdentifiers() {
        String dot = DotExport.of(board(Arrays.asList(cls("Pizza")),
                new ArrayList<CanvasEdge>()), "o");

        assertTrue(dot.contains("n0 ["), dot);
        assertTrue(dot.contains("tooltip=\"" + NS + "Pizza\""), dot);
        assertFalse(dot.contains("  " + NS), "an IRI must never be used as a node name");
    }

    /** An edge to something not on the board is dropped, not invented. */
    @Test
    void anEdgeToNowhereIsDropped() {
        CanvasNode pizza = cls("Pizza");
        String dot = DotExport.of(board(Arrays.asList(pizza),
                Arrays.asList(new CanvasEdge("sub|a|b", pizza.getId(), NS + "Absent", "",
                        CanvasEdge.Kind.SUBCLASS))), "o");

        assertFalse(dot.contains("->"), "the canvas never drags a term onto a diagram: " + dot);
    }

    /** An inferred edge is dotted, so a conclusion cannot be mistaken for an axiom. */
    @Test
    void anInferredEdgeIsDotted() {
        CanvasNode dog = cls("Dog");
        CanvasNode animal = cls("Animal");
        String dot = DotExport.of(board(Arrays.asList(dog, animal),
                Arrays.asList(new CanvasEdge("inf|a|b", dog.getId(), animal.getId(), "inferred",
                        CanvasEdge.Kind.INFERRED_SUBCLASS))), "o");

        assertTrue(dot.contains("style=dotted"), dot);
    }

    /** The identifier and the label are two lines, as they are on the board. */
    @Test
    void aNodeShowsItsIdentifierAboveItsLabel() {
        CanvasNode role = new CanvasNode(NS + "BFO_0000023", NodeKind.CLASS, "role")
                .withCurie("obo:BFO_0000023");

        String dot = DotExport.of(board(Arrays.asList(role), new ArrayList<CanvasEdge>()), "o");

        assertTrue(dot.contains("label=\"obo:BFO_0000023\\nrole\""), dot);
    }

    /** A label that repeats the identifier is written once, as on the board. */
    @Test
    void anIdenticalLabelIsNotRepeated() {
        CanvasNode node = new CanvasNode(NS + "X", NodeKind.CLASS, "obo:X").withCurie("obo:X");

        assertTrue(DotExport.of(board(Arrays.asList(node), new ArrayList<CanvasEdge>()), "o")
                .contains("label=\"obo:X\""));
    }

    /**
     * A quote or a backslash in a label does not break the file.
     *
     * <p>Backslashes are escaped before quotes, or escaping the quotes would then escape the
     * escapes - which produces a file that parses, draws the wrong thing, and takes an hour to
     * diagnose.
     */
    @Test
    void awkwardTextIsEscaped() {
        assertEquals("\"a \\\"quoted\\\" word\"", DotExport.quote("a \"quoted\" word"));
        assertEquals("\"back\\\\slash\"", DotExport.quote("back\\slash"));
        assertEquals("\"two\\nlines\"", DotExport.quote("two\nlines"));
        assertEquals("\"\"", DotExport.quote(null));

        CanvasNode awkward = new CanvasNode(NS + "X", NodeKind.CLASS, "say \"hello\"");
        String dot = DotExport.of(board(Arrays.asList(awkward), new ArrayList<CanvasEdge>()), "o");
        assertEquals(countOf(dot, "\\\""), 2, "both quotes escaped, in: " + dot);
    }

    /** Each node kind gets a shape, and no kind falls through to nothing. */
    @Test
    void everyNodeKindHasAShape() {
        List<CanvasNode> all = new ArrayList<CanvasNode>();
        for (NodeKind kind : NodeKind.values()) {
            all.add(new CanvasNode(NS + kind.name(), kind, kind.name()));
        }
        String dot = DotExport.of(board(all, new ArrayList<CanvasEdge>()), "o");

        assertEquals(NodeKind.values().length, countOf(dot, "shape="),
                "a kind with no shape would render as a default box and lose its meaning");
    }

    /** Every edge kind gets a style, for the same reason. */
    @Test
    void everyEdgeKindHasAStyle() {
        CanvasNode a = cls("A");
        CanvasNode b = cls("B");
        List<CanvasEdge> edges = new ArrayList<CanvasEdge>();
        int n = 0;
        for (CanvasEdge.Kind kind : CanvasEdge.Kind.values()) {
            edges.add(new CanvasEdge("e" + n++, a.getId(), b.getId(), kind.name(), kind));
        }
        String dot = DotExport.of(board(Arrays.asList(a, b), edges), "o");

        assertEquals(CanvasEdge.Kind.values().length, countOf(dot, "->"), dot);
    }

    /** An empty board is a valid, empty graph rather than a broken file. */
    @Test
    void anEmptyBoardIsStillValid() {
        String dot = DotExport.of(board(new ArrayList<CanvasNode>(), new ArrayList<CanvasEdge>()),
                null);

        assertTrue(dot.contains("digraph \"board\" {"), dot);
        assertEquals(countOf(dot, "{"), countOf(dot, "}"));
    }

    /** No projection at all does not throw - the board may not have rendered yet. */
    @Test
    void noProjectionIsNotAnError() {
        String dot = DotExport.of(null, "o");

        assertTrue(dot.contains("digraph"), dot);
        assertEquals(countOf(dot, "{"), countOf(dot, "}"));
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
