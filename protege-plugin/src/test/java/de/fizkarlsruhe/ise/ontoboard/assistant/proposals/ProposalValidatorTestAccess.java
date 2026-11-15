package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import org.semanticweb.owlapi.model.OWLOntology;

/** Test helpers of this package, for tests in other packages. */
public final class ProposalValidatorTestAccess {

    private ProposalValidatorTestAccess() {
    }

    /** A reasoner that works until it is interrupted, counting down when it starts. */
    public static org.semanticweb.owlapi.reasoner.OWLReasonerFactory endless(
            java.util.concurrent.CountDownLatch started) {
        return ProposalChangesTest.endless(started);
    }

    /** An edit file that imports the given ontology, in the same manager. */
    public static OWLOntology importing(OWLOntology imported) {
        return ProposalValidatorTest.importing(imported);
    }
}
