package de.fizkarlsruhe.ise.ontoboard.axiom;

import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The rules an edge drawn on the canvas has to pass, and what it can then mean.
 *
 * <p>Separate from the view because these are the decisions, and the view is where they could not be
 * tested. Every one of them exists to stop the same thing: a line on the board that no axiom stands
 * behind. The canvas is a projection of the ontology - that is the property the whole tool rests on -
 * so an edge that is only a drawing would be the one line on screen that means nothing, and
 * indistinguishable from the ones that mean something.
 *
 * <p>Nothing here writes. It answers "is this pair of ends sayable, and if so in which ways", and the
 * caller offers exactly those ways.
 */
public final class DrawnEdge {

    /** Why a drawn edge cannot be turned into an axiom, or that it can. */
    public enum Verdict {

        /** The ends are usable; ask what the edge means. */
        OFFER(null),

        /** Both ends are the same term. */
        SAME_TERM("A term cannot be related to itself here."),

        /**
         * One end is a sticky note or a frame.
         *
         * <p>Those live in the sidecar and not in the ontology, so there is nothing to assert about
         * them - and an arrow to one would be a line with no axiom behind it.
         */
        ANNOTATION_END("Sticky notes and frames are not part of the ontology, so nothing can be "
                + "asserted about them."),

        /** An end is not a term the board is showing. Nothing to say, and nothing to say it about. */
        NOT_ON_BOARD(null);

        private final String message;

        Verdict(String message) {
            this.message = message;
        }

        /** What to tell the user, or null when there is nothing worth saying. */
        public String getMessage() {
            return message;
        }
    }

    /** What a legal pair of ends can be made to mean. */
    public enum Option {

        /** The one hierarchy link that is legal between these two - see {@link HierarchyAxioms}. */
        HIERARCHY,

        /** An object property restriction, which needs a class at each end. */
        OBJECT_PROPERTY
    }

    private DrawnEdge() {
    }

    /**
     * Whether these two ends can carry an axiom at all.
     *
     * <p>Order matters in one place: the same-term check comes before the annotation check, so
     * dragging a note onto itself is reported as the simpler of its two problems.
     */
    public static Verdict verdictFor(String sourceId, String targetId, Set<String> onBoard) {
        if (sourceId == null || targetId == null) {
            return Verdict.NOT_ON_BOARD;
        }
        if (sourceId.equals(targetId)) {
            return Verdict.SAME_TERM;
        }
        if (SchemaGraph.isAnnotationId(sourceId) || SchemaGraph.isAnnotationId(targetId)) {
            return Verdict.ANNOTATION_END;
        }
        if (onBoard == null || !onBoard.contains(sourceId) || !onBoard.contains(targetId)) {
            return Verdict.NOT_ON_BOARD;
        }
        return Verdict.OFFER;
    }

    /**
     * What this pair can be made to mean, in the order to offer it.
     *
     * <p>Hierarchy first, because it is the commoner assertion and the one that needs no further
     * questions: which of {@code rdfs:subClassOf}, {@code rdf:type} and {@code rdfs:subPropertyOf}
     * applies is decided by what the two ends are, not asked. A restriction comes second because it
     * opens a dialog.
     *
     * <p>An empty list is a real answer, and the caller should say why rather than offering nothing:
     * {@link HierarchyAxioms#whyNot} words it. It happens, for instance, when somebody draws from an
     * individual to an object property.
     */
    public static List<Option> optionsFor(OWLOntology ontology, OWLEntity source, OWLEntity target) {
        List<Option> options = new ArrayList<Option>();
        if (ontology == null || source == null || target == null) {
            return options;
        }
        if (!HierarchyAxioms.applicableTo(source, target).isEmpty()) {
            options.add(Option.HIERARCHY);
        }
        // Both ends must be classes: EdgeAxioms builds a restriction asserted about a class, pointing
        // at a class. Checked against the ontology rather than the entity type, because a term can be
        // on the board and no longer declared - a collaborator may have retracted it.
        if (isClass(ontology, source) && isClass(ontology, target)) {
            options.add(Option.OBJECT_PROPERTY);
        }
        return options;
    }

    private static boolean isClass(OWLOntology ontology, OWLEntity entity) {
        return entity.isOWLClass()
                && ontology.containsClassInSignature(IRI.create(entity.getIRI().toString()));
    }
}
