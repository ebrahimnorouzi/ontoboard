package de.fizkarlsruhe.ise.ontoboard.odk.make;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A real run of a goal, as {@code make <goal>} does it: {@link MakeFile#run}.
 *
 * <p>A dry run decides everything on the disk as it is before anything ran, and takes every
 * file whose recipe it printed for new. A real run decides as it goes: it hands a recipe's
 * lines to whoever runs them, looks at the file again afterwards, and stops at the first line
 * that fails. The recipes here are run by a few lines that stand in for a shell - they make
 * files, remove them, or fail - so that what make then decides can be seen.
 */
class MakeExecutionTest {

    private final List<String> ran = new ArrayList<String>();
    private final List<String> said = new ArrayList<String>();
    /** Now, for a file a recipe touches: later than every file the tests put on the disk. */
    private long clock = MemoryDisk.SAME_TIME + 60_000;

    private MemoryDisk disk(String makefile) {
        return new MemoryDisk("/work", "").file("Makefile", makefile);
    }

    private MakeFile read(MemoryDisk disk) {
        return MakeFile.read("Makefile", disk, new LinkedHashMap<String, String>());
    }

    /** Runs recipe lines: {@code touch F}, {@code rm -f F}, {@code false}; all else succeeds. */
    private MakeFile.Executor shell(final MemoryDisk disk) {
        return new MakeFile.Executor() {
            @Override
            public int run(MakeFile.Step step) {
                ran.add(step.command());
                String[] words = step.command().split(" ");
                if (words[0].equals("false")) {
                    return 1;
                }
                if (words[0].equals("touch")) {
                    clock += 1000;
                    disk.file(words[1], "", clock);
                }
                if (words[0].equals("rm")) {
                    disk.remove(words[words.length - 1]);
                }
                return 0;
            }

            @Override
            public void say(String line) {
                said.add(line);
            }
        };
    }

    private boolean run(MemoryDisk disk, String goal) {
        return read(disk).run(goal, shell(disk));
    }

    // ------------------------------------------------------------- order, echo, stop

    @Test
    void theRecipeLinesAreHandedOverInMakesOrder() {
        MemoryDisk disk = disk("all: a b\n\techo all\na:\n\techo a\nb: a\n\techo b one\n"
                + "\techo b two\n");

        assertTrue(run(disk, "all"));

        assertEquals(Arrays.asList("echo a", "echo b one", "echo b two", "echo all"), ran);
        assertEquals(ran, said, "make says each line before it is run");
    }

    @Test
    void aLineWrittenWithAnAtIsRunAndNotSaid() {
        MemoryDisk disk = disk("all:\n\t@echo quiet\n\techo loud\n");

        assertTrue(run(disk, "all"));

        assertEquals(Arrays.asList("echo quiet", "echo loud"), ran);
        assertEquals(Collections.singletonList("echo loud"), said);
    }

    @Test
    void aLineThatFailsStopsTheRunAndMakeSaysWhere() {
        MemoryDisk disk = disk("all: first\n\techo all\nfirst:\n\techo before\n\tfalse\n"
                + "\techo after\n");

        assertFalse(run(disk, "all"));

        assertEquals(Arrays.asList("echo before", "false"), ran);
        assertEquals("make: *** [Makefile:5: first] Error 1", said.get(said.size() - 1));
    }

    @Test
    void aLineWrittenWithADashFailsAndTheRunGoesOn() {
        MemoryDisk disk = disk("all:\n\t-false\n\techo after\n");

        assertTrue(run(disk, "all"));

        assertEquals(Arrays.asList("false", "echo after"), ran);
        assertEquals(Arrays.asList("false", "make: [Makefile:2: all] Error 1 (ignored)",
                "echo after"), said);
    }

    @Test
    void aGoalWithNothingToDoSaysSoAsMakeDoes() {
        MemoryDisk disk = disk("all: made\nmade:\n\ttouch made\n").file("made", "");

        assertTrue(run(disk, "all"));
        assertTrue(run(disk, "made"));

        assertEquals(Collections.<String>emptyList(), ran);
        assertEquals(Arrays.asList("make: Nothing to be done for 'all'.",
                "make: 'made' is up to date."), said);
    }

    @Test
    void whereMakeHasNoRuleItStopsAndSaysSoInItsWords() {
        MemoryDisk disk = disk("all: missing.txt\n\techo all\n");

        assertFalse(run(disk, "all"));

        assertEquals(Collections.<String>emptyList(), ran);
        assertEquals(Collections.singletonList("make: *** No rule to make target "
                + "'missing.txt', needed by 'all'.  Stop."), said);
    }

    // ------------------------------------------------------------- deciding as it goes

    /**
     * A file a recipe made is looked at again. Here the recipe of {@code stamp} runs and does
     * not touch it, so {@code final}, which is newer, is left as it is. A dry run takes every
     * file whose recipe it printed for new, and would remake {@code final}.
     */
    @Test
    void aFileItsRecipeDidNotTouchIsNotTakenForNew() {
        MemoryDisk disk = disk("final: stamp\n\ttouch final\nstamp: source\n\techo checked\n")
                .file("source", "", MemoryDisk.SAME_TIME + 5000)
                .file("stamp", "", MemoryDisk.SAME_TIME)
                .file("final", "", MemoryDisk.SAME_TIME + 1000);

        assertEquals(Arrays.asList("echo checked", "touch final"), read(disk).dryRun("final"));
        assertTrue(run(disk, "final"));

        assertEquals(Collections.singletonList("echo checked"), ran);
    }

    /** And a file a recipe did make counts from the moment it has. */
    @Test
    void aFileItsRecipeMadeMakesWhatNeedsItOld() {
        MemoryDisk disk = disk("final: stamp\n\ttouch final\nstamp: source\n\ttouch stamp\n")
                .file("source", "", MemoryDisk.SAME_TIME + 5000)
                .file("stamp", "", MemoryDisk.SAME_TIME)
                .file("final", "", MemoryDisk.SAME_TIME + 1000);

        assertTrue(run(disk, "final"));

        assertEquals(Arrays.asList("touch stamp", "touch final"), ran);
    }

    // ------------------------------------------------------------- sub-makes

    /**
     * A sub-make decides when it is reached, on the disk as the lines before it left it. ODK's
     * refresh of the imports cleans and then builds in one go: decided before the clean, there
     * would be nothing to build.
     */
    @Test
    void aSubMakeDecidesOnWhatTheLinesBeforeItLeft() {
        MemoryDisk disk = disk("outer:\n\trm -f made\n\t$(MAKE) inner\ninner: made\n"
                + "\techo inner\nmade:\n\ttouch made\n").file("made", "");

        assertEquals(Arrays.asList("rm -f made", "make inner",
                "make[1]: Entering directory '/work'", "echo inner",
                "make[1]: Leaving directory '/work'"), read(disk).dryRun("outer"),
                "decided before anything ran: 'made' is there");
        assertTrue(run(disk, "outer"));

        assertEquals(Arrays.asList("rm -f made", "touch made", "echo inner"), ran);
        assertEquals(Arrays.asList("rm -f made", "make inner",
                "make[1]: Entering directory '/work'", "touch made", "echo inner",
                "make[1]: Leaving directory '/work'"), said);
    }

    /** A line that only expands to make is a sub-make too in a real run: ODK's test_fast. */
    @Test
    void aLineThatOnlyExpandsToMakeIsEnteredToo() {
        MemoryDisk disk = disk("FAST = $(MAKE) X=1\nouter:\n\t$(FAST) inner\ninner:\n"
                + "\techo in $(X)\n");

        assertTrue(run(disk, "outer"));

        assertEquals(Collections.singletonList("echo in 1"), ran);
        assertEquals("make X=1 inner", said.get(0));
    }

    @Test
    void aFailureInASubMakeStopsTheMakeThatStartedIt() {
        MemoryDisk disk = disk("outer:\n\t$(MAKE) inner\n\techo never\ninner:\n\tfalse\n");

        assertFalse(run(disk, "outer"));

        assertEquals(Collections.singletonList("false"), ran);
        assertEquals(Arrays.asList("make inner", "make[1]: Entering directory '/work'", "false",
                "make[1]: *** [Makefile:5: inner] Error 1",
                "make[1]: Leaving directory '/work'",
                "make: *** [Makefile:2: outer] Error 2"), said);
    }

    // ------------------------------------------------------------- make -B

    private boolean runAlways(MemoryDisk disk, String goal) {
        LinkedHashMap<String, String> commandLine = new LinkedHashMap<String, String>();
        commandLine.put(MakeFile.ALWAYS_MAKE, "");
        return MakeFile.read("Makefile", disk, commandLine).run(goal, shell(disk));
    }

    /**
     * {@code make -B}: every target is made again, also one that is newer than what it is made
     * from. ODK's instructions release with it - an earlier build leaves files that were made
     * for another version - and a file nothing makes is still no target.
     */
    @Test
    void withAlwaysMakeATargetThatIsUpToDateIsMadeAgain() {
        MemoryDisk disk = disk("out: in\n\ttouch out\n")
                .file("in", "", MemoryDisk.SAME_TIME)
                .file("out", "", MemoryDisk.SAME_TIME + 5000);

        assertTrue(run(disk, "out"));
        assertEquals(Collections.<String>emptyList(), ran, "up to date: nothing to do");

        assertTrue(runAlways(disk, "out"));
        assertEquals(Collections.singletonList("touch out"), ran);
    }

    /** And what the target is made from is made again first, each once. */
    @Test
    void withAlwaysMakeWhatATargetIsMadeFromIsMadeAgainFirst() {
        MemoryDisk disk = disk("all: out other\nout: mid\n\ttouch out\nother: mid\n\ttouch other\n"
                + "mid: in\n\ttouch mid\n")
                .file("in", "", MemoryDisk.SAME_TIME)
                .file("mid", "", MemoryDisk.SAME_TIME + 1000)
                .file("out", "", MemoryDisk.SAME_TIME + 2000)
                .file("other", "", MemoryDisk.SAME_TIME + 2000);

        assertTrue(runAlways(disk, "all"));

        assertEquals(Arrays.asList("touch mid", "touch out", "touch other"), ran);
    }

    /** As make hands its flags down: a make that a recipe starts makes everything again too. */
    @Test
    void alwaysMakeGoesDownToAMakeARecipeStarts() {
        MemoryDisk disk = disk("fast:\n\t$(MAKE) out X=1\nout: in\n\ttouch out\n")
                .file("in", "", MemoryDisk.SAME_TIME)
                .file("out", "", MemoryDisk.SAME_TIME + 5000);

        assertTrue(run(disk, "fast"));
        assertEquals(Collections.<String>emptyList(), ran, "without it the make below finds "
                + "nothing to do");

        assertTrue(runAlways(disk, "fast"));
        assertEquals(Collections.singletonList("touch out"), ran);
    }

    /** A dry run says the same lines: what would be run is decided the same way. */
    @Test
    void aDryRunWithAlwaysMakeNamesEveryRecipe() {
        MemoryDisk disk = disk("out: in\n\ttouch out\n")
                .file("in", "", MemoryDisk.SAME_TIME)
                .file("out", "", MemoryDisk.SAME_TIME + 5000);
        LinkedHashMap<String, String> always = new LinkedHashMap<String, String>();
        always.put(MakeFile.ALWAYS_MAKE, "");

        assertEquals(Collections.singletonList("make: 'out' is up to date."),
                read(disk).dryRun("out"));
        assertEquals(Collections.singletonList("touch out"),
                MakeFile.read("Makefile", disk, always).dryRun("out"));
    }

    /** The flag is no variable: a makefile that asks for one of its name finds none. */
    @Test
    void alwaysMakeIsNoVariableOfTheMakefile() {
        MemoryDisk disk = disk("all:\n\techo [$(-B)]\n");
        LinkedHashMap<String, String> always = new LinkedHashMap<String, String>();
        always.put(MakeFile.ALWAYS_MAKE, "");

        assertFalse(MakeFile.read("Makefile", disk, always).variables()
                .containsKey(MakeFile.ALWAYS_MAKE), "the command line's variables: none");
        assertTrue(runAlways(disk, "all"));
        assertEquals(Collections.singletonList("echo []"), ran);
    }

    // ------------------------------------------------------------- what is not make's own stop

    /** What the reader does not read is not a failed run: it is thrown, as when planning. */
    @Test
    void whatTheReaderCannotFollowIsThrownNotSaid() {
        MemoryDisk disk = disk("outer:\n\tmake inner && echo done\ninner:\n\techo in\n");

        MakeFile.MakeException refused = assertThrows(MakeFile.MakeException.class,
                () -> run(disk, "outer"));

        assertTrue(refused.getMessage().contains("Makefile:2"), refused.getMessage());
        assertEquals(Collections.<String>emptyList(), ran);
    }
}
