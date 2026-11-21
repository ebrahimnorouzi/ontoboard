package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding;
import de.fizkarlsruhe.ise.ontoboard.robot.QualityReport;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.obolibrary.robot.ReportOperation;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What a pattern's import puts into a project is clean too (1.155.0, Task 19): the modules the
 * library's import cuts from the three patterns the NFDI, MatWerk and PMD projects use, read with
 * the project as ODK's report reads an edit file with its imports.
 */
class PatternModuleQualityTest {

    private static final List<String> PATTERNS = Arrays.asList("nfdi-dataset-description",
            "mwo-value-specification", "pmdco-measurement-and-setpoint");
    private static final String PROJECT = "http://purl.obolibrary.org/obo/test.owl";

    @Test
    void aProjectsReportHasNoErrorFromAPattern() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        // The project's own file says what ODK's seed says of it: a title, a description, a licence.
        OWLOntology project = manager.createOntology(IRI.create(PROJECT));
        for (String[] header : new String[][] {
            {"http://purl.org/dc/terms/title", "Test ontology"},
            {"http://purl.org/dc/terms/description", "An ontology that imports three patterns."}}) {
            manager.applyChange(new AddOntologyAnnotation(project, factory.getOWLAnnotation(
                    factory.getOWLAnnotationProperty(IRI.create(header[0])),
                    factory.getOWLLiteral(header[1]))));
        }
        manager.applyChange(new AddOntologyAnnotation(project, factory.getOWLAnnotation(
                factory.getOWLAnnotationProperty(IRI.create("http://purl.org/dc/terms/license")),
                IRI.create("https://creativecommons.org/licenses/by/4.0/"))));

        Set<String> moduleTerms = new TreeSet<String>();
        Map<String, List<String>> made = new TreeMap<String, List<String>>();
        for (String id : PATTERNS) {
            DesignPattern pattern = PatternLibrary.find(id);
            assertTrue(pattern != null, id + " is in the library");
            PatternLibrary.Contents contents = PatternLibrary.contentsOf(pattern);
            List<IRI> terms = new ArrayList<IRI>();
            for (OWLEntity entity : contents.getClasses()) {
                terms.add(entity.getIRI());
            }
            for (OWLEntity entity : contents.getProperties()) {
                terms.add(entity.getIRI());
            }
            // As "Add them" in the pattern library: every term the chooser lists, BOT, a module
            // named for the pattern - and a name made for each term the pattern leaves unlabelled.
            OWLOntology module = TermExtract.run(PatternLibrary.load(pattern), terms,
                    TermExtract.Method.BOT, IRI.create("http://purl.obolibrary.org/obo/test/imports/"
                            + id + "_import.owl")).getModule();
            List<String> named = NamesAsLabels.labelTheUnlabelled(module, project);
            if (!named.isEmpty()) {
                made.put(id, named);
            }
            for (OWLEntity entity : module.getSignature()) {
                if (!entity.isBuiltIn() && !entity.getIRI().toString().startsWith("http://www.w3.org/")) {
                    moduleTerms.add(entity.getIRI().toString());
                }
            }
            // The edit file with its imports, as one: what ODK's report with imports reads.
            manager.addAxioms(project, module.getAxioms());
        }

        Map<String, String> options = ReportOperation.getDefaultOptions();
        options.put(QualityReport.OPTION_PROFILE, new File(PatternAudit.ODK_PROFILE).getAbsolutePath());
        Map<String, String> fromAPattern = new TreeMap<String, String>();
        for (QualityFinding finding : QualityReport.run(project, options)) {
            if (finding.getSeverity() == QualityFinding.Severity.ERROR
                    && moduleTerms.contains(finding.getSubject())) {
                fromAPattern.put(finding.getSubject(), finding.getRule() + " " + finding.getValue());
            }
        }
        Set<String> unlabelled = new TreeSet<String>(moduleTerms);
        for (OWLAnnotationAssertionAxiom assertion : project.getAxioms(
                org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().isLabel()) {
                unlabelled.remove(assertion.getSubject().toString());
            }
        }

        assertTrue(fromAPattern.isEmpty(), "ODK's report finds ERRORs about the patterns' terms: "
                + fromAPattern);
        assertTrue(unlabelled.isEmpty(), "terms the modules bring without a label: " + unlabelled);
        assertTrue(made.isEmpty(), "the library's patterns label every term, so the import makes"
                + " no label from a name: " + made);
    }
}
