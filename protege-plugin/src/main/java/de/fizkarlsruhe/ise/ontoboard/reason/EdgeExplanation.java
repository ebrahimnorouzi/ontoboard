package de.fizkarlsruhe.ise.ontoboard.reason;

import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * Which axioms put a dotted edge on the board.
 *
 * <p>The canvas could draw the reasoner's conclusions but never say where one came from, and a
 * conclusion you cannot trace is one you have to take on faith. That is the wrong relationship to
 * have with a reasoner: the useful question about an unexpected inference is almost never "is the
 * reasoner right" - it is "which of my axioms did I not mean", and only the justification answers
 * it. The classic case is an inferred parent nobody wanted, which turns out to rest on a domain
 * axiom written years earlier for a different purpose.
 *
 * <p>This is a justification, not a path through the hierarchy. {@link InferredEdges} draws an
 * edge to the <em>nearest ancestor on the board</em>, which may be several subsumptions away, so
 * "Dog is a kind of Animal" can be true with no axiom mentioning both. Walking the hierarchy
 * would produce the chain through Mammal, and would be wrong the moment an inference comes from
 * somewhere other than a chain - a property domain, an equivalent-class definition, a
 * disjointness. Asking for a minimal set of axioms answers every case the same way.
 *
 * <p>Pure OWL API and robot-core. No Swing and no Protege, so it is testable.
 */
public final class EdgeExplanation {

    /**
     * How many distinct justifications to look for.
     *
     * <p>Two rather than one, because a second route to the same conclusion changes what you do
     * about it: deleting the one axiom you found will not remove the edge if another
     * justification still stands. Two rather than more, because each is found by searching
     * subsets of the ontology with a reasoner call per subset, and on a real ontology that is the
     * difference between a pause and a coffee.
     */
    public static final int DEFAULT_MAX = 2;

    private EdgeExplanation() {
    }

