package de.fizkarlsruhe.ise.ontoboard.odk.make;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * One rule of GNU make's per test: the rules that decide what {@code make -n} prints and what
 * {@code make -n -d} considers for ODK's Makefile. The parity tests check the whole against
 * make's recorded answers; these say which rule broke.
 */
class MakeFileTest {

    // ------------------------------------------------------------- variables

    @Test
    void aCommandLineVariableBeatsTheMakefilesAssignments() {
        assertEquals(Arrays.asList("echo cli"),
                dry("X = from-the-makefile\nX += more\nall:\n\techo $(X)\n", "all", "X=cli"));
    }

    @Test
    void aConditionalAssignmentTakesEffectOnlyWhenTheVariableIsUnset() {
        assertEquals(Arrays.asList("echo first set"),
                dry("A ?= first\nB = set\nB ?= second\nall:\n\techo $(A) $(B)\n", "all"));
    }

    @Test
    void appendingJoinsWithOneSpaceAndKeepsTheVariablesFlavour() {
        String makefile = "V = early\nR = $(V)\nR += r\nS := $(V)\nS += $(V)\nE += e\n"
                + "V = late\nall:\n\techo [$(R)] [$(S)] [$(E)]\n";
        assertEquals(Arrays.asList("echo [late r] [early early] [e]"), dry(makefile, "all"));
    }

    @Test
    void aRecursiveVariableExpandsWhenUsedASimpleOneWhenAssigned() {
        assertEquals(Arrays.asList("echo late early"),
                dry("V = early\nR = $(V)\nS := $(V)\nV = late\nall:\n\techo $(R) $(S)\n", "all"));
    }

    @Test
    void ifeqComparesTheExpandedSides() {
        String makefile = "SWITCH = $(ON)\nON = yes\nifeq ($(SWITCH),yes)\nX = taken\nelse\n"
                + "X = not taken\nendif\nifneq ($(SWITCH),yes)\nY = wrong\nendif\n"
                + "all:\n\techo $(X) [$(Y)]\n";
        assertEquals(Arrays.asList("echo taken []"), dry(makefile, "all"));
        assertEquals(Arrays.asList("echo not taken [wrong]"), dry(makefile, "all", "ON=no"));
    }

    // ------------------------------------------------------------- rules

    @Test
    void aPatternRuleGivesItsStemToDollarStar() {
        MemoryDisk disk = disk("out/%.txt: src/%.in\n\tconvert $< $* $@\n")
                .file("src/page.in", "");
        assertEquals(Arrays.asList("convert src/page.in page out/page.txt"),
                read(disk).dryRun("out/page.txt"));
    }

    @Test
    void anOrderOnlyPrerequisiteIsConsideredButIsNotInDollarCaretAndNeverMakesItsTargetOld() {
        String makefile = "t: a | d\n\techo $^ / $|\na:\n\techo making a\nd:\n\tmkdir d\n";
        MemoryDisk disk = disk(makefile).file("d/keep", "");
        assertEquals(Arrays.asList("echo making a", "echo a / d"), read(disk).dryRun("t"));
        assertEquals(Arrays.asList("Makefile", "a", "d", "t"),
                new ArrayList<String>(read(disk).considered("t")));

        // d is newer than t, and t is still up to date: d is only order-only.
        MemoryDisk older = disk(makefile).file("d/keep", "").file("t", "", 1L).file("a", "", 1L);
        assertEquals(Arrays.asList("make: 't' is up to date."), read(older).dryRun("t"));
    }

    @Test
    void aPhonyTargetIsRemadeEvenWhenAFileHasItsName() {
        MemoryDisk disk = disk(".PHONY: p\np:\n\techo p\nq:\n\techo q\n")
                .file("p", "").file("q", "");
        assertEquals(Arrays.asList("echo p"), read(disk).dryRun("p"));
        assertEquals(Arrays.asList("make: 'q' is up to date."), read(disk).dryRun("q"));
    }

    @Test
    void anExistingFileWithNoRuleIsALeaf() {
        MemoryDisk disk = disk("t: in.txt\n\tcat in.txt > t\n").file("in.txt", "");
        assertEquals(Arrays.asList("cat in.txt > t"), read(disk).dryRun("t"));
        assertEquals(Arrays.asList("Makefile", "in.txt", "t"),
                new ArrayList<String>(read(disk).considered("t")));
    }

    @Test
    void aTargetNewerThanItsPrerequisiteIsNotRemadeAndAnOlderOneIs() {
        String makefile = "t: in\n\tbuild t\n";
        assertEquals(Arrays.asList("make: 't' is up to date."),
                read(disk(makefile).file("t", "", 2000L).file("in", "", 1000L)).dryRun("t"));
        assertEquals(Arrays.asList("build t"),
                read(disk(makefile).file("t", "", 1000L).file("in", "", 2000L)).dryRun("t"));
    }

