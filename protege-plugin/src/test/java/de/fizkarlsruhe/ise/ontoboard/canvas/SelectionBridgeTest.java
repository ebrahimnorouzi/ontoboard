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
}
