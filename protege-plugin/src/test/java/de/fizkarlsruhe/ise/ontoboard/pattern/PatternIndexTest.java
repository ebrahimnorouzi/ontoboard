package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The index is generated from the pattern directories, so it has to still match them.
 *
 * <p>A stale index is the failure mode that would not be noticed: a pattern added to the
 * repository and left out of the index simply never appears in the library, and nothing
 * complains. So the whole index is rebuilt here and compared.
 */
class PatternIndexTest {

    /** The pattern directories, which sit beside the module rather than inside it. */
    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    /**
     * Regenerating the index produces the file that is shipped.
     *
     * <p>If this fails, the fix is to regenerate: the message prints what the index should
     * contain. It is not a formatting check - it loads all 123 ontologies - so it is the
     * slowest test here and the only one that can catch a pattern going missing.
     */
    @Test
    void theShippedIndexMatchesTheDirectories() throws Exception {
        File patterns = patternsDirectory();
        assertTrue(patterns.isDirectory(), "no patterns directory at " + patterns);

        String rebuilt = PatternIndex.buildFrom(patterns);
        String shipped = new String(Files.readAllBytes(new File(patterns, "index.tsv").toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");

        if (!shipped.equals(rebuilt)) {
            assertEquals(firstDifference(shipped, rebuilt), "",
                    "patterns/index.tsv is stale. Regenerate it with PatternIndex.buildFrom.");
        }
        assertEquals(shipped, rebuilt);
    }

    /** The library loads what the index describes, out of the built classpath. */
    @Test
    void everyIndexedPatternIsInTheBuild() throws Exception {
        PatternLibrary.forget();
        List<DesignPattern> all = PatternLibrary.all();
        assertFalse(all.isEmpty(), "the index resource did not load from the classpath");

        List<String> missing = new ArrayList<String>();
        for (DesignPattern pattern : all) {
            if (PatternLibrary.class.getClassLoader()
                    .getResourceAsStream(pattern.getResourcePath()) == null) {
                missing.add(pattern.getResourcePath());
            }
        }
        assertEquals("[]", missing.toString(), "indexed but not packaged");
    }

    /** Every directory on disk is in the index - the direction a stale index fails in. */
    @Test
    void everyPatternOnDiskIsIndexed() throws Exception {
        File patterns = patternsDirectory();
        List<String> onDisk = new ArrayList<String>();
        File[] found = patterns.listFiles();
        assertNotNull(found);
        for (File candidate : found) {
            if (candidate.isDirectory() && new File(candidate, "pattern.owl").isFile()) {
                onDisk.add(candidate.getName());
            }
        }

        List<String> indexed = new ArrayList<String>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            indexed.add(pattern.getId());
        }

        List<String> notIndexed = new ArrayList<String>(onDisk);
        notIndexed.removeAll(indexed);
        assertEquals("[]", notIndexed.toString(), "on disk but not in the index");
        assertEquals(onDisk.size(), indexed.size());
    }

    // ---------- the format ----------

    /** A row round-trips, including an empty trailing cell. */
    @Test
    void aRowParsesIntoItsColumns() throws Exception {
        String index = "# a comment\n\n"
                + "componency\tComponency Pattern\todp\tontologydesignpatterns.org\t"
                + "structural\tgeneral\t\tTo represent parts.\tWhat are the components?\n";

        List<DesignPattern> read = PatternIndex.read(new StringReader(index));

        assertEquals(1, read.size());
        DesignPattern one = read.get(0);
        assertEquals("componency", one.getId());
        assertEquals("Componency Pattern", one.getName());
        assertEquals("odp", one.getCollection());
        assertEquals("ontologydesignpatterns.org", one.getPublisher());
        assertEquals("structural", one.getCategory());
        assertEquals("general", one.getDomain());
        assertEquals("", one.getSameAs());
        assertFalse(one.isDuplicate());
        assertEquals("To represent parts.", one.getDescription());
        assertEquals("What are the components?", one.getCompetencyQuestions());
    }

    /** A short row is skipped rather than throwing or producing a half-built pattern. */
    @Test
    void aTruncatedRowIsSkipped() throws Exception {
        assertTrue(PatternIndex.read(new StringReader("a\tb\tc\n")).isEmpty());
    }

    // ---------- the publisher ----------

    /** The publisher is the host, without the www. */
    @Test
    void thePublisherIsTheHost() {
        assertEquals("ontologydesignpatterns.org",
                PatternIndex.publisherOf("http://www.ontologydesignpatterns.org/cp/owl/x.owl"));
        assertEquals("w3id.org", PatternIndex.publisherOf("https://w3id.org/MON/affordance.owl"));
        assertEquals("daselab.org", PatternIndex.publisherOf("http://daselab.org/WinstonPartWhole"));
    }

    /**
     * A path from somebody's disk names no publisher, and says so.
     *
     * <p>Three patterns carry an absolute {@code file:} IRI from the machine the harvest ran on,
     * pointing into a directory that is no longer in the repository. Deriving a publisher from
     * that would invent one.
     */
    @Test
    void aFileIriHasNoPublisher() {
        assertEquals(PatternIndex.UNKNOWN_PUBLISHER,
                PatternIndex.publisherOf("file:///C:/Users/someone/patterns/Airline.owl"));
        assertEquals(PatternIndex.UNKNOWN_PUBLISHER, PatternIndex.publisherOf(""));
        assertEquals(PatternIndex.UNKNOWN_PUBLISHER, PatternIndex.publisherOf(null));
        assertEquals(PatternIndex.UNKNOWN_PUBLISHER, PatternIndex.publisherOf("not a uri at all"));
    }

    // ---------- the description ----------

    /** A wiki section heading is not a description. */
    @Test
    void theWikiHeadingsAreStripped() {
        assertEquals("", PatternIndex.descriptionOf(metadata("description",
                "Diagram (this article has no graphical representation)")));
        assertEquals("To model substitutes.", PatternIndex.descriptionOf(metadata("description",
                "Diagram | Intent: | To model substitutes.")));
        assertEquals("Plain prose survives.",
                PatternIndex.descriptionOf(metadata("description", "Plain prose survives.")));
    }

    /** With nothing left, the competency questions stand in; otherwise nothing does. */
    @Test
    void anEmptyDescriptionFallsBackRatherThanLying() {
        Map<String, Object> rescued = metadata("description", "Diagram");
        rescued.put("competency_questions", "What are the parts?");
        assertEquals("What are the parts?", PatternIndex.descriptionOf(rescued));

        assertEquals("", PatternIndex.descriptionOf(metadata("description", "Diagram")));
    }

    /** A cell cannot contain a tab or a newline, because the format is tab separated. */
    @Test
    void aCellIsFlattened() {
        assertEquals("a b c", PatternIndex.oneLine("a\tb\nc", "x"));
        assertEquals("a b", PatternIndex.oneLine("a      b", "x"));
        assertEquals("x", PatternIndex.oneLine("   ", "x"));
    }

    // ---------- duplicates ----------

    /** Two patterns with the same signature are the same pattern; the first id wins. */
    @Test
    void anIdenticalSignatureIsADuplicate() {
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<String, Map<String, Object>>();
        metadata.put("zebra", metadata("pattern_iri", "http://example.org/z"));
        metadata.put("alpha", metadata("pattern_iri", "http://example.org/a"));
        Map<String, String> signatures = new LinkedHashMap<String, String>();
        signatures.put("zebra", "[http://x/A, http://x/B]");
        signatures.put("alpha", "[http://x/A, http://x/B]");

        Map<String, String> duplicates = PatternIndex.duplicatesIn(metadata, signatures);

        assertEquals("alpha", duplicates.get("zebra"));
        assertNull(duplicates.get("alpha"));
    }

    /** The same declared IRI is also a duplicate, even when the files differ. */
    @Test
    void theSameDeclaredIriIsADuplicate() {
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<String, Map<String, Object>>();
        metadata.put("agentrole", metadata("pattern_iri", "http://odp.org/agentrole.owl"));
        metadata.put("agent-role", metadata("pattern_iri", "http://odp.org/agentrole.owl"));
        Map<String, String> signatures = new LinkedHashMap<String, String>();
        signatures.put("agentrole", "[http://x/A]");
        signatures.put("agent-role", "[http://x/A, http://x/B]");

        Map<String, String> duplicates = PatternIndex.duplicatesIn(metadata, signatures);

        assertEquals("agent-role", duplicates.get("agentrole"));
    }

    /**
     * Two machine-local paths are not evidence of anything.
     *
     * <p>Grouping on a {@code file:} IRI would relate patterns to each other purely because the
     * same harvest produced both, which is a relation about the harvest and not the patterns.
     */
    @Test
    void aFileIriIsNotEvidenceOfDuplication() {
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<String, Map<String, Object>>();
        metadata.put("airline", metadata("pattern_iri", "file:///C:/x/Airline.owl"));
        metadata.put("ethnicgroup", metadata("pattern_iri", "file:///C:/x/Airline.owl"));
        Map<String, String> signatures = new LinkedHashMap<String, String>();
        signatures.put("airline", "[http://x/A]");
        signatures.put("ethnicgroup", "[http://x/B]");

        assertTrue(PatternIndex.duplicatesIn(metadata, signatures).isEmpty());
    }

    private static Map<String, Object> metadata(String key, String value) {
        Map<String, Object> one = new LinkedHashMap<String, Object>();
        one.put(key, value);
        return one;
    }

    /** The first differing line, which is what a developer needs when the index is stale. */
    private static String firstDifference(String shipped, String rebuilt) {
        String[] was = shipped.split("\n", -1);
        String[] now = rebuilt.split("\n", -1);
        for (int at = 0; at < Math.max(was.length, now.length); at++) {
            String left = at < was.length ? was[at] : "(no line)";
            String right = at < now.length ? now[at] : "(no line)";
            if (!left.equals(right)) {
                return "line " + (at + 1) + "\n  shipped : " + left + "\n  rebuilt : " + right;
            }
        }
        return "";
    }
}
