package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkQuestions;
import de.fizkarlsruhe.ise.ontoboard.odk.RecordedProject;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Write a test from a question...: the test is a question of the project - a {@code .rq} file
 * in {@code src/sparql} - in an ODK project as in one of the older layout, and it is asked of
 * the ontology with its imports in both. In an ODK project that takes the rule OntoBoard
 * keeps in the project's own makefile ({@link OdkQuestions}): ODK's own checks read the edit
 * file without its imports, where a question about an imported term has no answer. The
 * configuration is not touched, so nothing has to be brought in line afterwards, and the
 * dialog says what ODK's test does with the question.
 */
class QuestionCheckTest {

    private static final String QUESTION = "Does every pizza have a base?";
    private static final String NAME = "does_every_pizza_have_a_base";
    private static final String SPARQL = "# " + QUESTION + "\nSELECT ?term WHERE { }\n";
    private static final String OWN = "src/ontology/eggboil.Makefile";

    /** The eggboil seed, with OntoBoard's configuration over ODK's. */
    private static File odkProject(File temp) throws IOException {
        File root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        Files.write(new File(root, "src/ontology/eggboil-odk.yaml").toPath(),
                Goldens.configBytes("eggboil"));
        return root;
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** Every file under the folder whose bytes are not what they were, or that came or went. */
    private static TreeSet<String> changed(Map<String, byte[]> before, Map<String, byte[]> after) {
        TreeSet<String> paths = new TreeSet<String>(before.keySet());
        paths.addAll(after.keySet());
        TreeSet<String> changed = new TreeSet<String>();
        for (String path : paths) {
            if (!before.containsKey(path) || !after.containsKey(path)
                    || !Arrays.equals(before.get(path), after.get(path))) {
                changed.add(path);
            }
        }
        return changed;
    }

    private static Map<String, byte[]> tree(File root) {
        return new TreeMap<String, byte[]>(Goldens.tree(root));
    }

    // ------------------------------------------------------------------ an ODK project

    @Test
    void inAnOdkProjectTheQuestionIsARqFileAndTheRuleThatAsksItIsSeenTo(@TempDir File temp)
            throws IOException {
        File root = odkProject(temp);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        String ownBefore = text(new File(root, OWN));
        Map<String, byte[]> before = tree(root);
        boolean odk = QuestionTestAction.inAnOdkProject(edit);
        assertTrue(odk, "ODK's Makefile: an ODK project");

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, odk, QUESTION, SPARQL);

        File question = new File(root, "src/sparql/" + NAME + ".rq");
        assertEquals(question, saved.getFile());
        assertTrue(saved.isWritten() && saved.isAsked());
        assertEquals(SPARQL, text(question));
        assertEquals(new TreeSet<String>(Arrays.asList(OWN, "src/sparql/" + NAME + ".rq")),
                changed(before, tree(root)), "the question and the rule, and nothing else");
        assertEquals(OdkQuestions.with(ownBefore), text(new File(root, OWN)));
        assertArrayEquals(before.get("src/ontology/eggboil-odk.yaml"), Files.readAllBytes(
                new File(root, "src/ontology/eggboil-odk.yaml").toPath()),
                "a question is no ODK check: the configuration is not touched");
        assertFalse(new File(root, "src/sparql/" + NAME + "-violation.sparql").exists());
    }

    /** What the dialog says the first time, word for word. */
    @Test
    void theFirstQuestionSaysThatTheRuleWasAdded(@TempDir File temp) throws IOException {
        File root = odkProject(temp);

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        assertEquals("Saved as src/sparql/" + NAME + ".rq. ODK's test asks it of the ontology "
                + "with its imports: the rule that asks the project's questions was added to "
                + "eggboil.Makefile.\n\n", saved.getMessage());
    }

    @Test
    void theNextQuestionFindsTheRuleInItsPlace(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.save(root, true, QUESTION, SPARQL);
        Map<String, byte[]> before = tree(root);

        QuestionTestAction.Saved next = QuestionTestAction.save(root, true,
                "Is every topping a food?", SPARQL);

        assertTrue(next.isAsked());
        assertEquals(new TreeSet<String>(Arrays.asList("src/sparql/is_every_topping_a_food.rq")),
                changed(before, tree(root)), "the rule is there: only the question is written");
        assertEquals("Saved as src/sparql/is_every_topping_a_food.rq. ODK's test asks it of the "
                + "ontology with its imports (the rule for the project's questions in "
                + "eggboil.Makefile).\n\n", next.getMessage());
    }

