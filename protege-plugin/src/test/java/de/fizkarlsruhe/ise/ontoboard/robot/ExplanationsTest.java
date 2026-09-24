package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/** ROBOT's explain, over the two pizzas the suite keeps deliberately broken. */
class ExplanationsTest {

    private static OWLReasonerFactory hermit() {
        return Reasoners.Choice.HERMIT.newFactory();
    }

    private static OWLReasonerFactory elk() {
        return Reasoners.Choice.ELK.newFactory();
    }

    /** A healthy ontology must read as healthy, not as an empty failure. */
    @Test
    void ahealthyOntologyExplainsNothing() throws Exception {
        Explanations.Result result = Explanations.run(PizzaOntology.v2(), elk());

        assertTrue(result.isConsistent());
        assertTrue(result.getUnsatisfiable().isEmpty(), result.getUnsatisfiable().toString());
        assertTrue(result.isClean());
        assertTrue(result.getJustifications().isEmpty());
    }

    /**
     * An unsatisfiable class is named, and the axioms responsible are listed.
     *
     * <p>This is the question Protege's explanation workbench makes you ask one entailment at a
     * time: not "is something wrong" - the reasoner already said so - but "which axioms did it".
     */
    @Test
    void anUnsatisfiableClassIsExplainedByTheAxiomsThatCauseIt() throws Exception {
        OWLOntology broken = PizzaOntology.withUnsatisfiableClass();

        Explanations.Result result = Explanations.run(broken, hermit());

        assertTrue(result.isConsistent(), "the fixture is unsatisfiable, not inconsistent");
        assertFalse(result.isClean());
        assertFalse(result.getUnsatisfiable().isEmpty(),
                "the reasoner reports an unsatisfiable class, so this must name it");
        assertFalse(result.getJustifications().isEmpty(),
                "an unsatisfiable class with no justification tells a curator nothing");

        for (Explanations.Justification justification : result.getJustifications()) {
            assertFalse(justification.getEntailment().trim().isEmpty(),
                    "every justification must say what it explains");
            assertFalse(justification.getAxioms().isEmpty(),
                    "a justification with no axioms is not a justification");
        }
    }

    /**
     * An inconsistent ontology is explained as such, and its unsatisfiable classes are not listed.
     *
     * <p>In an inconsistent ontology every class is unsatisfiable, so listing them would bury the
     * one fact worth reading.
     */
    @Test
    void anInconsistentOntologyIsExplainedInsteadOfEveryClass() throws Exception {
        Explanations.Result result = Explanations.run(PizzaOntology.madeInconsistent(), hermit());

        assertFalse(result.isConsistent());
        assertFalse(result.isClean());
        assertTrue(result.getUnsatisfiable().isEmpty(),
                "an inconsistent ontology makes every class unsatisfiable; listing them all buries "
                        + "the inconsistency: " + result.getUnsatisfiable());
        assertFalse(result.getJustifications().isEmpty(),
                "an inconsistency with no explanation is the case this feature exists for");
    }

    /**
     * The axiom-impact summary is ROBOT's own, and it must actually be produced.
     *
     * <p>It is the part that turns twelve separate investigations into one answer: which single
     * axiom appears in the most justifications. Rendering it is safe in a bundle because its only
     * dependency is the OWL API's Manchester renderer - unlike renderExplanationAsMarkdown, which
     * needs a Protege class and is deliberately not used.
     */
    @Test
    void theImpactSummaryNamesTheAxiomsBehindTheMostJustifications() throws Exception {
        Explanations.Result result =
                Explanations.run(PizzaOntology.withUnsatisfiableClass(), hermit());

        assertFalse(result.getImpactSummary().trim().isEmpty(),
                "ROBOT's impact summary is the point of computing several justifications");
    }

    /** Rendering uses labels where they exist, so an explanation is readable by a curator. */
    @Test
    void axiomsAreRenderedWithLabelsAndOnOneLine() throws Exception {
        Explanations.Result result =
                Explanations.run(PizzaOntology.withUnsatisfiableClass(), hermit());

        for (Explanations.Justification justification : result.getJustifications()) {
            for (String axiom : justification.getAxioms()) {
                assertFalse(axiom.contains("\n"), "a table row cannot contain a newline: " + axiom);
                assertFalse(axiom.startsWith("http"),
                    "axioms must render with short names or labels, not raw IRIs: " + axiom);
            }
        }
    }

    @Test
    void groupingCollectsJustificationsUnderWhatTheyExplain() throws Exception {
        Explanations.Result result =
                Explanations.run(PizzaOntology.withUnsatisfiableClass(), hermit());

        java.util.Map<String, java.util.List<Explanations.Justification>> grouped =
                Explanations.byEntailment(result);

        int total = 0;
        for (java.util.List<Explanations.Justification> forOne : grouped.values()) {
            total += forOne.size();
        }
        assertTrue(total == result.getJustifications().size(),
                "grouping must not lose or duplicate a justification");
    }

    @Test
    void missingArgumentsAreRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Explanations.run(null, elk()));
        assertThrows(IllegalArgumentException.class,
                () -> Explanations.run(PizzaOntology.v2(), null));
    }
}
