package de.fizkarlsruhe.ise.ontoboard.canvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where every part of the exported key goes, as plain geometry.
 *
 * <p>An exported diagram used to carry no key and no prefix colours, so the one thing a reader
 * needs in order to decode it - what a dashed arrow means, what the outline colour says about a
 * term's namespace - stayed inside Protégé with the person who made the picture. The canvas has
 * had both since 1.53.0 and 1.63.0; the export simply never saw them, because
 * {@code mxCellRenderer} draws the graph model and the key is not in the graph model.
 *
 * <p><b>Geometry rather than drawing.</b> The key goes into a PNG through Java2D and into an SVG
 * as elements, and those are two renderers for one layout. Computing the layout once and handing
 * both of them the same list of shapes is what stops the PNG and the SVG from slowly diverging -
 * and it makes the part that is actually easy to get wrong, the arithmetic, something a test can
 * check without a screen.
 *
 * <p>Coordinates are relative to the key's own top-left corner, so a caller places it by adding
 * an offset.
 */
public final class LegendLayout {

    /** Outer padding, and the gap between the key and the diagram above it. */
    public static final int PADDING = 16;

    /** One row of the key. */
    public static final int ROW_HEIGHT = 22;

    /** The swatch column: wide enough for an edge sample to read as a line. */
    public static final int SWATCH_WIDTH = 34;

    private static final int SWATCH_HEIGHT = 14;
    private static final int HEADING_HEIGHT = 26;
    private static final int TEXT_SIZE = 12;
    private static final int HEADING_SIZE = 13;
    private static final int TITLE_SIZE = 15;

    /** Ink for labels, and the lighter ink for the explanation beside them. */
    static final String INK = "#1A1D21";
    static final String MUTED = "#5A6470";

    /** The hairline round the whole key, so it reads as a panel and not as part of the diagram. */
    static final String BORDER = "#D8DDE3";

    /** One thing to draw. A rectangle, a line, or a run of text - never more than one. */
    public static final class Shape {

        /** What this shape is. */
        public enum Kind {
            /** A filled and/or stroked rectangle. */
            RECT,
            /** A horizontal line, for an edge sample and for rules. */
            LINE,
            /** A run of text, with {@code x,y} at its left baseline. */
            TEXT
        }

        private final Kind kind;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final String fill;
        private final String stroke;
        private final boolean dashed;
        private final int opacity;
        private final boolean rounded;
        private final String text;
        private final int fontSize;
        private final boolean bold;

        Shape(Kind kind, int x, int y, int width, int height, String fill, String stroke,
                boolean dashed, int opacity, boolean rounded, String text, int fontSize,
                boolean bold) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fill = fill;
            this.stroke = stroke;
            this.dashed = dashed;
            this.opacity = opacity;
            this.rounded = rounded;
            this.text = text;
            this.fontSize = fontSize;
            this.bold = bold;
        }

        public Kind getKind() {
            return kind;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        /** {@code #RRGGBB}, or null for no fill. */
        public String getFill() {
            return fill;
        }

        /** {@code #RRGGBB}, or null for no stroke. */
        public String getStroke() {
            return stroke;
        }

        public boolean isDashed() {
            return dashed;
        }

        /** 0 to 100. Carried because one legend row's whole meaning is that it is faded. */
        public int getOpacity() {
            return opacity;
        }

        public boolean isRounded() {
            return rounded;
        }

        /** The text, for {@link Kind#TEXT}; null otherwise. */
        public String getText() {
            return text;
        }

        public int getFontSize() {
            return fontSize;
        }

        public boolean isBold() {
            return bold;
        }

        @Override
        public String toString() {
            return kind + "@" + x + "," + y + (text == null ? "" : " \"" + text + "\"");
        }
    }

    /** A laid-out key: its shapes and the box they need. */
    public static final class Key {
        private final List<Shape> shapes;
        private final int width;
        private final int height;

        Key(List<Shape> shapes, int width, int height) {
            this.shapes = Collections.unmodifiableList(shapes);
            this.width = width;
            this.height = height;
        }

        public List<Shape> getShapes() {
            return shapes;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        /** Nothing to draw, which is what an empty board produces. */
        public boolean isEmpty() {
            return shapes.isEmpty();
        }
    }

    private LegendLayout() {
    }

