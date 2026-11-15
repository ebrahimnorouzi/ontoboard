package de.fizkarlsruhe.ise.ontoboard.assistant.host;

import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import org.protege.editor.owl.model.inference.NoOpReasonerFactory;
import org.protege.editor.owl.model.inference.NoOpReasonerInfo;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoner "Explain the reasoner problem..." runs: the one chosen in Protégé's Reasoner
 * menu, named as the menu names it - or HermiT, when nothing is chosen there.
 *
 * <p>Decided by the reasoner chosen, never by the name of the one running. Until a reasoner is
 * started Protégé runs its own null reasoner, "Protégé Null Reasoner", whatever the menu says;
 * the column fell back to HermiT only for the name "none", so with nothing chosen - a fresh
 * install - it ran the null reasoner and said "The reasoner finds no problem" of an ontology
 * with unsatisfiable classes, and with a reasoner chosen and not started it said "Running
 * Protégé Null Reasoner..." over that reasoner's work (seen in Protégé 5.6.9 with the 1.153.0
 * candidate).
 */
public final class ProblemReasoner {

    /** What the column says it runs when Protégé has none chosen. */
    static final String HERMIT_INSTEAD = "HermiT (Protégé has no reasoner selected)";

    /** The name of a chosen reasoner that neither its menu entry nor its factory names. */
    static final String UNNAMED = "the reasoner chosen in Protégé";

    private ProblemReasoner() {
    }

    /**
     * @param chosen Protégé's current reasoner, as its reasoner manager hands it out; null when
     *               there is none
     * @return the factory to reason with, and the name to show for it
     */
    public static Object[] of(ProtegeOWLReasonerInfo chosen) {
        OWLReasonerFactory factory = chosen == null || isNone(chosen) ? null
                : chosen.getReasonerFactory();
        if (factory == null || factory instanceof NoOpReasonerFactory) {
            return new Object[] {Reasoners.Choice.HERMIT.newFactory(), HERMIT_INSTEAD};
        }
        String name = chosen.getReasonerName();
        if (name == null || name.trim().isEmpty()) {
            name = factory.getReasonerName();
        }
        return new Object[] {factory,
            name == null || name.trim().isEmpty() ? UNNAMED : name.trim()};
    }

    /** Protégé's "None": its own null reasoner, by class or by the identifier it is set up with. */
    private static boolean isNone(ProtegeOWLReasonerInfo chosen) {
        return chosen instanceof NoOpReasonerInfo
                || NoOpReasonerInfo.NULL_REASONER_ID.equals(chosen.getReasonerId());
    }
}
