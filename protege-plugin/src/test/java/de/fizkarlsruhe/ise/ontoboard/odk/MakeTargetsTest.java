package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading a project's real make targets.
 *
 * <p>The fixture is {@code ISE-FIZKarlsruhe/mwo/src/ontology/Makefile}, 767 lines of generated ODK
 * build, copied unmodified. That matters because what this replaced returned six hardcoded names
 * after opening the file only to check it existed - so it was correct for Makefiles this plugin
 * writes and wrong for every real one. mwo has roughly thirty targets, and the six hardcoded names
 * would have hidden every {@code mirror-*} and {@code all_imports} target, which are what an ODK
 * build is for.
 *
 * <p>The interesting assertions are the exclusions. A Makefile is full of things that look like a
 * rule to a careless pattern - variable assignments most of all - and offering {@code ONT_ID} on a
 * menu would be embarrassing in a way nothing would catch at runtime.
 */
class MakeTargetsTest {

    private static final String FIXTURE = "src/test/resources/fixture-mwo-Makefile";

    private static List<String> mwo() throws Exception {
        return MakeTargets.parse(new String(
                Files.readAllBytes(new File(FIXTURE).toPath()), StandardCharsets.UTF_8));
    }

    // ---------- a real ODK Makefile ----------

    @Test
    void therealTargetsAreFoundAndThereAreFarMoreThanSix() throws Exception {
        List<String> targets = mwo();

        assertTrue(targets.size() > 15,
                "mwo has around thirty targets; the old hardcoded list had six: " + targets);
        assertTrue(targets.contains("all"), targets.toString());
        assertTrue(targets.contains("test"), targets.toString());
        assertTrue(targets.contains("prepare_release"), targets.toString());
    }

    /** The targets an ODK build exists for, which the hardcoded list omitted entirely. */
    @Test
    void theImportAndMirrorTargetsAreFound() throws Exception {
        List<String> targets = mwo();

        assertTrue(targets.contains("all_imports"), targets.toString());
        assertTrue(targets.contains("mirror-iao"), targets.toString());
        assertTrue(targets.contains("mirror-obi"), targets.toString());
    }

    // ---------- the exclusions ----------

    /**
     * The one a careless pattern gets wrong. GNU Make's assignment operators contain a colon, so
     * {@code ONT_ID := mwo} matches a naive {@code ^(\\w+):} and would appear on the menu.
     */
    @Test
    void variableAssignmentsAreNotTargets() throws Exception {
        List<String> targets = mwo();

        for (String name : targets) {
            assertFalse(name.equals("ONT") || name.equals("ONT_ID") || name.equals("SRC"),
                    "a variable assignment was read as a target: " + name);
        }
        assertEquals(0, MakeTargets.parse("ONT := mwo\nSRC ?= x\nCFLAGS += -g\n").size());
    }

    @Test
    void makesOwnDirectivesAreNotOffered() throws Exception {
        List<String> targets = mwo();

        assertFalse(targets.contains(".PHONY"), targets.toString());
        assertFalse(targets.contains(".PRECIOUS"), targets.toString());
        for (String name : targets) {
            assertFalse(name.startsWith("."), "a directive was offered: " + name);
        }
    }

    /** A pattern rule is a recipe, not a name anyone can invoke. */
    @Test
    void patternRulesAreNotTargets() {
        assertEquals(0, MakeTargets.parse("%.owl: %.obo\n\trobot convert -i $< -o $@\n").size());
    }

    /**
     * A file product can be built by name, but a menu of two hundred paths is not a menu - and the
     * phony targets that build them are listed anyway.
     */
    @Test
    void fileProductsAreNotOffered() throws Exception {
        for (String name : mwo()) {
            assertFalse(name.contains("/"), "a path was offered as a target: " + name);
            assertFalse(name.endsWith(".owl"), "a file was offered as a target: " + name);
        }
    }

    /** A recipe line containing a URL or a Java option would otherwise read as a rule. */
    @Test
    void recipeLinesAreNotTargets() {
        assertEquals(java.util.Arrays.asList("build"), MakeTargets.parse(
                "build:\n\tcurl https://example.org/x.owl -o x.owl\n\trobot -Xmx8G merge\n"));
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        assertEquals(java.util.Arrays.asList("real"),
                MakeTargets.parse("# fake: not a target\n\n\nreal:\n\techo hi\n"));
    }

    /** A define block's body is arbitrary text and may contain anything colon-shaped. */
    @Test
    void aDefineBlockBodyIsNotScannedForTargets() {
        assertEquals(java.util.Arrays.asList("real"), MakeTargets.parse(
                "define help_text\nnot_a_target: this is prose\nendef\n\nreal:\n\techo hi\n"));
    }

    @Test
    void aTargetIsNotListedTwiceWhenTheMakefileMentionsItTwice() {
        assertEquals(java.util.Arrays.asList("all", "clean"),
                MakeTargets.parse("all: clean\n\techo\n\nclean:\n\trm -f x\n\nall: extra\n"));
    }

    // ---------- ordering ----------

    /**
     * An ODK target list is long and mostly internal, so the ones a person actually types come
     * first. Without this the menu opens on alphabetical noise.
     */
    @Test
    void theTargetsPeopleActuallyRunComeFirst() throws Exception {
        List<String> ordered = MakeTargets.ordered(mwo());

        assertEquals("all", ordered.get(0));
        assertTrue(ordered.indexOf("test") < ordered.indexOf("mirror-iao"), ordered.toString());
        assertTrue(ordered.indexOf("prepare_release") < ordered.indexOf("normalize_src"),
                ordered.toString());
    }

    @Test
    void everyTargetSurvivesTheOrdering() throws Exception {
        List<String> targets = mwo();

        List<String> ordered = MakeTargets.ordered(targets);

        assertEquals(targets.size(), ordered.size(), "ordering must not drop a target");
        assertTrue(ordered.containsAll(targets));
    }

    // ---------- finding the file ----------

    @Test
    void theMakefileBesideTheEditFileIsRead(@TempDir Path dir) throws Exception {
        File editFile = new File(dir.toFile(), "mwo-edit.owl");
        Files.write(editFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir.toFile(), "Makefile").toPath(),
                "all: reason\n\treason:\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(java.util.Arrays.asList("all"), MakeTargets.of(editFile));
    }

    /**
     * No Makefile means no targets, not a guessed standard set - which is exactly what the
     * hardcoded version returned, for projects that had none of them.
     */
    @Test
    void noMakefileMeansNoTargetsRatherThanAGuess(@TempDir Path dir) throws Exception {
        File editFile = new File(dir.toFile(), "mwo-edit.owl");
        Files.write(editFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        assertTrue(MakeTargets.of(editFile).isEmpty());
        assertTrue(MakeTargets.of(null).isEmpty());
    }

    @Test
    void anEmptyOrNullMakefileParsesToNothing() {
        assertTrue(MakeTargets.parse("").isEmpty());
        assertTrue(MakeTargets.parse(null).isEmpty());
    }
}
