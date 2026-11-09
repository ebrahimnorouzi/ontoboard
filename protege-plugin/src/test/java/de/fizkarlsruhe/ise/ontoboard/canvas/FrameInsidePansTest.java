package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import org.junit.jupiter.api.Test;

/**
 * A press on the empty inside of a frame pans the board; a frame is taken by its title.
 *
 * <p>Found by a code review before a user study: a pattern's frame fills the window after the import,
 * the guide then says "drag the board to move it", and the drag took the frame and every class in it
 * - which also counted as arranging by hand, so the board stopped arranging itself.
 */
class FrameInsidePansTest {

    private static MouseEvent press(CollaborativeGraphComponent component, int x, int y) {
        return new MouseEvent(component.getGraphControl(), MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                MouseEvent.BUTTON1);
    }

    @Test
    void theInsidePansTheTitleAndTheBoxesDoNot() {
        SchemaGraph graph = new SchemaGraph();
        Object frame;
        Object box;
        graph.getModel().beginUpdate();
        try {
            frame = graph.insertVertex(graph.getDefaultParent(), SchemaGraph.FRAME_ID_PREFIX + "1",
                    "A pattern", 60, 60, 1800, 680, SchemaStyles.FRAME);
            box = graph.insertVertex(graph.getDefaultParent(), "http://example.org/a", "a", 100, 140,
                    160, 40, SchemaStyles.CLASS);
        } finally {
            graph.getModel().endUpdate();
        }
        CollaborativeGraphComponent component = new CollaborativeGraphComponent(graph);
        component.setSize(1024, 768);
        component.getGraphControl().setSize(4000, 4000);
        graph.getView().revalidate();

        assertSame(frame, component.getCellAt(900, 500), "the frame is what is under the press");
        assertTrue(component.isPanningEvent(press(component, 900, 500)), "inside the frame pans");
        assertFalse(component.isOnSomethingToDrag(press(component, 900, 500)));

        assertSame(box, component.getCellAt(150, 160));
        assertFalse(component.isPanningEvent(press(component, 150, 160)), "a box is dragged");
        assertTrue(component.isOnSomethingToDrag(press(component, 150, 160)));

        assertFalse(component.isPanningEvent(press(component, 900, 75)), "the title takes the frame");
        assertTrue(component.isOnSomethingToDrag(press(component, 900, 75)));

        assertTrue(component.isPanningEvent(press(component, 1950, 900)), "empty board pans");
    }
}
