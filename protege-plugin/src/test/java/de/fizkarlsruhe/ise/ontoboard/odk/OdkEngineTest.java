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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A target of an ODK project, run by OntoBoard itself.
 *
 * <p>The engine puts three things together: make's decisions ({@code MakeFile.run}), the shell
 * part of a recipe line ({@code RecipeShell}), and the programs a line names. Here everything
 * is the real thing - ODK's Makefile as it is seeded, read and run on a real folder - except
 * ROBOT and the three programs of ODK's image, which only remember what they were asked and
 * leave the files they were asked for. What ROBOT itself then writes is held against ODK's
 * own output in {@code FallbackEquivalenceTest}.
 */
class OdkEngineTest {

    /** One time for every file, as the seeds were recorded: make decides by file times. */
    private static final long SAME_TIME = 1_762_862_400_000L;

    @TempDir
    File temp;

    private File root;
    private File ontology;
    private File edit;

    /** Every call of robot: its arguments. */
    private final List<List<String>> robot = new ArrayList<List<String>>();
    /** The other programs that were called, by name. */
    private final List<String> others = new ArrayList<String>();
    /** The robot command that fails, or null. */
    private String failing;
    private int stopAfter = Integer.MAX_VALUE;

    @BeforeEach
    void aSeededProject() throws IOException {
        root = new File(temp, "eggboil");
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        ontology = new File(root, "src/ontology");
        edit = new File(ontology, "eggboil-edit.owl");
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

    private void write(String path, String text) throws IOException {
        File file = new File(ontology, path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private File file(String path) {
        return new File(ontology, path);
    }

    /** A program that is asked and answers 0. */
    private RecipeShell.Tool named(final String name) {
        return new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                others.add(name);
                return 0;
            }
        };
    }

    /**
     * The shell's own commands as they are, and stand-ins for the rest: a robot that leaves
     * every file it is asked to write, and fails when its line holds the failing command.
     */
    private Map<String, RecipeShell.Tool> tools() {
        Map<String, RecipeShell.Tool> tools =
                new LinkedHashMap<String, RecipeShell.Tool>(RecipeShell.builtins());
        tools.put("odk-info", named("odk-info"));
        tools.put("dicer-cli", named("dicer-cli"));
        tools.put("check-rdfxml", named("check-rdfxml"));
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.contains("expand") ? "robot expand is not run here" : null;
            }

            @Override
            public int run(RecipeShell.Call call) throws IOException {
                List<String> arguments = call.arguments();
                robot.add(arguments);
                if (failing != null && arguments.contains(failing)) {
                    call.complain("The ontology is inconsistent: nothing can be an egg.");
                    return 1;
                }
                boolean query = arguments.contains("query");
                for (int i = 0; i + 1 < arguments.size(); i++) {
                    String option = arguments.get(i);
                    // What the line writes: -o/--output, and the second word of a query's pair.
                    String written = option.equals("-o") || option.equals("--output")
                            ? arguments.get(i + 1)
                            : query && (option.equals("-s") || option.equals("--query"))
                                    && i + 2 < arguments.size() ? arguments.get(i + 2) : null;
                    if (written != null) {
                        File out = call.file(written);
                        Files.createDirectories(out.getParentFile().toPath());
                        Files.write(out.toPath(), "written by robot\n"
                                .getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (arguments.contains("verify")) {
                    call.say("PASS Rule ../sparql/check_labels-violation.sparql: 0 violation(s)");
                }
                return 0;
            }
        });
        return tools;
    }

    private OdkEngine.Outcome run(String goal) {
        return run(goal, Goldens.makeFixedVariables(), tools());
    }

    private OdkEngine.Outcome run(String goal, Map<String, String> commandLine,
            Map<String, RecipeShell.Tool> tools) {
        return OdkEngine.run(edit, goal, commandLine, tools,
                () -> robot.size() >= stopAfter);
    }

    /** The first word after the global options of each robot call: its first command. */
    private List<String> robotCommands() {
        List<String> commands = new ArrayList<String>();
        for (List<String> call : robot) {
            commands.add(call.get(2));
        }
        return commands;
    }

    // ------------------------------------------------------------------ a run

    @Test
    void theFastTestOfASeededProjectRunsLineByLine() {
        OdkEngine.Outcome outcome = run("test_fast");

        assertTrue(outcome.isOk(), outcome.getFailure() + "\n"
                + String.join("\n", outcome.getTranscript()));
        assertNull(outcome.getFailure());
        assertEquals(Arrays.asList("convert", "reason", "remove", "verify", "report", "merge",
                "annotate", "merge", "validate-profile"), robotCommands());
        assertEquals(Arrays.asList("odk-info", "dicer-cli"), others);
        for (List<String> call : robot) {
            assertEquals(Arrays.asList("--catalog", "catalog-v001.xml"), call.subList(0, 2));
        }

        // What the shell's own commands did around ROBOT: ODK's recipes, run for real.
        assertTrue(file("reports").isDirectory(), "mkdir -p reports");
        assertFalse(file("test.owl").exists(), "the reasoner's output, removed after the test");
        assertTrue(file("eggboil.owl").isFile(), "written under another name and moved");
        assertFalse(file("eggboil.owl.tmp.owl").exists());
        assertTrue(file("reports/validate_profile_owl2dl_eggboil.owl.txt").isFile());

        // And the log reads as make's own.
        List<String> log = outcome.getTranscript();
        assertEquals("make IMP=false PAT=false COMP=false MIR=false test", log.get(0));
        assertEquals("make[1]: Entering directory '" + ontology.getAbsolutePath()
                .replace('\\', '/') + "'", log.get(1));
        assertEquals("ODK Makefile v1.6.1", log.get(2));
        assertTrue(log.contains("PASS Rule ../sparql/check_labels-violation.sparql: "
                + "0 violation(s)"), log.toString());
        assertEquals("Finished running all tests successfully.", log.get(log.size() - 2));
        assertTrue(log.get(log.size() - 1).startsWith("make[1]: Leaving directory"));
    }

    @Test
    void aSecondRunDoesOnlyWhatIsStillToDo() {
        assertTrue(run("test_fast").isOk());
        int first = robot.size();

        OdkEngine.Outcome again = run("test_fast");

        assertTrue(again.isOk(), again.getFailure());
        // The tests themselves are phony and run again; the files they are run on are there.
        assertTrue(robot.size() - first < first, "fewer robot calls than the first run: "
                + robotCommands().subList(first, robot.size()));
        assertFalse(robotCommands().subList(first, robot.size()).contains("convert"),
                "the edit file has not changed, so it is not converted again");
    }

    // ------------------------------------------------------------------ what stops it

    @Test
    void aLineThatFailsStopsTheRunAndTheFailureSaysWhereAndWhy() {
        failing = "reason";

        OdkEngine.Outcome outcome = run("test_fast");

        assertFalse(outcome.isOk());
        assertTrue(outcome.ranAnything());
        assertEquals(Arrays.asList("convert", "reason"), robotCommands());
        assertTrue(outcome.getFailure().contains("reason_test"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("nothing can be an egg"), outcome.getFailure());
        List<String> log = outcome.getTranscript();
        assertTrue(log.get(log.size() - 3).matches(
                "make\\[1\\]: \\*\\*\\* \\[Makefile:\\d+: reason_test\\] Error 1"),
                log.get(log.size() - 3));
        assertTrue(log.get(log.size() - 1).matches(
                "make: \\*\\*\\* \\[Makefile:\\d+: test_fast\\] Error 2"), log.get(log.size() - 1));
    }

    /**
     * A program that breaks - an error of its own, not a failure it reports - fails its line,
     * as any program that fails does. It is not OntoBoard's operation that breaks.
     */
    @Test
    void aProgramThatBreaksFailsItsLine() {
        Map<String, RecipeShell.Tool> tools = tools();
        final RecipeShell.Tool usual = tools.get("robot");
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return usual.whyNot(arguments);
            }

            @Override
            public int run(RecipeShell.Call call) {
                throw new IllegalStateException("the reasoner fell over");
            }
        });

