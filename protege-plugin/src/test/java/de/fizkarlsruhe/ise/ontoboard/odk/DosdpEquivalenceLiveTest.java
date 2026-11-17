package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameFile;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.rows;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project with DOSDP patterns - ODK's own test configuration with patterns, with rows and a
 * second pattern of OntoBoard's - tested and released by OntoBoard's own engine with
 * dosdp-tools 0.19.3, held against ODK's own make in ODK's image ({@code equivalence/dosdp},
 * recorded by {@code tools/odk-goldens/dosdp_equivalence_goldens.py}): every recipe line make
 * said, in order; the files the run made or changed; the patterns' products as the same
 * ontology and the same file; the term lists line for line.
 *
 * <p>Live, as it runs dosdp-tools, which OntoBoard downloads on first use: it skips unless that
 * is installed - in {@code ontoboard.dosdp.home} when it is given, else where OntoBoard installs
 * it. Given the release's archive as {@code ontoboard.dosdp.archive} too, it installs it there
 * first, with OntoBoard's own installer.
 */
class DosdpEquivalenceLiveTest {

    @TempDir
    File top;

    private File project;
    private File edit;

    private static File home() {
        String given = System.getProperty("ontoboard.dosdp.home");
        return given == null || given.trim().isEmpty() ? DosdpTools.home() : new File(given);
    }

    @BeforeAll
    static void installedFromTheArchiveWhenOneIsGiven() throws IOException {
        String archive = System.getProperty("ontoboard.dosdp.archive");
        if (archive != null && !archive.trim().isEmpty() && !DosdpTools.isInstalled(home())) {
            DosdpTools.install(new File(archive), home());
        }
    }

    private static Map<String, RecipeShell.Tool> tools() {
        return OdkTools.forProject(null, home());
    }

    /** The recording's project: ODK's seed of it, with the overlay over it. */
    private void theProject() throws IOException {
        project = new File(top, "ptt");
        write(Goldens.tree(new File(Goldens.root(), "equivalence/dosdp/project")));
        write(Goldens.tree(new File(Goldens.root(), "equivalence/dosdp/overlay")));
        edit = new File(project, "src/ontology/ptt-edit.owl");
    }

