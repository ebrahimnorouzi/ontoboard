package de.fizkarlsruhe.ise.ontoboard.odk.make;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a real run does, on the seeded projects: {@link MakeFile#runPlan} against the plans
 * that make's own dry runs vouch for.
 *
 * <p>A dry run and a real run part in one place of ODK's Makefile: {@code test_fast}, whose
 * recipe is {@code $(MAKE_FAST) test}. make prints that line under {@code -n} and stops, and
 * runs it otherwise. Everywhere else the two are the same commands.
 */
class MakeRunPlanTest {

    static Stream<Arguments> seeds() {
        List<Arguments> seeds = new ArrayList<Arguments>();
        for (String name : Goldens.seedNames()) {
            seeds.add(Arguments.of(name));
        }
        return seeds.stream();
    }

    /** The commands of a plan, sub-makes opened, in the order they run; messages left out. */
    private static List<String> commands(List<MakeFile.Step> steps) {
        List<String> commands = new ArrayList<String>();
        for (MakeFile.Step step : steps) {
            if (step.kind() == MakeFile.Step.Kind.MESSAGE) {
                continue;
            }
            commands.add(step.command());
            commands.addAll(commands(step.steps()));
        }
        return commands;
    }

    /**
     * {@code test_fast} run is {@code make test} with the four switches off: the label
     * {@code test-fast} of the goldens, which make itself printed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("seeds")
    void testFastRunsTheTestWithItsSwitchesOff(String name) {
        MemoryDisk disk = MemoryDisk.seed(name);

        List<MakeFile.Step> run = MakeFile.read("Makefile", disk, Goldens.makeFixedVariables())
                .runPlan("test_fast");

        assertEquals(1, run.size());
        assertEquals(MakeFile.Step.Kind.SUB_MAKE, run.get(0).kind());
        assertEquals("make IMP=false PAT=false COMP=false MIR=false test", run.get(0).command());
        List<String> inside = commands(run.get(0).steps());
        assertFalse(inside.isEmpty(), "the tests themselves");
        assertEquals(commands(MakeFile.read("Makefile", disk, MakeRuns.commandLine("test-fast"))
                .runPlan("test")), inside);
        assertEquals(commands(MakeFile.read("Makefile", disk, MakeRuns.commandLine("test-fast"))
                .plan("test")), inside, "and that is what make printed for the fast test");
    }

    /** Every other recorded run: a real run's commands are the dry run's. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("de.fizkarlsruhe.ise.ontoboard.odk.make.MakeRuns#all")
    void elsewhereARunIsWhatTheDryRunPrints(String name, String label) {
        if (MakeRuns.goal(label).equals("test_fast")) {
            return;
        }
        MakeFile make = MakeFile.read("Makefile", MemoryDisk.seed(name),
                MakeRuns.commandLine(label));

        assertEquals(commands(make.plan(MakeRuns.goal(label))),
                commands(make.runPlan(MakeRuns.goal(label))));
    }
}
