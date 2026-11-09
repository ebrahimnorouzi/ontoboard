package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.canvas.mxGraphics2DCanvas;
import com.mxgraph.shape.mxRectangleShape;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxCellState;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

/**
 * A defined class's box: the class's own rectangle with a badge on its left, a disc in the box's
 * colour holding three white bars - the mark Protégé's class tree gives a defined class.
 *
 * <p>The label also starts with {@code ≡}, and that stays: search, tooltips and exports read it. But
 * in the board's font at 13 px the character is three faint bars no taller than an equals sign, and a
 * tester read a defined {@code ≡ publication} as {@code = publication} at 100% on an ordinary screen -
 * measured the same in Helvetica Neue, Segoe UI and Dialog. Bars drawn as strokes read at any zoom.
 *
 * <p>A shape rather than an overlay like {@link NoteBadgeLayer}: Export image draws shapes, so the
 * badge is in a PNG too. It sits inside the box, in the padding the label is centred in - every box
 * keeps at least 21 px either side of its text - so it never covers a word.
 */
final class DefinedClassShape extends mxRectangleShape {

    /** The name a style gives the shape by. */
    static final String NAME = "obDefinedClass";

    /** The badge's diameter at 100%. */
    static final double SIZE = 13;

    /** From the box's left edge to the badge, at 100%. */
    static final double INSET = 5;

    @Override
    public void paintShape(mxGraphics2DCanvas canvas, mxCellState state) {
        super.paintShape(canvas, state);
        Graphics2D graphics = canvas.getGraphics();
        if (graphics == null) {
            return;
        }
        double scale = canvas.getScale();
        double size = SIZE * scale;
        double x = state.getX() + INSET * scale;
        double y = state.getY() + (state.getHeight() - size) / 2;
        Color disc = mxUtils.getColor(state.getStyle(), mxConstants.STYLE_STROKECOLOR,
                new Color(0x2D, 0x6F, 0xBF));
        Graphics2D badge = (Graphics2D) graphics.create();
        try {
            badge.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            badge.setColor(disc);
            badge.fill(new Ellipse2D.Double(x, y, size, size));
            badge.setColor(Color.WHITE);
            badge.setStroke(new BasicStroke((float) Math.max(1, 1.4 * scale)));
            double left = x + size * 0.27;
            double right = x + size * 0.73;
            for (double row : new double[] {0.32, 0.5, 0.68}) {
                badge.draw(new Line2D.Double(left, y + size * row, right, y + size * row));
            }
        } finally {
            badge.dispose();
        }
    }
}
