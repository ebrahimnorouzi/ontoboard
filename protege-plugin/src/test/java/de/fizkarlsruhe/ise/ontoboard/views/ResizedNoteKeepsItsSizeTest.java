package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGeometry;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxRectangle;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.BoardHistory;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayoutStore;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sticky note resized by its handles keeps the size it was given.
 *
 * <p>Found in 1.135.1: since a resize is followed like a move, widening a note recorded an undo
 * step and marked the board as arranged by hand, and the next refresh - which any edit anywhere in
 * Protege triggers - drew the note at its old size again. {@code captureInto} copied a frame's x,
 * y, w and h into the layout but only a note's x and y, so the sidecar kept the old size as well,
 * and Redo of the resize put back a note that had never been resized.
 *
 * <p>Driven the way the board is: JGraphX's {@code resizeCell}, which fires {@code CELLS_RESIZED};
 * {@link SchemaCanvasView#listenForMovesAndResizes}; a listener doing what the view's does before
 * it routes - remember the board, then copy the live geometry back with
 * {@link SchemaCanvasView#captureInto}, read from the cells as {@code capturePositions} reads it;
 * and {@link SchemaGraph#render}, which is what a refresh draws with.
 */
class ResizedNoteKeepsItsSizeTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String NOTE = SchemaGraph.NOTE_ID_PREFIX + "1";
    private static final String FRAME = SchemaGraph.FRAME_ID_PREFIX + "1";

    /** The note's own box, and the one its top-left handle drags it out to. */
    private static final double[] AS_MADE = {60, 300, 180, 120};
    private static final double[] RESIZED = {40, 280, 300, 200};

    private final CanvasLayout layout = new CanvasLayout();
    private final BoardHistory history = new BoardHistory();
    private final Projection projection = new Projection(
            Collections.singletonList(new CanvasNode(PERSON, NodeKind.CLASS, "Person")),
            Collections.<CanvasEdge>emptyList());
    private final SchemaGraph graph = new SchemaGraph();

    /** A term, a note as a new one is made - 180 by 120 - and a frame, apart, on a drawn board. */
    ResizedNoteKeepsItsSizeTest() {
        CanvasLayout.NodeLayout person = new CanvasLayout.NodeLayout(400, 40);
        person.w = 160;
        person.h = 60;
        layout.nodes.put(PERSON, person);
        CanvasLayout.NoteLayout note = new CanvasLayout.NoteLayout();
        note.id = NOTE;
        note.text = "check this with Bob";
        note.x = AS_MADE[0];
        note.y = AS_MADE[1];
        layout.notes.add(note);
        CanvasLayout.FrameLayout frame = new CanvasLayout.FrameLayout();
        frame.id = FRAME;
        frame.label = "People";
        frame.x = 400;
        frame.y = 300;
        frame.w = 340;
        frame.h = 240;
        layout.frames.add(frame);
        graph.render(projection, layout);
        SchemaCanvasView.listenForMovesAndResizes(graph, (sender, event) -> {
            history.record(mxEvent.CELLS_RESIZED.equals(event.getName())
                    ? "resizing things on the board" : "moving things on the board", layout);
            SchemaCanvasView.captureInto(layout, Collections.singleton(PERSON), id -> {
                Object cell = graph.getCellForId(id);
                if (!(cell instanceof mxCell)) {
                    return null;
                }
                mxGeometry geometry = ((mxCell) cell).getGeometry();
                return geometry == null ? null : new double[] {
                        geometry.getX(), geometry.getY(),
                        geometry.getWidth(), geometry.getHeight() };
            });
        });
    }

    /** Where a cell is drawn, as {@code {x, y, w, h}}. */
    private static double[] drawn(SchemaGraph on, String id) {
        mxGeometry at = on.getModel().getGeometry(on.getCellForId(id));
        return new double[] {at.getX(), at.getY(), at.getWidth(), at.getHeight()};
    }

    private void resizeTheNote() {
        graph.resizeCell(graph.getCellForId(NOTE),
                new mxRectangle(RESIZED[0], RESIZED[1], RESIZED[2], RESIZED[3]));
        assertEquals("resizing things on the board", history.nextUndoAction(),
                "the resize reached the board's listener");
    }

    @Test
    void aNoteResizedByItsHandlesKeepsItsSizeThroughARefresh() {
        resizeTheNote();

        graph.render(projection, layout);

        assertArrayEquals(RESIZED, drawn(graph, NOTE), "the note as it was resized");
    }

    @Test
    void aResizedNoteKeepsItsSizeThroughASaveAndReopen(@TempDir Path dir) {
        resizeTheNote();
        File ontology = dir.resolve("tiny.owl").toFile();

        CanvasLayoutStore.save(ontology, layout);
        SchemaGraph reopened = new SchemaGraph();
        reopened.render(projection, CanvasLayoutStore.load(ontology));

        assertArrayEquals(RESIZED, drawn(reopened, NOTE), "the note as it was saved");
    }

    /** What undoBoardChange and redoBoardChange do: the board put back in place, then drawn. */
    @Test
    void undoTakesTheResizeBackAndRedoPutsItBack() {
        resizeTheNote();

        layout.copyFrom(history.undo(layout).getBoard());
        graph.render(projection, layout);
        assertArrayEquals(AS_MADE, drawn(graph, NOTE), "undone");

        layout.copyFrom(history.redo(layout).getBoard());
        graph.render(projection, layout);
        assertArrayEquals(RESIZED, drawn(graph, NOTE), "redone");
    }

    @Test
    void aMovedNoteStillKeepsItsPlace() {
        graph.moveCells(new Object[] {graph.getCellForId(NOTE)}, 100, 40);

        graph.render(projection, layout);

        assertArrayEquals(new double[] {160, 340, 180, 120}, drawn(graph, NOTE),
                "the note where it was dragged, at the size it had");
    }

    @Test
    void aTermAndAFrameResizedByTheirHandlesKeepTheirSizeAsBefore() {
        graph.resizeCell(graph.getCellForId(PERSON), new mxRectangle(400, 40, 240, 80));
        graph.resizeCell(graph.getCellForId(FRAME), new mxRectangle(400, 300, 420, 260));

        graph.render(projection, layout);

        assertArrayEquals(new double[] {400, 40, 240, 80}, drawn(graph, PERSON), "the term");
        assertArrayEquals(new double[] {400, 300, 420, 260}, drawn(graph, FRAME), "the frame");
    }
}
