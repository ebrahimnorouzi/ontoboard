package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The repair loop (maintainer: "feed in there, ask the LLM for an explanation, apply the
 * suggestion, check the consistency again and by iterative loop see if it's solved or not"):
 * the model's removal is applied to a copy, the reasoner runs again, and the new justification
 * goes back, up to three rounds.
 */
class RepairLoopTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLReasonerFactory hermit = Reasoners.Choice.HERMIT.newFactory();
    private BenchCase unsatisfiable;
    private Set<OWLAxiom> fault;
    private OWLOntology mutated;
    private final List<ChatRequest> asked = new ArrayList<ChatRequest>();

    @BeforeEach
    void pickAnUnsatisfiableCase() {
        for (BenchCase c : new CaseGenerator(pizza, "mini-pizza.ofn", "abc", hermit, "HermiT", 1)
                .repair(6)) {
            if ("unsatisfiable".equals(c.getProblem()) && unsatisfiable == null) {
                unsatisfiable = c;
            }
        }
        fault = BenchCases.faultAxioms(unsatisfiable);
        mutated = RepairLoop.copy(pizza, fault);
    }

    private RepairLoop.Outcome run(Function<ChatRequest, String> model) {
        return RepairLoop.run(unsatisfiable, pizza, hermit, "HermiT", "ROLE", 1024, request -> {
            asked.add(request);
            String text = model.apply(request);
            return new Reply(text, 10, 5, "stop", 7);
        });
    }

    private static String removal(String axiom) {
        return "That axiom is the mistake.\n```ontoboard-proposal\naction: remove-axiom\naxiom: "
                + axiom + "\nreason: injected\n```";
    }

    private String injectedSubClassOf() {
        for (OWLAxiom axiom : fault) {
            if (axiom instanceof OWLSubClassOfAxiom) {
                return AssistantRendering.render(mutated, axiom);
            }
        }
        throw new AssertionError("the fault has a SubClassOf");
    }

    /** The disjointness the fault class runs into: an axiom of the ontology, not of the fault. */
    private String originalDisjointness() {
        for (OWLDisjointClassesAxiom axiom : pizza.getAxioms(AxiomType.DISJOINT_CLASSES)) {
            for (OWLClassExpression side : axiom.getClassExpressions()) {
                if (unsatisfiable.getClash().contains(
                        de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels.forEntity(pizza,
                                side.asOWLClass()))) {
                    return AssistantRendering.render(mutated, axiom);
                }
            }
        }
        throw new AssertionError("the clash is a disjointness");
    }

    @Test
    void removingTheInjectedAxiomSolvesItInOneRound() {
        RepairLoop.Outcome outcome = run(request -> removal(injectedSubClassOf()));

        assertTrue(outcome.solved);
        assertEquals(1, outcome.rounds);
        assertTrue(outcome.removedFault);
        assertEquals(0, outcome.collateral);
        assertEquals(1.0, outcome.validProposals(), 0.0);
        assertNull(outcome.error);
        assertTrue(Scorers.repair(outcome, java.util.Collections.<String>emptySet()).isPass());
    }

    /** Solved, but by deleting what the ontology says, not what was wrong: no pass. */
    @Test
    void removingTheDisjointnessIsCollateral() {
        RepairLoop.Outcome outcome = run(request -> removal(originalDisjointness()));

        assertTrue(outcome.solved);
        assertFalse(outcome.removedFault);
        assertEquals(1, outcome.collateral);
        assertFalse(Scorers.repair(outcome, java.util.Collections.<String>emptySet()).isPass());
    }

    @Test
    void proposingNothingStopsAfterThreeRoundsUnsolved() {
        RepairLoop.Outcome outcome = run(request -> "I am not sure what is wrong.");

        assertFalse(outcome.solved);
        assertEquals(3, outcome.rounds);
        assertEquals(3, asked.size());
        assertEquals(0.0, outcome.validProposals(), 0.0);
        assertTrue(asked.get(1).getMessages().get(asked.get(1).getMessages().size() - 1)
                .getText().contains("Nothing was removed"), "the next round says why");
    }

    /** An ontology that is inconsistent as it is: x is both a vegetarian and a meat topping. */
    private BenchCase asIsInconsistent(OWLOntology base) {
        OWLNamedIndividual x = base.getOWLOntologyManager().getOWLDataFactory()
                .getOWLNamedIndividual(MiniPizza.iri("x"));
        org.semanticweb.owlapi.model.OWLDataFactory f =
                base.getOWLOntologyManager().getOWLDataFactory();
        base.getOWLOntologyManager().addAxiom(base, f.getOWLClassAssertionAxiom(
                f.getOWLClass(MiniPizza.iri("VegetarianTopping")), x));
        base.getOWLOntologyManager().addAxiom(base, f.getOWLClassAssertionAxiom(
                f.getOWLClass(MiniPizza.iri("MeatTopping")), x));
        return new CaseGenerator(base, "mini-pizza.ofn", "abc", hermit, "HermiT", 1).repair(1)
                .get(0);
    }

    /**
     * Review I3 (1.137.0): removing the whole justification "solved" an as-is problem with no
     * collateral. Only one of the three was needed; the other two are collateral.
     */
    @Test
    void removingAWholeJustificationIsCollateral() {
        OWLOntology base = MiniPizza.load();
        BenchCase c = asIsInconsistent(base);
        List<String> lines = new ArrayList<String>();
        for (String line : c.getContext().get(0).getText().split("\n")) {
            if (line.startsWith("  ")) {
                lines.add(line.trim());
            }
        }
        StringBuilder all = new StringBuilder();
        for (String line : lines) {
            all.append(removal(line));
        }

        RepairLoop.Outcome outcome = RepairLoop.run(c, base, hermit, "HermiT", "ROLE", 1024,
                request -> new Reply(all.toString(), 1, 1, "stop", 1));

        assertTrue(outcome.solved);
        assertEquals(3, lines.size(), c.getContext().get(0).getText());
        assertEquals(2, outcome.collateral);
        assertFalse(Scorers.repair(outcome, java.util.Collections.<String>emptySet()).isPass());
    }

    @Test
    void removingOneNeededAxiomOfAnAsIsProblemPasses() {
        OWLOntology base = MiniPizza.load();
        BenchCase c = asIsInconsistent(base);
        String first = null;
        for (String line : c.getContext().get(0).getText().split("\n")) {
            if (line.startsWith("  ") && line.contains("MeatTopping") && !line.contains("Disjoint")) {
                first = line.trim();
            }
        }
        final String only = first;

        RepairLoop.Outcome outcome = RepairLoop.run(c, base, hermit, "HermiT", "ROLE", 1024,
                request -> new Reply(removal(only), 1, 1, "stop", 1));

        assertTrue(outcome.solved);
        assertTrue(outcome.removedFault);
        assertEquals(0, outcome.collateral);
        assertTrue(Scorers.repair(outcome, java.util.Collections.<String>emptySet()).isPass());
    }

    /**
     * 1.139.0, a repair that changes instead of deleting, on the datatype-clash fixture as the
     * loop runs it. The fixture's copper value is a float, so the range must stay float; the
     * aluminium value is retyped. (Making the range decimal would clash with copper instead.)
     */
    @Test
    void retypingTheDecimalValueSolvesTheDatatypeClash() throws java.io.IOException {
        OWLOntology clash = AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/datatype-clash.ofn"));
        BenchCase c = new CaseGenerator(clash, "datatype-clash.ofn", "abc", hermit, "HermiT", 1)
                .repair(1).get(0);
        String value = null;
        for (String line : c.getContext().get(0).getText().split("\n")) {
            if (line.startsWith("  ") && line.contains("^^xsd:decimal")) {
                value = line.trim();
            }
        }
        final String old = value;

        RepairLoop.Outcome outcome = RepairLoop.run(c, clash, hermit, "HermiT", "ROLE", 1024,
                request -> new Reply("```ontoboard-proposal\naction: replace-axiom\naxiom: " + old
                        + "\nwith: " + old.replace("xsd:decimal", "xsd:float") + "\n```", 1, 1,
                        "stop", 1));

        assertTrue(old != null, c.getContext().get(0).getText());
        assertTrue(outcome.solved, String.valueOf(outcome.error));
        assertEquals(1, outcome.rounds);
        assertTrue(outcome.removedFault);
        assertEquals(0, outcome.collateral);
    }

    /**
     * Review 1.141.0 I2: an answer HermiT cannot reason with (a facet xsd:string does not have)
     * ended RepairLoop.run with an UnsupportedFacetException, and with it the whole run.
     */
    @Test
    void anAnswerTheReasonerCannotHandleIsAnErrorNotAThrow() throws java.io.IOException {
        OWLOntology clash = AssistantBench.load(new java.io.File(
                "../benchmarks/assistant/ontologies/fixtures/datatype-clash.ofn"));
        BenchCase c = new CaseGenerator(clash, "datatype-clash.ofn", "abc", hermit, "HermiT", 1)
                .repair(1).get(0);

        RepairLoop.Outcome outcome = RepairLoop.run(c, clash, hermit, "HermiT", "ROLE", 1024,
                request -> new Reply("```ontoboard-proposal\naction: add-axiom\naxiom: "
                        + "'has melting point in degrees Celsius' Range xsd:string[>= 5]\n```", 1, 1, "stop", 1));

        assertFalse(outcome.solved);
        assertTrue(outcome.error != null && outcome.error.contains("could not check"),
                String.valueOf(outcome.error));
        assertEquals(1, outcome.replies.size(), "the answer paid for is kept");
    }

    /**
     * Review 1.148.0 bench-02: an applied addition counted as a removal, so a removal that was
     * refused, sent with an addition, was answered "The reasoner still finds the problem after
     * that removal." - false, and without the reason the removal was refused.
     */
    @Test
    void aRefusedRemovalSentWithAnAdditionIsSaidInTheNextRequest() {
        List<String> script = new ArrayList<String>();
        script.add(removal("Pizza SubClassOf Calzone")
                + "\n```ontoboard-proposal\naction: add-axiom\naxiom: City SubClassOf Country\n```");
        script.add(removal(injectedSubClassOf()));

        RepairLoop.Outcome outcome = run(request -> script.remove(0));

        assertTrue(outcome.solved);
        assertEquals(2, outcome.rounds);
        assertEquals(2, outcome.matched, "the addition was applied, and the removal of round 2");
        String next = asked.get(1).getMessages().get(asked.get(1).getMessages().size() - 1)
                .getText();
        assertTrue(next.contains("Nothing was removed: "), next);
        assertTrue(next.contains("Pizza SubClassOf Calzone"), next);
        assertTrue(next.contains("The addition was applied, but an addition alone cannot remove"
                + " the problem."), next);
        assertFalse(next.contains("after that removal"), next);
        assertFalse(next.contains(".."), next);
    }

    /** An addition alone: nothing was removed, and the next request says so. */
    @Test
    void anAdditionAloneIsNotCalledARemoval() {
        RepairLoop.Outcome outcome = run(request ->
                "```ontoboard-proposal\naction: add-axiom\naxiom: City SubClassOf Country\n```");

        assertFalse(outcome.solved);
        String next = asked.get(1).getMessages().get(asked.get(1).getMessages().size() - 1)
                .getText();
        assertTrue(next.contains("Nothing was removed: the answer proposed no removal. The"
                + " addition was applied"), next);
        assertFalse(next.contains("after that removal"), next);
    }

    /** The request the model got in this round, counted from 1. */
    private String request(int round) {
        ChatRequest request = asked.get(round - 1);
        return request.getMessages().get(request.getMessages().size() - 1).getText();
    }

    /** An axiom of the ontology that has nothing to do with the clash. */
    private String unrelatedAxiom() {
        for (OWLSubClassOfAxiom axiom : pizza.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (axiom.getSubClass().isNamed() && axiom.getSuperClass().isNamed()
                    && axiom.getSubClass().asOWLClass().getIRI().equals(MiniPizza.iri("Margherita"))) {
                return AssistantRendering.render(mutated, axiom);
            }
        }
        throw new AssertionError("Margherita SubClassOf NamedPizza");
    }

    /**
     * Review 1.149.0 bench-01: a block with an action OntoBoard does not know was refused as
     * "only changes to the logic are applied here", after an empty description, and the
     * validator's list of the actions it knows never reached the model.
     */
    @Test
    void aRemovalWithAnUnknownActionIsToldTheActionsThereAre() {
        List<String> script = new ArrayList<String>();
        script.add("```ontoboard-proposal\naction: remove\naxiom: " + injectedSubClassOf()
                + "\n```");
        script.add(removal(injectedSubClassOf()));

        run(request -> script.remove(0));

        String next = request(2);
        assertTrue(next.contains("remove-axiom"), next);
        assertFalse(next.contains("only changes to the logic"), next);
        assertFalse(next.contains(": :") || next.contains("Nothing was removed: :"), next);
    }

    /**
     * Review 1.149.0 bench-03: when one removal was applied and another refused, the next
     * request said only "after that removal", and the refusal and its reason were dropped.
     */
    @Test
    void aRefusedRemovalBesideAnAppliedOneIsSaid() {
        List<String> script = new ArrayList<String>();
        script.add(removal(unrelatedAxiom()) + "\n" + removal("Pizza SubClassOf Calzone"));
        script.add(removal(injectedSubClassOf()));

        RepairLoop.Outcome outcome = run(request -> script.remove(0));

        assertEquals(2, outcome.rounds);
        String next = request(2);
        assertTrue(next.contains("The reasoner still finds the problem after that removal."),
                next);
        assertTrue(next.contains("Not applied: \"Pizza SubClassOf Calzone\""), next);
        assertFalse(next.contains(".."), next);
    }

    /**
     * Review 1.149.0 bench-04: the same addition twice counted twice as a block that changed the
     * logic, though the second changed nothing, while a repeated removal counts against.
     */
    @Test
    void anAdditionThatChangesNothingCountsAgainst() {
        String addition = "```ontoboard-proposal\naction: add-axiom\naxiom: City SubClassOf"
                + " Country\n```";

        RepairLoop.Outcome outcome = run(request -> addition + "\n" + addition + "\n"
                + removal(injectedSubClassOf()));

        assertTrue(outcome.solved);
        assertEquals(3, outcome.proposals);
        assertEquals(2, outcome.matched, "the second addition changed nothing");
    }

    /**
     * Review 1.149.0 bench-06: for a problem the ontology already had, collateral was 0 when the
     * call failed, or the reasoner, after a removal outside the original justification; for an
     * injected one it is counted as it happens.
     */
    @Test
    void aRemovalOutsideTheJustificationCountsWhenTheNextCallFails() {
        OWLOntology base = MiniPizza.load();
        BenchCase c = asIsInconsistent(base);
        String outside = null;
        for (OWLSubClassOfAxiom axiom : base.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (axiom.getSubClass().isNamed() && axiom.getSuperClass().isNamed()
                    && axiom.getSubClass().asOWLClass().getIRI().equals(MiniPizza.iri("Margherita"))) {
                outside = AssistantRendering.render(base, axiom);
            }
        }
        final String removeIt = removal(outside);
        List<Integer> calls = new ArrayList<Integer>();

        RepairLoop.Outcome outcome = RepairLoop.run(c, base, hermit, "HermiT", "ROLE", 1024,
                request -> {
                    calls.add(1);
                    if (calls.size() > 1) {
                        throw new de.fizkarlsruhe.ise.ontoboard.assistant.connect
                                .ModelCallException(de.fizkarlsruhe.ise.ontoboard.assistant
                                        .connect.ModelCallException.Kind.UNREACHABLE,
                                        "the server stopped answering");
                    }
                    return new Reply(removeIt, 1, 1, "stop", 1);
                });

        assertFalse(outcome.solved);
        assertTrue(outcome.error != null, "the second call failed");
        assertEquals(1, outcome.collateral, "the removal outside the justification");
    }

    /**
     * Review 1.150.0 bench-01: for a problem the ontology already had, a removal from a
     * justification the loop itself sent in a later round counted as collateral, where the same
     * removal in round 1 counts as the fault. Five individuals in one clash, of which the context
     * shows three: round 1 removes from what it was shown, round 2 from a justification it was
     * shown only then, and the third call fails.
     */
    @Test
    void aRemovalFromAJustificationSentLaterIsNoCollateral() {
        OWLOntology base = MiniPizza.load();
        org.semanticweb.owlapi.model.OWLDataFactory f =
                base.getOWLOntologyManager().getOWLDataFactory();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String name : new String[] {"v", "w", "x", "y", "z"}) {
            OWLNamedIndividual one = f.getOWLNamedIndividual(MiniPizza.iri(name));
            base.getOWLOntologyManager().addAxiom(base, f.getOWLClassAssertionAxiom(
                    f.getOWLClass(MiniPizza.iri("VegetarianTopping")), one));
            base.getOWLOntologyManager().addAxiom(base, f.getOWLClassAssertionAxiom(
                    f.getOWLClass(MiniPizza.iri("MeatTopping")), one));
        }
        BenchCase c = new CaseGenerator(base, "mini-pizza.ofn", "abc", hermit, "HermiT", 1)
                .repair(1).get(0);
        List<Integer> calls = new ArrayList<Integer>();

        RepairLoop.Outcome outcome = RepairLoop.run(c, base, hermit, "HermiT", "ROLE", 1024,
                request -> {
                    calls.add(1);
                    if (calls.size() > 2) {
                        throw new de.fizkarlsruhe.ise.ontoboard.assistant.connect
                                .ModelCallException(de.fizkarlsruhe.ise.ontoboard.assistant
                                        .connect.ModelCallException.Kind.UNREACHABLE,
                                        "the server stopped answering");
                    }
                    StringBuilder shown = new StringBuilder(request.getSystem());
                    for (de.fizkarlsruhe.ise.ontoboard.assistant.connect.Message m
                            : request.getMessages()) {
                        shown.append('\n').append(m.getText());
                    }
                    String pick = null;
                    java.util.List<String> lines = new ArrayList<String>();
                    for (String line : shown.toString().split("\n")) {
                        if (line.startsWith("  ") && line.contains("VegetarianTopping")
                                && !line.contains("Disjoint")) {
                            lines.add(line.trim());
                            if (pick == null && !seen.contains(line.trim())) {
                                pick = line.trim();
                            }
                        }
                    }
                    seen.addAll(lines);
                    return new Reply(pick == null ? "I am not sure." : removal(pick), 1, 1,
                            "stop", 1);
                });

        assertFalse(outcome.solved);
        assertEquals(2, outcome.matched, "a removal in each of two rounds");
        assertTrue(outcome.removedFault);
        assertEquals(0, outcome.collateral, "both were in a justification the loop showed");
    }

    /**
     * Review 1.150.0 bench-02: a logic block without its axiom: line was named "null" in the next
     * request, a text the model never wrote.
     */
    @Test
    void aBlockWithoutItsAxiomIsNotCalledNull() {
        List<String> script = new ArrayList<String>();
        script.add("```ontoboard-proposal\naction: replace-axiom\nwith: City SubClassOf Country"
                + "\n```");
        script.add(removal(injectedSubClassOf()));

        run(request -> script.remove(0));

        String next = request(2);
        assertTrue(next.contains("Nothing was removed"), next);
        assertFalse(next.contains("\"null\""), next);
    }

    /**
     * Review 1.150.0 bench-03: a replacement of an axiom an earlier block of the same answer
     * removed was dropped with its new axiom, and the next request did not say so.
     */
    @Test
    void aReplacementOfAnAxiomAlreadyRemovedIsSaid() {
        List<String> script = new ArrayList<String>();
        script.add(removal(unrelatedAxiom()) + "\n```ontoboard-proposal\naction: replace-axiom"
                + "\naxiom: " + unrelatedAxiom() + "\nwith: Margherita SubClassOf Pizza\n```");
        script.add(removal(injectedSubClassOf()));

        run(request -> script.remove(0));

        String next = request(2);
        assertTrue(next.contains("Not applied: \"" + unrelatedAxiom() + "\""), next);
        assertTrue(next.contains("already removed"), next);
    }

    /** A wrong first try, then the right one: the second round gets the new justification. */
    @Test
    void aSecondRoundCanSolveWhatTheFirstDidNot() {
        String unrelated = AssistantRendering.render(mutated, pizza.getAxioms(
                AxiomType.SUBCLASS_OF).iterator().next());
        List<String> script = new ArrayList<String>();
        script.add(removal("Pizza SubClassOf Calzone"));
        script.add(removal(injectedSubClassOf()));

        RepairLoop.Outcome outcome = run(request -> script.remove(0));

        assertTrue(outcome.solved);
        assertEquals(2, outcome.rounds);
        assertEquals(0.5, outcome.validProposals(), 0.0);
        assertFalse(unrelated.isEmpty());
    }
}