    /**
     * Lays the key out to a given width.
     *
     * <p>Entries are grouped by form explicitly rather than by watching for the form to change
     * as the list is walked. That is not a style preference: emitting a heading whenever the kind
     * changes printed "Relationships" twice on the real legend, because the two inferred rows sit
     * after the modifier rows - a defect that shipped and was visible the first time anybody
     * rendered the panel.
     *
     * @param entries the shape and line key; may be empty
     * @param namespaces the prefix colours on this board; may be empty
     * @param width how wide the key may be, in pixels at scale 1
     */
    public static Key of(List<CanvasLegend.Entry> entries,
            List<CanvasLegend.Namespace> namespaces, int width) {
        List<Shape> shapes = new ArrayList<Shape>();
        if ((entries == null || entries.isEmpty())
                && (namespaces == null || namespaces.isEmpty())) {
            return new Key(shapes, 0, 0);
        }
        int usableWidth = Math.max(width, 2 * PADDING + SWATCH_WIDTH + 120);
        int y = PADDING;

        shapes.add(text(PADDING, y + TITLE_SIZE, "Key", TITLE_SIZE, true, INK));
        y += HEADING_HEIGHT + 4;

        for (CanvasLegend.Form form : CanvasLegend.Form.values()) {
            List<CanvasLegend.Entry> ofForm = new ArrayList<CanvasLegend.Entry>();
            if (entries != null) {
                for (CanvasLegend.Entry entry : entries) {
                    if (entry.getForm() == form) {
                        ofForm.add(entry);
                    }
                }
            }
            if (ofForm.isEmpty()) {
                continue;
            }
            shapes.add(text(PADDING, y + HEADING_SIZE, headingFor(form), HEADING_SIZE, true,
                    MUTED));
            y += HEADING_HEIGHT;
            for (CanvasLegend.Entry entry : ofForm) {
                shapes.addAll(rowFor(entry, y, usableWidth));
                y += ROW_HEIGHT;
            }
            y += 6;
        }

        if (namespaces != null && !namespaces.isEmpty()) {
            shapes.add(text(PADDING, y + HEADING_SIZE, "Namespaces", HEADING_SIZE, true, MUTED));
            y += HEADING_HEIGHT;
            for (CanvasLegend.Namespace namespace : namespaces) {
                // A chip rather than a swatch: the namespace colour is an outline on the board,
                // and a filled box here would read as a fill there.
                shapes.add(new Shape(Shape.Kind.RECT, PADDING, y + 3, 14, 14,
                        "#FFFFFF", namespace.getColour(), false, 100, true, null, 0, false));
                shapes.add(text(PADDING + SWATCH_WIDTH, y + 14, namespace.getPrefix(), TEXT_SIZE,
                        false, INK));
                y += ROW_HEIGHT;
            }
            y += 6;
        }

        int height = y + PADDING - 6;
        // The panel border, first in the list so everything else draws over it.
        shapes.add(0, new Shape(Shape.Kind.RECT, 0, 0, usableWidth - 1, height - 1,
                "#FFFFFF", BORDER, false, 100, false, null, 0, false));
        return new Key(shapes, usableWidth, height);
    }

    /** The swatch and the two runs of text for one entry. */
    private static List<Shape> rowFor(CanvasLegend.Entry entry, int y, int usableWidth) {
        List<Shape> row = new ArrayList<Shape>();
        if (entry.getForm() == CanvasLegend.Form.EDGE) {
            row.add(new Shape(Shape.Kind.LINE, PADDING, y + SWATCH_HEIGHT / 2 + 3,
                    SWATCH_WIDTH - 8, 0, null, entry.getStroke(),
                    entry.getDashPattern() != null && !entry.getDashPattern().isEmpty(),
                    entry.getOpacity(), false, null, 0, false));
        } else {
            row.add(new Shape(Shape.Kind.RECT, PADDING, y + 3, SWATCH_WIDTH - 8, SWATCH_HEIGHT,
                    entry.getFill(), entry.getStroke(),
                    entry.getDashPattern() != null && !entry.getDashPattern().isEmpty(),
                    entry.getOpacity(), true, null, 0, false));
        }
        row.add(text(PADDING + SWATCH_WIDTH, y + 14, entry.getLabel(), TEXT_SIZE, true, INK));
        if (entry.getMeaning() != null && !entry.getMeaning().trim().isEmpty()) {
            int meaningLeft = PADDING + SWATCH_WIDTH + labelWidth(entry.getLabel()) + 8;
            row.add(text(meaningLeft, y + 14,
                    fitted(entry.getMeaning(), usableWidth - meaningLeft - PADDING),
                    TEXT_SIZE, false, MUTED));
        }
        return row;
    }

    /**
     * As much of {@code text} as fits in {@code available} pixels, with an ellipsis if cut.
     *
     * <p>Needed because the legend's explanations are sentences - "A restriction on an object
     * property - by default SubClassOf(A ObjectSomeValuesFrom(R B)), read as every A ..." - and
     * an exported key is a fixed width. Unfitted, they ran past the panel's own border and off
     * the side of the picture, which looked like a rendering fault rather than a long sentence.
     *
     * <p>Cut rather than wrapped. A wrapped row has a variable height, and every row below it
     * would move; the full text is in the Legend dialog, which is where somebody who wants the
     * whole sentence should be sent.
     */
    static String fitted(String text, int available) {
        if (text == null) {
            return "";
        }
        int room = (int) Math.floor(available / 6.6);
        if (room <= 1) {
            return "";
        }
        if (text.length() <= room) {
            return text;
        }
        return text.substring(0, Math.max(1, room - 1)).trim() + "\u2026";
    }

    /**
     * How wide a bold label is, estimated.
     *
     * <p>Estimated rather than measured, because measuring needs a font and a font needs a
     * graphics environment - and this layout is computed for an SVG as readily as for a PNG. The
     * estimate is deliberately generous: too much gap is untidy, too little overlaps two runs of
     * text, and only one of those is unreadable.
     */
    static int labelWidth(String label) {
        return label == null ? 0 : (int) Math.round(label.length() * 7.2);
    }

    private static String headingFor(CanvasLegend.Form form) {
        switch (form) {
            case NODE:
                return "Terms";
            case EDGE:
                return "Relationships";
            case MODIFIER:
            default:
                return "Markers";
        }
    }

    private static Shape text(int x, int baseline, String value, int size, boolean bold,
            String colour) {
        return new Shape(Shape.Kind.TEXT, x, baseline, 0, 0, colour, null, false, 100, false,
                value, size, bold);
    }
}
