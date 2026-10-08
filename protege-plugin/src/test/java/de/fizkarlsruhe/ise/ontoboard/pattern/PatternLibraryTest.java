package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

/**
 * Reading the bundled pattern library, and what it says about each pattern.
 *
 * <p>These run against the real 123 patterns rather than fixtures, because the thing worth
 * guarding is a claim about them - that the counts shown are the file's own, that the
 * duplicates are flagged, that nothing in the index is missing from the jar.
 */
class PatternLibraryTest {

    /** The library loads, and is the size the directory is. */
    @Test
    void theLibraryLoads() {
        PatternLibrary.forget();
        List<DesignPattern> all = PatternLibrary.all();

        assertEquals(163, all.size(), "the bundled collection");
        assertNotNull(PatternLibrary.find("componency"));
        assertNull(PatternLibrary.find("no-such-pattern"));
    }

    /**
     * The counts come from the file, not from the metadata that ships beside it.
     *
     * <p>The reason the index carries no counts. {@code affordance} declares two classes in its
     * metadata and has eight; {@code vesselspecies} declares zero and has five.
     */
    @Test
    void theCountsAreTheFilesOwn() throws Exception {
        PatternLibrary.Contents affordance =
                PatternLibrary.contentsOf(PatternLibrary.find("affordance"));
        assertEquals(8, affordance.getClasses().size(),
                "metadata.json says 2; the ontology is what counts");

        PatternLibrary.Contents vessel =
                PatternLibrary.contentsOf(PatternLibrary.find("vesselspecies"));
        assertEquals(5, vessel.getClasses().size(), "metadata.json says 0");
    }

    /** Terms are classes then properties, and that is what would be imported. */
    @Test
    void theTermsAreTheSignature() throws Exception {
        PatternLibrary.Contents contents =
                PatternLibrary.contentsOf(PatternLibrary.find("componency"));

        assertEquals(contents.getClasses().size() + contents.getProperties().size(),
                contents.getTerms().size());
        assertFalse(contents.getTerms().isEmpty());
        for (IRI term : contents.getTerms()) {
            assertNotNull(term);
        }
    }

    /**
     * A pattern's own imports are reported, not resolved.
     *
     * <p>101 of the 123 declare them. Resolving them would make opening a pattern depend on
     * thirteen websites and would pull DUL in behind the ones that import it.
     */
    @Test
    void importsAreReportedRatherThanFollowed() throws Exception {
        PatternLibrary.Contents contents =
                PatternLibrary.contentsOf(PatternLibrary.find("componency"));

        assertFalse(contents.getImports().isEmpty(), "componency imports the annotation schema");
        // Nothing from the imported ontology leaked into the signature: the counts are small
        // and local, not the hundreds an upper ontology would add.
        assertTrue(contents.getClasses().size() < 20,
                "an import was followed: " + contents.describe());
    }

    /** Every pattern in the index opens. */
    @Test
    void allOfThemParse() throws Exception {
        List<String> broken = new ArrayList<String>();
        int classes = 0;
        for (DesignPattern pattern : PatternLibrary.all()) {
            try {
                classes += PatternLibrary.contentsOf(pattern).getClasses().size();
            } catch (IOException unreadable) {
                broken.add(pattern.getId() + ": " + unreadable.getMessage());
            }
        }
        assertEquals("[]", broken.toString());
        assertEquals(1391, classes, "the measured total across the collection");
    }

    /**
     * A pattern whose import is an absolute path opens from the bundle.
     *
     * <p>The two airline patterns declare {@code owl:imports file:/schemas/cpannotationschema
     * .owl}. Read from a file that fails as an unreachable import and is silenced; read from a
     * bare stream it becomes {@code urn:absolute:/schemas/...}, for which no factory exists, and
     * that exception is not a missing import so nothing silences it. In the plugin they are
     * always read from a stream, so without a document IRI these two are the only patterns in
     * the library that will not open - and only in the shipped jar, never in a test that reads
     * the directory.
     */
    @Test
    void aPatternWithAnAbsoluteImportStillOpens() throws Exception {
        PatternLibrary.Contents contents =
                PatternLibrary.contentsOf(PatternLibrary.find("airline"));

        assertEquals(8, contents.getClasses().size());
        assertFalse(contents.getImports().isEmpty(), "the import is still reported");
        assertTrue(PatternLibrary.baseFor(PatternLibrary.find("airline")).toString()
                .startsWith("file:/"), "the base has to be a scheme with a factory");
    }

