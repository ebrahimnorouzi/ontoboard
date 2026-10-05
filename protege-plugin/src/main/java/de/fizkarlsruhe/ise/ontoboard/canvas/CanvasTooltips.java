package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import java.util.Map;

/**
 * What the canvas says when you hover something.
 *
 * <p>It used to say the cell's id, because that is what {@code SchemaGraph.getToolTipForCell}
 * returned. For a node the id is its IRI, which is at least true. For everything else it was an
 * internal string: hovering an arrow gave
 * {@code rest|some|http://…/pizza#Pizza|http://…#hasTopping|http://…#PizzaTopping} and hovering a
 * sticky note gave {@code ontoboard-note-3f2a1b9c}. Both read as a bug in the plugin, and both
 * withheld the answer to the question a schema diagram raises most often: <em>which axiom is this
 * arrow?</em>
 *
 * <p>Everything here is derived from the {@link de.fizkarlsruhe.ise.ontoboard.model.Projection} the
 * canvas is already drawing - no ontology access, no reasoner - so a tooltip cannot disagree with
 * what is on screen. It is a separate class of pure functions because the wording is the part worth
 * testing and {@code SchemaGraph} needs a live {@code mxGraph} to exist at all.
 *
 * <p>Written as small HTML fragments: Swing renders those in a tooltip, and a two-line tooltip is
 * the difference between naming the axiom and naming it legibly.
 */
public final class CanvasTooltips {

    private CanvasTooltips() {
    }

    /**
     * A node: what it is called, what kind of thing it is, and its full identifier.
     *
     * <p>The label is what a domain expert reads and the IRI is what an engineer needs, so both are
     * present and the label comes first. Markers are spelled out rather than left to the border
     * style, because "this class can have no instances" is not something to communicate by line
     * weight alone.
     */
    public static String forNode(CanvasNode node) {
        if (node == null) {
            return null;
        }
        StringBuilder text = new StringBuilder("<html><b>");
        text.append(escape(node.getLabel())).append("</b><br>");
        text.append(describe(node.getKind()));
        if (node.isUnsatisfiable()) {
            text.append("<br><b>Cannot have instances</b> - the reasoner found this class "
                    + "contradictory");
        }
        if (node.isImported()) {
            // Said in words as well as drawn, because the fade is a hint and this is a rule: an edit
            // to an imported term goes into the edit file, where the next refresh of the imports can
            // discard it or leave a second definition behind.
            text.append("<br><b>Imported</b> - defined in another ontology. Refer to it freely; "
                    + "editing it here writes into your file, not the import.");
        }
        appendSummary(text, node.getSummary());
        appendNotes(text, node.getNotes());
        return text.append("<br><font size=\"-2\">").append(escape(node.getId()))
                .append("</font></html>").toString();
    }

    /**
     * An edge: the axiom it stands for, in words, and whether anybody asserted it.
     *
     * <p>The asserted-or-inferred line is the one that matters most and is invisible otherwise
     * except as a dashed line somebody has to look up. An inferred edge is not in the ontology: it
     * is a conclusion, it disappears when the reasoner is switched off, and deleting it is not
     * possible. Saying so on hover is cheaper than explaining it afterwards.
     *
     * @param labelsById labels of the nodes on the board, so the two ends can be named the way they
     *     are drawn rather than by IRI
     */
    public static String forEdge(CanvasEdge edge, Map<String, String> labelsById) {
        if (edge == null) {
            return null;
        }
        String source = nameOf(edge.getSourceId(), labelsById);
        String target = nameOf(edge.getTargetId(), labelsById);
        StringBuilder text = new StringBuilder("<html><b>");
        text.append(escape(source)).append("</b> ")
                .append(escape(phraseFor(edge)))
                .append(" <b>").append(escape(target)).append("</b><br>");
        text.append(constructFor(edge.getKind()));
        text.append("<br>").append(isInferred(edge.getKind())
                ? "<b>Inferred by the reasoner</b> - not an axiom in the ontology, and it will go "
                        + "when inferences are switched off"
                : "Asserted in the ontology");
        return text.append("</html>").toString();
    }