        OdkEngine.Outcome outcome = run("test_fast", Goldens.makeFixedVariables(), tools);

        assertFalse(outcome.isOk());
        assertTrue(outcome.getFailure().contains("the reasoner fell over"), outcome.getFailure());
        assertTrue(String.join("\n", outcome.getTranscript()).contains("] Error 1"),
                "make's own word on the target that failed");
    }

    /** What the engine cannot do is known before the first line runs: nothing half done. */
    @Test
    void aProgramWithoutAStandInIsRefusedBeforeAnythingRuns() {
        Map<String, RecipeShell.Tool> tools = tools();
        tools.remove("dicer-cli");

        OdkEngine.Outcome outcome = run("test_fast", Goldens.makeFixedVariables(), tools);

        assertFalse(outcome.isOk());
        assertFalse(outcome.ranAnything());
        assertEquals(0, robot.size());
        assertEquals(0, others.size());
        assertTrue(outcome.getFailure().contains("dicer-cli"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("Docker"), outcome.getFailure());
        assertFalse(file("tmp/eggboil-preprocess.owl").exists());
    }

    @Test
    void aCallItsProgramRefusesIsRefusedBeforeAnythingRunsToo() throws IOException {
        // A project whose release needs a robot command that is not run here.
        Map<String, RecipeShell.Tool> tools = tools();
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.contains("validate-profile")
                        ? "robot validate-profile is not run here" : null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                robot.add(call.arguments());
                return 0;
            }
        });

        OdkEngine.Outcome outcome = run("test_fast", Goldens.makeFixedVariables(), tools);

        assertFalse(outcome.isOk());
        assertFalse(outcome.ranAnything());
        assertEquals(0, robot.size());
        assertTrue(outcome.getFailure().contains("robot validate-profile is not run here"),
                outcome.getFailure());
    }

    /** A rule of the project's own is make's to run, in ODK's container. */
    @Test
    void aRuleOfTheProjectsOwnIsRefusedBeforeAnythingRuns() throws IOException {
        write("eggboil.Makefile", "## mine\nreason_test:\n\techo mine\n");
        assertTrue(file("eggboil.Makefile").setLastModified(SAME_TIME));

        OdkEngine.Outcome outcome = run("test_fast");

        assertFalse(outcome.isOk());
        assertFalse(outcome.ranAnything());
        assertEquals(0, robot.size());
        assertTrue(outcome.getFailure().contains("eggboil.Makefile:2"), outcome.getFailure());
    }

    /**
     * What a run looks like from before it is not always what it does: a line may remove what
     * a later target needs, and make then runs a line nobody asked about. Each line is asked
     * again when make hands it over, and one that cannot be run here stops the run instead of
     * being run all the same.
     */
    @Test
    void aLineOnlyTheRunReachesIsAskedWhenItComes() {
        assertTrue(run("test_fast").isOk(), "everything is built once");
        robot.clear();
        others.clear();
        Map<String, RecipeShell.Tool> tools = tools();
        // The first line of the test removes the preprocessed edit file ...
        tools.put("odk-info", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return null;
            }

            @Override
            public int run(RecipeShell.Call call) {
                assertTrue(file("tmp/eggboil-preprocess.owl").delete());
                return 0;
            }
        });
        // ... and converting the edit file again is something this robot does not do.
        final RecipeShell.Tool usual = tools.get("robot");
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return arguments.contains("eggboil-edit.owl")
                        ? "this robot does not read the edit file" : null;
            }

            @Override
            public int run(RecipeShell.Call call) throws IOException {
                return usual.run(call);
            }
        });

        OdkEngine.Outcome outcome = run("test_fast", Goldens.makeFixedVariables(), tools);

        assertFalse(outcome.isOk());
        assertEquals(0, robot.size(), "the line was not run, nor any after it: "
                + robotCommands());
        assertTrue(outcome.getFailure().contains("this robot does not read the edit file"),
                outcome.getFailure());
        assertTrue(outcome.getTranscript().get(outcome.getTranscript().size() - 1)
                .matches("make: \\*\\*\\* \\[Makefile:\\d+: test_fast\\] Error 2"));
    }

    @Test
    void aRunThatIsStoppedStopsBeforeTheNextLine() {
        stopAfter = 3;

        OdkEngine.Outcome outcome = run("test_fast");

        assertFalse(outcome.isOk());
        assertEquals(3, robot.size());
        assertTrue(outcome.getFailure().contains("stopped"), outcome.getFailure());
        assertTrue(outcome.getFailure().contains("clean"), "what a half-done build leaves is "
                + "said: " + outcome.getFailure());
    }

    /**
     * A program that takes its time over one line - a fetch - ends when the person stops
     * the run, and its line fails. That is said as the stop it was, not as a failure of
     * the line.
     */
    @Test
    void aStopDuringALineIsSaidAsAStop() {
        final boolean[] stopped = {false};
        Map<String, RecipeShell.Tool> tools = tools();
        final RecipeShell.Tool robot = tools.get("robot");
        final int[] calls = {0};
        tools.put("robot", new RecipeShell.Tool() {
            @Override
            public String whyNot(List<String> arguments) {
                return robot.whyNot(arguments);
            }

            @Override
            public int run(RecipeShell.Call call) throws IOException {
                if (++calls[0] == 2) {
                    stopped[0] = true;
                    call.complain("the fetch was stopped");
                    return 130;
                }
                return robot.run(call);
            }
        });

        OdkEngine.Outcome outcome = OdkEngine.run(edit, "test_fast",
                Goldens.makeFixedVariables(), tools, () -> stopped[0]);

        assertFalse(outcome.isOk());
        assertEquals(2, calls[0], "nothing ran after it");
        assertTrue(outcome.getFailure().startsWith("You stopped 'test_fast'"),
                outcome.getFailure());
        assertTrue(outcome.getTranscript().contains("Stopped."),
                outcome.getTranscript().toString());
    }

    // ------------------------------------------------------------------ asked, not run

    /**
     * For a list of targets to offer: whether one would be refused, found without running a
     * line. It is the sentence a run would refuse with.
     */
    @Test
    void whetherATargetCanRunIsAskedWithoutRunningIt() throws IOException {
        Map<String, RecipeShell.Tool> all = tools();
        assertNull(OdkEngine.whyNot(edit, "test_fast", Goldens.makeFixedVariables(), all));
        assertNull(OdkEngine.whyNot(edit, "clean", Goldens.makeFixedVariables(), all));

        Map<String, RecipeShell.Tool> without = tools();
        without.remove("check-rdfxml");
        assertNull(OdkEngine.whyNot(edit, "test_fast", Goldens.makeFixedVariables(), without),
                "the fast test checks no RDF/XML");
        String release = OdkEngine.whyNot(edit, "prepare_release_fast",
                Goldens.makeFixedVariables(), without);
        assertTrue(release.contains("check-rdfxml"), release);
        assertTrue(release.contains("ODK's container") && release.contains("ODK's own tools")
                && release.contains("OntoBoard > Check requirements..."),
                "a refusal names what else runs it: " + release);
        assertFalse(release.toLowerCase(java.util.Locale.ROOT).contains("start docker"),
                release);
        assertEquals(release, run("prepare_release_fast", Goldens.makeFixedVariables(), without)
                .getFailure());

        assertEquals(0, robot.size());
        assertEquals(0, others.size());
        assertFalse(file("tmp/eggboil-preprocess.owl").exists());
    }

    // ------------------------------------------------------------------ other targets

    @Test
    void cleanRemovesWhatOdksCleanRemoves() throws IOException {
        write("tmp/merged-eggboil-edit.ofn", "x");
        write("mirror/ro.owl", "x");
        write("eggboil.owl", "x");
        write("eggboil-full.obo", "x");

        OdkEngine.Outcome outcome = run("clean");

        assertTrue(outcome.isOk(), outcome.getFailure());
        assertEquals(Collections.singletonList(".gitkeep"), Arrays.asList(file("tmp").list()));
        assertEquals(0, file("mirror").list().length);
        assertFalse(file("eggboil.owl").exists());
        assertFalse(file("eggboil-full.obo").exists());
        assertTrue(edit.isFile());
        assertEquals(0, robot.size());
    }

    /**
     * The fast release, with the version and the shape of the version IRI the Release dialog
     * chose: both are variables of the run, on make's command line, and reach the recipes
     * through its sub-makes. The release files end at the top of the project, and what was
     * built beside the edit file is cleared away, as ODK does it.
     */
    @Test
    void theFastReleasePutsItsFilesAtTheTopWithTheVersionChosen() {
        Map<String, String> commandLine = new LinkedHashMap<String, String>();
        commandLine.putAll(Goldens.makeFixedVariables());
        commandLine.put("VERSION", "2.5.0");
        commandLine.put("ANNOTATE_ONTOLOGY_VERSION", "annotate -V https://example.org/eggboil/"
                + "eggboil/2.5.0/$@ --annotation owl:versionInfo 2.5.0");

        OdkEngine.Outcome outcome = run("prepare_release_fast", commandLine, tools());

        assertTrue(outcome.isOk(), outcome.getFailure() + "\n"
                + String.join("\n", outcome.getTranscript()));
        for (String product : new String[] {"eggboil.owl", "eggboil.obo", "eggboil-base.owl",
            "eggboil-base.obo", "eggboil-full.owl", "eggboil-full.obo"}) {
            assertTrue(new File(root, product).isFile(), product + " at the top of the project");
            assertFalse(file(product).exists(), product + " cleared from src/ontology");
        }
        assertFalse(file("tmp/merged-eggboil-edit.ofn").exists());
        boolean shaped = false;
        for (List<String> call : robot) {
            int at = call.indexOf("-V");
            if (at >= 0 && call.get(at + 1).equals(
                    "https://example.org/eggboil/eggboil/2.5.0/eggboil-base.owl")) {
                shaped = true;
            }
            assertFalse(call.contains("https://example.org/eggboil/eggboil/releases/2.5.0/"
                    + "eggboil.owl"), "ODK's own shape is not written beside the one chosen");
        }
        assertTrue(shaped, "each product gets its own IRI of the shape chosen");
        assertEquals(3, Collections.frequency(others, "check-rdfxml"), others.toString());
        assertTrue(outcome.getTranscript().contains("make copy_release_files"));
    }
}
