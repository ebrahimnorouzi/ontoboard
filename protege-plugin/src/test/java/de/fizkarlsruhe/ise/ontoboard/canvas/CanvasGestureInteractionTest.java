package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.mxgraph.model.mxGeometry;
import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.view.mxCellState;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collections;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The gestures a reader performs, driven through the real component.
 *
 * <p><b>Why this exists, and why the tests beside it were not enough.</b> 1.123.0 shipped two
 * regressions that {@link CanvasPanTest} could not have caught, because that one checks
 * arithmetic and these were about which events reached which handler:
 *
 * <ul>
 *   <li>The panning handler recorded every press and consumed any release that had travelled, so
 *       it ate the release of a NODE DRAG and of a RUBBERBAND - and
 *       {@code mxGraphHandler.mouseReleased}, which applies both, is guarded by
 *       {@code !isConsumed}. A dragged node snapped back; a dragged selection never took.</li>
 *   <li>The board was pushed into positive space with {@code mxGraphControl.setTranslate}, which
 *       moves the pixels and nothing else. Measured: with the control translated by (340,540), a
 *       cell painted at (520,670) was still found by {@code getCellAt} at (180,130) and not at
 *       all where it was drawn. Every click and drag landed at the wrong place on any board with
 *       content above the origin.</li>
 * </ul>
 *
 * <p>Both were reported by the maintainer as "select is not working" and "I cannot move the
 * items", after the release. One test that presses, drags and releases would have caught either.
 *
 * <p><b>Why it skips.</b> {@code mxGraphHandler} constructs a {@code java.awt.dnd.DragSource},
 * which throws {@code HeadlessException}, so the component cannot be built without a display.
 * The suite runs headless, so this skips there; the build runs it again under a virtual display,
 * which is the only way it covers anything in CI.
 */
class CanvasGestureInteractionTest {

    private static final String A = "http://example.org/gesture#A";
    private static final String B = "http://example.org/gesture#B";

    private SchemaGraph graph;
    private CollaborativeGraphComponent component;
    private JFrame frame;

