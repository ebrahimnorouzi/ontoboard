package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxIGraphModel;
import com.mxgraph.swing.handler.mxGraphHandler;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxGraph;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The drag handler, with alignment guides.
 *
 * <p>Everything about moving cells is the library's. What this adds is the answer to "what am I
 * lined up with", worked out each time the preview moves and handed to {@link GuideLayer} to
 * draw.
 *
 * <p><b>Why the preview and not the mouse.</b> The preview is where the node will land, grid snap
 * already applied - {@code getPreviewLocation} calls {@code mxGraph.snap} unless Alt is held - so
 * guides computed from it agree with where the node ends up. Computing them from the pointer
 * would light a guide up while the node was still a few pixels away from it.
 *
 * <p><b>The arithmetic, and why it is not just {@code previewBounds}.</b> {@code previewBounds}
 * is positioned from {@code bbox}, the bounding box <em>including</em> label overhang and stroke
 * width, while the rectangles it has to be compared against are cell states. The two differ by a
 * pixel or two, which is most of a four-pixel tolerance. {@code getPreviewLocation} builds its
 * result as the snapped cell bounds plus {@code (bbox - cellBounds)}, so subtracting that offset
 * back off recovers the cell rectangle exactly, in the same coordinates
 * {@code mxCellState.getRectangle} reports.
 */
public class GuideGraphHandler extends mxGraphHandler {

    /**
     * Only nodes you can see are candidates.
     *
     * <p>Two reasons, and the second is the real one. A board of several hundred terms would
     * otherwise be scanned on every mouse event of every drag. And a guide whose other end is
     * off screen is a line to nowhere: it says "aligned with something" and leaves the user to
     * scroll about looking for what.
     */
    private static final int OFF_SCREEN_MARGIN = 40;

    private List<AlignmentGuides.Guide> guides = Collections.emptyList();

    public GuideGraphHandler(mxGraphComponent graphComponent) {
        super(graphComponent);
    }

    /** The guides to draw right now, which is almost always none. */
    public List<AlignmentGuides.Guide> getGuides() {
        return guides;
    }

    @Override
    public void setPreviewBounds(Rectangle bounds) {
        super.setPreviewBounds(bounds);
        show(bounds == null ? Collections.<AlignmentGuides.Guide>emptyList() : guidesFor(bounds));
    }

    @Override
    public void setVisible(boolean value) {
        super.setVisible(value);
        if (!value) {
            show(Collections.<AlignmentGuides.Guide>emptyList());
        }
    }

    @Override
    public void reset() {
        super.reset();
        show(Collections.<AlignmentGuides.Guide>emptyList());
    }

    /**
     * The cursor over empty board says which gesture mode the canvas is in.
     *
     * <p>Over a cell the library's answer stands - a movable cell gets the move cursor, a
     * collapsible one the fold cursor - and only the null it returns for empty board is replaced.
     *
     * <p>Worth noting what this costs, because it is not free: {@code mouseMoved} consumes the
     * event whenever a handler supplies a cursor, so empty board now consumes where it did not.
     * That is the same thing that already happens over every node on the board, which is where
     * anything downstream would care, so the change in behaviour is to the part of the canvas
     * where nothing is listening.
     */
    @Override
    protected java.awt.Cursor getCursor(java.awt.event.MouseEvent event) {
        java.awt.Cursor fromLibrary = super.getCursor(event);
        if (fromLibrary != null || !(graphComponent instanceof CollaborativeGraphComponent)) {
            return fromLibrary;
        }
        CollaborativeGraphComponent board = (CollaborativeGraphComponent) graphComponent;
        if (board.isSpaceHeld()) {
            return MOVE_CURSOR;
        }
        return java.awt.Cursor.getPredefinedCursor(
                board.getGestureMode() == CanvasGesture.Mode.PAN
                        ? java.awt.Cursor.HAND_CURSOR
                        : java.awt.Cursor.CROSSHAIR_CURSOR);
    }

    /**
     * Replaces the guides and repaints only what changed.
     *
     * <p>Thin strips rather than the whole control. A guide spans the nodes it joins, which on a
     * tall board is most of the viewport, and this runs on every mouse event of a drag; repainting
     * the board each time would make dragging a node stutter on exactly the large diagrams where
     * alignment matters most.
     */
    private void show(List<AlignmentGuides.Guide> next) {
        if (sameLines(guides, next)) {
            return;
        }
        Rectangle before = GuideLayer.areaOf(guides);
        guides = next;
        Rectangle after = GuideLayer.areaOf(next);
        Rectangle dirty = before == null ? after : (after == null ? before : before.union(after));
        if (dirty != null && graphComponent != null) {
            graphComponent.getGraphControl().repaint(dirty);
        }
    }

    /** The guides for a preview at these bounds, in cell-state coordinates. */
    private List<AlignmentGuides.Guide> guidesFor(Rectangle preview) {
        if (cellBounds == null || bbox == null || graphComponent == null) {
            return Collections.emptyList();
        }
        Rectangle moving = new Rectangle(
                (int) Math.round(preview.x - (bbox.getX() - cellBounds.getX())),
                (int) Math.round(preview.y - (bbox.getY() - cellBounds.getY())),
                (int) Math.round(cellBounds.getWidth()),
                (int) Math.round(cellBounds.getHeight()));
        return AlignmentGuides.forMove(moving, stationaryRectangles());
    }

    /** Every visible vertex that is not one of the cells being dragged. */
    private List<Rectangle> stationaryRectangles() {
        List<Rectangle> rectangles = new ArrayList<Rectangle>();
        mxGraph graph = graphComponent.getGraph();
        if (graph == null) {
            return rectangles;
        }
        Set<Object> moving = new HashSet<Object>();
        if (cells != null) {
            moving.addAll(Arrays.asList(cells));
        }
        Rectangle visible = graphComponent.getViewport() == null
                ? null
                : graphComponent.getViewport().getViewRect();
        if (visible != null) {
            visible = new Rectangle(visible.x - OFF_SCREEN_MARGIN, visible.y - OFF_SCREEN_MARGIN,
                    visible.width + 2 * OFF_SCREEN_MARGIN,
                    visible.height + 2 * OFF_SCREEN_MARGIN);
        }

        mxIGraphModel model = graph.getModel();
        Object parent = graph.getDefaultParent();
        int children = model.getChildCount(parent);
        for (int index = 0; index < children; index++) {
            Object child = model.getChildAt(parent, index);
            if (!model.isVertex(child) || moving.contains(child)) {
                continue;
            }
            mxCellState state = graph.getView().getState(child);
            if (state == null) {
                continue;
            }
            Rectangle rectangle = state.getRectangle();
            if (visible == null || visible.intersects(rectangle)) {
                rectangles.add(rectangle);
            }
        }
        return rectangles;
    }

    /** Whether two guide lists would draw the same thing, so an unchanged drag repaints nothing. */
    private static boolean sameLines(List<AlignmentGuides.Guide> left,
            List<AlignmentGuides.Guide> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            AlignmentGuides.Guide a = left.get(index);
            AlignmentGuides.Guide b = right.get(index);
            if (a.getMatch() != b.getMatch() || a.getPosition() != b.getPosition()
                    || a.getStart() != b.getStart() || a.getEnd() != b.getEnd()) {
                return false;
            }
        }
        return true;
    }
}
