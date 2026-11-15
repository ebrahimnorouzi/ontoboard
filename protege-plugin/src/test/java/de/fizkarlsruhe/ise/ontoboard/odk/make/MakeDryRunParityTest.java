package de.fizkarlsruhe.ise.ontoboard.odk.make;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * For every seeded project and every label of {@code make-n/targets.txt}, the reader's dry run
 * is the text GNU make 4.3 printed for {@code make -n} in the ODK image - exactly.
 *
 * <p>One thing is made equal on both sides: the absolute folder in {@code make[1]: Entering
 * directory '...'}, which is where the run happened, not something the Makefile decides.
 * Nothing else is normalised.
 */
class MakeDryRunParityTest {

    private static final String FOLDER = "<FOLDER>";

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("de.fizkarlsruhe.ise.ontoboard.odk.make.MakeRuns#all")
    void theDryRunIsWhatMakePrinted(String name, String label) {
        String expected = Goldens.makeDryRun(name, label);
        assertFalse(expected.startsWith("!make -n failed"),
                "make itself refused " + name + " " + label + "; this test expects it ran");
        MemoryDisk disk = MemoryDisk.seed(name);

        MakeFile make = MakeFile.read("Makefile", disk, MakeRuns.commandLine(label));
        List<String> printed = make.dryRun(MakeRuns.goal(label));

        String actual = printed.isEmpty() ? "" : String.join("\n", printed) + "\n";
        String theirs = expected.replace("/tmp/odk/" + name + "/src/ontology", FOLDER);
        String ours = actual.replace(disk.folder(), FOLDER);
        assertEquals(theirs, ours, MakeRuns.firstDifference(theirs, ours));
    }
}