    @Test
    void aFileOnlyAChainOfPatternRulesMakesIsIntermediateAndIsLookedAtOnlyWhenNeeded() {
        String makefile = "%.b: %.a\n\tcp $< $@\n%.c: %.b\n\tcp $< $@\n";
        MemoryDisk upToDate = disk(makefile).file("x.a", "").file("x.c", "");
        assertEquals(Arrays.asList("make: 'x.c' is up to date."), read(upToDate).dryRun("x.c"));
        assertEquals(Arrays.asList("Makefile", "x.a", "x.c"),
                new ArrayList<String>(read(upToDate).considered("x.c")));

        MemoryDisk missing = disk(makefile).file("x.a", "");
        assertEquals(Arrays.asList("cp x.a x.b", "cp x.b x.c"), read(missing).dryRun("x.c"));
        assertEquals(Arrays.asList("Makefile", "x.a", "x.b", "x.c"),
                new ArrayList<String>(read(missing).considered("x.c")));
    }

    @Test
    void aGoalThatNeedsNothingSaysSo() {
        assertEquals(Arrays.asList("make: Nothing to be done for 'none'."),
                dry(".PHONY: none\nnone:\n", "none"));
    }

    @Test
    void thereIsNoRuleToMakeAMissingFileNobodyMakes() {
        MakeFile.MakeException needed = assertThrows(MakeFile.MakeException.class,
                () -> dry("t: missing\n\techo t\n", "t"));
        assertTrue(needed.getMessage().contains("No rule to make target 'missing', needed by 't'"),
                needed.getMessage());
        MakeFile.MakeException goal = assertThrows(MakeFile.MakeException.class,
                () -> dry("t:\n\techo t\n", "absent"));
        assertTrue(goal.getMessage().contains("No rule to make target 'absent'"),
                goal.getMessage());
    }

    // ------------------------------------------------------------- recipes

    @Test
    void aContinuedRecipeLineKeepsItsBackslashAndLineBreakLessOneTab() {
        assertEquals(Arrays.asList("first \\\n\tsecond \\\n  third"),
                dry("all:\n\tfirst \\\n\t\tsecond \\\n  third\n", "all"));
    }

    @Test
    void aRecipeLineThatSaysDollarMakeIsFollowedAndOneThatOnlyExpandsToMakeIsNot() {
        String makefile = "OTHER = $(MAKE) inner\nouter:\n\t$(MAKE) inner X=1\n"
                + "viaother:\n\t$(OTHER)\ninner:\n\techo inner $(X)\n";
        assertEquals(Arrays.asList("make inner X=1", "make[1]: Entering directory '/work'",
                "echo inner 1", "make[1]: Leaving directory '/work'"), dry(makefile, "outer"));
        assertEquals(Arrays.asList("make inner"), dry(makefile, "viaother"));
    }

    @Test
    void thePlanSaysWhereEachCommandComesFrom() {
        List<MakeFile.Step> plan = read(disk("X = 1\nall:\n\t@echo a\n\t-rm x\n")).plan("all");
        assertEquals(2, plan.size());
        MakeFile.Step echo = plan.get(0);
        assertEquals("echo a", echo.command());
        assertEquals("all", echo.target());
        assertEquals("Makefile", echo.file());
        assertEquals(3, echo.line());
        assertTrue(echo.silent());
        assertFalse(echo.ignoreErrors());
        assertEquals(4, plan.get(1).line());
        assertEquals("rm x", plan.get(1).command());
        assertTrue(plan.get(1).ignoreErrors());

        List<MakeFile.Step> outer =
                read(disk("outer:\n\t$(MAKE) inner\ninner:\n\techo in\n")).plan("outer");
        assertEquals(1, outer.size());
        assertEquals("make inner", outer.get(0).command());
        assertEquals(1, outer.get(0).steps().size());
        assertEquals("echo in", outer.get(0).steps().get(0).command());
        assertEquals("inner", outer.get(0).steps().get(0).target());
        assertEquals(4, outer.get(0).steps().get(0).line());
    }

    // ------------------------------------------------------------- functions

    @Test
    void foreachExpandsItsTextOncePerWord() {
        assertEquals("[<a> <b> <c>]", value("$(foreach n,a b c,<$(n)>)"));
    }

    @Test
    void patsubstReplacesWordByWordAndKeepsTheReplacementsSpaces() {
        assertEquals("[a.o b.h c.o]", value("$(patsubst %.c,%.o,a.c b.h c.c)"));
        assertEquals("[ x/a  x/b]", value("$(patsubst %, x/%, a b)"));
    }

