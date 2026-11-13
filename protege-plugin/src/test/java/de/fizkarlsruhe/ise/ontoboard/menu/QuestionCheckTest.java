package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkChecks;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkProjectConfig;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkScaffold;
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
 * Write a test from a question..., in an ODK project: the test is one of ODK's checks - its file
 * and its name in the configuration - and the dialog says so. In a project of the older layout
 * nothing has changed.
 */
class QuestionCheckTest {

    private static final String QUESTION = "Does every pizza have a base?";
    private static final String NAME = "does_every_pizza_have_a_base";
    private static final String SPARQL = "# " + QUESTION + "\nSELECT ?term WHERE { }\n";

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

    @Test
    void inAnOdkProjectTheQuestionIsAnOdkCheck(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        File config = new File(root, "src/ontology/eggboil-odk.yaml");
        String configBefore = text(config);
        Map<String, byte[]> before = tree(root);
        boolean odk = QuestionTestAction.inAnOdkProject(edit);
        assertTrue(odk, "ODK's Makefile: an ODK project");

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, odk, QUESTION, SPARQL);

        File check = new File(root, "src/sparql/" + NAME + "-violation.sparql");
        assertEquals(check, saved.getFile());
        assertTrue(saved.isWritten() && saved.isListed());
        assertEquals(SPARQL, text(check));
        assertEquals(NAME, OdkChecks.declaredIn(text(config))
                .get(OdkChecks.declaredIn(text(config)).size() - 1), "listed last");
        assertEquals(configBefore + "    - " + NAME + "\n", text(config), "one line more");
        assertEquals(new TreeSet<String>(Arrays.asList("src/ontology/eggboil-odk.yaml",
                "src/sparql/" + NAME + "-violation.sparql")), changed(before, tree(root)),
                "nothing else in the folder changed");
        assertFalse(new File(root, "src/sparql/" + NAME + ".rq").exists());

