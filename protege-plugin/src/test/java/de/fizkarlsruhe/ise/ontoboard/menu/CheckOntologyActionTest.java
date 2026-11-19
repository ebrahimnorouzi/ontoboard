package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.LooseCheck;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Check and test &gt; Check this ontology...: the three checks of a loose ontology as the result
 * shows them - a row each, the verdicts in the log, and the summary counting what failed.
 */
class CheckOntologyActionTest {

    /** A small ontology with a title, a license, one labelled class and one without a label. */
    private static OWLOntology small() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/k.owl"));
        IRI kitchen = IRI.create("https://example.org/K_0000001");
        IRI pan = IRI.create("https://example.org/K_0000002");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(factory.getOWLClass(kitchen)));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), kitchen, factory.getOWLLiteral("kitchen", "en")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(factory.getOWLClass(pan),
                factory.getOWLClass(kitchen)));
        return ontology;
    }

    @Test
    void eachCheckIsARowAndTheSummaryCountsWhatFailed() throws Exception {
        LooseCheck.Result checked = LooseCheck.run(small(), LooseCheck.Options.defaults());

        OperationResult result = CheckOntologyAction.reported("Check this ontology", checked,
                new File("k.owl"));

        assertEquals(Arrays.asList("Check", "Result", "Verdict"), result.getColumns());
        assertEquals(3, result.getRows().size());
        List<String> labels = result.getRows().get(2);
        assertEquals("Labels", labels.get(0));
        assertEquals("FAIL", labels.get(1));
        assertTrue(labels.get(2).contains("K_0000002"), labels.get(2));
        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, result.getStatus());
        int failed = 0;
        for (LooseCheck.Line line : checked.lines()) {
            failed += line.passed() ? 0 : 1;
        }
        assertEquals(failed + " of 3 checks found something to put right.",
                result.getSummary());
        String log = String.join("\n", result.getLog());
        assertTrue(log.contains("Labels: " + checked.lines().get(2).verdict()), log);
        assertTrue(log.contains("k.owl"), "what was checked is named: " + log);
    }

    /** With a label on every class and the header ODK's profile asks for, all three pass. */
    @Test
    void allPassedIsSaidAsSuch() throws Exception {
        OWLOntology ontology = small();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                IRI.create("https://example.org/K_0000002"), factory.getOWLLiteral("pan", "en")));
        String[][] header = {{"title", "Kitchen"}, {"description", "Things of a kitchen."}};
        for (String[] said : header) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,
                    factory.getOWLAnnotation(factory.getOWLAnnotationProperty(IRI.create(
                            "http://purl.org/dc/terms/" + said[0])),
                            factory.getOWLLiteral(said[1]))));
        }
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology,
                factory.getOWLAnnotation(factory.getOWLAnnotationProperty(IRI.create(
                        "http://purl.org/dc/terms/license")),
                        IRI.create("https://creativecommons.org/licenses/by/4.0/"))));
        LooseCheck.Result checked = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        OperationResult result = CheckOntologyAction.reported("Check this ontology", checked,
                null);

        assertTrue(checked.passed(), checked.toString());
        assertEquals("All 3 checks passed.", result.getSummary());
        assertTrue(String.join("\n", result.getLog()).contains("has not been saved"),
                result.getLog().toString());
    }

    /** The user, 2026-11-19: "check this ontology, is not offering the choice for reasoner". */
    @Test
    void theFormOffersTheReasoners() {
        java.util.List<Parameter> parameters = CheckOntologyAction.parameters(null);

        Parameter reasoner = null;
        for (Parameter parameter : parameters) {
            if ("reasoner".equals(parameter.getKey())) {
                reasoner = parameter;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(reasoner);
        assertEquals(de.fizkarlsruhe.ise.ontoboard.odk.ReleaseReasoners.names(),
                reasoner.getChoices());
        assertEquals("HermiT", reasoner.getDefaultValue());
    }
}
