package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * That the key tells the truth about the diagram.
 *
 * <p>A legend is only worth having while it agrees with what is drawn, and nothing about a
 * disagreement announces itself - a stale legend simply misinforms, quietly, forever. Two
 * properties are therefore asserted rather than maintained by hand: that every kind of node and
 * edge the canvas can draw has a row, and that the colours and dash patterns in the legend are the
 * ones the stylesheet actually uses.
 */
class CanvasLegendTest {

    // ---------- completeness ----------

    /**
     * Adding a NodeKind without a legend row fails here. That is the point: the alternative is
     * remembering, and this project already found a canvas drawing something the legend did not
     * mention - sub-properties, which are not drawn at all.
     */
    @Test
    void everyNodeKindTheCanvasCanDrawIsExplained() {
        assertTrue(CanvasLegend.nodeKindsWithoutAnEntry().isEmpty(),
                "these node kinds are drawn but not in the legend: "
                        + CanvasLegend.nodeKindsWithoutAnEntry());
    }

    @Test
    void everyEdgeKindTheCanvasCanDrawIsExplained() {
        assertTrue(CanvasLegend.edgeKindsWithoutAnEntry().isEmpty(),
                "these edge kinds are drawn but not in the legend: "
                        + CanvasLegend.edgeKindsWithoutAnEntry());
    }

    @Test
    void theLegendHasARowForEachKindAndNoInventedOnes() {
        Set<String> real = new HashSet<String>();
        for (NodeKind kind : NodeKind.values()) {
            real.add(CanvasLegend.styleFor(kind));
        }
        for (CanvasEdge.Kind kind : CanvasEdge.Kind.values()) {
            real.add(CanvasLegend.styleFor(kind));
        }

        assertEquals(real, CanvasLegend.styleNamesMentioned(),
                "the legend mentions a style the canvas never draws, or misses one it does");
    }

    // ---------- agreement with the stylesheet ----------

