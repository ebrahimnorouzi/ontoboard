package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.read;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.rows;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByTheReport;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.starting;
import static org.junit.jupiter.api.Assertions.assertAll;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A whole target, run by OntoBoard's own engine, held against the same target as ODK's own make
 * ran it in ODK's image ({@code equivalence/egg}, recorded by {@code tools/odk-goldens}).
 *
 * <p>The project, the command line and the age of the files are the recording's. What has to be
 * the same: every recipe line make said, in its order; what ROBOT said of each check; the files
 * the run made; the report's findings; the profile's verdict; and what was built - the same
 * axioms under the same names. What may differ is what a program says of itself: the image names
 * its programs' versions, OntoBoard says what runs here.
 */
class FallbackEquivalenceTest {

    @TempDir
    File top;

    /** The command line of the recording (tools/odk-goldens/equivalence_goldens.py). */
    private static final Map<String, String> AS_RECORDED = new LinkedHashMap<String, String>();

    static {
        AS_RECORDED.put("VERSION", "1.0.0");
        AS_RECORDED.put("TODAY", "2026-11-11");
        AS_RECORDED.put("OBODATE", "11:11:2026 12:00");
    }

    @Test
    void theFastTestRunsHereAsItRanInOdksImage() throws Exception {
        File ontology = EggFixture.write(top, false);
        allOfTheSameAge(top);
        Map<String, String> before = hashes(top);

        // The engine as an operation calls it: with the programs OntoBoard has.
        assertNull(OdkEngine.whyNot(new File(ontology, "eggboil-edit.owl"), "test_fast",
                AS_RECORDED), "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(new File(ontology, "eggboil-edit.owl"),
                "test_fast", AS_RECORDED, null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep("test_fast.transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        List<String> recorded = lines(text("test_fast/transcript.txt"));

        assertEquals(String.join("\n", saidByMake(recorded)), String.join("\n", saidByMake(ours)),
                "every recipe line make said, in the order it ran them");
        assertEquals(starting("PASS Rule ", recorded), starting("PASS Rule ", ours),
                "what ROBOT said of each check");
        assertEquals(String.join("\n", saidByTheReport(recorded)),
                String.join("\n", saidByTheReport(ours)), "what ROBOT's report said: of its "
                        + "rules, then the counts and the first findings");
        assertEquals("Finished running all tests successfully.", ours.get(ours.size() - 2),
                "the recipe's own last word, before make leaves the folder");

        Set<String> made = madeSince(before, top);
        keep("test_fast.made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles("test_fast"), made, "the files the run made");

        String report = "src/ontology/reports/eggboil-edit.owl-obo-report.tsv";
        assertEquals(rows(text("test_fast/files/" + report)), rows(read(new File(top, report))),
                "the report: its header, and its findings in any order");

        String verdict = "src/ontology/reports/validate_profile_owl2dl_eggboil.owl.txt";
        assertEquals(text("test_fast/files/" + verdict).trim(),
                read(new File(top, verdict)).trim(), "the profile's verdict");

        for (String built : new String[] {"src/ontology/eggboil-full.owl",
            "src/ontology/eggboil.owl", "src/ontology/tmp/eggboil-preprocess.owl",
            "src/ontology/tmp/merged-eggboil-edit.ofn", "src/ontology/tmp/validate.ofn"}) {
            File theirs = new File(top, "recorded/" + built);
            Files.createDirectories(theirs.getParentFile().toPath());
            Files.write(theirs.toPath(), Goldens.equivalence("test_fast/files/" + built));
            assertSameOntology(built, theirs, new File(top, built));
        }
    }

    /**
     * ODK's fast release - the release without fetching the imports again - run by OntoBoard's
     * own engine: the tests first, then ODK's tables of the ontology, the products in OWL and
     * in OBO, copied to the top of the project as ODK copies them.
     */
    @Test
    void theFastReleaseRunsHereAsItRanInOdksImage() throws Exception {
        File ontology = EggFixture.write(top, false);
        allOfTheSameAge(top);
        Map<String, String> before = hashes(top);
        File edit = new File(ontology, "eggboil-edit.owl");

        assertNull(OdkEngine.whyNot(edit, "prepare_release_fast", AS_RECORDED),
                "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(edit, "prepare_release_fast", AS_RECORDED,
                null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep("prepare_release_fast.transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        List<String> recorded = lines(text("prepare_release_fast/transcript.txt"));

        assertEquals(String.join("\n", saidByMake(recorded)), String.join("\n", saidByMake(ours)),
                "every recipe line make said, in the order it ran them");
        assertEquals(starting("PASS Rule ", recorded), starting("PASS Rule ", ours),
                "what ROBOT said of each check");

        Set<String> made = madeSince(before, top);
        keep("prepare_release_fast.made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles("prepare_release_fast"), made,
                "the files the release made: the products at the top, the tables, the verdict");

        for (String table : new String[] {"basic-report", "class-count-by-prefix", "edges",
            "obsoletes", "synonyms", "xrefs", "eggboil-edit.owl-obo-report"}) {
            String path = "src/ontology/reports/" + table + ".tsv";
            String here = read(new File(top, path));
            assertEquals(rows(text("prepare_release_fast/files/" + path)), rows(here),
                    table + ": its header, and its rows in any order");
            assertFalse(here.contains("\r"), table + ": lines end as in ODK's table");
        }

        String verdict = "src/ontology/reports/validate_profile_owl2dl_eggboil.owl.txt";
        assertEquals(text("prepare_release_fast/files/" + verdict).trim(),
                read(new File(top, verdict)).trim(), "the profile's verdict");

        List<Executable> products = new ArrayList<Executable>();
        for (final String product : new String[] {"eggboil.owl", "eggboil-base.owl",
            "eggboil-full.owl", "eggboil.obo", "eggboil-base.obo", "eggboil-full.obo",
            "src/ontology/tmp/validate.ofn"}) {
            File kept = new File("target/fallback-equivalence/prepare_release_fast."
                    + new File(product).getName());
            Files.copy(new File(top, product).toPath(), kept.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            final File theirs = new File(top, "recorded/" + product);
            Files.createDirectories(theirs.getParentFile().toPath());
            Files.write(theirs.toPath(),
                    Goldens.equivalence("prepare_release_fast/files/" + product));
            products.add(new Executable() {
                @Override
                public void execute() throws Throwable {
                    assertSameProduct(product, theirs, new File(top, product));
                }
            });
        }
        assertAll("the release's products, each held against ODK's", products);
    }

    // ------------------------------------------------------------------ the recording, the disk
    // How the two are compared - saidByMake, saidByTheReport, starting, rows,
    // assertSameOntology, assertSameProduct, read, lines, allOfTheSameAge, hashes, madeSince - is
    // in RunComparisons, which SideBySideLiveTest holds ODK's make running now against with.

    private static String text(String recorded) {
        return new String(Goldens.equivalence(recorded), StandardCharsets.UTF_8);
    }

    /** The files the image's run made or changed, from the project's root. */
    private static Set<String> recordedFiles(String target) {
        File files = new File(Goldens.root(), "equivalence/egg/" + target + "/files");
        return new TreeSet<String>(Goldens.tree(files).keySet());
    }

    /** Kept under target/, for a person to read after a run. */
    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/fallback-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