        String message = saved.getMessage();
        assertTrue(message.contains("src/sparql/" + NAME + "-violation.sparql"), message);
        assertTrue(message.contains("eggboil-odk.yaml"), message);
        assertTrue(message.contains("robot_report: custom_sparql_checks"), message);
        assertTrue(message.contains("OntoBoard > Project > Update project files..."), message);
        assertTrue(message.startsWith(QuestionTestAction.savedMessage(
                "src/sparql/" + NAME + "-violation.sparql", "eggboil-odk.yaml")), message);
    }

    /** The message the brief gives, word for word. */
    @Test
    void theMessageSaysWhereTheCheckIsAndWhatRunsIt() {
        assertEquals("Saved as src/sparql/every_pizza_has_a_base-violation.sparql and listed in "
                + "eggboil-odk.yaml (robot_report: custom_sparql_checks), which is how ODK's test "
                + "finds it. ODK's Makefile is written from that file: it runs the new check after "
                + "OntoBoard > Project > Update project files...",
                QuestionTestAction.savedMessage(
                        "src/sparql/every_pizza_has_a_base-violation.sparql", "eggboil-odk.yaml")
                        .replace("\n", " "));
    }

    /**
     * Where the list did not exist, ODK's default checks are named in it now, and the dialog says
     * so - it is a change to the file the user did not ask for in words.
     */
    @Test
    void theFirstCheckSaysOdksOwnAreListedToo(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        File config = new File(root, "src/ontology/eggboil-odk.yaml");
        String full = text(config);
        Files.write(config.toPath(), full.substring(0, full.indexOf("  custom_sparql_checks:"))
                .getBytes(StandardCharsets.UTF_8));

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        assertTrue(saved.isListed());
        assertEquals(Arrays.asList("owldef-self-reference", "iri-range", "label-with-iri",
                "multiple-replaced_by", NAME), OdkChecks.declaredIn(text(config)));
        assertTrue(saved.getMessage().contains("owldef-self-reference, iri-range, label-with-iri, "
                + "multiple-replaced_by"), saved.getMessage());
    }

    /** Asking the same question again replaces the test, and the list names it once. */
    @Test
    void askingAgainReplacesTheTestAndListsItOnce(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        File config = new File(root, "src/ontology/eggboil-odk.yaml");
        QuestionTestAction.save(root, true, QUESTION, SPARQL);
        String listed = text(config);

        QuestionTestAction.Saved again =
                QuestionTestAction.save(root, true, QUESTION, SPARQL + "# again\n");

        assertEquals(listed, text(config));
        assertEquals(SPARQL + "# again\n", text(again.getFile()));
        assertTrue(again.isListed());
        assertTrue(again.getMessage().contains("already"), again.getMessage());
    }

    /**
     * The file is written, the configuration then cannot be: the dialog says which half
     * happened, and the file stays.
     */
    @Test
    void aConfigurationThatCannotTakeTheNameKeepsTheTestAndSaysSo(@TempDir File temp)
            throws IOException {
        File root = odkProject(temp);
        File config = new File(root, "src/ontology/eggboil-odk.yaml");
        String broken = text(config) + "id: twice\n";
        Files.write(config.toPath(), broken.getBytes(StandardCharsets.UTF_8));

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        assertTrue(saved.isWritten());
        assertFalse(saved.isListed());
        assertEquals(SPARQL, text(saved.getFile()), "the test is kept");
        assertEquals(broken, text(config), "the configuration is untouched");
        String message = saved.getMessage();
        assertTrue(message.contains("src/sparql/" + NAME + "-violation.sparql"), message);
        assertTrue(message.contains("not listed in eggboil-odk.yaml"), message);
        assertTrue(message.contains("more than once"), "why: " + message);
        assertTrue(message.contains(NAME), message);
    }

    /**
     * The configuration changed, so the project's files follow it at once - and the dialog
     * says that instead of sending the person to Update project files.
     */
    @Test
    void whereTheProjectsFilesFollowedTheDialogSaysSo(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);
        assertTrue(saved.changedTheConfiguration(), "the list has one name more");

        String message = QuestionTestAction.messageWith(saved, "Then the project's files were "
                + "brought in line with it: Updated 1 file, by OntoBoard itself.", true);

        assertTrue(message.contains("src/sparql/" + NAME + "-violation.sparql"), message);
        assertTrue(message.contains("Updated 1 file, by OntoBoard itself."), message);
        assertTrue(message.contains("ODK's test runs the new check from now on."), message);
        assertFalse(message.contains("it runs the new check after"),
                "nothing is left to do: " + message);
        assertTrue(message.endsWith("\n\n"), "the test's answer follows");
    }

    /** Where nobody could bring them in line, the dialog says why and what does. */
    @Test
    void whereTheyDidNotTheDialogSaysWhyAndThatTheCheckDoesNotRunYet(@TempDir File temp)
            throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.Saved saved = QuestionTestAction.save(root, true, QUESTION, SPARQL);

        String message = QuestionTestAction.messageWith(saved, "A container is switched off "
                + "and this project is made with another ODK.", false);

        assertTrue(message.contains("ODK's test does not run the new check yet."), message);
        assertTrue(message.contains("A container is switched off and this project is made "
                + "with another ODK."), message);
        assertFalse(message.contains("from now on"), message);
        assertTrue(message.endsWith("\n\n"));
    }

    /** A check the list names already changes no configuration: nothing has to follow. */
    @Test
    void aCheckListedAlreadyChangesNoConfiguration(@TempDir File temp) throws IOException {
        File root = odkProject(temp);
        QuestionTestAction.save(root, true, QUESTION, SPARQL);

        QuestionTestAction.Saved again =
                QuestionTestAction.save(root, true, QUESTION, SPARQL + "# again\n");

        assertTrue(again.isListed());
        assertFalse(again.changedTheConfiguration());
    }

    /** In a project of the older layout the test is a .rq file and no configuration is touched. */
    @Test
    void inAProjectOfTheOlderLayoutNothingHasChanged(@TempDir File temp) throws IOException {
        OdkProjectConfig project = new OdkProjectConfig("demo", "Demo", "",
                "http://example.org/demo.owl",
                "https://creativecommons.org/publicdomain/zero/1.0/", temp);
        OdkScaffold.create(project);
        File root = project.getProjectRoot();
        File edit = new File(root, "src/ontology/demo-edit.owl");
        assertTrue(edit.isFile());
        Map<String, byte[]> before = tree(root);
        boolean odk = QuestionTestAction.inAnOdkProject(edit);
        assertFalse(odk, "OntoBoard's own Makefile: the older layout");

        QuestionTestAction.Saved saved = QuestionTestAction.save(root, odk, QUESTION, SPARQL);

        File rq = new File(root, "src/sparql/" + NAME + ".rq");
        assertEquals(rq, saved.getFile());
        assertEquals(SPARQL, text(rq));
        assertEquals(new TreeSet<String>(Arrays.asList("src/sparql/" + NAME + ".rq")),
                changed(before, tree(root)), "the .rq file and nothing else");
        assertArrayEquals(before.get("src/ontology/demo-odk.yaml"),
                Files.readAllBytes(new File(root, "src/ontology/demo-odk.yaml").toPath()));
        assertEquals("Saved as src/sparql/" + NAME + ".rq.\n\n", saved.getMessage());
        assertFalse(saved.isListed());
        assertFalse(QuestionTestAction.inAnOdkProject(new File(temp, "loose.owl")));
        assertFalse(QuestionTestAction.inAnOdkProject(null));
    }
}
