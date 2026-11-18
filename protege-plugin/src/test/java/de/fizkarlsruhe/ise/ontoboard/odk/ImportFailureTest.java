package de.fizkarlsruhe.ise.ontoboard.odk;

import static de.fizkarlsruhe.ise.ontoboard.odk.ImportFailure.Kind.CONTENT;
import static de.fizkarlsruhe.ise.ontoboard.odk.ImportFailure.Kind.NETWORK;
import static de.fizkarlsruhe.ise.ontoboard.odk.ImportFailure.Kind.SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A refresh that stopped on fetching an import's source: which import, from where, and whether
 * the network, the server or what came was why - read from what make and the fetch said, in
 * ODK's image and in OntoBoard's engine alike. The transcripts are real runs of both;
 * resources/import-failure/README.txt says how they were made.
 */
class ImportFailureTest {

    /** What the sentence says of each kind, in plain words. */
    private static final Map<ImportFailure.Kind, String> WHY =
            new EnumMap<ImportFailure.Kind, String>(ImportFailure.Kind.class);

    static {
        WHY.put(NETWORK, "did not answer");
        WHY.put(SERVER, "answered, but not with the ontology");
        WHY.put(CONTENT, "is not an ontology ROBOT can read");
    }

    @TempDir
    File top;

    static Stream<Arguments> recorded() {
        return Stream.of(
                Arguments.of("engine-refused", "ro", "http://127.0.0.1:9/obo/ro.owl", NETWORK),
                Arguments.of("image-refused", "ro", "http://127.0.0.1:9/obo/ro.owl", NETWORK),
                Arguments.of("engine-noname", "ro", "http://no-such-host.invalid/obo/ro.owl",
                        NETWORK),
                Arguments.of("image-noname", "ro", "http://no-such-host.invalid/obo/ro.owl",
                        NETWORK),
                Arguments.of("engine-all", "ro", "http://127.0.0.1:9/obo/ro.owl", NETWORK),
                Arguments.of("image-all", "ro", "http://127.0.0.1:9/obo/ro.owl", NETWORK),
                Arguments.of("engine-notfound", "iao", "http://127.0.0.1:8765/obo/iao.owl",
                        SERVER),
                // ODK's curl saves the server's page as the file: ROBOT cannot read what came.
                Arguments.of("image-notfound", "iao", "http://127.0.0.1:8765/obo/iao.owl",
                        CONTENT),
                Arguments.of("image-page", "ro", "http://127.0.0.1:8765/obo/ro.owl", CONTENT),
                Arguments.of("engine-pato-refused", "pato", "http://127.0.0.1:9/pato-base.owl",
                        NETWORK),
                Arguments.of("image-pato-refused", "pato", "http://127.0.0.1:9/pato-base.owl",
                        NETWORK),
                Arguments.of("engine-pato-notfound", "pato",
                        "http://127.0.0.1:8765/missing.owl", SERVER),
                Arguments.of("image-pato-notfound", "pato",
                        "http://127.0.0.1:8765/missing.owl", SERVER),
                Arguments.of("image-pato-page", "pato", "http://127.0.0.1:8765/page.owl",
                        CONTENT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recorded")
    void theImportItsAddressAndWhyAreFound(String run, String id, String address,
            ImportFailure.Kind kind) throws IOException {
        ImportFailure failure = ImportFailure.of(transcript(run));

        assertNotNull(failure, run);
        assertEquals(id, failure.importId());
        assertEquals(address, failure.address());
        assertEquals(kind, failure.kind());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recorded")
    void theSentenceNamesThemAndSaysWhatWasSaid(String run, String id, String address,
            ImportFailure.Kind kind) throws IOException {
        ImportFailure failure = ImportFailure.of(transcript(run));
        String sentence = failure.sentence();

        assertTrue(sentence.contains("the import " + id + " "), sentence);
        assertTrue(sentence.contains(address), sentence);
        assertTrue(sentence.contains(WHY.get(kind)), sentence);
        assertTrue(sentence.contains(failure.said()), sentence);
    }

    /** The fetch's own last word: curl's after its tries, ROBOT's first line. */
    @Test
    void whatWasSaidIsTheFetchsOwnWord() throws IOException {
        assertEquals("curl: (7) nothing answers at 127.0.0.1:9 (http://127.0.0.1:9/obo/ro.owl)",
                ImportFailure.of(transcript("engine-refused")).said());
        // After curl's progress meter, on its line, and after four tries that said the same.
        assertEquals("curl: (6) Could not resolve host: no-such-host.invalid",
                ImportFailure.of(transcript("image-noname")).said());
        assertEquals("INVALID ONTOLOGY FILE ERROR Could not load a valid ontology from file: "
                + "iao-download.owl", ImportFailure.of(transcript("image-notfound")).said());
        assertEquals("org.semanticweb.owlapi.io.UnparsableOntologyException: Problem parsing "
                + "http://127.0.0.1:8765/page.owl",
                ImportFailure.of(transcript("image-pato-page")).said());
    }

    /**
     * OWL API 4.5.9 reads a web page as an empty ontology: the run gets past the fetch and
     * stops at the module, which is no fetch that failed.
     */
    @Test
    void aModuleThatCouldNotBeBuiltIsNoFailedFetch() throws IOException {
        assertNull(ImportFailure.of(transcript("engine-page")));
        assertNull(ImportFailure.of(transcript("engine-pato-page")));
    }

    @Test
    void aRunThatStoppedForSomethingElseHasNone() {
        assertNull(ImportFailure.of(Collections.<String>emptyList()));
        assertNull(ImportFailure.of(Arrays.asList(
                "robot --catalog catalog-v001.xml reason --input obim-edit.owl",
                "make: *** [Makefile:240: reason_test] Error 1")));
        assertNull(ImportFailure.of(Arrays.asList(
                "curl -L http://127.0.0.1:9/obo/ro.owl --create-dirs -o tmp/ro-download.owl",
                "robot --catalog catalog-v001.xml convert -i tmp/ro-download.owl -o "
                        + "tmp/mirror-ro.owl",
                "Mirrors different, updating.")), "a run that went through");
        assertNull(ImportFailure.of(Arrays.asList(
                "curl: (6) Could not resolve host: purl.obolibrary.org",
                "make: *** [Makefile:484: mirror-iao] Error 6")),
                "without the line that fetched, nothing names the address");
        assertNull(ImportFailure.of(Arrays.asList(
                "echo \"ERROR: You have configured your default mirror type to be custom; this "
                        + "behavior needs to be overwritten in obim.Makefile!\" && false",
                "ERROR: You have configured your default mirror type to be custom; this "
                        + "behavior needs to be overwritten in obim.Makefile!",
                "make: *** [Makefile:891: mirror/x.owl] Error 1")), "ODK's own custom mirror");
        assertNull(ImportFailure.of(Arrays.asList(
                "curl -L file:///nowhere/x.owl --create-dirs -o tmp/x-download.owl",
                "curl: (37) there is no file at file:///nowhere/x.owl",
                "make[1]: *** [Makefile:498: mirror-x] Error 37")),
                "curl's word on the line itself: neither the network, the server nor what came");
    }

    /** A project's own rule that fetches another way: an earlier import's fetch is not it. */
    @Test
    void anotherImportsFetchIsNotTakenForTheOneThatFailed() {
        assertNull(ImportFailure.of(Arrays.asList(
                "curl -L http://purl.obolibrary.org/obo/ro.owl --create-dirs -o "
                        + "tmp/ro-download.owl --retry 4 --max-time 200 && \\",
                "robot --catalog catalog-v001.xml convert -i tmp/ro-download.owl -o "
                        + "tmp/mirror-ro.owl",
                "wget http://example.org/x.owl -O tmp/mirror-x.owl",
                "wget: unable to resolve host address 'example.org'",
                "make[1]: *** [obim.Makefile:12: mirror-x] Error 4")));
    }

    /** The engine says a recipe that goes over several lines as one entry of its transcript. */
    @Test
    void aRecipeSaidAsOneEntryIsReadLineByLine() {
        ImportFailure failure = ImportFailure.of(Arrays.asList(
                "curl -L http://127.0.0.1:9/obo/ro.owl --create-dirs -o tmp/ro-download.owl "
                        + "--retry 4 --max-time 200 && \\\nrobot --catalog catalog-v001.xml "
                        + "convert -i tmp/ro-download.owl -o tmp/mirror-ro.owl",
                "curl: (7) nothing answers at 127.0.0.1:9 (http://127.0.0.1:9/obo/ro.owl)",
                "make[1]: *** [Makefile:498: mirror-ro] Error 7"));

        assertEquals("ro", failure.importId());
        assertEquals("http://127.0.0.1:9/obo/ro.owl", failure.address());
        assertEquals(NETWORK, failure.kind());
    }

    /** ODK's other mirror rules: a base, gzipped, and ROBOT's remove -I that makes a base. */
    @Test
    void eachOfOdksWaysToFetchIsRead() {
        assertEquals("http://purl.obolibrary.org/obo/uberon/uberon-base.owl.gz",
                ImportFailure.of(Arrays.asList(
                        "curl -L http://purl.obolibrary.org/obo/uberon/uberon-base.owl.gz "
                                + "--create-dirs -o mirror/uberon-base.owl.gz --retry 4 "
                                + "--max-time 200 && \\",
                        "robot convert -i mirror/uberon-base.owl.gz -o tmp/mirror-uberon.owl",
                        "curl: (28) Connection timed out after 30001 milliseconds",
                        "make[1]: *** [Makefile:510: mirror-uberon] Error 28")).address());
        ImportFailure removed = ImportFailure.of(Arrays.asList(
                "robot --catalog catalog-v001.xml remove -I http://example.org/x.owl "
                        + "--base-iri http://purl.obolibrary.org/obo/X  --axioms external "
                        + "--preserve-structure false --trim false -o tmp/mirror-x.owl",
                "org.semanticweb.owlapi.io.OWLOntologyCreationIOException: "
                        + "java.net.UnknownHostException: example.org",
                "make[1]: *** [Makefile:530: mirror-x] Error 1"));
        assertEquals("x", removed.importId());
        assertEquals("http://example.org/x.owl", removed.address());
        assertEquals(NETWORK, removed.kind());
        assertEquals(SERVER, ImportFailure.of(Arrays.asList(
                "robot convert -I http://example.org/x.owl -o tmp/mirror-x.owl",
                "java.io.IOException: Server returned HTTP response code: 503 for URL: "
                        + "http://example.org/x.owl",
                "make[1]: *** [Makefile:530: mirror-x] Error 1")).kind());
    }

    // ------------------------------------------------------------------ the copy it has

    @Test
    void theCopyOfTheSourceIsOfferedWithItsDate() throws IOException {
        File ontology = new File(top, "src/ontology");
        File copy = new File(ontology, "mirror/ro.owl");
        Files.createDirectories(copy.getParentFile().toPath());
        Files.write(copy.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(copy.setLastModified(Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()));
        ImportFailure failure = ImportFailure.of(transcript("image-refused"));

        assertEquals(copy, failure.copy(ontology));
        assertEquals("Build the module from the copy you have (mirror/ro.owl, from 2026-11-10)",
                failure.offer(ontology));
    }

    /** ODK's refresh-imports empties mirror/ before it fetches: nothing is left to offer. */
    @Test
    void withoutACopyNothingIsOffered() throws IOException {
        File ontology = new File(top, "src/ontology");
        Files.createDirectories(new File(ontology, "mirror").toPath());
        ImportFailure failure = ImportFailure.of(transcript("image-all"));

        assertNull(failure.copy(ontology));
        assertNull(failure.offer(ontology));
    }

    // ------------------------------------------------------------------ said by make's explanation

    /** Build and Refresh imports say it through the explanation of a run that failed. */
    @Test
    void theExplanationOfTheRunSaysIt() throws IOException {
        List<String> lines = transcript("image-refused");
        ProcessRunner.Outcome failed = new ProcessRunner.Outcome(2, lines, false);
        String sentence = ImportFailure.of(lines).sentence();

        assertEquals(sentence, MakeRun.explain("refresh-ro", failed));
        String build = MakeRun.explain("test", failed);
        assertTrue(build.startsWith(sentence), build);
        assertTrue(build.contains("test_fast"), "the fast target builds from what there is: "
                + build);
    }

    /** As ProcessRunner reads a program's output: a carriage return ends a line too. */
    private static List<String> transcript(String run) throws IOException {
        List<String> lines = new ArrayList<String>();
        try (InputStream in = ImportFailureTest.class.getResourceAsStream(
                "/import-failure/" + run + ".txt")) {
            assertNotNull(in, run);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
