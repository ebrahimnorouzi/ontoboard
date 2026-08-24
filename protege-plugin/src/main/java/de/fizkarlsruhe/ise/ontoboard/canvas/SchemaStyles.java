package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import java.util.HashMap;
import java.util.Map;

/**
 * Visual styles for canvas nodes and edges.
 *
 * <p>Legibility is the goal, not decoration. A schema diagram is read far more often than it
 * is drawn, so the choices here favour telling things apart at a glance: shape carries the
 * entity kind, stroke colour carries the namespace (see {@link PrefixColours}), and edge
 * weight and dash pattern distinguish a hierarchy link from a property link without needing
 * the label.
 */
public final class SchemaStyles {

    public static final String CLASS = "obClass";
    public static final String INDIVIDUAL = "obIndividual";
    public static final String DATATYPE = "obDatatype";
    public static final String LITERAL = "obLiteral";

    public static final String SUBCLASS = "obSubClass";
    public static final String OBJECT_PROPERTY = "obObjectProperty";
    public static final String DATA_PROPERTY = "obDataProperty";
    public static final String TYPE = "obType";

    /** Canvas background - a hair off white so white node fills read as raised. */
    public static final String CANVAS_BACKGROUND = "#F7F8FA";

    private static final String FONT = "Segoe UI, Helvetica Neue, Arial, sans-serif";
    private static final String INK = "#1A1D21";
    private static final String EDGE_INK = "#4A5560";

    private SchemaStyles() {
    }

    public static void install(mxGraph graph) {
        mxStylesheet sheet = graph.getStylesheet();

        // A class is the thing users read most, so it gets the strongest treatment:
        // rounded, shadowed, bold, and roomy enough for a two-line rdfs:label.
        sheet.putCellStyle(CLASS, vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#4A90D9",
                true, true, 13, mxConstants.FONT_BOLD));
        sheet.putCellStyle(INDIVIDUAL, vertex(mxConstants.SHAPE_RHOMBUS, "#F4F0FA", "#7B61A8",
                false, false, 12, 0));
        sheet.putCellStyle(DATATYPE, vertex(mxConstants.SHAPE_ELLIPSE, "#EEF7F1", "#3E8E5A",
                false, false, 11, 0));
        sheet.putCellStyle(LITERAL, vertex(mxConstants.SHAPE_RECTANGLE, "#FFF9E6", "#B08900",
                false, false, 11, 0));

        // SubClassOf is the backbone of a schema diagram: heavier and dashed so the
        // hierarchy is visible even when property edges crowd around it.
        sheet.putCellStyle(SUBCLASS,
                edge("#556677", "8 4", mxConstants.ARROW_BLOCK, 1.8f));
        sheet.putCellStyle(OBJECT_PROPERTY,
                edge("#4A90D9", null, mxConstants.ARROW_CLASSIC, 1.4f));
        sheet.putCellStyle(DATA_PROPERTY,
                edge("#3E8E5A", null, mxConstants.ARROW_OPEN, 1.1f));
        sheet.putCellStyle(TYPE,
                edge("#7B61A8", "2 4", mxConstants.ARROW_OPEN, 1.1f));
    }

    private static Map<String, Object> vertex(String shape, String fill, String stroke,
            boolean rounded, boolean shadow, int fontSize, int fontStyle) {
        Map<String, Object> style = new HashMap<String, Object>();
        style.put(mxConstants.STYLE_SHAPE, shape);
        style.put(mxConstants.STYLE_FILLCOLOR, fill);
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_STROKEWIDTH, 1.6f);
        style.put(mxConstants.STYLE_FONTCOLOR, INK);
        style.put(mxConstants.STYLE_FONTFAMILY, FONT);
        style.put(mxConstants.STYLE_FONTSIZE, fontSize);
        if (fontStyle != 0) {
            style.put(mxConstants.STYLE_FONTSTYLE, fontStyle);
        }
        style.put(mxConstants.STYLE_ROUNDED, rounded);
        if (rounded) {
            style.put(mxConstants.STYLE_ARCSIZE, 12);
        }
        style.put(mxConstants.STYLE_SHADOW, shadow);
        // Long labels wrap instead of overflowing the shape.
        style.put(mxConstants.STYLE_WHITE_SPACE, "wrap");
        style.put(mxConstants.STYLE_SPACING, 6);
        style.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_CENTER);
        style.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_MIDDLE);
        return style;
    }

    private static Map<String, Object> edge(String stroke, String dashPattern, String endArrow,
            float width) {
        Map<String, Object> style = new HashMap<String, Object>();
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_STROKEWIDTH, width);
        style.put(mxConstants.STYLE_FONTCOLOR, EDGE_INK);
        style.put(mxConstants.STYLE_FONTFAMILY, FONT);
        style.put(mxConstants.STYLE_FONTSIZE, 11);
        style.put(mxConstants.STYLE_ENDARROW, endArrow);
        // Orthogonal routing keeps a dense diagram readable; free-form curves cross more.
        style.put(mxConstants.STYLE_EDGE, mxConstants.EDGESTYLE_ORTHOGONAL);
        style.put(mxConstants.STYLE_ROUNDED, true);
        // A label sitting on top of its own line is unreadable, so give it a backing.
        style.put(mxConstants.STYLE_LABEL_BACKGROUNDCOLOR, "#F7F8FA");
        if (dashPattern != null) {
            style.put(mxConstants.STYLE_DASHED, true);
            style.put(mxConstants.STYLE_DASH_PATTERN, dashPattern);
        }
        return style;
    }
}
