package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * "Every kind of X has some P that is a Y": a class answers when it, or a class above it, says P
 * some Y or some kind of Y - not just some anything (1.135.2).
 *
 * <p>Found building an application ontology on a domain ontology: a question of the shape "every
 * kind of X has some P", about a class of the application's own, passed before anything was
 * drawn, because the classes above it in the domain ontology already said P some broader thing.
 * For a class that deep, no "has some P" question can be red, and the test-first step shows
 * nothing. Naming what the property points to makes it red until the class says so.
 */
class QuestionThatNamesWhatThePropertyPointsToTest {

    private static final String NS = "http://example.org/tensile#";

    private OWLOntologyManager manager;
    private OWLDataFactory df;
    private OWLOntology ontology;
    private OWLObjectProperty hasInput;
    private OWLClass process;
    private OWLClass test;
    private OWLClass piece;
    private OWLClass bar;
    private OWLClass shortBar;

    private OWLClass cls(String name) {
        OWLClass c = df.getOWLClass(IRI.create(NS + name));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(c));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                c.getIRI(), df.getOWLLiteral(name)));
        return c;
    }

    /** That shape in small: a process that takes some piece, a test under it, a bar a piece. */
    @BeforeEach
    void ontology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        df = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/tensile"));
        hasInput = df.getOWLObjectProperty(IRI.create(NS + "hasInput"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(hasInput));
        process = cls("process");
        test = cls("tensileTest");
        piece = cls("piece");
        bar = cls("bar");
        shortBar = cls("shortBar");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test, process));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(bar, piece));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(shortBar, bar));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(process,
                df.getOWLObjectSomeValuesFrom(hasInput, piece)));
    }

    private List<String> broken(QuestionTests.Template template, OWLClass second) {
        String sparql = QuestionTests.query(template, "Q?", test.getIRI(), hasInput.getIRI(),
                second == null ? null : second.getIRI(), 0);
        List<String> terms = new ArrayList<String>();
        for (List<String> row : SparqlQuery.run(ontology, sparql).getRows()) {
            terms.add(row.get(0).replace(NS, ""));
        }
        Collections.sort(terms);
        return terms;
    }

    /** Why the rule exists: "has some has input" is answered by the process above, at once. */
    @Test
    void hasSomeIsAnsweredByTheClassAboveBeforeAnythingIsDrawn() {
        assertEquals(Collections.<String>emptyList(),
                broken(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, null));
    }

    @Test
    void aFillerBroaderThanTheOneAskedForDoesNotAnswer() {
        assertEquals(Arrays.asList("tensileTest"),
                broken(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, bar));
    }

    @Test
    void saidOnTheClassItAnswersAndEveryKindOfItInheritsTheAnswer() {
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test,
                df.getOWLObjectSomeValuesFrom(hasInput, bar)));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(cls("narrowerTest"), test));

        assertEquals(Collections.<String>emptyList(),
                broken(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, bar));
    }

    /** Some short bar is some bar: a kind of the class asked for answers too. */
    @Test
    void aKindOfTheFillerAnswers() {
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(test,
                df.getOWLObjectSomeValuesFrom(hasInput, shortBar)));

        assertEquals(Collections.<String>emptyList(),
                broken(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, bar));
    }

    /**
     * A defined kind of the class - equivalent to process and has input some short bar - answers by
     * its definition; the class itself, which says nothing, still breaks the rule.
     */
    @Test
    void aDefinitionAnswersForTheClassItDefines() {
        OWLClass defined = cls("definedTest");
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(defined, test));
        manager.addAxiom(ontology, df.getOWLEquivalentClassesAxiom(defined,
                df.getOWLObjectIntersectionOf(process,
                        df.getOWLObjectSomeValuesFrom(hasInput, shortBar))));

        assertEquals(Arrays.asList("tensileTest"),
                broken(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS, bar));
    }

    @Test
    void theClassThePropertyAndWhatItPointsToAreReadBackFromTheTest() {
        String sparql = QuestionTests.query(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS,
                "Q?", test.getIRI(), hasInput.getIRI(), bar.getIRI(), 0);

        assertEquals(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS,
                QuestionTests.templateOf(sparql));
        assertEquals(test.getIRI(), QuestionTests.classIn(sparql));
        assertEquals(hasInput.getIRI(), QuestionTests.propertyIn(sparql));
        assertEquals(bar.getIRI(), QuestionTests.fillerIn(sparql));
        assertEquals(null, QuestionTests.fillerIn(QuestionTests.query(
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME, "Q?", test.getIRI(),
                hasInput.getIRI(), null, 0)));
    }

    /** It reads like the question it is: the filler where the dialog's words put it. */
    @Test
    void theWordsSayWhatThePropertyPointsTo() {
        assertEquals("Every kind of <class> has some <property> that is a <second class>",
                QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME_THAT_IS.getWords());
        assertEquals(QuestionTests.Template.EVERY_SUBCLASS_HAS_SOME,
                QuestionTests.Template.values()[0], "the default rule stays the default");
    }
}
