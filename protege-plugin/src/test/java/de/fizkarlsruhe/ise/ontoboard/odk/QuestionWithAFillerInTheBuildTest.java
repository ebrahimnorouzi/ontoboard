package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * A question that names what its property points to, in the build: red while the class takes
 * only what the process above it takes, with advice that names the class to relate it to; green
 * once it says so (1.135.2). Found building an application ontology on a domain ontology, where a
 * question "every kind of X has some P", about a class of the application's own, was green before
 * anything was drawn.
 */
class QuestionWithAFillerInTheBuildTest {

    private static final String NS = "http://example.org/tensile#";
    private static final String QUESTION =
            "Every kind of tensile test has some has input that is a bar?";

    private static IRI iri(String name) {
        return IRI.create(NS + name);
    }

    private static OWLOntology tensile(File edit, boolean answered) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(IRI.create(edit.toURI()));
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLObjectProperty hasInput = df.getOWLObjectProperty(iri("hasInput"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasInput));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                hasInput.getIRI(), df.getOWLLiteral("has input")));
        String[][] named = {{"process", "process"}, {"tensileTest", "tensile test"},
            {"piece", "piece"}, {"bar", "bar"}};
        for (String[] one : named) {
            OWLClass c = df.getOWLClass(iri(one[0]));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(c));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    c.getIRI(), df.getOWLLiteral(one[1])));
        }
        OWLClass process = df.getOWLClass(iri("process"));
        OWLClass test = df.getOWLClass(iri("tensileTest"));
        OWLClass bar = df.getOWLClass(iri("bar"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test, process));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(bar, df.getOWLClass(iri("piece"))));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(process,
                df.getOWLObjectSomeValuesFrom(hasInput, df.getOWLClass(iri("piece")))));
        if (answered) {
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test,
                    df.getOWLObjectSomeValuesFrom(hasInput, bar)));
        }
        File sparql = new File(edit.getParentFile().getParentFile(), "sparql");
        Files.write(new File(sparql, QuestionTests.fileNameFor(QUESTION)).toPath(),
                QuestionTests.query(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS,
                        QUESTION, test.getIRI(), hasInput.getIRI(), bar.getIRI(), 0)
                        .getBytes(StandardCharsets.UTF_8));
        return ontology;
    }

    @Test
    void redWhileTheProcessAboveAnswersOnlyForAPieceWithAdviceThatNamesTheBar(
            @TempDir File where) throws Exception {
        File edit = BuildAdviceTest.scaffold(where);
        OWLOntology ontology = tensile(edit, false);

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "sparql_test");

        String failure = String.valueOf(outcome.getFailure());
        assertFalse(outcome.isOk(), failure);
        assertTrue(failure.contains(QuestionTests.fileNameFor(QUESTION)), failure);
        assertTrue(failure.contains("does not answer this question yet"), failure);
        assertTrue(failure.contains("right-click tensile test on the board"), failure);
        assertTrue(failure.contains("Relate to another term"), failure);
        assertTrue(failure.contains("pick bar"), failure);
        assertTrue(failure.contains("set Property to has input"), failure);
    }

    @Test
    void greenOnceTheTestSaysItTakesSomeBar(@TempDir File where) throws Exception {
        File edit = BuildAdviceTest.scaffold(where);
        OWLOntology ontology = tensile(edit, true);

        InProcessTargets.Outcome outcome = InProcessTargets.run(edit, ontology,
                Reasoners.Choice.ELK.newFactory(), "sparql_test");

        assertTrue(outcome.isOk(), String.valueOf(outcome.getFailure()));
    }
}