    /**
     * What the ontology says about the term: its parents, its equivalents, what it excludes.
     *
     * <p>Taken from OntoGraf, whose tooltip offers these as switchable sections. The hover used
     * to say what a term is called and nothing about what it means.
     *
     * <p>Disjointness earns its place most: the canvas draws none, anywhere, so this is the only
     * surface on which a term's disjointness appears at all. "Cannot also be" rather than
     * "disjoint with", because the hover is read by domain experts as often as by ontologists.
     */
    private static void appendSummary(StringBuilder text,
            de.fizkarlsruhe.ise.ontoboard.model.TermSummary summary) {
        if (summary == null || summary.isEmpty()) {
            return;
        }
        appendRow(text, "Kind of", summary.getSuperClasses());
        appendRow(text, "Same as", summary.getEquivalents());
        appendRow(text, "Cannot also be", summary.getDisjoints());
    }

    private static void appendRow(StringBuilder text, String heading, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        text.append("<br><b>").append(heading).append(":</b> ")
                .append(escape(de.fizkarlsruhe.ise.ontoboard.model.TermSummary.joined(values)));
    }

    /** A sticky note: its text, and a reminder that it is not in the ontology. */
    public static String forNote(String noteText) {
        return "<html><b>Sticky note</b><br>"
                + escape(noteText == null || noteText.trim().isEmpty() ? "(empty)" : noteText)
                + "<br><font size=\"-2\">On the diagram only - not in the ontology, and not in a "
                + "release. OntoBoard &gt; Notes writes one that is.</font></html>";
    }

    /** A frame: its label, and the same reminder. */
    public static String forFrame(String frameLabel) {
        return "<html><b>Frame: "
                + escape(frameLabel == null || frameLabel.trim().isEmpty() ? "(unlabelled)"
                        : frameLabel)
                + "</b><br><font size=\"-2\">A region on the diagram. It groups what is inside it "
                + "visually and asserts nothing.</font></html>";
    }

    // ===================================================================== wording

    /**
     * How the edge reads between its two ends.
     *
     * <p>A restriction edge's label already carries the property name and, for a universal, the
     * {@code (only)} suffix - see {@code OntologyProjection}, which builds it. So the label is used
     * where there is one and the kind supplies the phrase where there is not.
     */
    static String phraseFor(CanvasEdge edge) {
        String label = edge.getLabel() == null ? "" : edge.getLabel().trim();
        switch (edge.getKind()) {
            case SUBCLASS:
            case INFERRED_SUBCLASS:
                return "is a kind of";
            case TYPE:
            case INFERRED_TYPE:
                return "is an instance of";
            case SUB_PROPERTY:
                return "is a sub-property of";
            case DATA_PROPERTY:
                return label.isEmpty() ? "has a data property to" : label + " to";
            case OBJECT_PROPERTY:
            default:
                // "has topping" or "has topping (only)". Reading it as "... some ..." where the
                // label carries no qualifier would assert an existential that may not be one.
                return label.isEmpty() ? "is related to" : label;
        }
    }

    /** The OWL construct behind the edge, named the way the ontology names it. */
    static String constructFor(CanvasEdge.Kind kind) {
        switch (kind) {
            case SUBCLASS:
            case INFERRED_SUBCLASS:
                return "rdfs:subClassOf";
            case TYPE:
            case INFERRED_TYPE:
                return "rdf:type";
            case SUB_PROPERTY:
                return "rdfs:subPropertyOf";
            case DATA_PROPERTY:
                return "A data property restriction or range";
            case OBJECT_PROPERTY:
            default:
                return "An object property restriction";
        }
    }

    static boolean isInferred(CanvasEdge.Kind kind) {
        return kind == CanvasEdge.Kind.INFERRED_SUBCLASS || kind == CanvasEdge.Kind.INFERRED_TYPE;
    }