    @Test
    void sortSortsAndRemovesDuplicates() {
        assertEquals("[a b c]", value("$(sort b a c a)"));
    }

    @Test
    void notdirKeepsWhatFollowsTheLastSlash() {
        assertEquals("[a.c b.c z]", value("$(notdir src/a.c b.c /x/y/z)"));
    }

    @Test
    void wildcardListsMatchingFilesThroughTheDisk() {
        MemoryDisk disk = disk("X = $(wildcard lib/*.jar none/*.jar)\nall:\n\t@echo [$(X)]\n")
                .file("lib/b.jar", "").file("lib/a.jar", "").file("lib/c.txt", "")
                .file("lib/.hidden.jar", "");
        assertEquals(Arrays.asList("echo [lib/a.jar lib/b.jar]"), read(disk).dryRun("all"));
    }

    @Test
    void stripCollapsesWhitespace() {
        assertEquals("[a b]", value("$(strip   a   b  )"));
    }

    @Test
    void wordPicksTheNthWord() {
        assertEquals("[b]", value("$(word 2, a b c)"));
    }

    @Test
    void addprefixPrefixesEveryWord() {
        assertEquals("[-i a -i b]", value("$(addprefix -i ,a b)"));
    }

    @Test
    void realpathGivesAbsolutePathsOfWhatExists() {
        MemoryDisk disk = disk("X = $(realpath b/f ./b missing)\nall:\n\t@echo [$(X)]\n")
                .file("b/f", "");
        assertEquals(Arrays.asList("echo [/work/b/f /work/b]"), read(disk).dryRun("all"));
    }

    @Test
    void evalReadsItsTextAsMakefileLinesWhereverItIsExpanded() {
        String makefile = "$(eval A = from-eval)\nall:\n\t$(eval B = $(A) late)echo $(A) $(B)\n";
        assertEquals(Arrays.asList("echo from-eval from-eval late"), dry(makefile, "all"));
    }

    @Test
    void theShellAnswersOnlyTheFormsOdksTemplateHas() {
        MakeFile.Shell shell = MakeFile.odkShell(
                Clock.fixed(Instant.parse("2026-11-12T08:30:00Z"), ZoneOffset.UTC));
        String makefile = "TODAY ?= $(shell date +%Y-%m-%d)\n"
                + "OBODATE ?= $(shell date +'%d:%m:%Y %H:%M')\n"
                + "UP = $(shell echo ro | tr a-z A-Z)\n"
                + "all:\n\techo $(TODAY) $(OBODATE) $(UP)\nbad:\n\techo $(shell ls -l)\n";
        MakeFile make = MakeFile.read("Makefile", disk(makefile),
                new LinkedHashMap<String, String>(), shell);
        assertEquals(Arrays.asList("echo 2026-11-12 12:11:2026 08:30 RO"), make.dryRun("all"));
        MakeFile.MakeException refused =
                assertThrows(MakeFile.MakeException.class, () -> make.dryRun("bad"));
        assertTrue(refused.getMessage().contains("ls -l"), refused.getMessage());
    }

    // ------------------------------------------------------------- refusals

    @Test
    void anUnknownFunctionIsRefusedWithTheFileAndLine() {
        MemoryDisk disk = disk("A = 1\ninclude extra.mk\n")
                .file("extra.mk", "B = 2\n\nC = $(filter a,a b)\n");
        MakeFile.MakeException refused =
                assertThrows(MakeFile.MakeException.class, () -> read(disk));
        assertTrue(refused.getMessage().contains("extra.mk:3"), refused.getMessage());
        assertTrue(refused.getMessage().contains("filter"), refused.getMessage());
    }

    @Test
    void whatOdksTemplateDoesNotUseIsRefusedWithTheFileAndLine() {
        String[] unknown = {"vpath %.c src", "override X = 1", "ifdef X\nendif",
            "-include other.mk", "unexport X", "X != ls", "t:: a", "t: X = 1", "a b: %.c: %.h",
            ".SUFFIXES:", "t: a ; echo t", "X = $(X:a=b)", "X = $?", "t: $$(wildcard *.c)",
            "ifeq \"a\" \"b\"\nendif"};
        for (String line : unknown) {
            MakeFile.MakeException refused = assertThrows(MakeFile.MakeException.class,
                    () -> read(disk("A = 1\n" + line + "\nall:\n\techo\n")), line);
            assertTrue(refused.getMessage().contains("Makefile:2"),
                    line + " -> " + refused.getMessage());
        }
    }

    // ------------------------------------------------------------- what a caller may ask

