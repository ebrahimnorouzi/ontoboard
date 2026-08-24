package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionBridgeTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private SchemaGraph graph;
    private List<String> outbound;
    private SelectionBridge bridge;

    @BeforeEach
    void setUp() {
        graph = new SchemaGraph();
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.emptyList()), new CanvasLayout());

        outbound = new ArrayList<>();
        bridge = new SelectionBridge(graph, outbound::add);
        bridge.install();
    }

    @Test
    void selectingACellReportsItsIriOutward() {
        graph.setSelectionCell(graph.getCellForId(PERSON));
        assertEquals(Collections.singletonList(PERSON), outbound);
    }

    @Test
    void selectOnCanvasHighlightsTheCellWithoutReportingBack() {
        bridge.selectOnCanvas(AGENT);
        assertEquals(AGENT, bridge.currentCanvasSelection());
        assertEquals(Collections.emptyList(), outbound,
                "inbound selection must not echo back and cause a feedback loop");
    }

    @Test
    void selectingAnUnknownIriClearsTheSelection() {
        bridge.selectOnCanvas(PERSON);
        bridge.selectOnCanvas("http://example.org/tiny#Absent");
        assertNull(bridge.currentCanvasSelection());
    }

    /**
     * {@link SchemaGraph#render} clears and recreates every cell, so a live selection does
     * not survive a re-render even though the same IRI is still on the canvas afterwards.
     * The view's {@code refresh()} calls {@code resyncAfterRender} with whatever was
     * selected beforehand precisely to paper over that; here it must restore the selection
     * through the same suppressed path as {@link #selectOnCanvas}, so Protege - which never
     * lost track of the same entity - is not notified again for a selection that, from its
     * point of view, never changed.
     */
    @Test
    void resyncAfterRenderRestoresASurvivingSelectionWithoutReportingBack() {
        graph.setSelectionCell(graph.getCellForId(PERSON));
        String selectedBeforeRender = bridge.currentCanvasSelection();
        List<String> outboundBeforeRender = new ArrayList<>(outbound);

        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.emptyList()), new CanvasLayout());
        assertNull(bridge.currentCanvasSelection(),
                "render() recreates every cell, so the old selection object must be gone");

        bridge.resyncAfterRender(selectedBeforeRender);

        assertEquals(PERSON, bridge.currentCanvasSelection());
        assertEquals(outboundBeforeRender, outbound,
                "restoring a surviving selection after render must not notify Protege again");
    }

    /**
     * When the previously-selected IRI is no longer on the canvas after a render (removed
     * from the diagram, or dropped from the ontology), the loss is real: the canvas and
     * Protege's selection would otherwise disagree forever, since Protege still thinks that
     * entity is selected. So, unlike the ordinary "unknown IRI" case in
     * {@link #selectOnCanvas}, this is reported outward with {@code null} so the caller can
     * clear Protege's selection too and the two models end up agreeing again.
     */
    @Test
    void resyncAfterRenderClearsAndReportsWhenTheSelectionFallsOffTheCanvas() {
        graph.setSelectionCell(graph.getCellForId(PERSON));
        String selectedBeforeRender = bridge.currentCanvasSelection();

        graph.render(new Projection(Collections.singletonList(
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.emptyList()), new CanvasLayout());

        bridge.resyncAfterRender(selectedBeforeRender);

        assertNull(bridge.currentCanvasSelection());
        assertEquals(Arrays.asList(PERSON, null), outbound,
                "the selected entity fell off the canvas, so Protege must be told to clear its selection");
    }
}
