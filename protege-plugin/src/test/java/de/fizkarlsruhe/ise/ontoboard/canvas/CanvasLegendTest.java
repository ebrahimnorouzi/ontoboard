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
        }
    }

    // ---------- the rows are usable ----------

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
}
