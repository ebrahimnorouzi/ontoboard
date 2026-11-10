package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The library searched by what the terms of a pattern are called.
 *
 * <p>Asked for after a rehearsal of the study: a search for {@code hardness} said "Nothing
 * matches." while a pattern holding {@code hardness} and {@code indentation hardness} sat in the
 * library, and the task needed exactly that pattern.
 */
class PatternLabelsTest {

    /** The pattern directories, which sit beside the module rather than inside it. */
    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    /**
     * Regenerating the labels table produces the file that is shipped.
     *
     * <p>Exactly, unlike the index: imports are not followed, so the network cannot change a row.
     * Regenerate with {@code mvn -o test -Dtest=PatternLabelsTest -Dontoboard.writeLabels=true}.
     */
    @Test
    void theShippedLabelsMatchThePatterns() throws Exception {
        String rebuilt = PatternIndex.labelsFrom(patternsDirectory());
        File shipped = new File(patternsDirectory(), "labels.tsv");
        if (Boolean.getBoolean("ontoboard.writeLabels")) {
            Files.write(shipped.toPath(), rebuilt.getBytes(StandardCharsets.UTF_8));
            return;
        }
        assertTrue(shipped.isFile(), "patterns/labels.tsv is missing - regenerate it");
        assertEquals(new String(Files.readAllBytes(shipped.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n"), rebuilt,
                "patterns/labels.tsv no longer matches the patterns - regenerate it");
    }

    /** Found by its terms, and only after what matched by name or description. */
    @Test
    void aPatternIsFoundByWhatItsTermsAreCalled() {
        List<DesignPattern> all = PatternLibrary.all();
        DesignPattern measurement = PatternLibrary.find("pmdco-measurement-and-setpoint");
        assertFalse(PatternLibrary.matching(all, "hardness").contains(measurement),
                "its name and description do not say hardness, so this tests the terms");
        List<DesignPattern> byTerms = PatternLibrary.matchingByTerms(all, "hardness");
        assertTrue(byTerms.contains(measurement), String.valueOf(byTerms));
        assertTrue(PatternLibrary.termsMatching(measurement, "indentation hardness")
                .contains("indentation hardness"));
        assertTrue(PatternLibrary.matchingByTerms(all, "indentation hardness")
                .contains(measurement), "every word, as the other search");
    }

    /** What already matched by name is not listed a second time by its terms. */
    @Test
    void whatMatchedAlreadyIsNotListedTwice() {
        List<DesignPattern> all = PatternLibrary.all();
        for (DesignPattern pattern : PatternLibrary.matching(all, "measurement")) {
            assertFalse(PatternLibrary.matchingByTerms(all, "measurement").contains(pattern),
                    pattern.getId());
        }
        assertTrue(PatternLibrary.matchingByTerms(all, "").isEmpty());
        assertTrue(PatternLibrary.matchingByTerms(all, null).isEmpty());
    }

    /** One of the user's own patterns is searched by its terms the same way. */
    @Test
    void aPatternOfYourOwnIsSearchedByItsTermsToo(@TempDir File root) throws Exception {
        File file = new File(root, "mine/gauges.ttl");
        assertTrue(file.getParentFile().mkdirs());
        Files.write(file.toPath(), String.join("\n",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
                "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
                "@prefix : <http://example.org/gauges#> .",
                "<http://example.org/gauges> a owl:Ontology .",
                ":Gauge a owl:Class ; rdfs:label \"pressure gauge\"@en .",
                ":Unlabelled a owl:Class .",
                "").getBytes(StandardCharsets.UTF_8));

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root,
                new LinkedHashSet<String>());

        assertEquals(1, scan.getPatterns().size(), scan.getProblems().toString());
        DesignPattern mine = scan.getPatterns().get(0);
        assertEquals("[pressure gauge]", mine.getTermLabels().toString(),
                "only labels the file gives, never an identifier");
        assertTrue(PatternLibrary.matchingByTerms(scan.getPatterns(), "pressure")
                .contains(mine));
    }
}
