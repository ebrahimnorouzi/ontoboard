package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;

import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseReasoners;
import de.fizkarlsruhe.ise.ontoboard.odk.ReleaseVerdict;

/**
 * A release of an ontology in no project asks the reasoner the person chose, as a project's
 * release does (D1, 1.155.0): an inconsistent ontology or a class with no members is not
 * released.
 */
class ReleaseActionVerdictTest {

    /** The egg project's contradiction in one file: only a reasoner reading inverses sees it. */
    private static OWLOntology egg() throws Exception {
        return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new StringDocumentSource("Prefix(obo:=<http://purl.obolibrary.org/obo/>)\n"
                        + "Ontology(<http://purl.obolibrary.org/obo/egg.owl>\n"
                        + "SubClassOf(obo:BFO_0000019 obo:BFO_0000020)\n"
                        + "DisjointClasses(obo:BFO_0000020 obo:BFO_0000031)\n"
                        + "SubClassOf(obo:IAO_0000003 obo:BFO_0000031)\n"
                        + "InverseObjectProperties(obo:IAO_0000039 obo:IAO_0000040)\n"
                        + "ObjectPropertyRange(obo:IAO_0000039 obo:IAO_0000003)\n"
                        + "SubClassOf(obo:EGG_0000001 obo:BFO_0000019)\n"
                        + "SubClassOf(obo:EGG_0000001 ObjectSomeValuesFrom(obo:IAO_0000040 "
                        + "obo:EGG_0000000))\n"
                        + "ClassAssertion(obo:EGG_0000001 obo:EGG_0000002)\n)\n"));
    }

    @Test
    void aStandaloneInconsistentOntologyIsRefused() throws Exception {
        OperationResult.Builder result = OperationResult.of("Release");

        String refusal = ReleaseAction.whatTheReasonerSays(egg(), "HermiT", 60_000,
                () -> false, verdict -> true, result);

        assertNotNull(refusal);
        assertTrue(refusal.contains("inconsistent"), refusal);
        assertTrue(refusal.contains("Nothing was released"), refusal);
    }

    @Test
    void elkChosenLetsItThroughAndSaysWhatElkMisses() throws Exception {
        OperationResult.Builder result = OperationResult.of("Release");

        assertNull(ReleaseAction.whatTheReasonerSays(egg(), "ELK", 60_000, () -> false,
                verdict -> true, result));
        String said = result.summary("done").build().toPlainText();
        assertTrue(said.contains("OWL 2 EL"), said);
    }

    @Test
    void notFinishedAsksAndNoMeansNo() throws Exception {
        List<ReleaseVerdict> asked = new ArrayList<ReleaseVerdict>();

        String refusal = ReleaseAction.whatTheReasonerSays(egg(), "HermiT", 0, () -> false,
                verdict -> {
                    asked.add(verdict);
                    return false;
                }, OperationResult.of("Release"));

        assertEquals(1, asked.size());
        assertEquals(ReleaseVerdict.Kind.TIMED_OUT, asked.get(0).kind());
        assertNotNull(refusal);
        assertTrue(refusal.contains("not checked"), refusal);
    }

    @Test
    void notFinishedGoesOnWhenThePersonSaysSo() throws Exception {
        OperationResult.Builder result = OperationResult.of("Release");

        assertNull(ReleaseAction.whatTheReasonerSays(egg(), "HermiT", 0, () -> false,
                verdict -> true, result));
        String said = result.summary("done").build().toPlainText();
        assertTrue(said.contains("not checked"), said);
    }

    /** The release's reasoners are the ones a release can run; Structural reasons about nothing. */
    @Test
    void theReleaseOffersTheReleaseReasoners() {
        assertEquals(ReleaseReasoners.names(), ReleaseAction.releaseReasonerChoices());
    }
}