    /** An edge this cannot be asked about, and why. */
    public static final class NotInferredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotInferredException(String message) {
            super(message);
        }
    }

    /** What was asked, what came back, and - when nothing came back - what that means. */
    public static final class Why {
        private final String entailment;
        private final List<List<String>> justifications;
        private final String note;

        Why(String entailment, List<List<String>> justifications, String note) {
            this.entailment = entailment;
            List<List<String>> copies = new ArrayList<List<String>>();
            for (List<String> one : justifications) {
                copies.add(Collections.unmodifiableList(new ArrayList<String>(one)));
            }
            this.justifications = Collections.unmodifiableList(copies);
            this.note = note;
        }

        /** The conclusion, in Manchester syntax - the question, restated. */
        public String getEntailment() {
            return entailment;
        }

        /** Each justification: a minimal set of axioms that forces the conclusion. */
        public List<List<String>> getJustifications() {
            return justifications;
        }

        /** Empty when the generator found no justification within the limit. */
        public boolean isEmpty() {
            return justifications.isEmpty();
        }

        /** The sentence above the table, which differs sharply when nothing was found. */
        public String getSummary() {
            if (isEmpty()) {
                return note;
            }
            if (justifications.size() == 1) {
                int size = justifications.get(0).size();
                return "One justification, of " + size + (size == 1 ? " axiom." : " axioms.")
                        + " Remove or correct any one of them and the inference goes.";
            }
            return justifications.size() + " independent justifications. The inference survives "
                    + "until every one of them is broken, so removing a single axiom will not "
                    + "remove the edge.";
        }
    }

    /**
     * The axiom an inferred edge claims, ready to be explained.
     *
     * <p>Rebuilt from the edge id rather than carried on the edge, for the same reason
     * {@code AxiomRemoval} does it: the id is what the canvas hands back from a right-click, and
     * it is the one part of an edge that survives a refresh.
     *
     * @return the entailment, never null
     * @throws NotInferredException when the id is not one {@link InferredEdges} produced - an
     *     asserted edge already is an axiom and has nothing to explain
     */
    public static OWLAxiom entailmentFor(String edgeId, OWLDataFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("a data factory is required");
        }
        // Keep empty segments. Java's default split drops trailing empties, so an id of nothing
        // but delimiters yields a zero-length array and an ArrayIndexOutOfBoundsException in
        // place of the refusal documented here - the trap AxiomRemoval fell into, and these ids
        // come from a peer in a shared session.
        String[] parts = edgeId == null ? new String[0] : edgeId.split("\\|", -1);
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()
                || !("inf".equals(parts[0]) || "inft".equals(parts[0]))) {
            throw new NotInferredException("Edge " + quote(edgeId) + " is not one the reasoner "
                    + "produced, so there is nothing to explain: it is asserted in the ontology.");
        }
        if ("inf".equals(parts[0])) {
            return factory.getOWLSubClassOfAxiom(factory.getOWLClass(IRI.create(parts[1])),
                    factory.getOWLClass(IRI.create(parts[2])));
        }
        return factory.getOWLClassAssertionAxiom(factory.getOWLClass(IRI.create(parts[2])),
                factory.getOWLNamedIndividual(IRI.create(parts[1])));
    }

    /**
     * Why the reasoner drew this edge.
     *
     * <p>Slow by nature: a justification is found by taking axioms away and asking the reasoner
     * whether the conclusion survives, so the cost is many classifications rather than one. Call
     * it off the event thread.
     *
     * <p>It reads the ontology and does not change it. Worth stating because the technique is
     * subtractive and the obvious implementation of it would remove axioms from the ontology it
     * was handed; there is a test that counts the axioms before and after.
     *
     * @param factory the factory behind the reasoner that drew the edge. Not any reasoner: ELK
     *     cannot see an entailment that follows from a cardinality restriction, so explaining
     *     HermiT's conclusion with ELK finds nothing at all, and nothing reads as "there is no
     *     reason" rather than "you asked the wrong reasoner".
     * @throws NotInferredException if the edge is not one the reasoner produced
     * @throws de.fizkarlsruhe.ise.ontoboard.robot.RobotException if the generator cannot run here
     */
    public static Why explain(OWLOntology ontology, OWLReasonerFactory factory, String edgeId,
            int max) {
        if (ontology == null || factory == null) {
            throw new IllegalArgumentException("an ontology and a reasoner factory are required");
        }
        OWLAxiom entailment =
                entailmentFor(edgeId, ontology.getOWLOntologyManager().getOWLDataFactory());
        String asked = Explanations.render(ontology, entailment);

        List<List<String>> justifications = new ArrayList<List<String>>();
        for (Explanations.Justification one
                : Explanations.forEntailment(ontology, factory, entailment, max)) {
            justifications.add(one.getAxioms());
        }
        return new Why(asked, justifications, justifications.isEmpty() ? nothingFound() : "");
    }

    /** {@link #explain(OWLOntology, OWLReasonerFactory, String, int)} with the default depth. */
    public static Why explain(OWLOntology ontology, OWLReasonerFactory factory, String edgeId) {
        return explain(ontology, factory, edgeId, DEFAULT_MAX);
    }

    /**
     * Why this cannot be explained right now, or null when it can.
     *
     * <p>Asked before the work starts, because the work is slow and a wait that ends in "no
     * reasoner" is a wait that should never have begun. It lives here rather than in the view
     * because {@link InferredEdges} already knows how to recognise a reasoner that infers
     * nothing, and a second copy of that test is how the two come to disagree.
     *
     * <p>The gap it closes is real rather than theoretical: inferences are drawn once and then
     * stay on the board, so a user can switch the reasoner off in Protege and still be looking
     * at dotted edges. Right-clicking one would otherwise spend a minute in the explanation
     * generator to arrive at an empty table.
     *
     * @param running the reasoner Protege currently has, which may be null or a no-op one
     */
    public static String whyUnavailable(org.semanticweb.owlapi.reasoner.OWLReasoner running) {
        if (running == null) {
            return "No reasoner is running, so there is nothing to explain with. Start one from "
                    + "Protege's Reasoner menu, then ask again.";
        }
        if (InferredEdges.isNoOp(running)) {
            return "The selected reasoner (" + running.getReasonerName() + ") infers nothing, so "
                    + "it cannot say why anything was inferred. Choose ELK or HermiT from "
                    + "Protege's Reasoner menu and start it.";
        }
        return null;
    }

    /**
     * What an empty result means, which is not "there is no reason".
     *
     * <p>The edge is on the board because a reasoner concluded it, so a justification exists.
     * Coming back empty says the generator did not find one, and the causes worth naming are the
     * ones a user can do something about.
     */
    private static String nothingFound() {
        return "The reasoner drew this edge, but no justification was found for it. That usually "
                + "means the conclusion comes from an import that is not loaded here, or that the "
                + "reasoner running on the canvas and the one asked to explain disagree about "
                + "what follows. The edge is still the reasoner's conclusion: this is a gap in "
                + "the explanation, not in the inference.";
    }

    private static String quote(String value) {
        return value == null ? "(none)" : "'" + value + "'";
    }
}
