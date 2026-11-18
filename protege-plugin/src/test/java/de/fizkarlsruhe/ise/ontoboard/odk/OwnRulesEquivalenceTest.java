package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameFile;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rules of a project's own, of nfdicore's shape, run by OntoBoard's own engine without a
 * container, held against ODK's own make in ODK's image ({@code equivalence/own}, recorded by
 * {@code tools/odk-goldens/own_rules_goldens.py}): the fast test with an own makefile there; the
 * import modules built again from the mirrors as they are - one by a MIREOT rule of theirs
 * between ODK's seed's updates and nfdicore's, then sed over it; one by a BOT rule of theirs;
 * one by ODK's own rule - and the fast release, whose base is built by a rule of theirs.
 *
 * <p>Held as {@code FallbackEquivalenceTest} holds ODK's own targets: every recipe line make
 * said, in order; the files the run made or changed; each module and the base as the same
 * ontology and the same file, but for what shows the OWL API's version.
 */
class OwnRulesEquivalenceTest {

    @TempDir
    File top;

    private File project;
    private File edit;

    /** The recording's project: the oboimports seed with the overlay over it. */
    private void theProject() throws IOException {
        project = new File(top, "obim");
        write(Goldens.seedTree("oboimports"));
        write(Goldens.tree(new File(Goldens.root(), "equivalence/own/overlay")));
        edit = new File(project, "src/ontology/obim-edit.owl");
    }

    private void write(Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> one : files.entrySet()) {
            File target = new File(project, one.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), one.getValue());
        }
    }

    /** The recording's command line. */
    private static Map<String, String> commandLine() {
        Map<String, String> line = new LinkedHashMap<String, String>();
        line.put("VERSION", "1.0.0");
        line.put("TODAY", "2026-11-11");
        line.put("OBODATE", "11:11:2026 12:00");
        return line;
    }

    private static String recorded(String path) {
        return new String(Goldens.bytes(new File(Goldens.root(), "equivalence/own/" + path)),
                StandardCharsets.UTF_8);
    }

    private static Set<String> recordedFiles(String target) {
        return new TreeSet<String>(Goldens.tree(new File(Goldens.root(),
                "equivalence/own/" + target + "/files")).keySet());
    }

    /** A recorded file, written beside the project to be read as an ontology. */
    private File theirs(String target, String path) throws IOException {
        File file = new File(top, "recorded/" + target + "/" + path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), Goldens.bytes(new File(Goldens.root(),
                "equivalence/own/" + target + "/files/" + path)));
        return file;
    }

    /**
     * Runs a target here and holds against ODK's recording what make said and which files the
     * run made or changed. The recording leaves tmp/plugins out - ODK's copies of ROBOT's
     * plugin jars - and so does this.
     */
    private void runHeldAgainstTheRecording(String target) throws Exception {
        theProject();
        allOfTheSameAge(project);
        Map<String, String> before = hashes(project);

        assertNull(OdkEngine.whyNot(edit, target, commandLine()), "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(edit, target, commandLine(), null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep(target + ".transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        assertEquals(String.join("\n", saidByMake(lines(recorded(target + "/transcript.txt")))),
                String.join("\n", saidByMake(ours)),
                "every recipe line make said, in the order it ran them");
        Set<String> made = madeSince(before, project);
        for (Iterator<String> each = made.iterator(); each.hasNext();) {
            if (each.next().startsWith("src/ontology/tmp/plugins/")) {
                each.remove();
            }
        }
        keep(target + ".made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles(target), made, "the files the run made or changed");
    }

    private void assertBuiltAsOdkBuiltIt(String target, String built) throws Exception {
        assertSameOntology(built, theirs(target, built), new File(project, built));
        assertSameFile(built, theirs(target, built), new File(project, built));
    }

    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/own-rules-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theFastTestWithRulesOfTheirOwnRunsHereAsItRanInOdksImage() throws Exception {
        runHeldAgainstTheRecording("test_fast");
    }

    @Test
    void theModulesAreBuiltHereByTheirRulesAndByOdksAsOdkBuiltThem() throws Exception {
        runHeldAgainstTheRecording("no-mirror-refresh-imports");

        for (String module : new String[] {"iao", "ro", "pato"}) {
            assertBuiltAsOdkBuiltIt("no-mirror-refresh-imports",
                    "src/ontology/imports/" + module + "_import.owl");
        }
    }

    @Test
    void theReleaseWithABaseOfTheirOwnIsBuiltHereAsOdkBuiltIt() throws Exception {
        runHeldAgainstTheRecording("prepare_release_fast");

        for (String product : new String[] {"obim-base.owl", "obim.owl", "obim-full.owl"}) {
            assertSameOntology(product, theirs("prepare_release_fast", product),
                    new File(project, product));
            assertSameProduct(product, theirs("prepare_release_fast", product),
                    new File(project, product));
        }
    }
}
