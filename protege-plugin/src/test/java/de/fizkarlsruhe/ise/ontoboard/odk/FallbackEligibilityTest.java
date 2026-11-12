package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When OntoBoard's own engine may run a target of an ODK project - the fallback for a machine
 * without Docker.
 *
 * <p>It may when two things hold. The Makefile is the one ODK v1.6.1 writes for the project's
 * configuration, so the recipes are ODK's. And the project's own {@code <id>.Makefile} changes
 * nothing of what make would run for the target: a rule or a setting of the project's own is
 * for make in ODK's container, and the refusal names it with its line. A target of their own
 * that the run never reaches is no obstacle.
 */
class FallbackEligibilityTest {

    /** One time for every file, as the seeds were recorded: make decides by file times. */
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

    private void write(String name, String text) throws IOException {
        File file = new File(ontology, name);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(SAME_TIME));
    }

    private String read(String name) throws IOException {
        return new String(Files.readAllBytes(new File(ontology, name).toPath()),
                StandardCharsets.UTF_8);
    }

    private String why(String goal) {
        return FallbackEligibility.why(edit, goal);
    }

    // ------------------------------------------------------------------ nothing of their own

    @Test
    void aSeededProjectOverridesNothing() throws IOException {
        project("eggboil", "eggboil");

        assertNull(why("test_fast"));
        assertNull(why("test"));
        assertNull(why("prepare_release_fast"));
        assertNull(why("clean"));
    }

    @Test
    void whatItMayRunIsThePlanOfARun() throws IOException {
        project("eggboil", "eggboil");

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                new LinkedHashMap<String, String>());

        assertTrue(verdict.allowed(), verdict.why());
        assertEquals(1, verdict.plan().size());
        assertEquals(MakeFile.Step.Kind.SUB_MAKE, verdict.plan().get(0).kind());
        assertFalse(verdict.plan().get(0).steps().isEmpty(), "the tests behind test_fast");
    }

    /**
     * The shape of a release's version IRI reaches make on its command line. That is a
     * setting of the run, not one of the project's Makefile, and does not stop the engine.
     */
    @Test
    void aVariableOfTheRunIsNotASettingOfTheirs() throws IOException {
        project("eggboil", "eggboil");
        Map<String, String> run = new LinkedHashMap<String, String>();
        run.put("VERSION", "1.0.0");
        run.put("ANNOTATE_ONTOLOGY_VERSION", "annotate -V https://example.org/eggboil/eggboil/"
                + "1.0.0/$@ --annotation owl:versionInfo 1.0.0");

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit,
                "prepare_release_fast", run);

        assertTrue(verdict.allowed(), verdict.why());
    }

    // ------------------------------------------------------------------ something of their own

    @Test
    void aRuleOfTheirsThatTheRunReachesIsNamedWithItsLine() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\n\nreason_test:\n\techo hi\n");

        String why = why("test_fast");

        assertTrue(why.contains("eggboil.Makefile:3"), why);
        assertTrue(why.contains("reason_test"), why);
        assertTrue(why.contains("Docker"), why);
    }

    @Test
    void aSettingOfTheirsThatARecipeReadsIsNamedWithItsLine() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nREPORT_FAIL_ON = WARN\n");

        String why = why("test_fast");

        assertTrue(why.contains("eggboil.Makefile:2"), why);
        assertTrue(why.contains("REPORT_FAIL_ON"), why);
        assertNull(why("clean"), "a target whose recipes do not read it runs");
    }

    @Test
    void aTargetOfTheirOwnIsNoObstacleAndIsNotOneTheEngineRuns() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nmytarget:\n\techo mine\n");

        assertNull(why("test_fast"));
        String own = why("mytarget");
        assertTrue(own.contains("eggboil.Makefile:2") && own.contains("mytarget"), own);
    }

    /**
     * A rule of theirs for one import module - how a project builds a module its own way -
     * changes a refresh of the imports. It does not change the fast test, which takes the
     * modules as they are.
     */
    @Test
    void aRuleForAnImportModuleStopsTheRefreshAndNotTheFastTest() throws IOException {
        project("oboimports", "obim");
        write("obim.Makefile", "## mine\n$(IMPORTDIR)/ro_import.owl: $(MIRRORDIR)/ro.owl\n"
                + "\tmy-extract $< $@\n");
        write("mirror/ro.owl", "the mirror their rule builds from");

        assertNull(why("test_fast"));
        String refresh = why("refresh-imports");
        assertTrue(refresh.contains("obim.Makefile:2"), refresh);
        assertTrue(refresh.contains("imports/ro_import.owl"), refresh);
    }

    /**
     * Where make itself would stop, the engine does not start - and says that it is make that
     * stops, since Docker would not help. Their rule needs a file that is not there and that
     * nothing makes.
     */
    @Test
    void whereMakeItselfWouldStopThatIsSaid() throws IOException {
        project("oboimports", "obim");
        write("obim.Makefile", "## mine\n$(IMPORTDIR)/ro_import.owl: $(MIRRORDIR)/ro.owl\n"
                + "\tmy-extract $< $@\n");

        String why = why("test_fast");

        assertTrue(why.contains("No rule to make target 'mirror/ro.owl'"), why);
        assertTrue(why.contains("make itself stops"), why);
        assertFalse(why.contains("Docker"), "the container's make would stop there too: " + why);
    }

    /**
     * make takes the first pattern rule that fits, and ODK's stands before the project's own
     * makefile is read. A pattern rule of theirs for what ODK has a pattern rule for is
     * therefore never the one make runs - and is no obstacle, as odd as it reads.
     */
    @Test
    void aPatternRuleMakeWouldNotChooseIsNoObstacle() throws IOException {
        project("oboimports", "obim");
        write("obim.Makefile", "## mine\n$(IMPORTDIR)/%_import.owl: $(MIRRORDIR)/%.owl\n"
                + "\tmy-extract $< $@\n");

        assertNull(why("refresh-imports"));
    }

    @Test
    void anIncludeThatCannotBeFollowedStopsEverything() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\ninclude nowhere.mk\n");

        assertTrue(why("test_fast").contains("nowhere.mk"), why("test_fast"));
        assertTrue(why("clean").contains("nowhere.mk"), why("clean"));
    }

    @Test
    void somethingOfMakeTheReaderDoesNotReadStopsEverythingAndSaysWhere() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nifdef SOMETHING\nX = 1\nendif\n");

        String why = why("test_fast");

        assertTrue(why.contains("eggboil.Makefile:2"), why);
        assertTrue(why.contains("Docker"), why);
    }

    // ------------------------------------------------------------------ whose Makefile it is

    @Test
    void aMakefileOfAnotherOdkIsRefusedWithItsVersion() throws IOException {
        project("eggboil", "eggboil");
        write("Makefile", read("Makefile").replace("ODK_VERSION_MAKEFILE =      v1.6.1",
                "ODK_VERSION_MAKEFILE =      v1.5.4"));

        String why = why("test_fast");

        assertTrue(why.contains("ODK v1.5.4"), why);
        assertTrue(why.contains("OntoBoard > Project > Update project files..."), why);
    }

    /** ODK's Makefile is written from the configuration. Changed since, it is the older one. */
    @Test
    void aMakefileOlderThanItsConfigurationSaysUpdate() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil-odk.yaml", read("eggboil-odk.yaml").replace("title: \"Test kitchen "
                + "ontology\"", "title: \"Another title\""));

        String why = why("test_fast");

        assertTrue(why.contains("configuration"), why);
        assertTrue(why.contains("OntoBoard > Project > Update project files..."), why);
    }

    /** A checkout with Windows line endings is the same Makefile and the same configuration. */
    @Test
    void windowsLineEndingsDoNotMakeItAnotherMakefile() throws IOException {
        project("eggboil", "eggboil");
        List<String> unix = commands(FallbackEligibility.of(edit, "test_fast",
                new LinkedHashMap<String, String>()).plan());
        write("Makefile", read("Makefile").replace("\n", "\r\n"));
        write("eggboil.Makefile", read("eggboil.Makefile").replace("\n", "\r\n"));
        write("eggboil-odk.yaml", read("eggboil-odk.yaml").replace("\n", "\r\n"));

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                new LinkedHashMap<String, String>());

        assertTrue(verdict.allowed(), verdict.why());
        assertFalse(unix.isEmpty());
        assertEquals(unix, commands(verdict.plan()), "the same commands, and no carriage "
                + "return in any of them");
    }

    /** The commands of a plan, sub-makes opened, in the order they run. */
    private static List<String> commands(List<MakeFile.Step> steps) {
        List<String> commands = new ArrayList<String>();
        for (MakeFile.Step step : steps) {
            if (step.kind() != MakeFile.Step.Kind.MESSAGE) {
                commands.add(step.command());
            }
            commands.addAll(commands(step.steps()));
        }
        return commands;
    }

    @Test
    void aFolderWithoutAMakefileOrAConfigurationIsNotOdks() throws IOException {
        project("eggboil", "eggboil");
        assertTrue(new File(ontology, "eggboil-odk.yaml").delete());
        assertTrue(why("test_fast").contains("eggboil-odk.yaml") || why("test_fast")
                .contains("configuration"), why("test_fast"));

        assertTrue(new File(ontology, "Makefile").delete());
        assertTrue(why("test_fast").contains("Makefile"), why("test_fast"));
    }
}