    private static String describe(NodeKind kind) {
        if (kind == null) {
            return "Unknown";
        }
        switch (kind) {
            case CLASS:
                return "Class";
            case INDIVIDUAL:
                return "Individual";
            case DATATYPE:
                return "Datatype";
            case LITERAL:
                return "Literal";
            case OBJECT_PROPERTY:
                return "Object property";
            case DATA_PROPERTY:
                return "Data property";
            default:
                return kind.name();
        }
    }

    /** The label a node is drawn with, or its short name when it is not on the board. */
    private static String nameOf(String id, Map<String, String> labelsById) {
        String label = labelsById == null ? null : labelsById.get(id);
        if (label != null && !label.trim().isEmpty()) {
            return label;
        }
        if (id == null) {
            return "?";
        }
        int cut = Math.max(id.lastIndexOf('#'), id.lastIndexOf('/'));
        return cut < 0 ? id : id.substring(cut + 1);
    }

    /**
     * Escapes what Swing's HTML renderer would otherwise read as markup.
     *
     * <p>Not theoretical: a label containing {@code <} or {@code &} is legal in an ontology, and an
     * unescaped one silently truncates the tooltip at that character or renders as a broken tag.
     */
    /**
     * The editorial notes, quoted.
     *
     * <p>This line used to read "Has an editorial note" - a marker that raises a question and then
     * refuses to answer it, with the words a dialog away in the main menu bar. The note is the reason
     * the term is drawn with a heavier border, so it is the thing to say.
     *
     * <p>Wrapped and truncated, which the other tooltips here do not need. A sticky note is a phrase
     * by nature; an editorial note is prose, and "the parent is provisional pending the review agreed
     * in issue 412, see also the alignment discussion" in one unbroken line is a tooltip wider than
     * the screen it is explaining.
     *
     * <p>Only the first is quoted when a term carries several, with a count for the rest. Three
     * paragraphs from three editors on hover is not a tooltip, and the Notes dialog shows them all
     * with their authors and dates.
     */
    private static void appendNotes(StringBuilder text, java.util.List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return;
        }
        text.append("<br><i>Note:</i> ").append(wrap(escape(truncate(notes.get(0)))));
        if (notes.size() > 1) {
            text.append("<br><font size=\"-2\">and ").append(notes.size() - 1)
                    .append(notes.size() == 2 ? " more note" : " more notes")
                    .append(" - OntoBoard &gt; Notes shows them all</font>");
        }
    }

    /** How much of a note a tooltip carries before it stops being one. */
    private static final int NOTE_LIMIT = 220;

    /** Roughly how wide, in characters, a wrapped line runs. */
    private static final int WRAP_AT = 64;

    private static String truncate(String note) {
        String trimmed = note.trim();
        if (trimmed.length() <= NOTE_LIMIT) {
            return trimmed;
        }
        // Cut at a space rather than mid-word, when there is one to cut at near the limit.
        int space = trimmed.lastIndexOf(' ', NOTE_LIMIT);
        return trimmed.substring(0, space > NOTE_LIMIT - 20 ? space : NOTE_LIMIT).trim() + "\u2026";
    }

    /**
     * Line breaks on word boundaries.
     *
     * <p>Done here rather than by giving the HTML a width, because a {@code <div>} with a fixed width
     * in a Swing tooltip sizes the whole tooltip to that width - including the one-line tooltips, which
     * would then be mostly empty space.
     *
     * <p>Runs over the escaped text, so it counts an escaped entity as its several characters rather
     * than the one it renders as. A line that comes out slightly short because the note contained an
     * ampersand is not worth the bookkeeping to avoid.
     */
    static String wrap(String escaped) {
        StringBuilder wrapped = new StringBuilder(escaped.length() + 16);
        int sinceBreak = 0;
        for (String word : escaped.split(" ")) {
            if (sinceBreak > 0 && sinceBreak + 1 + word.length() > WRAP_AT) {
                wrapped.append("<br>");
                sinceBreak = 0;
            } else if (sinceBreak > 0) {
                wrapped.append(' ');
                sinceBreak++;
            }
            wrapped.append(word);
            sinceBreak += word.length();
        }
        return wrapped.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
