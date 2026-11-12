package de.fizkarlsruhe.ise.ontoboard.odk;

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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.util.VersionInfo;

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

    // ------------------------------------------------------------------ the products

    /**
     * ODK's ROBOT writes with OWL API 4.5.29. Protege 5.6.9 has the same one; Protege 5.5.0
     * has 4.5.9, which is also what these tests run on unless told otherwise
     * ({@code -Dowlapi.version=4.5.29}).
     */
    private static boolean onOdksOwlApi() {
        return VersionInfo.getVersionInfo().getVersion().startsWith("4.5.29");
    }

    /**
     * A product of the release against ODK's, as closely as the OWL API this runs on allows.
     *
     * <p>On ODK's own OWL API the file is ODK's character for character, but for the one line
     * in which the OWL API names its own build. On the older one the content is the same and
     * the writing differs where that OWL API writes otherwise: in OBO the header names the
     * Dublin Core properties in full where ODK's declares short names for them; in RDF/XML a
     * plain string carries its datatype - so there the ontologies are compared, not the text.
     */
    private static void assertSameProduct(String name, File theirs, File ours) throws Exception {
        if (onOdksOwlApi()) {
            assertEquals(butForTheLineOfTheOwlApi(read(theirs)),
                    butForTheLineOfTheOwlApi(read(ours)), name + ": ODK's file, character for "
                            + "character but for the line in which the OWL API names its build");
        } else if (name.endsWith(".obo")) {
            assertEquals(String.join("\n", withItsShortNamesInFull(lines(read(theirs)))),
                    String.join("\n", lines(read(ours))), name + ": ODK's file, the short names "
                            + "of its header written in full as the older OWL API writes them");
        } else {
            assertSameOntology(name, theirs, ours);
        }
    }

    private static String butForTheLineOfTheOwlApi(String text) {
        return text.replaceAll("<!-- Generated by the OWL API \\(version [^)]*\\) "
                + "https://github.com/owlcs/owlapi -->", "<!-- Generated by the OWL API -->");
    }

    /**
     * An OBO file's lines without its {@code idspace} lines, and with every header property
     * they give a short name to written by its whole IRI.
     */
    private static List<String> withItsShortNamesInFull(List<String> obo) {
        Map<String, String> spaces = new LinkedHashMap<String, String>();
        for (String line : obo) {
            if (line.startsWith("idspace: ")) {
                String[] parts = line.split(" ");
                spaces.put(parts[1] + ":", parts[2]);
            }
        }
        List<String> inFull = new ArrayList<String>();
        for (String line : obo) {
            if (line.startsWith("idspace: ")) {
                continue;
            }
            String written = line;
            for (Map.Entry<String, String> space : spaces.entrySet()) {
                if (line.startsWith("property_value: " + space.getKey())) {
                    written = "property_value: " + space.getValue()
                            + line.substring(("property_value: " + space.getKey()).length());
                }
            }
            inFull.add(written);
        }
        return inFull;
    }

    // ------------------------------------------------------------------ what is compared

    /**
     * The lines of a transcript that are make's own: each recipe line as it says it before it
     * runs it, and its word on entering and leaving the folder, with the folder left out. What
     * the programs print is not among them.
     */
    private static List<String> saidByMake(List<String> transcript) {
        List<String> said = new ArrayList<String>();
        for (String line : transcript) {
            if (line.matches("make\\[\\d+\\]: (Entering|Leaving) directory .*")) {
                said.add(line.substring(0, line.indexOf("directory") + "directory".length()));
            } else if (line.startsWith("robot ") || line.startsWith("mkdir ")
                    || line.startsWith("if [") || line.equals("fi") || line.startsWith("echo ")
                    || line.startsWith("make ") || line.startsWith("rsync ")
                    || line.startsWith("rm ") || line.startsWith("\t")) {
                said.add(line);
            }
        }
        return said;
    }

    private static List<String> starting(String with, List<String> transcript) {
        List<String> found = new ArrayList<String>();
        for (String line : transcript) {
            if (line.startsWith(with)) {
                found.add(line);
            }
        }
        return found;
    }

    /** A table's header, then its rows sorted: the order of a query's rows is the store's. */
    private static List<String> rows(String table) {
        List<String> all = lines(table);
        List<String> rows = new ArrayList<String>(all.subList(1, all.size()));
        Collections.sort(rows);
        rows.add(0, all.get(0));
        return rows;
    }

    private static void assertSameOntology(String name, File theirs, File ours) throws Exception {
        OWLOntology recorded = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(theirs);
        OWLOntology here = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(ours);
        assertEquals(recorded.getOntologyID(), here.getOntologyID(), name + ": its names");
        assertEquals(sorted(recorded.getAnnotations()), sorted(here.getAnnotations()),
                name + ": what the ontology says of itself");
        assertEquals(sorted(recorded.getAxioms(Imports.EXCLUDED)),
                sorted(here.getAxioms(Imports.EXCLUDED)), name + ": its axioms");
    }

    private static String sorted(Set<?> things) {
        Set<String> sorted = new TreeSet<String>();
        for (Object thing : things) {
            sorted.add(thing.toString());
        }
        return String.join("\n", sorted);
    }

    // ------------------------------------------------------------------ the recording, the disk

    private static String text(String recorded) {
        return new String(Goldens.equivalence(recorded), StandardCharsets.UTF_8);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<String>();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            lines.add(line);
        }
        return lines;
    }

    /** The files the image's run made or changed, from the project's root. */
    private static Set<String> recordedFiles(String target) {
        File files = new File(Goldens.root(), "equivalence/egg/" + target + "/files");
        return new TreeSet<String>(Goldens.tree(files).keySet());
    }

    /** make decides by the age of files: the recording gave every file of the project one age. */
    private static void allOfTheSameAge(File folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder.toPath())) {
            for (Path path : all.collect(Collectors.toList())) {
                path.toFile().setLastModified(1_794_398_400_000L);
            }
        }
    }

    private static Map<String, String> hashes(File folder) throws Exception {
        Map<String, String> hashes = new TreeMap<String, String>();
        Path root = folder.toPath();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path path : all.collect(Collectors.toList())) {
                if (Files.isRegularFile(path)) {
                    StringBuilder hex = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path))) {
                        hex.append(String.format("%02x", b & 0xff));
                    }
                    hashes.put(root.relativize(path).toString().replace('\\', '/'),
                            hex.toString());
                }
            }
        }
        return hashes;
    }

    /** The files that are new or changed since a look at the folder. */
    private static Set<String> madeSince(Map<String, String> before, File folder)
            throws Exception {
        Set<String> made = new TreeSet<String>();
        for (Map.Entry<String, String> now : hashes(folder).entrySet()) {
            if (!now.getValue().equals(before.get(now.getKey()))) {
                made.add(now.getKey());
            }
        }
        return made;
    }

    /** Kept under target/, for a person to read after a run. */
    private static void keep(String name, List<String> lines) throws IOException {
        File folder = new File("target/fallback-equivalence");
        Files.createDirectories(folder.toPath());
        Files.write(new File(folder, name).toPath(),
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