    // ---------- grouping, which is what was asked for ----------

    /** Grouped by publisher, which is the division that carries information. */
    @Test
    void theyGroupByPublisher() {
        Map<String, List<DesignPattern>> groups = PatternLibrary.byPublisher();

        assertEquals(17, groups.size(),
                "thirteen behind the ODP portal, the three pattern pages harvested in 1.87.0, "
                        + "and basic-formal-ontology.org for the four extracted in 1.96.0");
        assertTrue(groups.containsKey("ontologydesignpatterns.org"));
        int total = 0;
        for (List<DesignPattern> group : groups.values()) {
            total += group.size();
        }
        assertEquals(163, total, "every pattern lands in exactly one group");
    }

    /** And by category, for the other way people look. */
    @Test
    void theyGroupByCategory() {
        Map<String, List<DesignPattern>> groups = PatternLibrary.byCategory();

        assertTrue(groups.containsKey("structural"));
        int total = 0;
        for (List<DesignPattern> group : groups.values()) {
            total += group.size();
        }
        assertEquals(163, total);
    }

    /** Ten patterns are another one under a second name, and say so. */
    @Test
    void duplicatesAreFlagged() {
        int duplicates = 0;
        for (DesignPattern pattern : PatternLibrary.all()) {
            if (pattern.isDuplicate()) {
                duplicates++;
                assertNotNull(PatternLibrary.find(pattern.getSameAs()),
                        pattern.getId() + " points at " + pattern.getSameAs()
                                + ", which is not in the library");
                assertFalse(PatternLibrary.find(pattern.getSameAs()).isDuplicate(),
                        "a duplicate must not point at another duplicate");
            }
        }
        assertEquals(10, duplicates);
        assertEquals("agent-role", PatternLibrary.find("agentrole").getSameAs());
    }

    // ---------- search ----------

    /** Every word has to match, so a second word narrows. */
    @Test
    void searchNarrowsWithEachWord() {
        int justTime = PatternLibrary.matching("time").size();
        int timeAndPart = PatternLibrary.matching("time part").size();

        assertTrue(justTime > 0);
        assertTrue(timeAndPart < justTime, justTime + " then " + timeAndPart);
        assertEquals(163, PatternLibrary.matching("").size());
        assertEquals(163, PatternLibrary.matching(null).size());
    }

    /** Case does not matter, and the id is searched as well as the name. */
    @Test
    void searchIsForgiving() {
        assertFalse(PatternLibrary.matching("COMPONENCY").isEmpty());
        assertFalse(PatternLibrary.matching("agentrole").isEmpty());
    }

    // ---------- copying one out ----------

    /** A pattern can be written to disk, which is how ROBOT gets a file IRI for it. */
    @Test
    void aPatternCanBeCopiedOut(@TempDir File root) throws Exception {
        File mirror = new File(root, "mirror");

        File copied = PatternLibrary.copyTo(PatternLibrary.find("componency"), mirror);

        assertTrue(copied.isFile());
        assertEquals("componency.owl", copied.getName());
        assertTrue(copied.length() > 0);
        assertTrue(mirror.isDirectory(), "the directory is created rather than required");
    }

    /** Asking for something not in the build fails with the path, not with a null. */
    @Test
    void aMissingPatternSaysWhichOne() {
        DesignPattern invented = PatternIndexTestSupport.patternNamed("not-shipped");

        IOException failure = assertThrows(IOException.class,
                () -> PatternLibrary.contentsOf(invented));
        assertTrue(failure.getMessage().contains("not-shipped"), failure.getMessage());
    }
}
