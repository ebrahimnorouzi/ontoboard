package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;

/**
 * Check this ontology, without a project: the reasoner, ROBOT's quality report with ODK's
 * default profile and the label check, on whatever is open - each with its verdict in a line,
 * nothing written. The testers of 1.132.0 asked for it: "Today Build needs a project."
 */
class LooseCheckTest {

    private static final String EGG = "https://example.org/eggboil/";

    /** The egg's edit file by itself, as somebody opens a loose .owl file. */
    private static OWLOntology egg() throws Exception {
        byte[] bytes = UpdateCases.before("egg-changed").get("src/ontology/eggboil-edit.owl");
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // What it imports is not there: it is checked alone, and nothing is fetched.
        final IRI nowhere = IRI.create(new File("target/no-such-import.owl").getAbsoluteFile());
        manager.getIRIMappers().add(iri -> nowhere);
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        return manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                new ByteArrayInputStream(bytes), IRI.create(EGG + "eggboil-edit.owl")));
    }

    private static OWLClass egg(OWLOntology ontology, String number) {
        return ontology.getOWLOntologyManager().getOWLDataFactory()
                .getOWLClass(IRI.create(EGG + "EGGBOIL_" + number));
    }

    private static List<String> checks(LooseCheck.Result result) {
        List<String> names = new ArrayList<String>();
        for (LooseCheck.Line line : result.lines()) {
            names.add(line.check());
        }
        return names;
    }

    @Test
    void theEggAlonePassesEachCheck() throws Exception {
        LooseCheck.Result result = LooseCheck.run(egg(), LooseCheck.Options.defaults());

        assertEquals(Arrays.asList("Reasoner", "Quality report", "Labels"), checks(result));
        for (LooseCheck.Line line : result.lines()) {
            assertTrue(line.passed(), line.check() + ": " + line.verdict());
        }
        assertTrue(result.passed());
        assertTrue(result.lines().get(0).verdict().contains("ELK"),
                result.lines().get(0).verdict());
        assertTrue(result.lines().get(1).verdict().contains("ODK's default profile"),
                result.lines().get(1).verdict());
    }

    @Test
    void anUnsatisfiableClassIsNamedOnTheReasonersLine() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLClass impossible = egg(ontology, "0000098");
        manager.addAxiom(ontology, factory.getOWLDisjointClassesAxiom(egg(ontology, "0000001"),
                egg(ontology, "0000002")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(impossible,
                egg(ontology, "0000001")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(impossible,
                egg(ontology, "0000002")));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), impossible.getIRI(),
                factory.getOWLLiteral("egg that is a boiling", "en")));

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line reasoner = result.lines().get(0);
        assertFalse(reasoner.passed(), reasoner.verdict());
        assertTrue(reasoner.verdict().contains("egg that is a boiling"), reasoner.verdict());
        assertFalse(result.passed());
    }

    @Test
    void aClassWithoutALabelIsNamedOnTheLabelsLine() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLSubClassOfAxiom(
                egg(ontology, "0000099"), egg(ontology, "0000001")));

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line labels = result.lines().get(2);
        assertFalse(labels.passed(), labels.verdict());
        assertTrue(labels.verdict().contains("EGGBOIL_0000099"), labels.verdict());
        assertTrue(result.lines().get(0).passed(), "the reasoner has nothing against it");
    }

    @Test
    void anErrorOfTheProfileFailsTheReportAndNamesItsRule() throws Exception {
        OWLOntology ontology = egg();
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString()
                    .equals("http://purl.org/dc/terms/license")) {
                manager.applyChange(new RemoveOntologyAnnotation(ontology, annotation));
            }
        }

        LooseCheck.Result result = LooseCheck.run(ontology, LooseCheck.Options.defaults());

        LooseCheck.Line report = result.lines().get(1);
        assertFalse(report.passed(), report.verdict());
        assertTrue(report.verdict().contains("missing_ontology_license"), report.verdict());
    }
}
