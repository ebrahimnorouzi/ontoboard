package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.rows;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByTheReport;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.starting;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Ten of ODK's own sample configurations, of different kinds - seeded by OntoBoard as ODK seeds
 * them ({@code SeedCorpusTest}) - their fast test and their fast release run by OntoBoard's own
 * engine and held against ODK's make in ODK's image ({@code equivalence/corpus}, recorded by
 * {@code tools/odk-goldens/corpus_runs_goldens.py}), as {@code FallbackEquivalenceTest} holds
 * the egg's: make's exit; every recipe line make said, in its order; what ROBOT said of each
 * check and of its report; the files the run made or changed; and each of them by its kind - a
 * report's rows in any order, the profile's verdict, an ontology the run built along the way as
 * the same axioms, a product as ODK's file but for what shows the OWL API's version, anything
 * else byte for byte.
 */
class CorpusEquivalenceTest {

    /** The ten, as corpus_runs_goldens.SAMPLES has them. */
    private static final String[] SAMPLES = {"tests__test-minimal", "tests__test-release",
        "tests__test-release-reason", "tests__test-release-materialise",
        "tests__test-robot-report", "tests__test-sparql-report", "tests__test-edit-obo",
        "tests__test-uribase-suffix", "tests__test-module-star", "examples__minimal"};

    private static final String[] TARGETS = {"test_fast", "prepare_release_fast"};

    /** The recording's command line. */
    private static final Map<String, String> AS_RECORDED = new LinkedHashMap<String, String>();

    static {
        AS_RECORDED.put("VERSION", "1.0.0");
        AS_RECORDED.put("TODAY", "2026-11-11");
        AS_RECORDED.put("OBODATE", "11:11:2026 12:00");
    }

    @TempDir
    File top;

    private File project;

    static Stream<Arguments> runs() {
        List<Arguments> runs = new ArrayList<Arguments>();
        for (String sample : SAMPLES) {
            for (String target : TARGETS) {
                runs.add(Arguments.of(sample, target));
            }
        }
        return runs.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("runs")
    void eachRunsHereAsItRanInOdksImage(String sample, String target) throws Exception {
        File edit = seeded(sample);
        allOfTheSameAge(project);
        Map<String, String> before = hashes(project);
        boolean odkPassed = recorded(sample, target, "status.txt").trim().equals("0");

        // On Protégé 5.5.0's Java a release with a JSON product is refused before it runs:
        // obographs, which writes JSON, needs Java 11 (docs/limitations.md). That is held.
        boolean json = false;
        for (String path : recordedFiles(sample, target)) {
            json |= path.endsWith(".json");
        }
        if (json && System.getProperty("java.specification.version").startsWith("1.")) {
            String why = OdkEngine.whyNot(edit, target, AS_RECORDED);
            assertTrue(why != null && why.contains("JSON") && why.contains("Java 11"),
                    String.valueOf(why));
            return;
        }

        if (odkPassed) {
            assertNull(OdkEngine.whyNot(edit, target, AS_RECORDED), "asked beforehand, it can");
        }
        OdkEngine.Outcome outcome = OdkEngine.run(edit, target, AS_RECORDED, null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep(sample + "." + target + ".transcript.txt", ours);
        assertEquals(odkPassed, outcome.isOk(), "make's exit as ODK's: " + outcome.getFailure()
                + "\n" + String.join("\n", ours));
        List<String> recorded = lines(recorded(sample, target, "transcript.txt"));
        assertEquals(String.join("\n", saidByMake(recorded)), String.join("\n", saidByMake(ours)),
                "every recipe line make said, in the order it ran them");
        assertEquals(starting("PASS Rule ", recorded), starting("PASS Rule ", ours),
                "what ROBOT said of each check");
        assertEquals(String.join("\n", saidByTheReport(recorded)),
                String.join("\n", saidByTheReport(ours)), "what ROBOT's report said");

        Set<String> made = madeSince(before, project);
        for (Iterator<String> each = made.iterator(); each.hasNext();) {
            if (each.next().startsWith("src/ontology/tmp/plugins/")) {
                each.remove();
            }
        }
        keep(sample + "." + target + ".made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles(sample, target), made, "the files the run made or changed");

        List<Executable> each = new ArrayList<Executable>();
        for (final String path : made) {
            each.add(() -> assertMadeAsOdkMadeIt(sample, target, path));
        }
        assertAll(sample + " " + target + ": each file the run made, held against ODK's", each);
    }

    /** One file the run made, held against ODK's as its kind is held. */
    private void assertMadeAsOdkMadeIt(String sample, String target, String path)
            throws Exception {
        File ours = new File(project, path);
        File theirs = new File(top, "recorded/" + sample + "/" + target + "/" + path);
        Files.createDirectories(theirs.getParentFile().toPath());
        Files.write(theirs.toPath(), Goldens.bytes(new File(Goldens.root(),
                "equivalence/corpus/" + sample + "/" + target + "/files/" + path)));
        String name = new File(path).getName();
        if (path.contains("/reports/") && name.endsWith(".tsv")) {
            assertEquals(rows(RunComparisons.read(theirs)), rows(RunComparisons.read(ours)),
                    path + ": its header, and its rows in any order");
        } else if (name.startsWith("validate_profile_")) {
            assertEquals(RunComparisons.read(theirs).trim(), RunComparisons.read(ours).trim(),
                    path + ": the profile's verdict");
        } else if (path.startsWith("src/ontology/tmp/") && (name.endsWith(".owl")
                || name.endsWith(".ofn"))) {
            assertSameOntology(path, theirs, ours);
        } else if (name.endsWith(".owl") || name.endsWith(".ofn") || name.endsWith(".obo")
                || name.endsWith(".json") || name.endsWith(".ttl") || name.endsWith(".owx")) {
            assertSameProduct(path, theirs, ours);
        } else {
            assertEquals(new String(Files.readAllBytes(theirs.toPath()), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(ours.toPath()), StandardCharsets.UTF_8),
                    path + ": byte for byte");
        }
    }

    /** OntoBoard's seed of the sample, which is ODK's, file for file. */
    private File seeded(String sample) throws IOException {
        project = new File(top, sample);
        for (Map.Entry<String, byte[]> file : OdkInstaller.renderSeed(
                Goldens.corpusConfig(sample)).files().entrySet()) {
            File target = new File(project, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        Map<String, Object> model = Goldens.corpusModel(sample);
        return new File(project, "src/ontology/" + model.get("id") + "-edit."
                + model.get("edit_format"));
    }

    private static String recorded(String sample, String target, String file) {
        return new String(Goldens.bytes(new File(Goldens.root(), "equivalence/corpus/" + sample
                + "/" + target + "/" + file)), StandardCharsets.UTF_8);
    }

    private static Set<String> recordedFiles(String sample, String target) {
        return new TreeSet<String>(Goldens.tree(new File(Goldens.root(), "equivalence/corpus/"
                + sample + "/" + target + "/files")).keySet());
    }

    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/corpus-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
