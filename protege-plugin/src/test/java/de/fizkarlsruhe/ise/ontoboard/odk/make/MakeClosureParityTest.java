package de.fizkarlsruhe.ise.ontoboard.odk.make;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * For every seeded project and every label, what the reader says make considers is the set of
 * names GNU make 4.3 printed as {@code Considering target file '...'} or {@code Pruning file
 * '...'} under {@code make -n -d} - its own makefiles and those of every sub-make it followed
 * included.
 */
class MakeClosureParityTest {

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("de.fizkarlsruhe.ise.ontoboard.odk.make.MakeRuns#all")
    void theConsideredNamesAreMakes(String name, String label) {
        List<String> expected = Goldens.makeClosure(name, label);

        MakeFile make = MakeFile.read("Makefile", MemoryDisk.seed(name),
                MakeRuns.commandLine(label));
        List<String> actual = new ArrayList<String>(make.considered(MakeRuns.goal(label)));

        TreeSet<String> missing = new TreeSet<String>(expected);
        missing.removeAll(actual);
        TreeSet<String> extra = new TreeSet<String>(actual);
        extra.removeAll(expected);
        assertEquals(expected, actual, "make considered, the reader did not: " + missing
                + "; the reader considered, make did not: " + extra);
    }
}
