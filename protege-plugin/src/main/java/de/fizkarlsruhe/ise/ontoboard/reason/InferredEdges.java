package de.fizkarlsruhe.ise.ontoboard.reason;

import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

/**
 * The subsumptions a reasoner found that the ontology does not state.
 *
 * <p>Reasoning was already available in Protege - the plugin simply never showed any of it on the
 * canvas, so a diagram of a reasoned ontology looked exactly like a diagram of an unreasoned one.
 * That is the gap this closes: the whole point of drawing a hierarchy is to see its shape, and half
 * the shape was missing.
 *
 * <p><b>Inferred edges are never drawn like asserted ones.</b> They carry
 * {@link CanvasEdge.Kind#INFERRED_SUBCLASS}, which the stylesheet renders dotted and grey. Drawing
 * a reasoner's conclusion identically to an axiom would be the most misleading thing this canvas
 * could do: an inference disappears when the axioms behind it change, and someone who mistook one
 * for the other would "delete" an edge that was never there.
 *
 * <p>Takes an {@link OWLReasoner} rather than creating one. Protege owns the reasoner the user
 * chose and started - reaching around it to instantiate another would mean a second copy of the
 * ontology, a second classification, and a canvas disagreeing with the class hierarchy beside it.
 * It also makes this class testable: OWL API's own structural reasoner computes told subsumption
 * closure, which is enough to have something genuinely inferred to assert on.
 */
public final class InferredEdges {

