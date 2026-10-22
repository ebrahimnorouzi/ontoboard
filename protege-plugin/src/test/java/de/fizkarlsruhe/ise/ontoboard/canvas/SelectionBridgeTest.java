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
    // ---------- a property is several arrows, and a click is not always the first cell ----------

    private static final String KNOWS = "http://example.org/tiny#knows";

    /** Person -knows-> Agent and Agent -knows-> Person: one property, two arrows. */
    private void renderTwoArrowsForOneProperty() {
        String one = de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.of(
                de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Origin.SUBCLASS,
                "some", PERSON, KNOWS, AGENT);
        String two = de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.of(
                de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Origin.SUBCLASS,
                "some", AGENT, KNOWS, PERSON);
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Arrays.asList(
                        new de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge(one, PERSON, AGENT,
                                "knows",
                                de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge.Kind
                                        .OBJECT_PROPERTY),
                        new de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge(two, AGENT, PERSON,
                                "knows",
                                de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge.Kind
                                        .OBJECT_PROPERTY))),
                new CanvasLayout());
    }

    /**
     * Selecting a property from Protege's tree highlights every arrow it owns.
     *
     * <p>Not one of them: which arrow a first-wins index would pick depends on the order the
     * projection emitted its axioms, so the board would jump to an arbitrary one - measured 400px
     * from the one the reader was looking at - and not to the same one twice.
     */
    @Test
    void selectingAPropertyHighlightsEveryArrowItOwns() {
        renderTwoArrowsForOneProperty();

        bridge.selectOnCanvas(KNOWS);

        assertEquals(2, graph.getSelectionCells().length,
                "one property, two arrows, both of them it");
        assertEquals(Collections.emptyList(), outbound, "still an inbound selection");
    }

    /**
     * Clicking an arrow and being told about the property it stands for is not a change.
     *
     * <p>This is the echo that used to deselect the arrow the reader had just clicked: the canvas
     * tells Protege the property, Protege tells the canvas the property, and the canvas looked for
     * a cell whose id <em>is</em> that IRI. An arrow's id is {@code pe|...|property|...}, so it
     * found none and cleared the selection.
     */
    @Test
    void beingToldThePropertyOfTheArrowAlreadySelectedChangesNothing() {
        renderTwoArrowsForOneProperty();
        String arrow = de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.of(
                de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId.Origin.SUBCLASS,
                "some", PERSON, KNOWS, AGENT);
        graph.setSelectionCell(graph.getCellForId(arrow));
        assertEquals(Collections.singletonList(arrow), outbound,
                "the bridge reports the cell id; it is the view's entityToSelect that reads the "
                        + "property out of it, which is why the IRI that comes back in does not "
                        + "match any cell's id and why this case needed fixing at all");

        bridge.selectOnCanvas(KNOWS);

        assertEquals(arrow, bridge.currentCanvasSelection(),
                "the arrow the reader clicked must still be the selected one");
        assertEquals(1, graph.getSelectionCells().length,
                "and the other arrow for the same property must not have joined it");
    }

    /**
     * After a Ctrl-click, Protege is told about the term just clicked - not the first one.
     *
     * <p>{@code mxGraphSelectionModel.getSelectionCell} is {@code cells.get(0)}, and this canvas
     * turns single selection off, so the canvas used to report whichever term was selected
     * <em>first</em>. Measured: click Person, Ctrl-click Agent, and Protege was told Person. That
     * is the reported defect - clicking a class does not take Protege to that class - on a gesture
     * that needs no rubberband to reach.
     */
    @Test
    void ctrlClickingASecondTermReportsTheSecondOne() {
        graph.setSelectionCell(graph.getCellForId(PERSON));
        graph.addSelectionCell(graph.getCellForId(AGENT));

        assertEquals(Arrays.asList(PERSON, AGENT), outbound,
                "the second click must report Agent, not Person again");
    }

    /** A marquee over several terms has no single answer, so Protege is left where it was. */
    @Test
    void selectingSeveralTermsAtOnceReportsNothing() {
        graph.setSelectionCells(new Object[] {
            graph.getCellForId(PERSON), graph.getCellForId(AGENT)});

        assertEquals(Collections.emptyList(), outbound,
                "Protege holds one entity; an arbitrary member of a group is worse than leaving "
                        + "the panel where the reader put it");
    }

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
