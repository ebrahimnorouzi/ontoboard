package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.canvas.mxSvgCanvas;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxUtils;
import com.mxgraph.view.mxCellState;
import com.mxgraph.view.mxGraph;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * JGraphX's SVG canvas, with a class box's rows (1.155.0): the rule under its name and each row,
 * placed where {@link RowsShape} paints them on the board and in a PNG - as text, which a reader
 * of the SVG can select and search. JGraphX's own canvas draws a shape it does not know as a
 * rectangle, and knows nothing of rows.
 */
class RowsSvgCanvas extends mxSvgCanvas {

    /** The class the rule's path carries in the SVG. */
    static final String RULE = "ob-rows-rule";

    RowsSvgCanvas(Document document) {
        super(document);
    }

    @Override
    public Object drawCell(mxCellState state) {
        Object drawn = super.drawCell(state);
        mxGraph graph = state.getView().getGraph();
        if (!(graph instanceof SchemaGraph) || document == null) {
            return drawn;
        }
        List<String> rows = ((SchemaGraph) graph).rowsOf(state.getCell());
        if (rows.isEmpty()) {
            return drawn;
        }
        double scale = getScale();
        double rule = RowsShape.ruleAt(state, scale);
        if (rule >= state.getY() + state.getHeight()) {
            return drawn;
        }
        mxPoint at = getTranslate();
        Map<String, Object> style = state.getStyle();
        Element group = document.createElement("g");
        float opacity = mxUtils.getFloat(style, mxConstants.STYLE_OPACITY, 100);
        if (opacity < 100) {
            group.setAttribute("opacity", String.valueOf(opacity / 100));
        }
        Element line = document.createElement("path");
        line.setAttribute("class", RULE);
        line.setAttribute("d", "M " + (state.getX() + at.getX()) + " " + (rule + at.getY())
                + " L " + (state.getX() + state.getWidth() + at.getX()) + " " + (rule + at.getY()));
        line.setAttribute("stroke", mxUtils.getString(style, mxConstants.STYLE_STROKECOLOR, "#808080"));
        line.setAttribute("stroke-width", String.valueOf(Math.max(1, scale)));
        line.setAttribute("fill", "none");
        group.appendChild(line);

        // The board's font for the name, plain, as RowsShape paints the rows.
        Font font = mxUtils.getFont(style, scale).deriveFont(Font.PLAIN);
        FontMetrics metrics = mxUtils.getFontMetrics(font);
        String family = mxUtils.getString(style, mxConstants.STYLE_FONTFAMILY,
                mxConstants.DEFAULT_FONTFAMILIES);
        String ink = mxUtils.getString(style, mxConstants.STYLE_FONTCOLOR, "#000000");
        for (RowsShape.Placed row : RowsShape.place(rows, state, scale, rule, metrics)) {
            Element text = document.createElement("text");
            text.setAttribute("x", String.valueOf(row.x + at.getX()));
            text.setAttribute("y", String.valueOf(row.baseline + at.getY()));
            text.setAttribute("font-family", family);
            text.setAttribute("font-size", String.valueOf(font.getSize()));
            text.setAttribute("fill", ink);
            text.appendChild(document.createTextNode(row.text));
            group.appendChild(text);
        }
        appendSvgElement(group);
        return drawn;
    }
}
