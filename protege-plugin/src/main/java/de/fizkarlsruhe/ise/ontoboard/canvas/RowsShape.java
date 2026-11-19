package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.canvas.mxGraphics2DCanvas;
import com.mxgraph.shape.mxRectangleShape;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxCellState;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.util.List;
import java.util.Map;

/**
 * A class's box drawn UML-like (1.155.0): its name at the top, a rule, and below it one row for
 * each of its data properties, {@code name : datatype [n..m]}, and its keys - left-aligned, as a
 * UML class's attributes are. The name is the cell's label, painted at the top by the board's text
 * painter; the rows come from {@link SchemaGraph#rowsOf}, so search, tooltips and collaboration,
 * which read a box's label, read its name and nothing more.
 *
 * <p>A shape, as {@link DefinedClassShape} is, because Export image draws shapes: the rows are in
 * a PNG too. A box too short for all its rows - one sized by hand - ends with how many more there
 * are, and a row too long for the box ends with an ellipsis: nothing is painted outside the box.
 */
class RowsShape extends mxRectangleShape {

    /** The name a style gives the shape by. */
    static final String NAME = "obRows";

    /** The same for a defined class, whose box carries the badge as well. */
    static final String DEFINED = "obDefinedRows";

    /** From the box's left edge to a row, and from its right edge to the longest, at 100%. */
    static final double INSET = 8;

    /** Between the name and the rule, and the rule and the first row, at 100%. */
    static final double GAP = 4;

    /** Below the last row, at 100%. */
    static final double BOTTOM = 6;

    /**
     * A box's height at 100% for its name and rows: the spacing above the name, the name's lines
     * in the inset the board draws a label in, the rule between its two gaps, the rows, and room
     * below them.
     */
    static double heightOf(double top, double name, double rows) {
        return top + 2 * mxConstants.LABEL_INSET + name + 2 * GAP + rows + BOTTOM;
    }

    @Override
    public void paintShape(mxGraphics2DCanvas canvas, mxCellState state) {
        super.paintShape(canvas, state);
        paintRows(canvas, state);
    }

    /** Where the rule under a box's name goes: under the name as the board laid it out. */
    static double ruleAt(mxCellState state, double scale) {
        mxRectangle label = state.getLabelBounds();
        if (label != null && label.getHeight() > 0) {
            return label.getY() + label.getHeight() + GAP * scale;
        }
        return state.getY() + (mxUtils.getDouble(state.getStyle(), mxConstants.STYLE_SPACING, 0)
                + 2 * mxConstants.LABEL_INSET + GAP) * scale;
    }

    /** The rule and the rows, over a box already painted. */
    static void paintRows(mxGraphics2DCanvas canvas, mxCellState state) {
        Graphics2D graphics = canvas.getGraphics();
        if (graphics == null || !(state.getView().getGraph() instanceof SchemaGraph)) {
            return;
        }
        List<String> rows = ((SchemaGraph) state.getView().getGraph()).rowsOf(state.getCell());
        if (rows.isEmpty()) {
            return;
        }
        double scale = canvas.getScale();
        double rule = ruleAt(state, scale);
        double bottom = state.getY() + state.getHeight();
        if (rule >= bottom) {
            return;
        }
        Map<String, Object> style = state.getStyle();
        Graphics2D painter = (Graphics2D) graphics.create();
        try {
            painter.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            painter.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            painter.setColor(mxUtils.getColor(style, mxConstants.STYLE_STROKECOLOR, Color.GRAY));
            painter.setStroke(new BasicStroke((float) Math.max(1, scale)));
            painter.draw(new Line2D.Double(state.getX(), rule, state.getX() + state.getWidth(), rule));

            // The name's font, plain: the name is bold, as a UML class's is, and its rows are not.
            Font font = FallbackTextShape.fontFor(style, scale).deriveFont(Font.PLAIN);
            painter.setFont(font);
            painter.setColor(mxUtils.getColor(style, mxConstants.STYLE_FONTCOLOR, Color.BLACK));
            FontMetrics metrics = painter.getFontMetrics();
            double line = metrics.getHeight() + mxConstants.LINESPACING * scale;
            double x = state.getX() + INSET * scale;
            double room = state.getWidth() - 2 * INSET * scale;
            double y = rule + GAP * scale + metrics.getAscent();
            for (int i = 0; i < rows.size() && y + metrics.getDescent() <= bottom; i++) {
                boolean last = i == rows.size() - 1;
                if (!last && y + line + metrics.getDescent() > bottom) {
                    painter.drawString(fitted("… " + (rows.size() - i) + " more", metrics, room),
                            (float) x, (float) y);
                    break;
                }
                painter.drawString(fitted(rows.get(i), metrics, room), (float) x, (float) y);
                y += line;
            }
        } finally {
            painter.dispose();
        }
    }

    /** The row as it fits the box, shortened with an ellipsis when it does not. */
    static String fitted(String row, FontMetrics metrics, double room) {
        if (metrics.stringWidth(row) <= room) {
            return row;
        }
        String shorter = row;
        while (shorter.length() > 1 && metrics.stringWidth(shorter + "…") > room) {
            shorter = shorter.substring(0, shorter.length() - 1);
        }
        return shorter + "…";
    }

    /** A defined class's box with rows: the badge as well, beside its name at the top. */
    static final class Defined extends DefinedClassShape {
        @Override
        public void paintShape(mxGraphics2DCanvas canvas, mxCellState state) {
            super.paintShape(canvas, state);
            paintRows(canvas, state);
        }

        @Override
        double badgeCentre(mxCellState state) {
            mxRectangle label = state.getLabelBounds();
            return label != null && label.getHeight() > 0 ? label.getCenterY()
                    : super.badgeCentre(state);
        }
    }
}
