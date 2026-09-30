package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Visual styles for canvas nodes and edges.
 *
 * <p>Legibility is the goal, not decoration. A schema diagram is read far more often than it
 * is drawn, so the choices here favour telling things apart at a glance: shape carries the
 * entity kind - except that an individual is a card with an underlined name, the UML
 * instance-specification convention, because a rhombus cannot hold an ontology label - stroke colour
 * carries the namespace for classes (see {@link PrefixColours}), and arrowhead, weight and dash
 * distinguish a hierarchy link from a property link without needing the label.
 *
 * <p>Every colour here has been measured against the canvas background and against its own fill.
 * Nothing in the node palette is below 4.4:1, and no edge is below 4:1 - WCAG 1.4.11's floor for a
 * non-text graphic is 3:1, which four of these strokes previously failed.
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

    /** A sticky note: annotation on the diagram, not in the ontology. */
    public static final String STICKY_NOTE = "obStickyNote";

    /** A frame: a labelled region grouping nodes, drawn behind them. */
    public static final String FRAME = "obFrame";

    /** The style name the legend uses for the note marker. Not a cell style; a label. */
    public static final String NOTED = "obNoted";

    /** The style name the legend uses for an unsatisfiable class. */
    public static final String UNSATISFIABLE = "obUnsatisfiable";

    /**
     * A term defined in an imported ontology rather than in the file being edited.
     *
     * <p>Drawn faded. Every other channel on a node already says something - the shape says what kind
     * of thing it is, the stroke colour says which namespace it came from, the border weight says
     * there is a note, and a dash would read as "inferred" - so opacity is the one free channel, and
     * it happens to say the right thing: an imported term is context rather than your work.
     *
     * <p>Opacity is a cell style, not an overlay, so it survives a PNG or SVG export. That matters
     * more here than for the note badge: the distinction is which terms a curator must not edit, and
     * a published diagram that draws imported terms exactly like local ones is a diagram that invites
     * the mistake.
     */
    public static final String IMPORTED = "obImported";

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
     * The interior of a class a reasoner says can have no instances.
     *
     * <p>Registered in the legend since the marker existed and never actually drawn on the canvas,
     * because {@code styleFor} only ever overrode the stroke - so the board showed a white card with
     * a red outline while the key showed a pink one. A red outline alone is also the single channel
     * colour vision deficiency takes away: {@code #C0392B} simulates to {@code #77771E} under
     * deuteranopia, which is deltaE 12.9 from the brown a namespace can be assigned.
     */
    public static final String UNSATISFIABLE_FILL = "#FDF0EE";

    /**
     * Secondary ink for an imported term.
     *
     * <p>{@code opacity} fades the shape and not the label - {@code mxGraphics2DCanvas.drawLabel}
     * reads a different key - so an imported term was a ghosted box with full-strength bold black
     * text sitting on it. {@code textOpacity} is the wrong fix: at 55% the ink composites to
     * {@code #7D8083}, 3.87:1, below the 4.5:1 floor for text. This is 5.84:1 on the composited fill
     * and still visibly secondary against {@code #1A1D21}'s 16.9:1.
     */
    public static final String IMPORTED_INK = "#5A6472";

    /**
     * The border weight of a term carrying an editorial note.
     *
     * <p>Heavy enough to notice while scanning a board, light enough not to read as selection.
     */
    public static final int NOTED_STROKE_WIDTH = 3;

    /**
     * How solid an imported term is drawn, as a percentage.
     *
     * <p>Faded enough to read as background at a glance, solid enough that the label is legible and
     * the namespace colour is still recognisable - the two things the fade must not take away.
     */
    public static final int IMPORTED_OPACITY = 55;

    /** Canvas background - a hair off white so white node fills read as raised. */
    public static final String CANVAS_BACKGROUND = "#F7F8FA";

    private static final String FONT = resolveFont();
    private static final String INK = "#1A1D21";
    private static final String EDGE_INK = "#4A5560";

    /**
     * One installed family, because {@code java.awt.Font} takes a family name and not a CSS chain.
     *
     * <p>This was the string {@code "Segoe UI, Helvetica Neue, Arial, sans-serif"}, which is a CSS
     * fallback list and means nothing to AWT: {@code new Font("Segoe UI, Helvetica Neue, Arial,
     * sans-serif", BOLD, 13).getFamily()} returns {@code "Dialog"}. So no board, screenshot or export
     * has ever been drawn in the typeface this code named - and the SVG writer emits the string
     * verbatim, so the PNG and the SVG of one board were in different fonts.
     *
     * <p>Resolved once at class initialisation, and falling back to the logical family, which always
     * exists. Probed headless on Windows 11: 223 families available including Segoe UI, so a CI
     * render and the design-proof test resolve the same way a desktop does.
     */
    private static String resolveFont() {
        String[] preferred = {"Segoe UI", "Inter", "Helvetica Neue", "Roboto",
                "DejaVu Sans", "Liberation Sans", "Arial"};
        try {
            Set<String> installed = new HashSet<String>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames(Locale.ENGLISH)));
            for (String family : preferred) {
                if (installed.contains(family)) {
                    return family;
                }
            }
        } catch (Throwable noFontsAvailable) {
            // A headless or font-less JRE. The logical family below always exists.
        }
        return Font.SANS_SERIF;
    }

    private SchemaStyles() {
    }

    public static void install(mxGraph graph) {
        // JGraphX paints a shadow as a hard offset copy of the same path in one flat colour - there
        // is no blur, spread or gradient anywhere on that path. The default is opaque mid-grey,
        // which reads as a smudge rather than a lift, is 3.72:1 on the canvas (more contrast than
        // four of the six node strokes had), and bleeds through the 55% fill of an imported term:
        // a grey shadow under a translucent white fill composites to #DEDEDF, which is why an
        // imported term rendered as a grey slab.
        //
        // These are non-final public statics. Mutating them is safe in this build specifically,
        // because the pom embeds jgraphx into the plugin's own OSGi bundle, so mxConstants and
        // mxSwingConstants load in the plugin's classloader rather than a shared one. If that embed
        // ever changes, this is the code to revisit.
        com.mxgraph.swing.util.mxSwingConstants.SHADOW_COLOR = new Color(0x0F, 0x17, 0x2A, 0x26);
        mxConstants.SHADOW_OFFSETX = 0;
        mxConstants.SHADOW_OFFSETY = 2;
        // The SVG path takes a colour string with no alpha, so this is the pre-composited equivalent.
        mxConstants.W3C_SHADOWCOLOR = "#D7D8DD";
        // One global corner radius for every orthogonal bend. Read only by the two canvases and by
        // mxConstants itself - never by a vertex shape, which rounds via STYLE_ARCSIZE.
        mxConstants.LINE_ARCSIZE = 20;

        mxStylesheet sheet = graph.getStylesheet();

        // A class is the thing users read most, so it gets the strongest treatment:
        // rounded, shadowed, bold, and roomy enough for a two-line rdfs:label.
        sheet.putCellStyle(CLASS, vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#2D6FBF",
                true, true, 13, mxConstants.FONT_BOLD));
        // The same class style with the heavier border, registered so the legend's swatch draws
        // exactly what the canvas draws rather than an approximation of it. The canvas itself
        // appends the width as an override, because the marker applies to every kind of node and
        // a registered style per kind-and-marker combination would be six styles saying one thing.
        Map<String, Object> noted = vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#2D6FBF",
                true, true, 13, mxConstants.FONT_BOLD);
        noted.put(mxConstants.STYLE_STROKEWIDTH, (float) NOTED_STROKE_WIDTH);
        sheet.putCellStyle(NOTED, noted);

        Map<String, Object> unsatisfiable = vertex(mxConstants.SHAPE_RECTANGLE, UNSATISFIABLE_FILL,
                UNSATISFIABLE_STROKE, true, true, 13, mxConstants.FONT_BOLD);
        unsatisfiable.put(mxConstants.STYLE_STROKEWIDTH, (float) NOTED_STROKE_WIDTH);
        sheet.putCellStyle(UNSATISFIABLE, unsatisfiable);

        // Registered for the same reason as NOTED: so the legend's swatch draws what the canvas
        // draws rather than an approximation. The canvas appends the opacity as an override, because
        // the marker applies to every kind of node.
        Map<String, Object> imported = vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#2D6FBF",
                true, true, 13, mxConstants.FONT_BOLD);
        imported.put(mxConstants.STYLE_OPACITY, IMPORTED_OPACITY);
        imported.put(mxConstants.STYLE_FONTCOLOR, IMPORTED_INK);
        sheet.putCellStyle(IMPORTED, imported);

        // Deliberately unlike every ontology shape: square corners, no shadow, left-aligned text
        // that wraps. A sticky note is not a term, and anything that let somebody mistake one for
        // a class would be worse than not drawing them at all.
        Map<String, Object> sticky = vertex(mxConstants.SHAPE_RECTANGLE, "#FFF3B0", "#A8871C",
                false, false, 12, 0);
        sticky.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_LEFT);
        sticky.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        sticky.put(mxConstants.STYLE_SPACING, 6);
        sticky.put(mxConstants.STYLE_WHITE_SPACE, "wrap");
        sheet.putCellStyle(STICKY_NOTE, sticky);

        // Behind everything, and not fillable enough to hide a node: a frame groups, it does not
        // obscure.
        Map<String, Object> frame = vertex(mxConstants.SHAPE_RECTANGLE, "none", "#2D6FBF",
                true, false, 13, mxConstants.FONT_BOLD);
        frame.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_LEFT);
        frame.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        frame.put(mxConstants.STYLE_SPACING, 8);
        frame.put(mxConstants.STYLE_DASHED, true);
        frame.put(mxConstants.STYLE_FONTCOLOR, "#2D6FBF");
        sheet.putCellStyle(FRAME, frame);

        // A card with an underlined name, not a rhombus. A 160x60 rhombus offers about 77px of
        // interior where a two-line label needs 128, so every individual with a real ontology label
        // hung out of both slanted edges; padding cannot rescue it, because the padding the geometry
        // demands wraps "Cheesey vegetable topping" onto three lines. The underline is UML's
        // instance-specification convention and is the channel that replaces the shape.
        Map<String, Object> individual = vertex(mxConstants.SHAPE_RECTANGLE, "#F3EEFB", "#6B4FA0",
                true, false, 13, mxConstants.FONT_UNDERLINE);
        individual.put(mxConstants.STYLE_ARCSIZE, 10);
        sheet.putCellStyle(INDIVIDUAL, individual);

        sheet.putCellStyle(DATATYPE, vertex(mxConstants.SHAPE_ELLIPSE, "#EAF5EF", "#2F7A4C",
                false, false, 12, 0));
        sheet.putCellStyle(LITERAL, vertex(mxConstants.SHAPE_RECTANGLE, "#FFF6DB", "#8A6A00",
                false, false, 12, 0));

        // A hollow triangle pointing at the parent: UML's generalisation head, which is what an
        // ontology reader already expects for is-a. Solid, because that frees the dash to mean "the
        // reasoner derived this" - previously asserted and inferred differed only by dash length and
        // one step of grey, which is a legend lookup rather than a glance, on the one distinction
        // this canvas must never blur.
        sheet.putCellStyle(SUBCLASS,
                edge("#3B4756", null, mxConstants.ARROW_BLOCK, 1.6f, 13, false));
        sheet.putCellStyle(OBJECT_PROPERTY,
                edge("#2D6FBF", null, mxConstants.ARROW_CLASSIC, 1.4f, 10, true));
        sheet.putCellStyle(DATA_PROPERTY,
                edge("#0E6E78", null, mxConstants.ARROW_OPEN, 1.1f, 10, true));
        sheet.putCellStyle(TYPE,
                edge("#6B4FA0", "2 4", mxConstants.ARROW_OPEN, 1.1f, 10, true));

        // A property hierarchy read alongside a class hierarchy needs to be distinguishable from
        // it, so subPropertyOf takes the same hollow head as subClassOf in the property colour.
        sheet.putCellStyle(SUB_PROPERTY,
                edge("#B35C00", null, mxConstants.ARROW_BLOCK, 1.6f, 13, false));

        // Inferred edges are dotted and grey: present, clearly derived, and never mistaken for
        // something the ontology actually asserts. A reasoner's conclusion drawn identically to an
        // asserted axiom would be the single most misleading thing this canvas could do. The old
        // #8A94A0 was 2.90:1 on the canvas, under WCAG's 3:1 floor for a non-text graphic.
        Map<String, Object> inferredSubclass =
                edge("#707B89", "1 5", mxConstants.ARROW_BLOCK, 1.4f, 13, false);
        derivedTail(inferredSubclass);
        sheet.putCellStyle(INFERRED_SUBCLASS, inferredSubclass);

        // The same grey and dots, and the open head an asserted type edge uses - so the colour says
        // "the reasoner concluded this" and the head says "this is a type", which are two
        // independent facts a reader needs at once.
        Map<String, Object> inferredType =
                edge("#707B89", "1 5", mxConstants.ARROW_OPEN, 1.4f, 10, true);
        derivedTail(inferredType);
        sheet.putCellStyle(INFERRED_TYPE, inferredType);

        sheet.putCellStyle(OBJECT_PROPERTY_NODE, hexagon("#FFEFDC", "#B35C00"));
        sheet.putCellStyle(DATA_PROPERTY_NODE, hexagon("#E6F2F4", "#0E6E78"));
    }

    /**
     * A property drawn as a node, with room for its label inside the taper.
     *
     * <p>{@code mxGraphView.getWordWrapWidth} subtracts the label insets and spacing from the cell's
     * full width and knows nothing about the shape, so a hexagon whose corners sit at a quarter and
     * three quarters of its width offers about 101px where the wrap width says 127.8 - and any
     * two-line label hangs out of both slanted edges. The extra 22px each side buys the wrap width
     * down to 88.2, which fits.
     */
    private static Map<String, Object> hexagon(String fill, String stroke) {
        Map<String, Object> style = vertex(mxConstants.SHAPE_HEXAGON, fill, stroke,
                false, false, 13, mxConstants.FONT_ITALIC);
        style.put(mxConstants.STYLE_SPACING_LEFT, 22);
        style.put(mxConstants.STYLE_SPACING_RIGHT, 22);
        return style;
    }

    /**
     * A small open circle where an inferred edge leaves its source.
     *
     * <p>The conventional "derived, not stated" tail. With it, the reading is: solid line with a
     * hollow triangle is an asserted is-a; dotted line with an open tail and a hollow triangle is an
     * inferred one. Two independent channels rather than one, which matters because after
     * deuteranopia the closest pair in this edge set is 12.0 deltaE apart - survivable only because
     * the dash is redundant with the hue.
     */
    private static void derivedTail(Map<String, Object> style) {
        style.put(mxConstants.STYLE_STARTARROW, mxConstants.ARROW_OVAL);
        style.put(mxConstants.STYLE_STARTSIZE, 7);
        style.put(mxConstants.STYLE_STARTFILL, Boolean.FALSE);
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
            float width, int endSize, boolean endFilled) {
        Map<String, Object> style = new HashMap<String, Object>();
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_STROKEWIDTH, width);
        style.put(mxConstants.STYLE_FONTCOLOR, EDGE_INK);
        style.put(mxConstants.STYLE_FONTFAMILY, FONT);
        style.put(mxConstants.STYLE_FONTSIZE, 11);
        style.put(mxConstants.STYLE_ENDARROW, endArrow);
        style.put(mxConstants.STYLE_ENDSIZE, endSize);
        if (!endFilled) {
            // JGraphX ships five arrowheads and none of them is a hollow triangle. The block marker
            // reads endFill and skips the fill while still returning the full marker length, so the
            // connector stops at the triangle's base rather than running through it.
            style.put(mxConstants.STYLE_ENDFILL, Boolean.FALSE);
        }
        // Orthogonal routing keeps a dense diagram readable; free-form curves cross more. Asked for
        // since the first version and never actually applied until 1.64.0, because the hierarchical
        // layout stamped noEdgeStyle=1 onto every edge it touched.
        style.put(mxConstants.STYLE_EDGE, mxConstants.EDGESTYLE_ORTHOGONAL);
        style.put(mxConstants.STYLE_ROUNDED, true);
        // White, with a hairline. The old #F7F8FA backing was the canvas colour, which meant every
        // exported PNG carried grey chips on a white page; white reads as a raised chip on the
        // canvas and disappears correctly on an export, and the hairline is what stops it looking
        // like a hole punched through the wire.
        style.put(mxConstants.STYLE_LABEL_BACKGROUNDCOLOR, "#FFFFFF");
        style.put(mxConstants.STYLE_LABEL_BORDERCOLOR, "#C9D2DC");
        style.put(mxConstants.STYLE_SPACING, 2);
        // A hair of air, so a 13px hollow triangle does not weld itself to the node's 1.6px outline.
        // Only at the target: a gap at the tail reads as a broken connection.
        style.put(mxConstants.STYLE_TARGET_PERIMETER_SPACING, 3);
        if (dashPattern != null) {
            style.put(mxConstants.STYLE_DASHED, true);
            style.put(mxConstants.STYLE_DASH_PATTERN, dashPattern);
        }
        return style;
    }
}
