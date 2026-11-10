package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What a failed test tells somebody to do depends on the test (1.135.1, Windows study
 * rehearsal). Every failing check ended with the label check's advice - "a missing label is added
 * in Protege under Annotations" - so the egg guide's T6, whose test asks that every kind of egg
 * boiling dataset has some has part, sent participants to add a label nobody was missing.
 */
public class BuildAdviceTest {

    private static final String QUESTION = "Every kind of egg boiling dataset has some has part?";
    private static final IRI DATASET = IRI.create("http://purl.obolibrary.org/obo/EGG_0001004");
    private static final IRI HAS_PART = IRI.create("http://purl.obolibrary.org/obo/BFO_0000051");
    private static final IRI DATUM = IRI.create("http://purl.obolibrary.org/obo/IAO_0000109");

    public static File scaffold(File where) {
        OdkProjectConfig config = new OdkProjectConfig("egg", "Test kitchen ontology",
                "Scaffolded by a test", "http://purl.obolibrary.org/obo/egg.owl",
                "https://creativecommons.org/licenses/by/4.0/", where);
        OdkScaffold.create(config);
        return new File(new File(new File(config.getProjectRoot(), "src"), "ontology"),
                "egg-edit.owl");
    }

    /** T6 as the guide has it: the dataset and has part, labelled, and the test written. */
    public static OWLOntology t6(File edit, boolean answered) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(IRI.create(edit.toURI()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass dataset = df.getOWLClass(DATASET);
        OWLClass datum = df.getOWLClass(DATUM);
        OWLObjectProperty hasPart = df.getOWLObjectProperty(HAS_PART);
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(dataset));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(datum));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasPart));
        for (Object[] named : new Object[][] {{DATASET, "egg boiling dataset"},
            {HAS_PART, "has part"}, {DATUM, "measurement datum"}}) {
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    (IRI) named[0], df.getOWLLiteral((String) named[1])));
        }
        if (answered) {
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dataset,
                    df.getOWLObjectSomeValuesFrom(hasPart, datum)));
        }
        File sparql = new File(edit.getParentFile().getParentFile(), "sparql");
        Files.write(new File(sparql, QuestionTests.fileNameFor(QUESTION)).toPath(),
                QuestionTests.query(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, QUESTION,
                        DATASET, HAS_PART, null, 1).getBytes(StandardCharsets.UTF_8));
        return ontology;
    }

    @Test
    void aQuestionTheModelDoesNotAnswerYetSaysSoAndHowToAnswerIt(@TempDir File where)
            throws Exception {
        File edit = scaffold(where);
        OWLOntology ontology = t6(edit, false);

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "sparql_test");

        String failure = String.valueOf(outcome.getFailure());
        assertFalse(outcome.isOk(), failure);
        assertTrue(failure.contains("every_kind_of_egg_boiling_dataset_has_some_has_part.rq"), failure);
        assertTrue(failure.contains("does not answer this question yet"), failure);
        assertTrue(failure.contains("right-click egg boiling dataset on the board"), failure);
        assertTrue(failure.contains("Relate to another term"), failure);
        assertTrue(failure.contains("set Property to has part"), failure);
        assertFalse(failure.contains("missing label"), failure);
        assertFalse(failure.contains("Annotations"), failure);
        assertFalse(failure.contains(DATASET.toString()), "named by its label: " + failure);
        assertTrue(String.join("\n", outcome.getTranscript()).contains(DATASET.toString()),
                "the log keeps the IRI");
    }

    @Test
    void theLabelCheckStillSaysWhereALabelIsAdded(@TempDir File where) throws Exception {
        File edit = scaffold(where);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(IRI.create(edit.toURI()));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(DATASET)));

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "sparql_test");

        String failure = String.valueOf(outcome.getFailure());
        assertFalse(outcome.isOk(), failure);
        assertTrue(failure.contains("check_labels.rq"), failure);
        assertTrue(failure.contains("a missing label is added in Protege under Annotations"),
                failure);
    }

    @Test
    void theChecksThatPassedAreNamedOnlyWhenAllPass(@TempDir File where) throws Exception {
        File edit = scaffold(where);
        InProcessTargets.Outcome passing = InProcessTargets.run(edit, t6(edit, true),
                Reasoners.Choice.ELK.newFactory(), "sparql_test");
        assertTrue(passing.isOk(), String.valueOf(passing.getFailure()));
        assertEquals(Arrays.asList("check_labels.rq",
                "every_kind_of_egg_boiling_dataset_has_some_has_part.rq"),
                passing.getPassingChecks());

        InProcessTargets.Outcome failing = InProcessTargets.run(edit, t6(edit, false),
                Reasoners.Choice.ELK.newFactory(), "sparql_test");
        assertFalse(failing.isOk());
        assertEquals(Collections.emptyList(), failing.getPassingChecks(),
                "the failure names the check that failed");
    }
}