    /** Asking the same question again replaces the test. */
    @Test
    void askingAgainReplacesTheTest(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.save(root, true, QUESTION, SPARQL);

        QuestionTestAction.Saved again =
                QuestionTestAction.save(root, true, QUESTION, SPARQL + "# again\n");

        assertEquals(SPARQL + "# again\n", text(again.getFile()));
        assertTrue(again.isAsked());
        assertEquals(1, text(new File(root, OWN)).split("OntoBoard: end of questions", -1).length
                - 1, "the rule is there once");
    }

    /** A rule somebody changed is written again as it was, and that is said. */
    @Test
    void aRuleThatWasChangedIsWrittenAgainAndThatIsSaid(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.save(root, true, QUESTION, SPARQL);
        File own = new File(root, OWN);
        String good = text(own);
        Files.write(own.toPath(), good.replace("*.rq", "cq*.rq").getBytes(StandardCharsets.UTF_8));

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true,
                "Is every topping a food?", SPARQL);

        assertEquals(good, text(own));
        assertTrue(saved.isAsked());
        assertTrue(saved.getMessage().contains("was written again in eggboil.Makefile, where it "
                + "had been changed"), saved.getMessage());
    }

    /**
     * The question is written, the rule then cannot be: the dialog says which half happened,
     * and the question stays.
     */
    @Test
    void whenTheRuleCannotBeWrittenTheQuestionIsKeptAndThatIsSaid(@TempDir File temp)
            throws IOException {
        File root = odkProject(temp);
        File own = new File(root, OWN);
        // A folder where the file should be: it can neither be read nor written as a file.
        Files.delete(own.toPath());
        Files.createDirectory(own.toPath());

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        assertTrue(saved.isWritten());
        assertFalse(saved.isAsked());
        assertEquals(SPARQL, text(saved.getFile()), "the question is kept");
        String message = saved.getMessage();
        assertTrue(message.startsWith("Saved as src/sparql/" + NAME + ".rq, but the rule that "
                + "asks the project's questions could not be written into eggboil.Makefile"),
                message);
        assertTrue(message.contains("ODK's test does not ask this question"), message);
        assertTrue(message.endsWith("\n\n"), "the test's answer follows");
    }

    /** A question that cannot be saved says so, and nothing is seen to. */
    @Test
    void aQuestionThatCannotBeSavedSaysSo(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        String ownBefore = text(new File(root, OWN));
        // A folder under the name the question's file would have.
        Files.createDirectories(new File(root, "src/sparql/" + NAME + ".rq").toPath());

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        assertFalse(saved.isWritten());
        assertFalse(saved.isAsked());
        assertTrue(saved.getMessage().startsWith("Could not save it"), saved.getMessage());
        assertEquals(ownBefore, text(new File(root, OWN)), "no rule for a question that is not "
                + "there");
    }

    // ------------------------------------------------------------------ the older layout

    /** In a project of the older layout the test is a .rq file, as it always was. */
    @Test
    void inAProjectOfTheOlderLayoutNothingHasChanged(@TempDir File temp) throws IOException {
        File root = new File(temp, "eggboil");
        File edit = RecordedProject.of1352(root);
        assertTrue(edit.isFile());
        Map<String, byte[]> before = tree(root);
        boolean odk = QuestionTestAction.inAnOdkProject(edit);
        assertFalse(odk, "OntoBoard's own Makefile: the older layout");

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, odk, QUESTION, SPARQL);

        File rq = new File(root, "src/sparql/" + NAME + ".rq");
        assertEquals(rq, saved.getFile());
        assertEquals(SPARQL, text(rq));
        assertEquals(new TreeSet<String>(Arrays.asList("src/sparql/" + NAME + ".rq")),
                changed(before, tree(root)), "the .rq file and nothing else: that layout's own "
                        + "Makefile asks every .rq it finds");
        assertEquals("Saved as src/sparql/" + NAME + ".rq.\n\n", saved.getMessage());
        assertTrue(saved.isAsked());
        assertFalse(QuestionTestAction.inAnOdkProject(new File(temp, "loose.owl")));
        assertFalse(QuestionTestAction.inAnOdkProject(null));
    }
}
