package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.BuildAdviceTest;
import de.fizkarlsruhe.ise.ontoboard.odk.InProcessTargets;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What a build says above its log (1.135.1, Windows study rehearsal). The result dialog cannot be
 * resized and its log box shows six lines: a passing test's PASS was the ninth, and HermiT's
 * warning the seventh, so a participant looking for "PASS" or for what went wrong had to scroll.
 */
class BuildResultTest {

    @Test
    void aPassingBuildNamesEachTestPassAboveTheLog(@TempDir File where) throws Exception {
        File edit = BuildAdviceTest.scaffold(where);
        OWLOntology ontology = BuildAdviceTest.t6(edit, true);
        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "test");

        String summary = BuildAction.inProcessResult(OperationResult.of("Build"), "test", edit,
                outcome, 1).getSummary();

        assertTrue(summary.startsWith("test finished in 1s"), summary);
        assertTrue(summary.contains("Tests: PASS check_labels.rq, PASS"
                + " every_kind_of_egg_boiling_dataset_has_some_has_part.rq."), summary);
    }

    @Test
    void aWarningIsSaidAboveTheLog(@TempDir File where) throws Exception {
        File edit = BuildAdviceTest.scaffold(where);
        OWLOntology ontology = BuildAdviceTest.t6(edit, true);
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        String ns = "http://purl.obolibrary.org/obo/EGG_";
        OWLClass value = df.getOWLClass(IRI.create(ns + "0001005"));
        OWLClass gdc = df.getOWLClass(IRI.create(ns + "0009001"));
        OWLClass sdc = df.getOWLClass(IRI.create(ns + "0009002"));
        OWLClass quality = df.getOWLClass(IRI.create(ns + "0009003"));
        OWLObjectProperty qualityOf = df.getOWLObjectProperty(IRI.create(ns + "0009004"));
        OWLObjectProperty hasQuality = df.getOWLObjectProperty(IRI.create(ns + "0009005"));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                value.getIRI(), df.getOWLLiteral("egg mass value")));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value, gdc));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(quality, sdc));
        manager.addAxiom(ontology, df.getOWLDisjointClassesAxiom(gdc, sdc));
        manager.addAxiom(ontology, df.getOWLInverseObjectPropertiesAxiom(qualityOf, hasQuality));
        manager.addAxiom(ontology, df.getOWLObjectPropertyRangeAxiom(hasQuality, quality));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value,
                df.getOWLObjectSomeValuesFrom(qualityOf, df.getOWLThing())));
        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "reason");
        assertTrue(outcome.isOk() && !outcome.getWarnings().isEmpty(), outcome.getTranscript()
                .toString());

        String summary = BuildAction.inProcessResult(OperationResult.of("Build"), "reason", edit,
                outcome, 1).getSummary();

        assertTrue(summary.contains("HermiT finds that egg mass value can have no members."),
                summary);
        assertTrue(summary.contains("in the log"), summary);
    }

    @Test
    void aBuildThatFailsAfterItsTestsSaysTheyPassed(@TempDir File where) throws Exception {
        File edit = BuildAdviceTest.scaffold(where);
        OWLOntology ontology = BuildAdviceTest.t6(edit, true);
        OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
        // An annotation property nobody declared: the profile check fails after the tests ran.
        ontology.getOWLOntologyManager().addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(
                df.getOWLAnnotationProperty(IRI.create("http://example.org/undeclared")),
                IRI.create("http://purl.obolibrary.org/obo/EGG_0001004"), df.getOWLLiteral("x")));
        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "test");
        assertFalse(outcome.isOk());

        String summary = BuildAction.inProcessResult(OperationResult.of("Build"), "test", edit,
                outcome, 1).getSummary();

        assertTrue(summary.contains("validate_profile found"), summary);
        assertTrue(summary.contains("Tests: PASS check_labels.rq, PASS"
                + " every_kind_of_egg_boiling_dataset_has_some_has_part.rq."), summary);
    }
}
