package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the shapes and lines on the canvas mean.
 *
 * <p>A diagram with four node shapes and four line styles and no key is a puzzle. This is the key,
 * expressed as data rather than as a hand-drawn picture, for one reason: {@link #entries()} can then
 * be checked for completeness against {@link NodeKind} and {@link CanvasEdge.Kind}, so a new kind
 * of thing on the canvas cannot be added without the legend gaining a row for it. A legend that
 * silently falls behind what is drawn is worse than none, because it is then actively misleading.
 *
 * <p>Colours and dash patterns are duplicated from {@link SchemaStyles} rather than read back out
 * of the mxGraph stylesheet. That is a deliberate, tested duplication:
 * {@code CanvasLegendTest.legendColoursMatchTheStylesheet} compares the two, so they cannot drift,
 * and reading them back would mean constructing an mxGraph - which cannot be done in a headless
 * test.
 */
public final class CanvasLegend {

    /** Whether a row is drawn as a box or as a line. */
    public enum Form {
        NODE,
        EDGE
    }

    /** One row of the key. */
    public static final class Entry {
        private final Form form;
        private final String styleName;
        private final String label;
        private final String meaning;
        private final String fill;
        private final String stroke;
        private final String dashPattern;

        Entry(Form form, String styleName, String label, String meaning, String fill,
                String stroke, String dashPattern) {
            this.form = form;
            this.styleName = styleName;
            this.label = label;
            this.meaning = meaning;
            this.fill = fill;
            this.stroke = stroke;
            this.dashPattern = dashPattern;
        }

        public Form getForm() {
            return form;
        }

        /** The {@link SchemaStyles} style this row illustrates. */
        public String getStyleName() {
            return styleName;
        }

        /** What to write beside the swatch, e.g. {@code Class} or {@code rdfs:subClassOf}. */
        public String getLabel() {
            return label;
        }

        /** One line saying what it means in the ontology, for a tooltip. */
        public String getMeaning() {
            return meaning;
        }

        /** {@code #RRGGBB}, or null for an edge. */
        public String getFill() {
            return fill;
        }

        public String getStroke() {
            return stroke;
        }

        /** mxGraph dash pattern, e.g. {@code "8 4"}, or null when solid. */
        public String getDashPattern() {
            return dashPattern;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private CanvasLegend() {
    }

    /**
     * Every row of the key, nodes first.
     *
     * <p>Meanings name the OWL construct rather than describing the picture: someone reading a
     * legend wants to know that a dashed grey arrow is {@code rdfs:subClassOf}, not that it is
     * dashed and grey.
     */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<Entry>();

        entries.add(new Entry(Form.NODE, SchemaStyles.CLASS, "Class",
                "An owl:Class. Bold, because it is what a schema is mostly made of.",
                "#FFFFFF", "#4A90D9", null));
        entries.add(new Entry(Form.NODE, SchemaStyles.INDIVIDUAL, "Individual",
                "An owl:NamedIndividual - a particular thing rather than a kind of thing.",
                "#F4F0FA", "#7B61A8", null));
        entries.add(new Entry(Form.NODE, SchemaStyles.DATATYPE, "Datatype",
                "A datatype such as xsd:string, at the far end of a data property.",
                "#EEF7F1", "#3E8E5A", null));
        entries.add(new Entry(Form.NODE, SchemaStyles.LITERAL, "Literal",
                "A literal value such as a number or a piece of text, at the end of a data "
                        + "property. Not drawn yet - the plugin has no literal nodes.",
                "#FFF9E6", "#B08900", null));

        entries.add(new Entry(Form.EDGE, SchemaStyles.SUBCLASS, "rdfs:subClassOf",
                "The child is a kind of the parent. Dashed and heavier, so the hierarchy stays "
                        + "readable when property edges crowd it.",
                null, "#556677", "8 4"));
        entries.add(new Entry(Form.EDGE, SchemaStyles.OBJECT_PROPERTY, "Object property",
                "A restriction on an object property - by default SubClassOf(A "
                        + "ObjectSomeValuesFrom(R B)), read as 'every A relates to some B'. The "
                        + "arrow's label names the property and its reading.",
                null, "#4A90D9", null));
        entries.add(new Entry(Form.EDGE, SchemaStyles.DATA_PROPERTY, "Data property",
                "A restriction on a data property, pointing at a datatype.",
                null, "#3E8E5A", null));
        entries.add(new Entry(Form.EDGE, SchemaStyles.TYPE, "rdf:type",
                "The individual is an instance of the class. Finely dashed, because it says "
                        + "something about one thing rather than about a kind.",
                null, "#7B61A8", "2 4"));

        return entries;
    }

    /**
     * Node kinds the canvas can draw but the legend does not explain, and vice versa.
     *
     * <p>Exposed so a test can insist the set is empty. The alternative - remembering to update a
     * picture - is exactly what leaves a legend lying about the diagram.
     */
    static Set<String> nodeKindsWithoutAnEntry() {
        Set<String> missing = new LinkedHashSet<String>();
        for (NodeKind kind : NodeKind.values()) {
            if (!hasEntryFor(styleFor(kind))) {
                missing.add(kind.name());
            }
        }
        return missing;
    }

    /** Edge kinds the canvas can draw but the legend does not explain. */
    static Set<String> edgeKindsWithoutAnEntry() {
        Set<String> missing = new LinkedHashSet<String>();
        for (CanvasEdge.Kind kind : CanvasEdge.Kind.values()) {
            if (!hasEntryFor(styleFor(kind))) {
                missing.add(kind.name());
            }
        }
        return missing;
    }

    /** The {@link SchemaStyles} name the canvas uses for {@code kind}. */
    static String styleFor(NodeKind kind) {
        switch (kind) {
            case INDIVIDUAL:
                return SchemaStyles.INDIVIDUAL;
            case DATATYPE:
                return SchemaStyles.DATATYPE;
            case LITERAL:
                return SchemaStyles.LITERAL;
            case CLASS:
            default:
                return SchemaStyles.CLASS;
        }
    }

    static String styleFor(CanvasEdge.Kind kind) {
        switch (kind) {
            case OBJECT_PROPERTY:
                return SchemaStyles.OBJECT_PROPERTY;
            case DATA_PROPERTY:
                return SchemaStyles.DATA_PROPERTY;
            case TYPE:
                return SchemaStyles.TYPE;
            case SUBCLASS:
            default:
                return SchemaStyles.SUBCLASS;
        }
    }

    private static boolean hasEntryFor(String styleName) {
        for (Entry entry : entries()) {
            if (entry.getStyleName().equals(styleName)) {
                return true;
            }
        }
        return false;
    }

    /** Style names the legend mentions, for a test that checks nothing is invented. */
    static Set<String> styleNamesMentioned() {
        Set<String> names = new LinkedHashSet<String>();
        for (Entry entry : entries()) {
            names.add(entry.getStyleName());
        }
        return Collections.unmodifiableSet(names);
    }
}
