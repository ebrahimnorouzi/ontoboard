package de.fizkarlsruhe.ise.ontoboard.assistant.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.robot.Reasoners;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The definition task's scores (round-2 spec, "Evaluation"): a valid proposal for the right term,
 * the genus named, and word overlap with the held-back definition. Validity is the plugin's own:
 * what would become a card the person can accept.
 */
class DefinitionScoresTest {

    private final OWLOntology pizza = MiniPizza.load();
    private BenchCase vegetarianPizza;
    private OWLOntology without;

    @BeforeEach
    void pickTheCase() {
        for (BenchCase c : new CaseGenerator(pizza, "mini-pizza.ofn", "abc",
                Reasoners.Choice.HERMIT.newFactory(), "HermiT", 1).definition(5)) {
            if (c.getSubjectIri().endsWith("#VegetarianPizza")) {
                vegetarianPizza = c;
            }
        }
        without = CaseGenerator.withoutDefinition(pizza, IRI.create(vegetarianPizza.getSubjectIri()));
    }

    private static String proposal(String subject, String value) {
        return "Here is one.\n```ontoboard-proposal\naction: set\nsubject: " + subject
                + "\nproperty: definition\nvalue: " + value + "\nreason: genus and differentia\n```";
    }

    private Score score(String reply) {
        return Scorers.definition(vegetarianPizza, reply, Collections.<String>emptySet(), without);
    }

    @Test
    void theCaseIsWhatTheFixtureSays() {
        assertEquals("vegetarian pizza", vegetarianPizza.getSubject());
        assertEquals(Collections.singletonList("Pizza"), vegetarianPizza.getGenus());
        assertEquals("A pizza with no meat.", vegetarianPizza.getReference());
    }

