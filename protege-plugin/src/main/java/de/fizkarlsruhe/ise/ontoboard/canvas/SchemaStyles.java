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
    public static final String SUB_PROPERTY = "obSubProperty";
    public static final String INFERRED_SUBCLASS = "obInferredSubClass";

    /** A type the reasoner worked out for an individual, rather than one that was stated. */
    public static final String INFERRED_TYPE = "obInferredType";

    /**
     * Properties drawn as nodes rather than as edge labels.
     *
     * <p>Needed because a property hierarchy has nowhere to live otherwise - rdfs:subPropertyOf
     * relates two properties, and an edge needs two nodes. Deliberately a different shape from a
     * class so a diagram mixing the two is still readable at a glance.
     */
    public static final String OBJECT_PROPERTY_NODE = "obObjectPropertyNode";
    public static final String DATA_PROPERTY_NODE = "obDataPropertyNode";

    /** Canvas background - a hair off white so white node fills read as raised. */
    /** A sticky note: annotation on the diagram, not in the ontology. */
    public static final String STICKY_NOTE = "obStickyNote";

    /** A frame: a labelled region grouping nodes, drawn behind them. */
    public static final String FRAME = "obFrame";

    /** The style name the legend uses for the note marker. Not a cell style; a label. */
    public static final String NOTED = "obNoted";

    /** The style name the legend uses for an unsatisfiable class. */
    public static final String UNSATISFIABLE = "obUnsatisfiable";

    /**
     * The outline of a class a reasoner says can have no instances.
     *
     * <p>Red, and it overrides the namespace colour rather than sharing the channel. That is a
     * deliberate ranking: which vocabulary a term came from stops mattering the moment the term
     * cannot exist, and a modelling error that is only visible if you already know which shade of
     * blue to look for is not visible.
     */
    public static final String UNSATISFIABLE_STROKE = "#C0392B";

    /**
     * The border weight of a term carrying an editorial note.
     *
     * <p>Heavy enough to notice while scanning a board, light enough not to read as selection.
     */
    public static final int NOTED_STROKE_WIDTH = 3;

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
        // The same class style with the heavier border, registered so the legend's swatch draws
        // exactly what the canvas draws rather than an approximation of it. The canvas itself
        // appends the width as an override, because the marker applies to every kind of node and
        // a registered style per kind-and-marker combination would be six styles saying one thing.
        Map<String, Object> noted = vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#4A90D9",
                true, true, 13, mxConstants.FONT_BOLD);
        noted.put(mxConstants.STYLE_STROKEWIDTH, (float) NOTED_STROKE_WIDTH);
        sheet.putCellStyle(NOTED, noted);

        Map<String, Object> unsatisfiable = vertex(mxConstants.SHAPE_RECTANGLE, "#FDF0EE",
                UNSATISFIABLE_STROKE, true, true, 13, mxConstants.FONT_BOLD);
        unsatisfiable.put(mxConstants.STYLE_STROKEWIDTH, (float) NOTED_STROKE_WIDTH);
        sheet.putCellStyle(UNSATISFIABLE, unsatisfiable);

        // Deliberately unlike every ontology shape: square corners, no shadow, left-aligned text
        // that wraps. A sticky note is not a term, and anything that let somebody mistake one for
        // a class would be worse than not drawing them at all.
        Map<String, Object> sticky = vertex(mxConstants.SHAPE_RECTANGLE, "#FFF3B0", "#D9C066",
                false, false, 12, 0);
        sticky.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_LEFT);
        sticky.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        sticky.put(mxConstants.STYLE_SPACING, 6);
        sticky.put(mxConstants.STYLE_WHITE_SPACE, "wrap");
        sheet.putCellStyle(STICKY_NOTE, sticky);

        // Behind everything, and not fillable enough to hide a node: a frame groups, it does not
        // obscure.
        Map<String, Object> frame = vertex(mxConstants.SHAPE_RECTANGLE, "none", "#4A90D9",
                true, false, 13, mxConstants.FONT_BOLD);
        frame.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_LEFT);
        frame.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        frame.put(mxConstants.STYLE_SPACING, 8);
        frame.put(mxConstants.STYLE_DASHED, true);
        frame.put(mxConstants.STYLE_FONTCOLOR, "#4A90D9");
        sheet.putCellStyle(FRAME, frame);

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

        // A property hierarchy read alongside a class hierarchy needs to be distinguishable from
        // it, so subPropertyOf takes the same dashed weight as subClassOf in the property colour.
        sheet.putCellStyle(SUB_PROPERTY,
                edge("#C77700", "8 4", mxConstants.ARROW_BLOCK, 1.8f));

        // Inferred edges are dotted and grey: present, clearly derived, and never mistaken for
        // something the ontology actually asserts. A reasoner's conclusion drawn identically to an
        // asserted axiom would be the single most misleading thing this canvas could do.
        sheet.putCellStyle(INFERRED_SUBCLASS,
                edge("#8A94A0", "1 5", mxConstants.ARROW_BLOCK, 1.4f));

        // Same grey and the same dots as an inferred subclass, and the open arrowhead an asserted
        // type edge uses - so the colour says "the reasoner concluded this" and the head says
        // "this is a type", which are two independent facts a reader needs at once.
        sheet.putCellStyle(INFERRED_TYPE,
                edge("#8A94A0", "1 5", mxConstants.ARROW_OPEN, 1.4f));

        sheet.putCellStyle(OBJECT_PROPERTY_NODE, vertex(mxConstants.SHAPE_HEXAGON, "#FFF4E5",
                "#C77700", false, false, 12, mxConstants.FONT_ITALIC));
        sheet.putCellStyle(DATA_PROPERTY_NODE, vertex(mxConstants.SHAPE_HEXAGON, "#EEF7F1",
                "#3E8E5A", false, false, 12, mxConstants.FONT_ITALIC));
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
