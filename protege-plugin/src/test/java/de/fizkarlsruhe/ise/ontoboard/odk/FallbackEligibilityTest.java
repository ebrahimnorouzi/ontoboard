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

    // ------------------------------------------------------------------ minor 9, the look-ahead

    /**
     * A configuration no other test renders, so that what was rendered before in this run
     * cannot hide a count: the seed's, with a line of its own.
     */
    private void aConfigurationOfItsOwn(String which) throws IOException {
        write("eggboil-odk.yaml", read("eggboil-odk.yaml") + "# " + which + "\n");
    }

    /**
     * Minor 9: the Build form asks the engine about every target it offers before it opens, and
     * each ask rendered ODK's whole seed to compare its Makefile - a pause of seconds on a click.
     * The render is kept per configuration: seven targets, one render.
     */
    @Test
    void askingAboutSevenTargetsRendersTheSeedOnce() throws IOException {
        project("eggboil", "eggboil");
        aConfigurationOfItsOwn("seven targets");
        int before = FallbackEligibility.rendersSoFar();

        for (String goal : java.util.Arrays.asList("test", "test_fast", "reason_test",
                "sparql_test", "robot_reports", "validate_idranges", "prepare_release_fast")) {
            FallbackEligibility.why(edit, goal);
        }

        assertEquals(1, FallbackEligibility.rendersSoFar() - before);
    }

    /** A configuration that changed - by one byte - is rendered again, and its Makefile with it. */
    @Test
    void aChangedConfigurationIsRenderedAgain() throws IOException {
        project("eggboil", "eggboil");
        aConfigurationOfItsOwn("changed");
        FallbackEligibility.why(edit, "test");
        int before = FallbackEligibility.rendersSoFar();

        write("eggboil-odk.yaml", read("eggboil-odk.yaml") + "\n");

        org.junit.jupiter.api.Assertions.assertNotNull(FallbackEligibility.of(edit, "test",
                new LinkedHashMap<String, String>()).notice(),
                "the Makefile is not the render of this configuration");
        assertEquals(1, FallbackEligibility.rendersSoFar() - before);
    }

    // ------------------------------------------------------------------ F5, make's own route

    /**
     * F5: whether the Makefile is ODK's render of the configuration, said for make's own runs -
     * in ODK's container, with ODK's tools - which run it as it is where the engine refuses it.
     */
    @Test
    void anEditedMakefileIsSaidForMakesRunsAndOdksIsNot() throws IOException {
        project("eggboil", "eggboil");
        assertNull(FallbackEligibility.notOdksMakefile(edit), "the seed's Makefile is ODK's");

        write("Makefile", read("Makefile") + "\n# a line of their own\n");

        String said = FallbackEligibility.notOdksMakefile(edit);
        assertTrue(said != null && said.startsWith("This project's Makefile is not the one "
                + "ODK v1.6.1 writes for its configuration"), said);
        assertTrue(said.contains("make runs it as it is"), said);
        assertTrue(said.contains("OntoBoard > Project > Update project files..."), said);
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

    // Since 1.154.0 the engine runs a project's own rules as make does, when every line of
    // them can be run here: what is theirs is no longer refused for being theirs.

    @Test
    void aRuleOfTheirsWhoseLinesRunHereIsRun() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\n\nreason_test:\n\techo hi\n");

        assertNull(why("test_fast"));
        assertNull(OdkEngine.whyNot(edit, "test_fast", noVariables()));
    }

    @Test
    void aSettingOfTheirsIsMakesOwn() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nREPORT_FAIL_ON = WARN\n");

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                noVariables());

        assertTrue(verdict.allowed(), verdict.why());
        assertTrue(String.join("\n", commands(verdict.plan())).contains("--fail-on WARN"),
                commands(verdict.plan()).toString());
    }

    @Test
    void aTargetOfTheirOwnIsOneTheEngineRunsToo() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nmytarget:\n\techo mine\n");

        assertNull(why("test_fast"));
        assertNull(why("mytarget"));
        assertNull(OdkEngine.whyNot(edit, "mytarget", noVariables()));
    }

    /**
     * A rule of theirs for one import module - how a project builds a module its own way - is
     * run when its programs are here; one the engine has no stand-in for is named, with the
     * line of their makefile it stands on, before anything runs. The fast test takes the
     * modules as they are and does not reach it.
     */
    @Test
    void aRuleOfTheirsWithAProgramTheEngineHasNotIsNamedWithItsLine() throws IOException {
        project("oboimports", "obim");
        write("obim.Makefile", "## mine\n$(IMPORTDIR)/ro_import.owl: $(MIRRORDIR)/ro.owl\n"
                + "\tmy-extract $< $@\n");
        write("mirror/ro.owl", "the mirror their rule builds from");

        assertNull(why("test_fast"));
        assertNull(OdkEngine.whyNot(edit, "test_fast", noVariables()));
        String refresh = OdkEngine.whyNot(edit, "refresh-imports", noVariables());
        assertTrue(refresh.contains("my-extract") && refresh.contains("obim.Makefile:3"),
                refresh);
        assertNamesTheOtherWays(refresh);
    }

    @Test
    void aRuleOfTheirsThatWritesOutsideTheProjectIsRefusedBeforeAnythingRuns()
            throws IOException {
        project("eggboil", "eggboil");
        write("eggboil.Makefile", "## mine\nreason_test:\n\trobot convert -i eggboil-edit.owl "
                + "-o ../../../outside.owl\n");

        String why = OdkEngine.whyNot(edit, "test_fast", noVariables());

        assertTrue(why != null && why.contains("../../../outside.owl"), why);
        assertFalse(new File(temp, "outside.owl").exists());
    }

    private static Map<String, String> noVariables() {
        return new LinkedHashMap<String, String>();
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
        assertNamesTheOtherWays(why);
    }

    // ------------------------------------------------------------------ whose Makefile it is

    /** 1.155.0: run as it is, as make in ODK's container runs it - its version said first. */
    @Test
    void aMakefileOfAnotherOdkIsRunWithItsVersionSaid() throws IOException {
        project("eggboil", "eggboil");
        write("Makefile", read("Makefile").replace("ODK_VERSION_MAKEFILE =      v1.6.1",
                "ODK_VERSION_MAKEFILE =      v1.5.4"));

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                new LinkedHashMap<String, String>());

        assertNull(verdict.why(), verdict.why());
        String notice = String.valueOf(verdict.notice());
        assertTrue(notice.contains("ODK v1.5.4"), notice);
        assertTrue(notice.contains("OntoBoard > Project > Update project files..."), notice);
    }

    /** ODK's Makefile is written from the configuration. Changed since, it is the older one. */
    @Test
    void aMakefileOlderThanItsConfigurationSaysUpdate() throws IOException {
        project("eggboil", "eggboil");
        write("eggboil-odk.yaml", read("eggboil-odk.yaml").replace("title: \"Test kitchen "
                + "ontology\"", "title: \"Another title\""));

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                new LinkedHashMap<String, String>());

        // 1.155.0: run as it is, as make in ODK's container runs it - the difference said first.
        assertNull(verdict.why(), verdict.why());
        String notice = String.valueOf(verdict.notice());
        assertTrue(notice.contains("configuration"), notice);
        assertTrue(notice.contains("OntoBoard > Project > Update project files..."), notice);
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
        assertNamesTheOtherWays(why("test_fast"));

        assertTrue(new File(ontology, "Makefile").delete());
        assertTrue(why("test_fast").contains("Makefile"), why("test_fast"));
    }

    /**
     * What OntoBoard's own engine does not run can be run otherwise, and a refusal says how:
     * in ODK's container, or with ODK's own tools where they are installed. A container is
     * one way among them, never the only advice.
     */
    private static void assertNamesTheOtherWays(String why) {
        assertTrue(why.contains("ODK's container"), why);
        assertTrue(why.contains("ODK's own tools"), why);
        assertTrue(why.contains("OntoBoard > Check requirements..."), why);
        assertFalse(why.toLowerCase(java.util.Locale.ROOT).contains("start docker"), why);
    }

    // ------------------------------------------------------------------ 1.155.0, as it is

    /**
     * nfdicore's and MWO's Makefiles say ODK v1.6, and their rules are the ones v1.6.1 writes:
     * the engine runs them, and says so (the user, 2026-11-19: their development must go on
     * with OntoBoard alone).
     */
    @Test
    void aMakefileAnOlderOdkWroteWithV161sRulesIsRunAsV161s() throws IOException {
        project("eggboil", "eggboil");
        write("Makefile", read("Makefile").replace("# ODK Version: v1.6.1", "# ODK Version: v1.6")
                .replace("ODK_VERSION_MAKEFILE =      v1.6.1", "ODK_VERSION_MAKEFILE =      v1.6"));

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                java.util.Collections.<String, String>emptyMap());

        assertTrue(verdict.allowed(), verdict.why());
        assertTrue(verdict.notice() != null && verdict.notice().contains("written by ODK v1.6")
                && verdict.notice().contains("rules are the ones ODK v1.6.1 writes"),
                String.valueOf(verdict.notice()));
    }

    /** MWO: the configuration gained an import the Makefile never got. Run as it is, said. */
    @Test
    void aMakefileThatDiffersIsRunAsItIsAndSaysHow() throws IOException {
        project("eggboil", "eggboil");
        write("Makefile", read("Makefile") + "\n# a line of their own\n");

        FallbackEligibility.Verdict verdict = FallbackEligibility.of(edit, "test_fast",
                java.util.Collections.<String, String>emptyMap());

        assertTrue(verdict.allowed(), verdict.why());
        String notice = String.valueOf(verdict.notice());
        assertTrue(notice.startsWith("This project's Makefile is not the one ODK v1.6.1 writes "
                + "for its configuration"), notice);
        assertTrue(notice.contains("# a line of their own"), notice);
        assertTrue(notice.contains("runs it as it is"), notice);
        assertTrue(notice.contains("Update project files..."), notice);
    }

    @Test
    void odksOwnMakefileNeedsNoNotice() throws IOException {
        project("eggboil", "eggboil");

        assertNull(FallbackEligibility.of(edit, "test_fast",
                java.util.Collections.<String, String>emptyMap()).notice());
    }
}
