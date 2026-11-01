package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Which entities the user has chosen to show (spec section 5.3). The canvas is opt-in
 * because Protege routinely opens ontologies far larger than any diagram can hold.
 *
 * <p>Removing an entity here is purely a view operation and never touches the ontology.
 */
public class CanvasMembership {

    private final CanvasLayout layout;

    public CanvasMembership(CanvasLayout layout) {
        this.layout = layout;
    }

    public boolean add(String iri) {
        // Put back by hand, so no longer hidden.
        layout.hidden.remove(iri);
        if (layout.onCanvas.contains(iri)) {
            return false;
        }
        layout.onCanvas.add(iri);
        return true;
    }

    /**
     * Takes a term off the board. On a board that shows everything it is also remembered as
     * hidden, or the next drawing would put it straight back.
     */
    public boolean remove(String iri) {
        layout.nodes.remove(iri);
        boolean removed = layout.onCanvas.remove(iri);
        if (removed && Boolean.TRUE.equals(layout.showsEverything)
                && !layout.hidden.contains(iri)) {
            layout.hidden.add(iri);
        }
        return removed;
    }

    public boolean contains(String iri) {
        return layout.onCanvas.contains(iri);
    }

    public Set<String> asSet() {
        return new HashSet<>(layout.onCanvas);
    }

    public int size() {
        return layout.onCanvas.size();
    }

    /**
     * Adds every entity directly related to {@code iri}, and says which ones were new.
     *
     * <p>Returned as identifiers rather than a count, which is what this returned until 1.59.0. The
     * count was enough to report and not enough to do anything with, so the caller discarded it: the
     * new terms went unplaced - a row along the top of the board, nowhere near what they neighbour -
     * nothing was saved, nothing was said, and there was no way to undo an expansion because nothing
     * remembered what it had added. All four of those are the same missing return value.
     *
     * <p>In discovery order, so a ring drawn from this list puts the class hierarchy before the
     * property restrictions rather than in whatever order a hash set produced.
     *
     * @return the identifiers newly put on the board, empty when every neighbour was already there
     */
    public List<String> expandOneHop(OWLOntology ontology, String iri) {
        List<String> added = new ArrayList<String>();
        for (String neighbour : neighboursOf(ontology, iri)) {
            if (add(neighbour)) {
                added.add(neighbour);
            }
        }
        return added;
    }

    /**
     * Everything one edge away from a term, where an edge is one the canvas draws.
     *
     * <p><b>Why this asks the projection instead of the axioms.</b> It used to read the axioms
     * itself - a second, hand-written notion of what relates to what, independent of the one
     * {@link de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection} draws - and every symptom
     * the two of them can have was present at once. Measured, against the ontologies in this
     * repository:
     *
     * <ul>
     *   <li><b>A hop on a property returned nothing, always.</b> Its collector kept only
     *       {@code entity.isOWLClass()} and had no branch that started from a property at all:
     *       85 of 85 object properties on MWO 3.0.1, 112 of 112 on DUL, 60 of 60 on NFDIcore.
     *       Sub- and super-properties, which are what the maintainer asked for, were among the
     *       things it could not reach - although the canvas draws {@code subprop} edges and has
     *       since 1.75.0.</li>
     *   <li><b>Properties inside a restriction were thrown away</b> - 102 of them on MWO - so a
     *       class whose only relation is {@code R some B} had B as a neighbour and not R.</li>
     *   <li><b>Imports were excluded.</b> {@code ontology.getAxioms(type)} with no
     *       {@code Imports} argument is {@code EXCLUDED}, while the canvas projects
     *       {@code INCLUDED}. On an ODK project - an edit file importing everything - the menu
     *       item said "has no neighbours that are not already on the board" about a term whose
     *       edges the board would have drawn the moment the terms arrived. Measured on a
     *       two-ontology fixture: 0 neighbours against 2.</li>
     *   <li><b>And it was too wide.</b> Its domain/range block jumped class to property to class,
     *       which is two hops, adding 21 neighbours on MWO that are not adjacent to the term at
     *       all.</li>
     * </ul>
     *
     * <p>So "one hop" is defined as what it means on screen: the other end of an edge the board
     * would draw, plus the property such an edge stands for, because a property drawn as an arrow
     * is on the board without being a node. One definition, in the place that already owns it,
     * and the kinds of relation the canvas learns to draw next are hops the moment it does.
     *
     * <p><b>What is still not a hop.</b> Disjointness, inverse pairs and the property
     * characteristics, because the canvas draws no edge for them - 15 disjointness and 38 inverse
     * axioms on MWO, all of them hover text. Adding them here would put a box on the board joined
     * to nothing, which reads as a mistake rather than as a relation.
     *
     * <p>Costs one full projection of the ontology per call: measured at 129ms for MWO 3.0.1's
     * 524 terms and 489 edges, on a menu item the user chose. Not cached, because a cache keyed on
     * anything less than the ontology's edit count is a cache that can be wrong, and being wrong
     * here is a neighbour silently missing.
     */
    public static Set<String> neighboursOf(OWLOntology ontology, String iri) {
        Set<String> found = new LinkedHashSet<String>();
        if (ontology == null || iri == null) {
            return found;
        }
        de.fizkarlsruhe.ise.ontoboard.model.Projection whole =
                de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection.project(ontology,
                        de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection
                                .termIdentifiers(ontology, Imports.INCLUDED));
        for (de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge edge : whole.getEdges()) {
            boolean fromHere = iri.equals(edge.getSourceId());
            boolean toHere = iri.equals(edge.getTargetId());
            String property = de.fizkarlsruhe.ise.ontoboard.model.PropertyEdgeId
                    .propertyIn(edge.getId());
            if (fromHere || toHere) {
                found.add(fromHere ? edge.getTargetId() : edge.getSourceId());
                if (property != null) {
                    // The arrow's own term. A property that is only ever drawn as an arrow is
                    // otherwise unreachable from the board it is drawn on.
                    found.add(property);
                }
            }
            if (iri.equals(property)) {
                // A hop from the property to what its arrow joins, which is the direction that
                // returned nothing at all before.
                found.add(edge.getSourceId());
                found.add(edge.getTargetId());
            }
        }
        found.remove(iri);
        found.remove(null);
        return found;
    }
}