    @BeforeEach
    void board() {
        assumeFalse(GraphicsEnvironment.isHeadless(),
                "the canvas component needs a display: mxGraphHandler builds a DragSource");

        graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        place(layout, A, 100, 100);
        place(layout, B, 400, 300);
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(A, NodeKind.CLASS, "A"),
                new CanvasNode(B, NodeKind.CLASS, "B")),
                Collections.<CanvasEdge>emptyList()), layout);

        component = new CollaborativeGraphComponent(graph);
        component.setGestureMode(CanvasGesture.Mode.PAN);
        frame = new JFrame();
        frame.getContentPane().add(component);
        frame.setSize(820, 620);
        // Off the screen: this is a test, not a demonstration, and it must not steal focus.
        frame.setLocation(-4000, -4000);
        frame.setVisible(true);
        component.setBounds(0, 0, 800, 600);
        frame.validate();
        installSelectionAsTheViewDoes();
        room();
    }

    @AfterEach
    void close() {
        if (frame != null) {
            frame.dispose();
        }
    }

    /** Dragging a term moves it. The release must reach the drag handler. */
    @Test
    void draggingANodeMovesIt() {
        mxGeometry before = graph.getModel().getGeometry(graph.getCellForId(A));
        Point on = centreOf(A);

        drag(on.x, on.y, on.x + 120, on.y + 60, 0);

        mxGeometry after = graph.getModel().getGeometry(graph.getCellForId(A));
        assertNotEquals(before.getX(), after.getX(), "the node did not move: " + after);
        assertEquals(before.getX() + 120, after.getX(), 1.0, "and it moved by what was dragged");
        assertEquals(before.getY() + 60, after.getY(), 1.0, "" + after);
    }

    /** Clicking a term selects it. */
    @Test
    void clickingANodeSelectsIt() {
        graph.clearSelection();
        Point on = centreOf(B);

        click(on.x, on.y);

        assertEquals(B, graph.getIdForCell(graph.getSelectionCell()));
    }

    /** Shift-drag is the additive marquee, and its release must reach the drag handler too. */
    @Test
    void shiftDraggingSelectsARegion() {
        graph.clearSelection();
        Point on = centreOf(B);

        drag(on.x - 140, on.y - 120, on.x + 140, on.y + 120, MouseEvent.SHIFT_DOWN_MASK);

        assertTrue(graph.getSelectionCells().length > 0, "the marquee selected nothing");
    }

    /** A plain drag on empty board moves the view. */
    @Test
    void draggingEmptyBoardPans() {
        component.getViewport().setViewPosition(new Point(200, 200));
        Point before = component.getViewport().getViewPosition();

        drag(700, 500, 600, 420, 0);

        assertNotEquals(before, component.getViewport().getViewPosition(), "the board did not pan");
    }

    /**
     * A node dragged above the origin does not slide the rest of the board.
     *
     * <p>The reported symptom was a drift. The shift that brings negative content back into reach
     * grows when a node goes further up, and without compensating the viewport the whole drawing
     * moves under the reader.
     */
    @Test
    void theBoardDoesNotDriftWhenTheShiftGrows() {
        component.getViewport().setViewPosition(new Point(0, 0));
        room();
        Point before = onScreen(B);

        graph.getModel().setGeometry(graph.getCellForId(A), new mxGeometry(-300, -500, 160, 60));
        room();

        Point after = onScreen(B);
        assertEquals(before.x, after.x, 1, "the board slid sideways: " + before + " -> " + after);
        assertEquals(before.y, after.y, 1, "the board slid up or down: " + before + " -> " + after);
    }

    /** And after that shift, a click still lands on the term it is over. */
    @Test
    void hitTestingFollowsTheShift() {
        graph.getModel().setGeometry(graph.getCellForId(A), new mxGeometry(-300, -500, 160, 60));
        room();
        graph.clearSelection();

        Point on = centreOf(A);
        click(on.x, on.y);

        assertEquals(A, graph.getIdForCell(graph.getSelectionCell()),
                "a click where the node is drawn must select that node - with the control's "
                        + "translate instead of the view's, this found nothing at all");
    }

    // ---------- the view's own wiring ----------

    private void room() {
        mxRectangle content = graph.getView().getGraphBounds();
        java.awt.Rectangle visible = component.getViewport().getViewRect();
        double scale = graph.getView().getScale();
        mxPoint was = graph.getView().getTranslate();
        Point shift = CanvasPan.shiftFor(content == null ? 0 : content.getX(),
                content == null ? 0 : content.getY());
        if (shift.x != 0 || shift.y != 0) {
            graph.getView().setTranslate(new mxPoint(
                    was.getX() + shift.x / scale, was.getY() + shift.y / scale));
            Point where = component.getViewport().getViewPosition();
            component.getViewport().setViewPosition(new Point(
                    Math.max(0, where.x + shift.x), Math.max(0, where.y + shift.y)));
        }
        Dimension wanted = CanvasPan.room(
                (content == null ? 0 : content.getX() + content.getWidth()) + shift.x,
                (content == null ? 0 : content.getY() + content.getHeight()) + shift.y,
                visible.width, visible.height, scale);
        if (wanted.width > 0) {
            graph.setMinimumGraphSize(new mxRectangle(0, 0, wanted.width, wanted.height));
            component.getGraphControl().updatePreferredSize();
        }
    }

    private void installSelectionAsTheViewDoes() {
        graph.getSelectionModel().setSingleSelection(false);
        new com.mxgraph.swing.handler.mxRubberband(component) {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)
                        && CanvasGesture.leftDragSelects(component.getGestureMode(),
                                component.isSpaceHeld(),
                                event.isShiftDown() || event.isControlDown(),
                                component.getCellAt(event.getX(), event.getY()) != null)) {
                    super.mousePressed(event);
                }
            }
        };
    }

    // ---------- plumbing ----------

    private static void place(CanvasLayout layout, String id, int x, int y) {
        CanvasLayout.NodeLayout at = new CanvasLayout.NodeLayout();
        at.x = x;
        at.y = y;
        layout.nodes.put(id, at);
        layout.onCanvas.add(id);
    }

    private Point centreOf(String id) {
        mxCellState state = graph.getView().getState(graph.getCellForId(id));
        return new Point((int) state.getCenterX(), (int) state.getCenterY());
    }

    private Point onScreen(String id) {
        mxCellState state = graph.getView().getState(graph.getCellForId(id));
        Point view = component.getViewport().getViewPosition();
        return new Point((int) state.getX() - view.x, (int) state.getY() - view.y);
    }

    private void click(int x, int y) {
        send(MouseEvent.MOUSE_PRESSED, x, y, 0);
        send(MouseEvent.MOUSE_RELEASED, x, y, 0);
        send(MouseEvent.MOUSE_CLICKED, x, y, 0);
    }

    private void drag(int fromX, int fromY, int toX, int toY, int modifiers) {
        send(MouseEvent.MOUSE_PRESSED, fromX, fromY, modifiers);
        for (int step = 1; step <= 10; step++) {
            send(MouseEvent.MOUSE_DRAGGED, fromX + (toX - fromX) * step / 10,
                    fromY + (toY - fromY) * step / 10, modifiers);
        }
        send(MouseEvent.MOUSE_RELEASED, toX, toY, modifiers);
    }

    private void send(int id, int x, int y, int modifiers) {
        java.awt.Component control = component.getGraphControl();
        MouseEvent event = new MouseEvent(control, id, System.currentTimeMillis(), modifiers,
                x, y, 1, false, MouseEvent.BUTTON1);
        if (id == MouseEvent.MOUSE_DRAGGED) {
            for (java.awt.event.MouseMotionListener listener
                    : control.getMouseMotionListeners()) {
                listener.mouseDragged(event);
            }
            return;
        }
        for (java.awt.event.MouseListener listener : control.getMouseListeners()) {
            if (id == MouseEvent.MOUSE_PRESSED) {
                listener.mousePressed(event);
            } else if (id == MouseEvent.MOUSE_RELEASED) {
                listener.mouseReleased(event);
            } else {
                listener.mouseClicked(event);
            }
        }
    }
}