    private void write(Map<String, byte[]> files) throws IOException {
        for (Map.Entry<String, byte[]> one : files.entrySet()) {
            File target = new File(project, one.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), one.getValue());
        }
    }

    /** The recording's command line: a run's switches, then its version and dates. */
    private static Map<String, String> commandLine(String... switches) {
        Map<String, String> line = new LinkedHashMap<String, String>();
        for (String one : switches) {
            line.put(one.substring(0, one.indexOf('=')), one.substring(one.indexOf('=') + 1));
        }
        line.put("VERSION", "1.0.0");
        line.put("TODAY", "2026-11-11");
        line.put("OBODATE", "11:11:2026 12:00");
        return line;
    }

    private static String recorded(String path) {
        return new String(Goldens.bytes(new File(Goldens.root(), "equivalence/dosdp/" + path)),
                StandardCharsets.UTF_8);
    }

    private static Set<String> recordedFiles(String target) {
        return new TreeSet<String>(Goldens.tree(new File(Goldens.root(),
                "equivalence/dosdp/" + target + "/files")).keySet());
    }

    /** A recorded file, written beside the project to be read as an ontology. */
    private File theirs(String target, String path) throws IOException {
        File file = new File(top, "recorded/" + target + "/" + path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), Goldens.bytes(new File(Goldens.root(),
                "equivalence/dosdp/" + target + "/files/" + path)));
        return file;
    }

    /**
     * Runs a target here as the recording ran it - the same switches, the same files a minute
     * newer than the rest - and holds against ODK's recording what make said and which files
     * the run made or changed. The recording leaves tmp/plugins out - ODK's copies of ROBOT's
     * plugin jars - and so does this.
     *
     * @param run the run's folder in the recording
     */
    private void runHeldAgainstTheRecording(String run, String target,
            Map<String, String> commandLine, String... newer) throws Exception {
        assumeTrue(DosdpTools.isInstalled(home()), "DOSDP-Tools " + DosdpTools.VERSION
                + " is not installed in " + home());
        theProject();
        allOfTheSameAge(project);
        for (String path : newer) {
            assertTrue(new File(project, path).setLastModified(RunComparisons.RECORDED_AGE
                    + 60_000L), path);
        }
        Map<String, String> before = hashes(project);

        assertNull(OdkEngine.whyNot(edit, target, commandLine, tools()),
                "asked beforehand, it can");
        long started = System.nanoTime();
        OdkEngine.Outcome outcome = OdkEngine.run(edit, target, commandLine, tools(), null);
        keep(run + ".seconds.txt", java.util.Collections.singletonList(String.valueOf(
                (System.nanoTime() - started) / 1_000_000_000L)));

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep(run + ".transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        assertEquals(String.join("\n", saidByMake(lines(recorded(run + "/transcript.txt")))),
                String.join("\n", saidByMake(ours)),
                "every recipe line make said, in the order it ran them");
        Set<String> made = madeSince(before, project);
        for (Iterator<String> each = made.iterator(); each.hasNext();) {
            if (each.next().startsWith("src/ontology/tmp/plugins/")) {
                each.remove();
            }
        }
        keep(run + ".made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles(run), made, "the files the run made or changed");
    }

    private void assertBuiltAsOdkBuiltIt(String target, String built) throws Exception {
        assertSameOntology(built, theirs(target, built), new File(project, built));
        assertSameFile(built, theirs(target, built), new File(project, built));
    }

    /** What the patterns' pipelines make: each pattern's axioms, and all of them merged. */
    private static final String[] FROM_THE_PATTERNS = {"src/patterns/data/default/example.ofn",
        "src/patterns/data/manual/locatedIn.ofn", "src/patterns/definitions.owl"};

    @Test
    void theTestOfAProjectWithPatternsRunsHereAsItRanInOdksImage() throws Exception {
        runHeldAgainstTheRecording("test", "test", commandLine("IMP=false", "MIR=false"));

        for (String built : FROM_THE_PATTERNS) {
            assertBuiltAsOdkBuiltIt("test", built);
        }
        assertSameFile("tmp/validate.ofn", theirs("test", "src/ontology/tmp/validate.ofn"),
                new File(project, "src/ontology/tmp/validate.ofn"));
    }

    /**
     * test with the imports built again from their mirrors - Build with "Download the imports'
     * sources again" off - just after a pattern and its row were added: dosdp-tools makes
     * pattern.owl (prototype) and each pipeline's term list (terms), and their terms go into
     * the imports' seed. The lists are held byte for byte: ROBOT's CSV ends its lines in CRLF,
     * so ODK's {@code sort | uniq} keeps a term twice, and so must this.
     */
    @Test
    void theTestThatBuildsTheImportsWithThePatternsTermsRunsHereAsInOdksImage()
            throws Exception {
        runHeldAgainstTheRecording("test-building-imports", "test", commandLine("MIR=false"),
                "src/patterns/dosdp-patterns/locatedIn.yaml",
                "src/patterns/data/manual/locatedIn.tsv");

        for (String built : new String[] {"src/patterns/pattern.owl",
            "src/patterns/definitions.owl", "src/ontology/imports/ro_import.owl",
            "src/ontology/imports/pato_import.owl"}) {
            assertBuiltAsOdkBuiltIt("test-building-imports", built);
        }
        // What dosdp-tools wrote, and what sort made of all of it: byte for byte.
        for (String list : new String[] {"src/patterns/data/default/example.txt",
            "src/patterns/data/manual/locatedIn.txt", "src/ontology/tmp/all_pattern_terms.txt",
            "src/ontology/tmp/seed.txt"}) {
            assertEquals(recorded("test-building-imports/files/" + list), bytesOf(list),
                    list + ": byte for byte");
        }
        // What ROBOT's queries wrote: the order of a query's rows is the store's - ODK's own
        // runs differ in it - so its header, then its rows in any order, each ended as ODK's.
        for (String table : new String[] {"src/ontology/tmp/pre_seed.txt",
            "src/ontology/tmp/pattern_owl_seed.txt"}) {
            assertEquals(linesInAnyOrder(recorded("test-building-imports/files/" + table)),
                    linesInAnyOrder(bytesOf(table)), table + ": its rows, each line's end kept");
        }
    }

    private String bytesOf(String path) throws IOException {
        return new String(Files.readAllBytes(new File(project, path).toPath()),
                StandardCharsets.UTF_8);
    }

    /** A table's header, then its rows sorted, each line with its CR where it has one. */
    private static List<String> linesInAnyOrder(String table) {
        List<String> all = new ArrayList<String>(java.util.Arrays.asList(table.split("\n", -1)));
        String header = all.remove(0);
        java.util.Collections.sort(all);
        all.add(0, header);
        return all;
    }

    @Test
    void theReleaseOfAProjectWithPatternsIsBuiltHereAsOdkBuiltIt() throws Exception {
        runHeldAgainstTheRecording("prepare_release", "prepare_release",
                commandLine("IMP=false", "MIR=false"));

        for (String built : FROM_THE_PATTERNS) {
            assertBuiltAsOdkBuiltIt("prepare_release", built);
        }
        for (String table : new String[] {"basic-report", "class-count-by-prefix", "edges",
            "obsoletes", "synonyms", "xrefs", "ptt-edit.owl-obo-report"}) {
            String path = "src/ontology/reports/" + table + ".tsv";
            assertEquals(rows(recorded("prepare_release/files/" + path)),
                    rows(RunComparisons.read(new File(project, path))),
                    table + ": its header, and its rows in any order");
        }
        String verdict = "src/ontology/reports/validate_profile_owl2dl_ptt.owl.txt";
        assertEquals(recorded("prepare_release/files/" + verdict).trim(),
                RunComparisons.read(new File(project, verdict)).trim(), "the profile's verdict");
        List<Executable> products = new ArrayList<Executable>();
        for (final String product : new String[] {"ptt.owl", "ptt-base.owl", "ptt-full.owl",
            "ptt.obo", "ptt-base.obo", "ptt-full.obo", "ptt.json", "ptt-base.json",
            "ptt-full.json"}) {
            final File theirs = theirs("prepare_release", product);
            products.add(() -> {
                if (product.endsWith(".owl")) {
                    assertSameOntology(product, theirs, new File(project, product));
                }
                assertSameProduct(product, theirs, new File(project, product));
            });
        }
        assertAll("the release's products, each held against ODK's", products);
    }

    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/dosdp-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
