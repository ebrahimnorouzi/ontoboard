package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Statements of a pattern's source that the curation is to keep. The final review of 1.155.0
 * compared every pattern before and after the curation as RDF and found one statement gone:
 * eventprocessing's {@code hasSensorReadingValue owl:equivalentProperty ssn:hasValue}. The source
 * declares ssn:hasValue nowhere, so the OWL API read no axiom from it, and the file the curation
 * wrote from the OWL API's model lost it. It is back as a listed fix (curation-fixes.tsv).
 */
class CurationKeptStatementsTest {

    private static final String E = "http://www.ontologydesignpatterns.org/cp/owl/eventprocessing.owl#";
    private static final String SSN = "http://purl.oclc.org/NET/ssnx/ssn#";

    private static File library() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile().getParentFile(),
                "patterns");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pattern.owl", "pattern.ttl"})
    void eventprocessingKeepsItsAlignmentToSsn(String file) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology pattern = manager.loadOntologyFromOntologyDocument(
                new FileDocumentSource(new File(new File(library(), "eventprocessing"), file)),
                new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                        MissingImportHandlingStrategy.SILENT));
        OWLDataFactory factory = manager.getOWLDataFactory();

        assertTrue(pattern.containsAxiom(factory.getOWLEquivalentObjectPropertiesAxiom(
                factory.getOWLObjectProperty(IRI.create(E + "hasSensorReadingValue")),
                factory.getOWLObjectProperty(IRI.create(SSN + "hasValue")))), file);
    }
}
