package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import java.util.HashMap;
import java.util.Map;

/** JGraphX styles for each node and edge kind (spec section 5.1). */
public final class SchemaStyles {

    public static final String CLASS = "obClass";
    public static final String INDIVIDUAL = "obIndividual";
    public static final String DATATYPE = "obDatatype";
    public static final String LITERAL = "obLiteral";

    public static final String SUBCLASS = "obSubClass";
    public static final String OBJECT_PROPERTY = "obObjectProperty";
    public static final String DATA_PROPERTY = "obDataProperty";
    public static final String TYPE = "obType";

    private SchemaStyles() {
    }

    public static void install(mxGraph graph) {
        mxStylesheet sheet = graph.getStylesheet();
        sheet.putCellStyle(CLASS, vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#4A90D9", true));
        sheet.putCellStyle(INDIVIDUAL, vertex(mxConstants.SHAPE_RHOMBUS, "#FFFFFF", "#7B61A8", false));
        sheet.putCellStyle(DATATYPE, vertex(mxConstants.SHAPE_ELLIPSE, "#FFFFFF", "#3E8E5A", false));
        sheet.putCellStyle(LITERAL, vertex(mxConstants.SHAPE_RECTANGLE, "#FFF9E6", "#B08900", false));

        sheet.putCellStyle(SUBCLASS, edge("#556677", "6 3", mxConstants.ARROW_BLOCK));
        sheet.putCellStyle(OBJECT_PROPERTY, edge("#4A90D9", null, mxConstants.ARROW_CLASSIC));
        sheet.putCellStyle(DATA_PROPERTY, edge("#3E8E5A", null, mxConstants.ARROW_OPEN));
        sheet.putCellStyle(TYPE, edge("#7B61A8", "2 3", mxConstants.ARROW_OPEN));
    }

    private static Map<String, Object> vertex(String shape, String fill, String stroke, boolean rounded) {
        Map<String, Object> style = new HashMap<>();
        style.put(mxConstants.STYLE_SHAPE, shape);
        style.put(mxConstants.STYLE_FILLCOLOR, fill);
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_FONTCOLOR, "#1A1A1A");
        style.put(mxConstants.STYLE_ROUNDED, rounded);
        return style;
    }

    private static Map<String, Object> edge(String stroke, String dashPattern, String endArrow) {
        Map<String, Object> style = new HashMap<>();
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_FONTCOLOR, "#333333");
        style.put(mxConstants.STYLE_ENDARROW, endArrow);
        if (dashPattern != null) {
            style.put(mxConstants.STYLE_DASHED, true);
            style.put(mxConstants.STYLE_DASH_PATTERN, dashPattern);
        }
        return style;
    }
}