    /**
     * The legend duplicates colours from SchemaStyles because reading them back needs an mxGraph,
     * which a headless test cannot render with - but it can build one. So the duplication is
     * checked rather than trusted.
     */
    @Test
    void legendColoursMatchTheStylesheet() {
        mxGraph graph = new mxGraph();
        SchemaStyles.install(graph);
        mxStylesheet sheet = graph.getStylesheet();

        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            Map<String, Object> style = sheet.getStyles().get(entry.getStyleName());
            assertNotNull(style, "no such style in the stylesheet: " + entry.getStyleName());

            assertEquals(style.get(com.mxgraph.util.mxConstants.STYLE_STROKECOLOR),
                    entry.getStroke(),
                    "stroke colour drifted for " + entry.getStyleName());
            if (entry.getForm() == CanvasLegend.Form.NODE) {
                assertEquals(style.get(com.mxgraph.util.mxConstants.STYLE_FILLCOLOR),
                        entry.getFill(),
                        "fill colour drifted for " + entry.getStyleName());
            }
            assertEquals(style.get(com.mxgraph.util.mxConstants.STYLE_DASH_PATTERN),
                    entry.getDashPattern(),
                    "dash pattern drifted for " + entry.getStyleName());
            // Opacity too, since 1.61.0: the imported row's entire distinction is its opacity, so a
            // row that carried the wrong value would illustrate the wrong thing while every other
            // assertion here passed.
            Object registered = style.get(com.mxgraph.util.mxConstants.STYLE_OPACITY);
            assertEquals(registered == null ? 100 : ((Number) registered).intValue(),
                    entry.getOpacity(),
                    "opacity drifted for " + entry.getStyleName());
        }
    }

    // ---------- the rows are usable ----------

    /**
     * The imported row exists and says what it is for.
     *
     * <p>Which terms must not be edited is the most consequential thing this legend says. Somebody who
     * edits an imported term loses the change at the next refresh of the imports, or leaves a second
     * definition behind that survives into a release - and neither shows up as an error.
     */
    @Test
    void theKeyExplainsWhichTermsAreImported() {
        CanvasLegend.Entry imported = null;
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            if (SchemaStyles.IMPORTED.equals(entry.getStyleName())) {
                imported = entry;
            }
        }

        assertNotNull(imported, "no row explains the faded terms the canvas draws");
        assertEquals(CanvasLegend.Form.MODIFIER, imported.getForm(),
                "imported is a marker over a kind, not a kind of its own");
        assertTrue(imported.getOpacity() < 100, "the row has to be drawn faded to show the fade");
        assertTrue(imported.getMeaning().contains("do not edit")
                        || imported.getMeaning().contains("not edit"),
                "the row should say what the fade means for the reader: " + imported.getMeaning());
    }

    @Test
    void everyRowHasALabelAndAMeaning() {
        List<CanvasLegend.Entry> entries = CanvasLegend.entries();
        assertFalse(entries.isEmpty());

        for (CanvasLegend.Entry entry : entries) {
            assertFalse(entry.getLabel().trim().isEmpty(), entry.getStyleName());
            assertTrue(entry.getMeaning().trim().length() > 20,
                    "a one-word meaning explains nothing: " + entry.getStyleName() + " -> "
                            + entry.getMeaning());
        }
    }

    /**
     * The labels name OWL constructs, because that is what a reader is trying to look up. "Dashed
     * grey arrow" would describe the picture they are already looking at.
     */
    @Test
    void theRelationshipRowsNameTheOwlConstructs() {
        Set<String> labels = new HashSet<String>();
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            if (entry.getForm() == CanvasLegend.Form.EDGE) {
                labels.add(entry.getLabel());
            }
        }
        assertTrue(labels.contains("rdfs:subClassOf"), labels.toString());
        assertTrue(labels.contains("rdf:type"), labels.toString());
    }

    @Test
    void nodesComeBeforeRelationships() {
        List<CanvasLegend.Entry> entries = CanvasLegend.entries();
        int lastNode = -1;
        int firstEdge = entries.size();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getForm() == CanvasLegend.Form.NODE) {
                lastNode = i;
            } else if (i < firstEdge) {
                firstEdge = i;
            }
        }
        assertTrue(lastNode < firstEdge,
                "the panel renders a heading per section, so the forms must not interleave");
    }

    @Test
    void anEdgeRowHasNoFillAndANodeRowDoes() {
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            if (entry.getForm() == CanvasLegend.Form.EDGE) {
                assertEquals(null, entry.getFill(), entry.getStyleName() + " is a line");
            } else {
                assertNotNull(entry.getFill(), entry.getStyleName() + " is a shape");
            }
        }
    }

    // ================================================== the namespace section

    /**
     * The legend explains the colour channel the canvas actually relies on.
     *
     * <p>Every node is outlined in a colour derived from its namespace, and the key said nothing
     * about it - so a board with two vocabularies showed purple-outlined boxes beside a legend whose
     * only purple swatch was "Individual", and the obvious reading was wrong. A key that omits the
     * channel carrying the most information is worse than no key, because it invites a confident
     * misreading.
     */
    @Test
    void theNamespacesOnABoardAreExplainedWithTheColoursThatBoardGaveThem() {
        java.util.Map<String, String> assigned = new java.util.HashMap<String, String>();
        assigned.put("pizza", "#8E44AD");
        assigned.put("bfo", "#E67E22");

        List<CanvasLegend.Namespace> rows = CanvasLegend.namespaces(assigned);

        assertEquals(2, rows.size());
        // Sorted, so the same board produces the same key twice running - a map's iteration order
        // would not.
        assertEquals("bfo", rows.get(0).getPrefix());
        assertEquals("#E67E22", rows.get(0).getColour());
        assertEquals("pizza", rows.get(1).getPrefix());
        assertEquals("#8E44AD", rows.get(1).getColour());
    }

    /** A board with no assigned colours gets no section rather than an empty heading. */
    @Test
    void aBoardWithNoNamespaceColoursHasNoNamespaceRows() {
        assertTrue(CanvasLegend.namespaces(null).isEmpty());
        assertTrue(CanvasLegend.namespaces(
                java.util.Collections.<String, String>emptyMap()).isEmpty());
    }

    /** A half-written sidecar entry is skipped rather than drawn as a blank swatch. */
    @Test
    void incompleteNamespaceEntriesAreSkipped() {
        java.util.Map<String, String> assigned = new java.util.HashMap<String, String>();
        assigned.put("", "#8E44AD");
        assigned.put("pizza", null);
        assigned.put("bfo", "#E67E22");

        List<CanvasLegend.Namespace> rows = CanvasLegend.namespaces(assigned);

        assertEquals(1, rows.size(), rows.toString());
        assertEquals("bfo", rows.get(0).getPrefix());
    }

    /**
     * Namespaces stay out of {@code entries()}, deliberately.
     *
     * <p>{@link #legendColoursMatchTheStylesheet} requires every entry to name a style that exists in
     * the stylesheet and to carry that style's colours. A namespace colour is assigned per board and
     * applied as an inline stroke override, so it has no stylesheet entry to match - putting one in
     * that list would mean weakening the test that keeps every other row honest.
     */
    @Test
    void theNamespaceRowsAreNotStylesheetEntries() {
        for (CanvasLegend.Entry entry : CanvasLegend.entries()) {
            assertFalse(entry.getLabel().toLowerCase(java.util.Locale.ROOT).contains("namespace"),
                    "a namespace row has been added to entries(), where it cannot satisfy "
                            + "legendColoursMatchTheStylesheet: " + entry.getLabel());
        }
    }
}
