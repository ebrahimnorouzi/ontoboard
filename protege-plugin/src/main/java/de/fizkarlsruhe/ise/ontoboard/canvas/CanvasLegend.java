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

    /**
     * What a legend row illustrates.
     *
     * <p>{@link #MODIFIER} is the third kind and the reason this is not a boolean. A note marker
     * is not a kind of thing the canvas draws - it is a change to how an existing kind is drawn -
     * so it must not be counted by the completeness check that pairs every {@code NodeKind} and
     * {@code CanvasEdge.Kind} with exactly one row. Folding it in there would either force an
     * invented kind or force the check to be loosened, and that check has already caught a real
     * omission once.
     */
    public enum Form {
        NODE,
        EDGE,

        /** A marker laid over a kind, such as a term that carries an editorial note. */
        MODIFIER
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

        entries.add(new Entry(Form.NODE, SchemaStyles.OBJECT_PROPERTY_NODE, "Object property",
                "An owl:ObjectProperty as a node rather than an arrow label. Properties are only "
                        + "nodes when you add them, because a diagram that boxed every property "
                        + "would be unreadable - but a property hierarchy needs them.",
                "#FFF4E5", "#C77700", null));
        entries.add(new Entry(Form.NODE, SchemaStyles.DATA_PROPERTY_NODE, "Data property",
                "An owl:DatatypeProperty as a node, for the same reason as an object property "
                        + "node.",
                "#EEF7F1", "#3E8E5A", null));

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

        entries.add(new Entry(Form.EDGE, SchemaStyles.SUB_PROPERTY, "rdfs:subPropertyOf",
                "One property is a specialisation of another - if A worksAt B then A employedBy "
                        + "B. Both properties have to be on the board for the arrow to appear.",
                null, "#C77700", "8 4"));
        entries.add(new Entry(Form.MODIFIER, SchemaStyles.UNSATISFIABLE, "Cannot have instances",
                "A reasoner has found this class unsatisfiable - two of its axioms cannot both "
                        + "hold, so nothing can ever be one. Shown only while inferences are on.",
                "#FDF0EE", SchemaStyles.UNSATISFIABLE_STROKE, null));
        entries.add(new Entry(Form.MODIFIER, SchemaStyles.NOTED, "Has an editorial note",
                "A heavier border. Somebody has written an editor or curator note on this term - "
                        + "read them all with OntoBoard > Notes > All notes.",
                "#FFFFFF", "#4A90D9", null));
        entries.add(new Entry(Form.EDGE, SchemaStyles.INFERRED_SUBCLASS, "Inferred subclass",
                "A subsumption the reasoner worked out that the ontology does not state "
                        + "directly. Dotted and grey so it is never mistaken for an asserted "
                        + "axiom - it will disappear if the axioms it followed from change.",
                null, "#8A94A0", "1 5"));
        entries.add(new Entry(Form.EDGE, SchemaStyles.INFERRED_TYPE, "Inferred type",
                "The reasoner worked out that this individual belongs to this class, though "
                        + "nothing says so directly - usually because the class is defined by "
                        + "conditions the individual happens to meet. Grey and dotted like any "
                        + "other conclusion.",
                null, "#8A94A0", "1 5"));

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

    /**
     * The {@link SchemaStyles} name the canvas uses for {@code kind}, or null when this class has
     * no mapping for it.
     *
     * <p><b>No catch-all default.</b> An earlier version ended both switches with
     * {@code default: return SchemaStyles.CLASS} (and {@code SUBCLASS}), which made the
     * completeness checks below vacuous: a brand-new kind fell into the default, landed on a style
     * that does have a legend row, and was reported as covered. Adding OBJECT_PROPERTY,
     * DATA_PROPERTY and SUB_PROPERTY proved it - the guard passed while three new things were
     * undocumented. Returning null instead is what makes {@link #nodeKindsWithoutAnEntry()} able
     * to see a gap at all.
     */
    static String styleFor(NodeKind kind) {
        switch (kind) {
            case CLASS:
                return SchemaStyles.CLASS;
            case INDIVIDUAL:
                return SchemaStyles.INDIVIDUAL;
            case DATATYPE:
                return SchemaStyles.DATATYPE;
            case LITERAL:
                return SchemaStyles.LITERAL;
            case OBJECT_PROPERTY:
                return SchemaStyles.OBJECT_PROPERTY_NODE;
            case DATA_PROPERTY:
                return SchemaStyles.DATA_PROPERTY_NODE;
            default:
                return null;
        }
    }

    /** As {@link #styleFor(NodeKind)}, and deliberately without a catch-all for the same reason. */
    static String styleFor(CanvasEdge.Kind kind) {
        switch (kind) {
            case SUBCLASS:
                return SchemaStyles.SUBCLASS;
            case OBJECT_PROPERTY:
                return SchemaStyles.OBJECT_PROPERTY;
            case DATA_PROPERTY:
                return SchemaStyles.DATA_PROPERTY;
            case TYPE:
                return SchemaStyles.TYPE;
            case SUB_PROPERTY:
                return SchemaStyles.SUB_PROPERTY;
            case INFERRED_SUBCLASS:
                return SchemaStyles.INFERRED_SUBCLASS;
            case INFERRED_TYPE:
                return SchemaStyles.INFERRED_TYPE;
            default:
                return null;
        }
    }

    private static boolean hasEntryFor(String styleName) {
        if (styleName == null) {
            // An unmapped kind. Reported as missing, which is the whole point.
            return false;
        }
        for (Entry entry : entries()) {
            if (entry.getStyleName().equals(styleName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The styles the legend explains that correspond to a kind the canvas draws.
     *
     * <p>Modifier rows are left out deliberately: they illustrate a change to how a kind is
     * drawn, not a kind, so counting them would break the pairing this set exists to check.
     */
    static Set<String> styleNamesMentioned() {
        Set<String> names = new LinkedHashSet<String>();
        for (Entry entry : entries()) {
            if (entry.getForm() != Form.MODIFIER) {
                names.add(entry.getStyleName());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /** Every modifier row, so a new marker cannot be added without the legend gaining one. */
    static Set<String> modifierStyleNames() {
        Set<String> names = new LinkedHashSet<String>();
        for (Entry entry : entries()) {
            if (entry.getForm() == Form.MODIFIER) {
                names.add(entry.getStyleName());
            }
        }
        return Collections.unmodifiableSet(names);
    }
}
