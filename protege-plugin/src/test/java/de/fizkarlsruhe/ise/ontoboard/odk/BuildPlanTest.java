package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What Build offers for an ODK project, and where it runs.
 *
 * <p>With ODK's container: the project's own targets, {@code test} first - the build its
 * maintainers run. Without it: the targets OntoBoard's own engine can run for this project,
 * {@code test} first there too, also where the imports have to be fetched for it; what is
 * not offered is said with its reason, never left out in silence.
 */
class BuildPlanTest {

    private static final long SAME_TIME = 1_762_862_400_000L;

    @TempDir
    File temp;

    private File ontology;
    private File edit;

    private void project(String seed, String id) throws IOException {
        File root = new File(temp, id);
        for (Map.Entry<String, byte[]> file : Goldens.seedTree(seed).entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        ontology = new File(root, "src/ontology");
        edit = new File(ontology, id + "-edit.owl");
        sameAge(root);
    }

    private static void sameAge(File folder) {
        File[] children = folder.listFiles();
        for (File child : children == null ? new File[0] : children) {
            if (child.isDirectory()) {
                sameAge(child);
            }
            assertTrue(child.setLastModified(SAME_TIME), child.getPath());
        }
    }

    /** The shell's own commands and four programs - and none that fetches. */
    private static Map<String, RecipeShell.Tool> tools() {
        Map<String, RecipeShell.Tool> tools =
                new LinkedHashMap<String, RecipeShell.Tool>(RecipeShell.builtins());
        for (String name : Arrays.asList("robot", "odk-info", "dicer-cli", "check-rdfxml")) {
            tools.put(name, new RecipeShell.Tool() {
                @Override
                public String whyNot(List<String> arguments) {
                    return null;
                }

                @Override
                public int run(RecipeShell.Call call) {
                    return 0;
                }
            });
        }
        return tools;
    }

    // ------------------------------------------------------------------ in ODK's container

    @Test
    void inTheContainerTheListIsTheMakefilesAndTestIsFirstChoice() throws IOException {
        project("eggboil", "eggboil");

        BuildPlan plan = BuildPlan.inContainer(edit, "obolibrary/odkfull:v1.6.1", "docker");

        assertFalse(plan.isInProcess());
        assertEquals("test", plan.defaultTarget());
        assertTrue(plan.targets().containsAll(Arrays.asList("test", "test_fast",
                "prepare_release", "refresh-imports", "clean")), plan.targets().toString());
        assertEquals(Collections.<String, String>emptyMap(), plan.refused());
        assertTrue(plan.explanation().contains("obolibrary/odkfull:v1.6.1"),
                plan.explanation());
        assertTrue(plan.explanation().contains("ODK's container"), plan.explanation());
        assertFalse(plan.explanation().contains("inside Protege"), plan.explanation());
    }

    // ------------------------------------------------------------------ without it

    @Test
    void withoutDockerAProjectWithNothingToFetchRunsTest() throws IOException {
        project("eggboil", "eggboil");

        BuildPlan plan = BuildPlan.inProcess(edit, tools(), "Docker is not running");

        assertTrue(plan.isInProcess());
        assertEquals(Arrays.asList("test", "test_fast", "reason_test", "sparql_test",
                "robot_reports", "validate_idranges", "clean"), plan.targets());
        assertEquals("test", plan.defaultTarget());
        assertEquals(Collections.<String, String>emptyMap(), plan.refused());
        assertTrue(plan.explanation().contains("Docker is not running"), plan.explanation());
        assertTrue(plan.explanation().contains("inside Protege"), plan.explanation());
        assertNull(plan.whyNothing());
    }

    /**
     * A project that imports from the web: the full test fetches the imports again, and with
     * the programs OntoBoard has the engine does that too. test is the choice, as it is with
     * the container, and the form does not send anybody to a container for it.
     */
    @Test
    void withoutDockerAProjectWithImportsToFetchRunsTestToo() throws IOException {
        project("oboimports", "obim");

        BuildPlan plan = BuildPlan.inProcess(edit, OdkTools.forProject(),
                "Docker is not running");

        assertEquals(Collections.<String, String>emptyMap(), plan.refused());
        assertEquals("test", plan.defaultTarget());
        assertFalse(plan.explanation().contains("need ODK's container"), plan.explanation());
        assertTrue(plan.explanation().contains("fetches"), plan.explanation());
        assertTrue(plan.help().contains("test_fast is the same on the imports as they are"),
                plan.help());
    }

    /**
     * A target that needs a program the engine has not is left out, and the form says why in
     * the refusal's own words; the fast test, which does not need it, is the choice.
     */
    @Test
    void aTargetThatNeedsAProgramTheEngineHasNotIsLeftOutAndTheFormSaysWhy()
            throws IOException {
        project("oboimports", "obim");

        BuildPlan plan = BuildPlan.inProcess(edit, tools(), "Docker is not running");

        assertFalse(plan.targets().contains("test"), plan.targets().toString());
        assertEquals("test_fast", plan.defaultTarget());
        assertTrue(plan.refused().get("test").contains("curl"), plan.refused().toString());
        assertTrue(plan.help().contains("test is not offered"), plan.help());
        assertTrue(plan.help().contains("curl"), "the refusal's own reason: " + plan.help());
        assertFalse(plan.help().contains("only ODK's container"), plan.help());
        assertTrue(plan.help().contains("test_fast"), plan.help());
    }

    /** Nothing the engine may run: said with the reason, and what to do. */
    @Test
    void whenNothingCanRunHereThePlanSaysWhy() throws IOException {
        project("eggboil", "eggboil");
        File makefile = new File(ontology, "Makefile");
        String text = new String(Files.readAllBytes(makefile.toPath()), StandardCharsets.UTF_8);
        Files.write(makefile.toPath(), text.replace("ODK_VERSION_MAKEFILE =      v1.6.1",
                "ODK_VERSION_MAKEFILE =      v1.5.4").getBytes(StandardCharsets.UTF_8));

        BuildPlan plan = BuildPlan.inProcess(edit, tools(), "Docker is not running");

        assertEquals(Collections.<String>emptyList(), plan.targets());
        assertNull(plan.defaultTarget());
        assertTrue(plan.whyNothing().contains("Docker is not running"), plan.whyNothing());
        assertTrue(plan.whyNothing().contains("ODK v1.5.4"), plan.whyNothing());
    }

    /** A target of the engine's list that this Makefile does not have is not offered. */
    @Test
    void onlyTargetsTheMakefileHasAreOffered() throws IOException {
        project("eggboil", "eggboil");

        BuildPlan plan = BuildPlan.inProcess(edit, tools(), "Docker is not running");

        List<String> makefiles = MakeRun.targetsFor(edit);
        for (String target : plan.targets()) {
            assertTrue(makefiles.contains(target), target);
        }
    }

    // ------------------------------------------------------------------ what a run says

    /** The checks that passed, for the result's summary: read off make's own lines. */
    @Test
    void thePassingChecksAreReadOffTheTranscript() {
        List<String> passing = BuildPlan.passingChecks(Arrays.asList(
                "robot --catalog catalog-v001.xml verify -i tmp/merged-eggboil-edit.ofn ...",
                "PASS Rule ../sparql/owldef-self-reference-violation.sparql: 0 violation(s)",
                "PASS Rule ../sparql/check_labels-violation.sparql: 0 violation(s)",
                "FAIL Rule ../sparql/every_egg_has_a_timer-violation.sparql: 2 violation(s)",
                "Finished running all tests successfully."));

        assertEquals(Arrays.asList("owldef-self-reference", "check_labels"), passing);
    }

    // ------------------------------------------------------------------ without a container

    private static final String NONE_ANSWERS = "Neither Docker nor Podman answers on this machine";

    /**
     * No container for an ODK project: OntoBoard's own engine builds - unless the person has
     * settled that ODK's targets run in a container only. Then nothing runs, and the reason
     * names the setting.
     */
    @Test
    void withoutAContainerTheEngineBuildsUnlessTheSettingIsAlways() throws IOException {
        project("eggboil", "eggboil");

        BuildPlan.WithoutContainer left = BuildPlan.withoutContainer(edit,
                OdkRoute.Use.AUTOMATIC, NONE_ANSWERS, null, tools());
        assertNull(left.refusal());
        assertTrue(left.plan().isInProcess());
        assertTrue(left.plan().targets().contains("test_fast"), left.plan().targets().toString());
        assertTrue(left.plan().explanation().startsWith(NONE_ANSWERS), left.plan().explanation());

        BuildPlan.WithoutContainer always = BuildPlan.withoutContainer(edit,
                OdkRoute.Use.ALWAYS_CONTAINER, NONE_ANSWERS, null, tools());
        assertNull(always.plan());
        assertTrue(always.refusal().startsWith(NONE_ANSWERS), always.refusal());
        assertTrue(always.refusal().contains(OdkRoute.Use.ALWAYS_CONTAINER.label()),
                always.refusal());
    }

    /** When the image is what is missing, how to get it is said with the refusal. */
    @Test
    void whenTheImageIsWhatIsMissingTheRefusalSaysHowToGetIt() throws IOException {
        project("eggboil", "eggboil");

        BuildPlan.WithoutContainer always = BuildPlan.withoutContainer(edit,
                OdkRoute.Use.ALWAYS_CONTAINER, "ODK's image is not on this machine",
                "Fetch it once with: docker pull an-image (about 5 GB).", tools());

        assertTrue(always.refusal().contains("docker pull an-image"), always.refusal());
    }

    /** A project the engine runs nothing of: that is the refusal, with the first reason. */
    @Test
    void whenTheEngineRunsNothingOfTheProjectThatIsTheRefusal() throws IOException {
        project("eggboil", "eggboil");
        File makefile = new File(ontology, "Makefile");
        Files.write(makefile.toPath(), (new String(Files.readAllBytes(makefile.toPath()),
                StandardCharsets.UTF_8) + "\n# a line of their own\n")
                .getBytes(StandardCharsets.UTF_8));
        sameAge(temp);

        BuildPlan.WithoutContainer left = BuildPlan.withoutContainer(edit,
                OdkRoute.Use.AUTOMATIC, NONE_ANSWERS, null, tools());

        assertNull(left.plan());
        assertTrue(left.refusal().startsWith(NONE_ANSWERS), left.refusal());
        assertTrue(left.refusal().contains("Makefile"), left.refusal());
    }
}