    @Test
    void aValidProposalThatNamesItsGenusPasses() {
        Score score = score(proposal("vegetarian pizza", "A pizza with no meat or fish topping."));

        assertEquals(1.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(1.0, score.getValues().get("namesGenus"), 0.0);
        // One block, one definition (1.146.0, review 1.145.0 bench-B).
        assertEquals(Double.valueOf(1.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertTrue(score.isPass());
    }

    /**
     * Review 1.145.0 bench-B, the checker's note: like text to axiom, only the first definition
     * offered for the term was scored, so a right one followed by an alternative passed and the
     * other order failed. The request asks for one block.
     */
    @Test
    void aRightDefinitionWithAnAlternativeDoesNotPassInEitherOrder() {
        String right = proposal("vegetarian pizza", "A pizza with no meat or fish topping.");
        String other = proposal("vegetarian pizza", "A dish that contains no meat.");

        Score rightFirst = score(right + "\n" + other);
        Score otherFirst = score(other + "\n" + right);

        assertFalse(rightFirst.isPass(), rightFirst.getValues().toString());
        assertFalse(otherFirst.isPass(), otherFirst.getValues().toString());
        assertEquals(Double.valueOf(2.0), rightFirst.getValues().get("proposals"));
        assertEquals(Double.valueOf(2.0), otherFirst.getValues().get("proposals"));
        // The other columns still describe the first block, so the row shows why it failed.
        assertEquals(1.0, rightFirst.getValues().get("namesGenus"), 0.0);
        assertEquals(0.0, otherFirst.getValues().get("namesGenus"), 0.0);
    }

    /** A block repeated word for word is one definition, not a second proposal. */
    @Test
    void aDefinitionRepeatedWordForWordIsOneAnswer() {
        String right = proposal("vegetarian pizza", "A pizza with no meat or fish topping.");

        Score twice = score(right + "\n" + right);

        assertEquals(Double.valueOf(1.0), twice.getValues().get("proposals"),
                twice.getValues().toString());
        assertTrue(twice.isPass(), twice.getValues().toString());
    }

    /**
     * Review 1.147.0 bench-02: a repeat that differs only by a no-break or a zero-width space
     * read the same and was still a second card.
     */
    @Test
    void aDefinitionRepeatedWithAnInvisibleSpaceIsOneAnswer() {
        Score score = score(proposal("vegetarian pizza", "A pizza with no meat or fish topping.")
                + "\n" + proposal("vegetarian pizza", "A pizza\u00a0with no meat or\u200b fish"
                        + " topping."));

        assertEquals(Double.valueOf(1.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertTrue(score.isPass(), score.getValues().toString());
    }

    /**
     * The 1.148.0 bench reviewer's declined note: a soft hyphen, a zero-width non-joiner or a
     * left-to-right mark - format characters a text shows as nothing - still made a second card.
     */
    @Test
    void aDefinitionRepeatedWithAnInvisibleFormatCharacterIsOneAnswer() {
        for (String invisible : new String[] {"­", "‌", "‍", "‎", "‏",
            "⁡"}) {
            Score score = score(proposal("vegetarian pizza", "A pizza with no meat or fish"
                    + " topping.") + "\n" + proposal("vegetarian pizza", "A pizza with no me"
                            + invisible + "at or fish topping."));

            assertEquals(Double.valueOf(1.0), score.getValues().get("proposals"),
                    Integer.toHexString(invisible.charAt(0)) + " " + score.getValues());
            assertTrue(score.isPass(), score.getValues().toString());
        }
    }

    /**
     * Review 1.149.0 bench-02: a format character was dropped when cards were compared but split
     * the word it stood in when words were counted, so a definition with a soft hyphen inside its
     * genus failed, and the same two blocks passed or failed by their order.
     */
    @Test
    void aFormatCharacterInsideAWordDoesNotSplitIt() {
        String plain = proposal("vegetarian pizza", "A pizza with no meat or fish topping.");
        String hyphened = proposal("vegetarian pizza", "A piz­za with no meat or fish"
                + " topping.");

        Score alone = score(hyphened);
        Score plainFirst = score(plain + "\n" + hyphened);
        Score hyphenedFirst = score(hyphened + "\n" + plain);

        assertEquals(Double.valueOf(1.0), alone.getValues().get("namesGenus"),
                alone.getValues().toString());
        assertTrue(alone.isPass(), alone.getValues().toString());
        assertTrue(plainFirst.isPass(), plainFirst.getValues().toString());
        assertTrue(hyphenedFirst.isPass(), hyphenedFirst.getValues().toString());
    }

    /**
     * Review 1.147.0 bench-02: a set card and an add card with the same definition are two cards
     * to judge, and counted as one.
     */
    @Test
    void aSetAndAnAddOfTheSameDefinitionAreTwoCards() {
        String set = proposal("vegetarian pizza", "A pizza with no meat or fish topping.");
        String add = set.replace("action: set", "action: add");

        Score score = score(set + "\n" + add);

        assertEquals(Double.valueOf(2.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertFalse(score.isPass(), score.getValues().toString());
    }

    /**
     * Review 1.146.0 bench-01: a definition repeated with one more space counted as a second
     * card, so a right answer failed. Two blocks that read the same are one card.
     */
    @Test
    void aDefinitionRepeatedWithOtherSpacingIsOneAnswer() {
        Score score = score(proposal("vegetarian pizza", "A pizza with no meat or fish topping.")
                + "\n" + proposal("vegetarian pizza", "A pizza  with no meat or fish  topping."));

        assertEquals(Double.valueOf(1.0), score.getValues().get("proposals"),
                score.getValues().toString());
        assertTrue(score.isPass(), score.getValues().toString());
    }

    /**
     * Every card the answer offers counts (1.146.0, ruling on the review of the bench-B fix): a
     * definition of another term, or a comment on this one, is a second card the person must
     * judge, where the request asks for one block.
     */
    @Test
    void anotherCardBesideTheDefinitionDoesNotPass() {
        String right = proposal("vegetarian pizza", "A pizza with no meat or fish topping.");
        String[] others = {
            proposal("cheese topping", "A pizza topping made of cheese."),
            "```ontoboard-proposal\naction: add\nsubject: vegetarian pizza\nproperty: comment\n"
                    + "value: Popular.\nreason: helpful\n```"};
        for (String other : others) {
            Score rightFirst = score(right + "\n" + other);
            Score otherFirst = score(other + "\n" + right);

            assertFalse(rightFirst.isPass(), other + rightFirst.getValues());
            assertFalse(otherFirst.isPass(), other + otherFirst.getValues());
            assertEquals(Double.valueOf(2.0), rightFirst.getValues().get("proposals"), other);
            assertEquals(Double.valueOf(2.0), otherFirst.getValues().get("proposals"), other);
            assertEquals(1.0, otherFirst.getValues().get("namesGenus"), 0.0);
        }
    }

    /**
     * The counterpart of TextToAxiomTest.everyShippedCaseIsPassedByItsGoldAxiom (1.146.0, review
     * 1.145.0 bench-B): each shipped case's own held-back definition, sent as one block, is one
     * valid proposal, and the one-block rule leaves its verdict as it was - a pass exactly when
     * it names its genus.
     */
    @Test
    void everyShippedCaseCountsItsReferenceAsOneValidProposal() throws java.io.IOException {
        java.io.File folder = new java.io.File("../benchmarks/assistant/cases");
        java.io.File[] files = folder.listFiles((dir, name) -> name.endsWith(".jsonl"));
        java.util.Arrays.sort(files);
        java.util.Map<String, OWLOntology> loaded = new java.util.HashMap<String, OWLOntology>();
        java.util.List<String> wrong = new java.util.ArrayList<String>();
        int checked = 0;
        for (java.io.File file : files) {
            for (BenchCase c : BenchCases.read(file)) {
                if (!BenchCase.DEFINITION.equals(c.getTask())) {
                    continue;
                }
                java.io.File source = new java.io.File(folder, c.getOntology());
                OWLOntology ontology = loaded.get(source.getPath());
                if (ontology == null) {
                    ontology = AssistantBench.load(source);
                    loaded.put(source.getPath(), ontology);
                }
                Score score = Scorers.definition(c, proposal(c.getSubject(),
                        c.getReference().replace('\n', ' ')), Collections.<String>emptySet(),
                        CaseGenerator.withoutDefinition(ontology, IRI.create(c.getSubjectIri())));
                checked++;
                java.util.Map<String, Double> values = score.getValues();
                if (!Double.valueOf(1.0).equals(values.get("validProposal"))
                        || !Double.valueOf(1.0).equals(values.get("proposals"))
                        || score.isPass() != Double.valueOf(1.0).equals(values.get("namesGenus"))) {
                    wrong.add(c.getId() + " " + values);
                }
            }
        }
        assertTrue(checked > 0, "no definition case found under " + folder.getPath());
        assertEquals(Collections.<String>emptyList(), wrong);
    }

    /** Content words {pizza, no, meat} against {pizza, no, meat, fish, topping}: F1 0.75. */
    @Test
    void overlapIsTheF1OfContentWords() {
        Score score = score(proposal("vegetarian pizza", "A pizza with no meat or fish topping."));

        assertEquals(0.75, score.getValues().get("overlap"), 0.001);
    }

    @Test
    void withoutTheGenusItFails() {
        Score score = score(proposal("vegetarian pizza", "A dish that contains no meat."));

        assertEquals(1.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(0.0, score.getValues().get("namesGenus"), 0.0);
        assertFalse(score.isPass());
    }

    @Test
    void aProposalForAnotherTermIsNotValid() {
        Score score = score(proposal("cheese topping", "A pizza topping made of cheese."));

        assertEquals(0.0, score.getValues().get("validProposal"), 0.0);
        assertFalse(score.isPass());
    }

    @Test
    void prosewithoutAProposalIsNotValid() {
        Score score = score("A vegetarian pizza is a pizza with no meat.");

        assertEquals(0.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(0.0, score.getValues().get("namesGenus"), 0.0);
        assertFalse(score.isPass());
    }

    /** Writing the held-back definition word for word is the best answer, not "already there". */
    @Test
    void theReferenceItselfIsValid() {
        Score score = score(proposal("vegetarian pizza", "A pizza with no meat."));

        assertEquals(1.0, score.getValues().get("validProposal"), 0.0);
        assertEquals(1.0, score.getValues().get("overlap"), 0.001);
    }
}