    @Test
    void theProjectsOwnMakefileIsSeenInTheRulesAndVariablesTheGoalDependsOn() {
        MemoryDisk disk = MemoryDisk.seed("eggboil");
        disk.file("eggboil.Makefile", disk.read("eggboil.Makefile")
                + "\nreports/eggboil-edit.owl-obo-report.tsv: tmp/merged-eggboil-edit.ofn"
                + " | reports\n\tmy-report $< $@\n\nREPORT_FAIL_ON = WARN\n\n"
                + "tmp/%.ofn: %.owl\n\tmy-convert $< $@\n");
        Map<String, String> commandLine = new LinkedHashMap<String, String>();
        commandLine.put("VERSION", "1.0.0");
        commandLine.put("TODAY", "2026-11-11");
        commandLine.put("OBODATE", "11:11:2026 12:00");
        MakeFile make = MakeFile.read("Makefile", disk, commandLine);
        Set<String> considered = make.considered("robot_reports");

        Set<String> ownRulesFor = new TreeSet<String>();
        MakeFile.Rule odksReportRule = null;
        for (MakeFile.Rule rule : make.rules()) {
            for (String name : considered) {
                if (rule.matches(name)) {
                    if (rule.file().equals("eggboil.Makefile")) {
                        ownRulesFor.add((rule.isPattern() ? "pattern " : "explicit ") + name);
                    } else if (name.equals("reports/eggboil-edit.owl-obo-report.tsv")
                            && !rule.isPattern()) {
                        odksReportRule = rule;
                    }
                }
            }
        }
        assertEquals(new TreeSet<String>(Arrays.asList(
                "explicit reports/eggboil-edit.owl-obo-report.tsv",
                "pattern tmp/merged-eggboil-edit.ofn")), ownRulesFor);

        MakeFile.Variable failOn = make.variables().get("REPORT_FAIL_ON");
        assertEquals("eggboil.Makefile", failOn.file());
        assertEquals("WARN", failOn.value());
        assertNotNull(odksReportRule);
        StringBuilder recipe = new StringBuilder();
        for (MakeFile.Line line : odksReportRule.recipe()) {
            recipe.append(line.text()).append('\n');
        }
        assertTrue(make.variablesReadBy(recipe.toString()).contains("REPORT_FAIL_ON"),
                make.variablesReadBy(recipe.toString()).toString());

        // The later recipe wins, as make warns.
        assertTrue(make.dryRun("robot_reports").contains("my-report tmp/merged-eggboil-edit.ofn"
                + " reports/eggboil-edit.owl-obo-report.tsv"), make.dryRun("robot_reports")
                .toString());
    }

    @Test
    void onDiskReadsARealFolderAndItsSubMakeFindsTheMakefileThere() {
        MakeFile.Disk disk = MakeFile.onDisk(new File(Goldens.root(), "seed/eggboil/src/ontology"));
        MakeFile make = MakeFile.read("Makefile", disk, MakeRuns.commandLine("refresh-imports"));
        // Neither clean nor an empty all_imports depends on the files' times, which a checkout sets.
        String expected = Goldens.makeDryRun("eggboil", "refresh-imports")
                .replace("/tmp/odk/eggboil/src/ontology", "<FOLDER>");
        String actual = (String.join("\n", make.dryRun("refresh-imports")) + "\n")
                .replace(disk.folder(), "<FOLDER>");
        assertEquals(expected, actual);
        assertEquals(Goldens.makeClosure("eggboil", "refresh-imports"),
                new ArrayList<String>(make.considered("refresh-imports")));
    }

    @Test
    void variablesReadByFollowsRecursiveVariablesButNotLoopVariablesOrAutomaticOnes() {
        MakeFile make = read(disk("A = $(B) x\nB = ${C}\nC = c\nD := $(E)\nE = e\n"
                + "all:\n\techo\n"));
        assertEquals(new TreeSet<String>(Arrays.asList("A", "B", "C", "D")),
                new TreeSet<String>(make.variablesReadBy("$(A) $(foreach n,$(D),$(n)) $@")));
    }

    // ------------------------------------------------------------- helpers

    private static MemoryDisk disk(String makefile) {
        return new MemoryDisk("/work", "").file("Makefile", makefile);
    }

    private static MakeFile read(MemoryDisk disk, String... variables) {
        Map<String, String> commandLine = new LinkedHashMap<String, String>();
        for (String variable : variables) {
            int equals = variable.indexOf('=');
            commandLine.put(variable.substring(0, equals), variable.substring(equals + 1));
        }
        return MakeFile.read("Makefile", disk, commandLine);
    }

    private static List<String> dry(String makefile, String goal, String... variables) {
        return read(disk(makefile), variables).dryRun(goal);
    }

    /** What a recursive variable set to the expression expands to in a recipe, in brackets. */
    private static String value(String expression) {
        String printed = dry("X = " + expression + "\nall:\n\t@echo [$(X)]\n", "all").get(0);
        return printed.substring("echo ".length());
    }
}
