package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.session.Questions;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/** Test data made from a finished ontology by injecting a fault or holding something back. */
class CaseGeneratorTest {

    private final OWLReasonerFactory hermit = Reasoners.Choice.HERMIT.newFactory();

    @TempDir
    File dir;

    private CaseGenerator generator(long seed) {
        return new CaseGenerator(MiniPizza.load(), "mini-pizza.ofn", "abc", hermit, "HermiT",
                seed);
    }

    private static List<BenchCase> of(List<BenchCase> cases, String task, boolean control) {
        List<BenchCase> chosen = new ArrayList<BenchCase>();
        for (BenchCase c : cases) {
            if (c.getTask().equals(task) && c.isControl() == control) {
                chosen.add(c);
            }
        }
        return chosen;
    }

    @Test
    void theSameSeedGivesTheSameCases() throws IOException {
        File first = new File(dir, "a.jsonl");
        File second = new File(dir, "b.jsonl");
        CaseGenerator one = generator(7);
        List<BenchCase> cases = new ArrayList<BenchCase>(one.explainProblem(4));
        cases.addAll(one.contextRequest(1, 1));
        CaseGenerator two = generator(7);
        List<BenchCase> again = new ArrayList<BenchCase>(two.explainProblem(4));
        again.addAll(two.contextRequest(1, 1));

        BenchCases.write(cases, first);
        BenchCases.write(again, second);

        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()));
        assertEquals(cases.size(), BenchCases.read(first).size());
    }

    @Test
    void everyUnsatisfiableCaseIsUnsatisfiable() {
        CaseGenerator generator = generator(1);

        List<BenchCase> cases = generator.explainProblem(4);

        List<BenchCase> unsatisfiable = new ArrayList<BenchCase>();
        for (BenchCase c : cases) {
            if ("unsatisfiable".equals(c.getProblem())) {
                unsatisfiable.add(c);
            }
        }
        assertFalse(unsatisfiable.isEmpty());
        for (BenchCase c : unsatisfiable) {
            OWLOntology mutated = generator.mutated(c);
            OWLReasoner reasoner = hermit.createReasoner(mutated);
            try {
                assertTrue(reasoner.isConsistent(), c.getId());
                assertEquals(1, reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom().size(),
                        c.getId());
            } finally {
                reasoner.dispose();
            }
            assertEquals(2, c.getClash().size(), c.getId());
            assertTrue(c.getContext().get(0).getTitle().endsWith("is unsatisfiable"), c.getId());
            assertTrue(c.getMessage().equals(Questions.whyUnsatisfiable(
                    c.getContext().get(0).getTitle().replaceFirst("^Why ", "")
                            .replaceFirst(" is unsatisfiable$", ""))), c.getMessage());
        }
    }

    @Test
    void everyInconsistentCaseIsInconsistent() {
        CaseGenerator generator = generator(1);

        List<BenchCase> inconsistent = new ArrayList<BenchCase>();
        for (BenchCase c : generator.explainProblem(4)) {
            if ("inconsistent".equals(c.getProblem())) {
                inconsistent.add(c);
            }
        }

        assertFalse(inconsistent.isEmpty());
        for (BenchCase c : inconsistent) {
            OWLReasoner reasoner = hermit.createReasoner(generator.mutated(c));
            try {
                assertFalse(reasoner.isConsistent(), c.getId());
            } finally {
                reasoner.dispose();
            }
            assertEquals("Why the ontology is inconsistent", c.getContext().get(0).getTitle());
            assertEquals(Questions.whyInconsistent(), c.getMessage());
        }
    }

    @Test
    void theGoldTermsAreTheJustificationsTerms() {
        for (BenchCase c : generator(1).explainProblem(4)) {
            assertTrue(c.getTerms().containsAll(c.getClash()), c.getId() + " " + c.getTerms());
            for (String term : c.getTerms()) {
                assertTrue(c.getContext().get(0).getText().contains(term), term);
            }
        }
    }

    /**
     * The pizza example is inconsistent on purpose (a deliberate datatype clash), and faults
     * cannot be injected into an ontology that is already broken. Its own
     * problem is the case - the one a domain expert arrives with.
     */
    @Test
    void anOntologyThatIsAlreadyInconsistentGivesOneAsIsCase() {
        OWLOntology broken = MiniPizza.load();
        org.semanticweb.owlapi.model.OWLDataFactory data =
                broken.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLNamedIndividual x =
                data.getOWLNamedIndividual(MiniPizza.iri("x"));
        broken.getOWLOntologyManager().addAxiom(broken, data.getOWLClassAssertionAxiom(
                data.getOWLClass(MiniPizza.iri("VegetarianTopping")), x));
        broken.getOWLOntologyManager().addAxiom(broken, data.getOWLClassAssertionAxiom(
                data.getOWLClass(MiniPizza.iri("MeatTopping")), x));

        List<BenchCase> cases = new CaseGenerator(broken, "mini-pizza.ofn", "abc", hermit,
                "HermiT", 1).explainProblem(4);

        assertEquals(1, cases.size());
        BenchCase asIs = cases.get(0);
        assertEquals("inconsistent", asIs.getProblem());
        assertTrue(asIs.getFault().isEmpty(), "nothing was injected");
        assertTrue(asIs.getId().contains("-asis-"), asIs.getId());
        assertEquals(Questions.whyInconsistent(), asIs.getMessage());
        assertTrue(asIs.getClash().contains("VegetarianTopping"), asIs.getClash().toString());
        assertTrue(asIs.getClash().contains("MeatTopping"), asIs.getClash().toString());
    }

    /** "funghi price" names the individual, not also the class labelled "funghi". */
    @Test
    void aShorterLabelInsideALongerOneIsNotATerm() {
        OWLOntology pizza = MiniPizza.load();
        org.semanticweb.owlapi.model.OWLDataFactory data =
                pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, data.getOWLDeclarationAxiom(
                data.getOWLClass(MiniPizza.iri("Cheese"))));
        pizza.getOWLOntologyManager().addAxiom(pizza, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), MiniPizza.iri("Cheese"), data.getOWLLiteral("cheese")));

        List<String> terms = CaseGenerator.termsIn(pizza,
                "cheese topping SubClassOf VegetarianTopping");

        assertTrue(terms.contains("cheese topping"), terms.toString());
        assertTrue(terms.contains("VegetarianTopping"), terms.toString());
        assertFalse(terms.contains("cheese"), terms.toString());
        assertEquals(java.util.Arrays.asList("cheese"),
                CaseGenerator.termsIn(pizza, "Some cheese, and nothing else."));
    }

    @Test
    void contextRequestCasesLeaveTheNeededTermOut() {
        List<BenchCase> held = of(generator(1).contextRequest(1, 1), "context-request", false);

        assertEquals(1, held.size());
        BenchCase c = held.get(0);
        assertEquals(1, c.getContext().size(), "only the term the question is about");
        assertEquals("vegetarian pizza", c.getContext().get(0).getTitle());
        assertEquals(1, c.getNeed().size());
        assertEquals("VegetarianTopping", c.getNeed().get(0));
        assertFalse(c.getContext().get(0).getText().contains("cheese topping"),
                "the needed term's own block - its subclasses - is held back");
        assertTrue(c.getMessage().contains("VegetarianTopping"), c.getMessage());
    }

    @Test
    void controlsKeepIt() {
        List<BenchCase> controls = of(generator(1).contextRequest(1, 1), "context-request", true);

        assertEquals(1, controls.size());
        BenchCase c = controls.get(0);
        assertEquals(2, c.getContext().size());
        assertTrue(c.getContext().get(1).getText().contains("cheese topping"));
        assertTrue(c.getNeed().isEmpty());
    }

    /** Review #5b: one control counted five times is not five controls. */
    @Test
    void controlsAreDistinct() {
        List<BenchCase> controls = of(generator(1).contextRequest(1, 3), "context-request", true);

        assertEquals(1, controls.size(), "mini-pizza has one candidate, so one control");
    }

    @Test
    void theHashIgnoresLineEndings() throws IOException {
        File lf = new File(dir, "lf.owl");
        File crlf = new File(dir, "crlf.owl");
        Files.write(lf.toPath(), "a\nb\n".getBytes(StandardCharsets.UTF_8));
        Files.write(crlf.toPath(), "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(CaseGenerator.sha256Lf(lf), CaseGenerator.sha256Lf(crlf));
        assertEquals(64, CaseGenerator.sha256Lf(lf).length());
    }

    /** The definition task: what "Suggest a definition" sends, with the answer held back. */
    @Test
    void aDefinitionCaseHoldsTheDefinitionBack() {
        List<BenchCase> cases = generator(1).definition(5);

        assertEquals(2, cases.size(), "vegetarian pizza and VegetarianTopping have both");
        for (BenchCase c : cases) {
            assertEquals(BenchCase.DEFINITION, c.getTask());
            String context = c.getContext().get(0).getText();
            assertFalse(c.getReference().isEmpty());
            assertFalse(context.contains(c.getReference()), context);
            assertFalse(c.getGenus().isEmpty());
            assertEquals(Questions.suggestDefinition(c.getSubject(), c.getGenus(), false),
                    c.getMessage());
        }
    }

    /** The repair task: the injected axioms travel with the case, so the runner can apply them. */
    @Test
    void repairCasesCarryTheirFaultAsAxioms() {
        List<BenchCase> cases = generator(1).repair(3);

        assertFalse(cases.isEmpty());
        for (BenchCase c : cases) {
            assertEquals(BenchCase.REPAIR, c.getTask());
            java.util.Set<org.semanticweb.owlapi.model.OWLAxiom> fault =
                    BenchCases.faultAxioms(c);
            assertFalse(fault.isEmpty(), c.getId());
            OWLOntology mutated = RepairLoop.copy(MiniPizza.load(), fault);
            OWLReasoner reasoner = hermit.createReasoner(mutated);
            try {
                boolean problem = !reasoner.isConsistent() || !reasoner.isSatisfiable(
                        mutated.getOWLOntologyManager().getOWLDataFactory().getOWLClass(
                                org.semanticweb.owlapi.model.IRI.create(c.getTargetIri())));
                assertTrue(problem, c.getId() + " has its problem once the fault is added");
            } finally {
                reasoner.dispose();
            }
        }
    }
}