    /** What a reasoner could not tell us, and why. */
    public static final class NotAvailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotAvailable(String message) {
            super(message);
        }
    }

    private InferredEdges() {
    }

    /**
     * Inferred subclass edges between nodes already on the board.
     *
     * <p>Only between nodes on the board, and only where no asserted edge already says the same
     * thing. Both restrictions are what keep the result useful rather than noisy: an inferred edge
     * duplicating an asserted one would draw two arrows between the same pair, and inferring
     * towards entities nobody put on the diagram would drag them onto it.
     *
     * @param reasoner a reasoner Protege has started; must not be a no-op one
     * @param onCanvas IRIs currently on the board
     * @param asserted the edges the projection already produced, so duplicates are skipped
     * @param factory the data factory for building the class objects to query
     * @return the extra edges, possibly empty when the ontology states everything it entails
     * @throws NotAvailable when no usable reasoner is running, with a message telling the user
     *     what to do about it - showing an empty result would read as "nothing was inferred",
     *     which is a different and misleading statement
     */
    public static List<CanvasEdge> subClassEdges(OWLReasoner reasoner, Set<String> onCanvas,
            Collection<CanvasEdge> asserted, OWLDataFactory factory) {
        requireUsable(reasoner);
        Set<String> alreadyDrawn = new HashSet<String>();
        for (CanvasEdge edge : asserted) {
            if (edge.getKind() == CanvasEdge.Kind.SUBCLASS) {
                alreadyDrawn.add(edge.getSourceId() + " -> " + edge.getTargetId());
            }
        }

        List<CanvasEdge> inferred = new ArrayList<CanvasEdge>();
        Set<String> emitted = new LinkedHashSet<String>();
        Map<String, Set<String>> closures = new HashMap<String, Set<String>>();

        for (String childIri : sorted(onCanvas)) {
            // The FULL closure, not the direct superclasses.
            //
            // Direct-only was the first attempt and it made the feature almost useless: the case
            // worth drawing is a subsumption that runs through an intermediate the user has not put
            // on the board - Dog < Mammal < Animal with only Dog and Animal shown. Mammal is Dog's
            // direct superclass, so direct-only found nothing. And when the whole chain IS on the
            // board, the asserted edges already show it, so there was nothing to add either way.
            Set<String> ancestors = closureOf(reasoner, factory, childIri, closures);

            // Of the ancestors that are on the board, keep only the nearest ones. Drawing every
            // ancestor would turn a hierarchy into a mesh - the objection that motivated
            // direct-only - but nearness is measured among the nodes actually shown rather than in
            // the full ontology, which is the distinction that was missed.
            List<String> nearest = new ArrayList<String>();
            for (String candidate : sorted(intersect(ancestors, onCanvas))) {
                if (candidate.equals(childIri)) {
                    continue;
                }
                boolean covered = false;
                for (String other : intersect(ancestors, onCanvas)) {
                    if (other.equals(candidate) || other.equals(childIri)) {
                        continue;
                    }
                    if (closureOf(reasoner, factory, other, closures).contains(candidate)) {
                        // A nearer on-board ancestor already leads to this one.
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    nearest.add(candidate);
                }
            }

            for (String parentIri : nearest) {
                String key = childIri + " -> " + parentIri;
                if (alreadyDrawn.contains(key) || !emitted.add(key)) {
                    continue;
                }
                inferred.add(new CanvasEdge("inf|" + childIri + "|" + parentIri, childIri,
                        parentIri, "inferred", CanvasEdge.Kind.INFERRED_SUBCLASS));
            }
        }
        return inferred;
    }

    /**
     * Inferred type edges: individuals on the board that the reasoner puts in a class on the board.
     *
     * <p>The other half of what a reasoner has to say, and the half the canvas used to throw away.
     * Class subsumption was drawn and individuals were not, so a board holding individuals showed
     * nothing at all when inferences were switched on - which reads as "the reasoner found
     * nothing" rather than "this tool does not look".
     *
     * <p>It is also the payoff case for the classic teaching ontology. A pizza whose toppings are
     * all vegetarian is a {@code VegetarianPizza} without anybody saying so; that conclusion is
     * the entire point of writing the definition, and it is about an individual.
     *
     * <p>Same two restrictions as {@link #subClassEdges}: only between things already on the
     * board, and only where no asserted edge says the same. Types are additionally narrowed to
     * the reasoner's <em>direct</em> types, because every ancestor of a direct type is also a
     * type - drawing them all would fan every individual out to the whole hierarchy above it.
     *
     * @param reasoner a reasoner Protege has started; must not be a no-op one
     * @param onCanvas IRIs currently on the board
     * @param asserted the edges the projection already produced, so duplicates are skipped
     * @param factory the data factory for building the individuals to query
     * @throws NotAvailable when no usable reasoner is running
     */
    public static List<CanvasEdge> typeEdges(OWLReasoner reasoner, Set<String> onCanvas,
            Collection<CanvasEdge> asserted, OWLDataFactory factory) {
        requireUsable(reasoner);

        Set<String> alreadyDrawn = new HashSet<String>();
        for (CanvasEdge edge : asserted) {
            if (edge.getKind() == CanvasEdge.Kind.TYPE) {
                alreadyDrawn.add(edge.getSourceId() + " -> " + edge.getTargetId());
            }
        }

        List<CanvasEdge> inferred = new ArrayList<CanvasEdge>();
        Set<String> emitted = new LinkedHashSet<String>();
        for (String individualIri : sorted(onCanvas)) {
            Set<String> types;
            try {
                types = directTypesOf(reasoner, factory, individualIri);
            } catch (RuntimeException notAnIndividual) {
                // Most IRIs on the board are classes, and asking for the types of one is not a
                // question with an answer. One awkward entity must not cost the whole result.
                continue;
            }
            for (String typeIri : sorted(types)) {
                if (!onCanvas.contains(typeIri)) {
                    // Inferring towards a class nobody put on the diagram would drag it on.
                    continue;
                }
                String key = individualIri + " -> " + typeIri;
                if (alreadyDrawn.contains(key) || !emitted.add(key)) {
                    continue;
                }
                inferred.add(new CanvasEdge("inft|" + individualIri + "|" + typeIri,
                        individualIri, typeIri, "inferred", CanvasEdge.Kind.INFERRED_TYPE));
            }
        }
        return inferred;
    }

    /**
     * The classes the reasoner puts an individual in directly.
     *
     * <p>Returns empty for anything that is not a named individual in the ontology, which is most
     * of what is on a board.
     */
    private static Set<String> directTypesOf(OWLReasoner reasoner, OWLDataFactory factory,
            String iri) {
        Set<String> types = new LinkedHashSet<String>();
        org.semanticweb.owlapi.model.OWLNamedIndividual individual =
                factory.getOWLNamedIndividual(org.semanticweb.owlapi.model.IRI.create(iri));
        if (!reasoner.getRootOntology().containsIndividualInSignature(
                individual.getIRI(), org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            return types;
        }
        for (org.semanticweb.owlapi.model.OWLClass type
                : reasoner.getTypes(individual, true).getFlattened()) {
            if (!type.isOWLThing() && !type.isOWLNothing()) {
                types.add(type.getIRI().toString());
            }
        }
        return types;
    }

    /** The reasoner check both entry points share, so they fail with one message. */
    private static void requireUsable(OWLReasoner reasoner) {
        if (reasoner == null) {
            throw new NotAvailable("No reasoner is running. Start one from Protege's Reasoner "
                    + "menu, then show inferences again.");
        }
        if (isNoOp(reasoner)) {
            throw new NotAvailable("The selected reasoner does not infer anything ("
                    + reasoner.getReasonerName() + "). Choose ELK or HermiT from Protege's "
                    + "Reasoner menu and start it.");
        }
    }

    /**
     * Every strict ancestor of {@code iri}, cached.
     *
     * <p>Cached because the nearest-ancestor test asks for the closure of each candidate, so a
     * board of n classes would otherwise classify n-squared times. owl:Thing and owl:Nothing are
     * dropped here: Thing is above everything and says nothing, and Nothing beneath an
     * unsatisfiable class is reported by {@link #unsatisfiableClasses} instead.
     */
    private static Set<String> closureOf(OWLReasoner reasoner, OWLDataFactory factory, String iri,
            Map<String, Set<String>> cache) {
        Set<String> cached = cache.get(iri);
        if (cached != null) {
            return cached;
        }
        Set<String> ancestors = new LinkedHashSet<String>();
        try {
            for (OWLClass parent : reasoner
                    .getSuperClasses(factory.getOWLClass(IRI.create(iri)), false)
                    .getFlattened()) {
                if (!parent.isOWLThing() && !parent.isOWLNothing()) {
                    ancestors.add(parent.getIRI().toString());
                }
            }
        } catch (RuntimeException cannotReason) {
            // An unsatisfiable or undeclared class can upset a reasoner. One bad class must not
            // cost the whole diagram its inferences, so this one contributes nothing and the rest
            // carry on.
            ancestors = new LinkedHashSet<String>();
        }
        cache.put(iri, ancestors);
        return ancestors;
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> both = new LinkedHashSet<String>();
        for (String value : left) {
            if (right.contains(value)) {
                both.add(value);
            }
        }
        return both;
    }

    /**
     * Classes on the board that the reasoner found unsatisfiable.
     *
     * <p>Worth surfacing separately from the edges: an unsatisfiable class is a modelling error,
     * not a shape, and it is the single most useful thing a reasoner has to say. Returned rather
     * than drawn so the caller decides how to mark them.
     */
    public static Set<String> unsatisfiableClasses(OWLReasoner reasoner, Set<String> onCanvas) {
        Set<String> unsatisfiable = new LinkedHashSet<String>();
        if (reasoner == null || isNoOp(reasoner)) {
            return unsatisfiable;
        }
        try {
            for (OWLClass bottom : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                String iri = bottom.getIRI().toString();
                if (onCanvas.contains(iri)) {
                    unsatisfiable.add(iri);
                }
            }
        } catch (RuntimeException cannotReason) {
            return unsatisfiable;
        }
        return unsatisfiable;
    }

    /**
     * Whether this reasoner will actually infer anything.
     *
     * <p>Protege hands out a no-op reasoner when the user has not started one, and it answers every
     * query with nothing. Treating that as "nothing was inferred" is how a user concludes their
     * ontology has no structure when in truth no reasoner ever ran. Detected by name because
     * {@code NoOpReasoner} lives in Protege's own packages, which this class deliberately does not
     * depend on so that it stays testable.
     */
    static boolean isNoOp(OWLReasoner reasoner) {
        String name = reasoner.getReasonerName();
        if (name == null) {
            return true;
        }
        String lower = name.toLowerCase();
        return lower.contains("noop") || lower.contains("no-op") || lower.contains("none");
    }

    /** Stable order, so the same ontology yields the same edges in the same sequence. */
    private static List<String> sorted(Set<String> iris) {
        List<String> ordered = new ArrayList<String>(iris);
        java.util.Collections.sort(ordered);
        return ordered;
    }
}
