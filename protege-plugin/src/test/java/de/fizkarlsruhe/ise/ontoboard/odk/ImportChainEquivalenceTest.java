package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.allOfTheSameAge;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameOntology;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.hashes;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.lines;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.madeSince;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.onOdksOwlApi;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.assertSameProduct;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.read;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.rows;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByMake;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.saidByTheReport;
import static de.fizkarlsruhe.ise.ontoboard.odk.RunComparisons.starting;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * ODK's import chain, run by OntoBoard's own engine without a container, held against the same
 * targets as ODK's own make ran them in ODK's image ({@code equivalence/loim}, recorded by
 * {@code tools/odk-goldens/imports_goldens.py}): the mirrors fetched, the modules cut from
 * them - and the full test and the full release, which build the imports first.
 *
 * <p>The project is the recording's: {@code seed/localimports} with the overlay over it, an
 * ontology that uses a term of each of its three imports. The ontologies to mirror are the
 * small ones in {@code equivalence/loim/upstream}, given to make as {@code OBOBASE=<their
 * folder as a file: address>}, as the recording gave them: nothing is fetched from the
 * network. What fetches is ODK's own recipe line - {@code curl -L $(OBOBASE)/<id>.owl ...} -
 * read by OntoBoard's stand-in for curl.
 */
class ImportChainEquivalenceTest {

    /** Where the recording's make found the ontologies to mirror. */
    private static final String RECORDED_UPSTREAM = "file:///work/in/upstream";

    @TempDir
    File top;

    private File project;
    private File edit;
    private final Map<String, String> commandLine = new LinkedHashMap<String, String>();

    /** The recording's project, and the recording's command line with this machine's folder. */
    private void theProject() throws IOException {
        project = new File(top, "project");
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>(
                Goldens.seedTree("localimports"));
        files.putAll(Goldens.tree(new File(Goldens.root(), "equivalence/loim/overlay")));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            File to = new File(project, file.getKey());
            Files.createDirectories(to.getParentFile().toPath());
            Files.write(to.toPath(), file.getValue());
        }
        File upstream = new File(top, "upstream");
        for (Map.Entry<String, byte[]> file : Goldens.tree(new File(Goldens.root(),
                "equivalence/loim/upstream")).entrySet()) {
            File to = new File(upstream, file.getKey());
            Files.createDirectories(to.getParentFile().toPath());
            Files.write(to.toPath(), file.getValue());
        }
        Files.createDirectories(new File(project, "src/ontology/tmp").toPath());
        edit = new File(project, "src/ontology/loim-edit.owl");
        commandLine.put("OBOBASE", here());
        commandLine.put("VERSION", "1.0.0");
        commandLine.put("TODAY", "2026-11-11");
        commandLine.put("OBODATE", "11:11:2026 12:00");
    }

    /** The folder of the ontologies to mirror, as an address. */
    private String here() {
        String address = new File(top, "upstream").toURI().toString();
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    private static String recorded(String path) {
        return new String(Goldens.bytes(new File(Goldens.root(), "equivalence/loim/" + path)),
                StandardCharsets.UTF_8);
    }

    private static Set<String> recordedFiles(String target) {
        return new TreeSet<String>(Goldens.tree(new File(Goldens.root(),
                "equivalence/loim/" + target + "/files")).keySet());
    }

    /** A recorded file, written beside the project to be read as an ontology. */
    private File theirs(String target, String path) throws IOException {
        File file = new File(top, "recorded/" + target + "/" + path);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), Goldens.bytes(new File(Goldens.root(),
                "equivalence/loim/" + target + "/files/" + path)));
        return file;
    }

    /** What make said, with the folder of the ontologies to mirror left out of it. */
    private List<String> saidByMakeHere(List<String> transcript, String upstream) {
        List<String> said = new ArrayList<String>();
        for (String line : saidByMake(transcript)) {
            said.add(line.replace(upstream, "<UPSTREAM>"));
        }
        return said;
    }

    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/import-chain-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Not held against a recording: the person stops the run while an ontology to mirror
     * comes in from a server that takes its time. The run ends then, not when the server
     * has finished, and it is said as the stop it was.
     */
    @Test
    void aStopWhileAnImportIsFetchedEndsTheRun() throws Exception {
        theProject();
        final AtomicLong askedAt = new AtomicLong();
        final List<String> asked = Collections.synchronizedList(new ArrayList<String>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                asked.add(exchange.getRequestURI().getPath());
                askedAt.compareAndSet(0, System.currentTimeMillis());
                // A little every 50 ms, for 8 s.
                exchange.sendResponseHeaders(200, 0);
                try {
                    for (int i = 0; i < 160; i++) {
                        exchange.getResponseBody().write(new byte[1024]);
                        exchange.getResponseBody().flush();
                        Thread.sleep(50);
                    }
                } catch (IOException | InterruptedException gone) {
                    // The one who asked has left.
                }
                exchange.close();
            }
        });
        server.start();
        try {
            commandLine.put("OBOBASE", "http://127.0.0.1:" + server.getAddress().getPort());

            OdkEngine.Outcome outcome = OdkEngine.run(edit, "refresh-imports", commandLine,
                    () -> !asked.isEmpty());

            long took = System.currentTimeMillis() - askedAt.get();
            assertFalse(outcome.isOk());
            assertEquals(1, asked.size(), "one fetch was begun, and none after it: " + asked);
            assertTrue(took < 4000, "ended " + took + " ms after the server was asked");
            assertTrue(outcome.getFailure().startsWith("You stopped 'refresh-imports'"),
                    outcome.getFailure());
            String[] left = new File(project, "src/ontology/tmp").list();
            for (String name : left == null ? new String[0] : left) {
                assertFalse(name.contains("download"), "no part of a fetch is left: " + name);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theImportsAreRefreshedHereAsOdkRefreshedThem() throws Exception {
        theProject();
        allOfTheSameAge(project);
        Map<String, String> before = hashes(project);

        assertNull(OdkEngine.whyNot(edit, "refresh-imports", commandLine),
                "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(edit, "refresh-imports", commandLine, null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep("refresh-imports.transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        List<String> theirs = lines(recorded("refresh-imports/transcript.txt"));
        assertEquals(String.join("\n", saidByMakeHere(theirs, RECORDED_UPSTREAM)),
                String.join("\n", saidByMakeHere(ours, here())),
                "every recipe line make said, in the order it ran them");

        Set<String> made = madeSince(before, project);
        keep("refresh-imports.made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles("refresh-imports"), made, "the files the run made");

        for (String id : new String[] {"ro", "iao", "bfo"}) {
            // What was fetched is what was there, byte for byte.
            assertArrayEquals(Goldens.bytes(new File(Goldens.root(),
                    "equivalence/loim/upstream/" + id + ".owl")),
                    Files.readAllBytes(new File(project, "src/ontology/tmp/" + id
                            + "-download.owl").toPath()), id + ": what curl fetched");
            for (String built : new String[] {"src/ontology/mirror/" + id + ".owl",
                "src/ontology/imports/" + id + "_import.owl"}) {
                assertSameOntology(built, theirs("refresh-imports", built),
                        new File(project, built));
                if (onOdksOwlApi()) {
                    assertEquals(recorded("refresh-imports/files/" + built),
                            read(new File(project, built)), built + ": on ODK's OWL API, the "
                                    + "file itself");
                }
            }
        }
        assertEquals(lines(recorded("refresh-imports/files/src/ontology/tmp/seed.txt")),
                lines(read(new File(project, "src/ontology/tmp/seed.txt"))),
                "the terms the modules are cut for");
    }

    /**
     * ODK's test as it is asked for by default: the imports fetched and built first, then
     * the reasoner, the report, the SPARQL checks and the profile, on an ontology that
     * uses what it imports.
     */
    @Test
    void theFullTestRunsHereAsItRanInOdksImage() throws Exception {
        theProject();
        allOfTheSameAge(project);
        Map<String, String> before = hashes(project);

        assertNull(OdkEngine.whyNot(edit, "test", commandLine), "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(edit, "test", commandLine, null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep("test.transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        List<String> theirs = lines(recorded("test/transcript.txt"));
        assertEquals(String.join("\n", saidByMakeHere(theirs, RECORDED_UPSTREAM)),
                String.join("\n", saidByMakeHere(ours, here())),
                "every recipe line make said, in the order it ran them");
        assertEquals(starting("PASS Rule ", theirs), starting("PASS Rule ", ours),
                "what ROBOT said of each check");
        assertEquals(String.join("\n", saidByTheReport(theirs)),
                String.join("\n", saidByTheReport(ours)), "what ROBOT's report said");
        assertTrue(ours.contains("Finished running all tests successfully."),
                "the recipe's own last word");

        Set<String> made = madeSince(before, project);
        keep("test.made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles("test"), made, "the files the run made");

        String report = "src/ontology/reports/loim-edit.owl-obo-report.tsv";
        assertEquals(rows(recorded("test/files/" + report)), rows(read(new File(project,
                report))), "the report: its header, and its findings in any order");
        String verdict = "src/ontology/reports/validate_profile_owl2dl_loim.owl.txt";
        assertEquals(recorded("test/files/" + verdict).trim(),
                read(new File(project, verdict)).trim(), "the profile's verdict");

        for (String built : new String[] {"src/ontology/loim-full.owl",
            "src/ontology/loim.owl", "src/ontology/tmp/loim-preprocess.owl",
            "src/ontology/tmp/merged-loim-edit.ofn", "src/ontology/tmp/validate.ofn",
            "src/ontology/imports/ro_import.owl", "src/ontology/imports/iao_import.owl",
            "src/ontology/imports/bfo_import.owl"}) {
            assertSameOntology(built, theirs("test", built), new File(project, built));
        }
    }

    /**
     * ODK's release as it is asked for by default: the imports fetched and built, the tests,
     * ODK's tables, the products in OWL and in OBO, copied to the top of the project.
     */
    @Test
    void theFullReleaseRunsHereAsItRanInOdksImage() throws Exception {
        theProject();
        allOfTheSameAge(project);
        Map<String, String> before = hashes(project);

        assertNull(OdkEngine.whyNot(edit, "prepare_release", commandLine),
                "asked beforehand, it can");
        OdkEngine.Outcome outcome = OdkEngine.run(edit, "prepare_release", commandLine, null);

        List<String> ours = lines(String.join("\n", outcome.getTranscript()));
        keep("prepare_release.transcript.txt", ours);
        assertTrue(outcome.isOk(), outcome.getFailure() + "\n" + String.join("\n", ours));
        List<String> theirs = lines(recorded("prepare_release/transcript.txt"));
        assertEquals(String.join("\n", saidByMakeHere(theirs, RECORDED_UPSTREAM)),
                String.join("\n", saidByMakeHere(ours, here())),
                "every recipe line make said, in the order it ran them");
        assertEquals(starting("PASS Rule ", theirs), starting("PASS Rule ", ours),
                "what ROBOT said of each check");

        Set<String> made = madeSince(before, project);
        keep("prepare_release.made.txt", new ArrayList<String>(made));
        assertEquals(recordedFiles("prepare_release"), made, "the files the release made");

        for (String table : new String[] {"basic-report", "class-count-by-prefix", "edges",
            "obsoletes", "synonyms", "xrefs", "loim-edit.owl-obo-report"}) {
            String path = "src/ontology/reports/" + table + ".tsv";
            String here = read(new File(project, path));
            assertEquals(rows(recorded("prepare_release/files/" + path)), rows(here),
                    table + ": its header, and its rows in any order");
            assertFalse(here.contains("\r"), table + ": lines end as in ODK's table");
        }
        String verdict = "src/ontology/reports/validate_profile_owl2dl_loim.owl.txt";
        assertEquals(recorded("prepare_release/files/" + verdict).trim(),
                read(new File(project, verdict)).trim(), "the profile's verdict");

        List<Executable> products = new ArrayList<Executable>();
        for (final String product : new String[] {"loim.owl", "loim-base.owl",
            "loim-full.owl", "loim.obo", "loim-base.obo", "loim-full.obo",
            "src/ontology/tmp/validate.ofn"}) {
            final File theirsFile = theirs("prepare_release", product);
            products.add(new Executable() {
                @Override
                public void execute() throws Throwable {
                    assertSameProduct(product, theirsFile, new File(project, product));
                }
            });
        }
        assertAll("the release's products, each held against ODK's", products);
    }
}
