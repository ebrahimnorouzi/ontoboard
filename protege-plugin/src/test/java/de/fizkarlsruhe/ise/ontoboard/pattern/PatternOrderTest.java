package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The order the library is listed in - the "separated by the source" part of the request. */
class PatternOrderTest {

    /** By publisher, then by name inside each publisher. */
    @Test
    void publisherFirstThenName() {
        List<DesignPattern> sorted = PatternOrder.sorted(PatternLibrary.all(),
                PatternOrder.BY_PUBLISHER);

        String publisher = "";
        String name = "";
        List<String> seen = new ArrayList<String>();
        for (DesignPattern pattern : sorted) {
            if (!pattern.getPublisher().equals(publisher)) {
                assertTrue(!seen.contains(pattern.getPublisher()),
                        pattern.getPublisher() + " appears in two blocks");
                seen.add(pattern.getPublisher());
                publisher = pattern.getPublisher();
                name = "";
            }
            assertTrue(name.compareToIgnoreCase(pattern.getName()) <= 0,
                    "out of order inside " + publisher + ": " + name + " then "
                            + pattern.getName());
            name = pattern.getName();
        }
        assertEquals(162, sorted.size());
    }

    /**
     * Every bundled pattern has a publisher (1.135.1). The airline patterns had none because their
     * only IRI was an absolute path from the machine the harvest ran on, as Ethnicgroup's had until
     * its file was given back the base it misspelt as {@code xmlns:base}; theirs was given back in
     * 1.135.1.
     */
    @Test
    void noBundledPatternIsWithoutAPublisher() {
        for (DesignPattern pattern : PatternLibrary.all()) {
            assertFalse(PatternIndex.UNKNOWN_PUBLISHER.equals(pattern.getPublisher()),
                    pattern.getId() + " has no publisher");
        }
    }

    /**
     * A pattern with no publisher comes last: a contributed one can have none. A bucket named for
     * an absence should not sit between two real ones.
     */
    @Test
    void theUnknownPublisherSortsLast() throws Exception {
        List<DesignPattern> patterns = new java.util.ArrayList<DesignPattern>(PatternLibrary.all());
        DesignPattern unknown = PatternIndex.read(new java.io.StringReader(
                "nobody\tNobody's pattern\todp\t" + PatternIndex.UNKNOWN_PUBLISHER
                        + "\tstructural\tgeneral\t\t\t\t\n")).get(0);
        patterns.add(0, unknown);
        List<DesignPattern> sorted = PatternOrder.sorted(patterns, PatternOrder.BY_PUBLISHER);
        assertEquals(unknown, sorted.get(sorted.size() - 1));
    }

    /** By name ignores case and never depends on index order. */
    @Test
    void byNameIsStable() {
        List<DesignPattern> once = PatternOrder.sorted(PatternLibrary.all(), PatternOrder.BY_NAME);
        List<DesignPattern> twice = PatternOrder.sorted(once, PatternOrder.BY_NAME);

        for (int at = 0; at < once.size(); at++) {
            assertEquals(once.get(at).getId(), twice.get(at).getId());
        }
        String previous = "";
        for (DesignPattern pattern : once) {
            assertTrue(previous.compareToIgnoreCase(pattern.getName()) <= 0);
            previous = pattern.getName();
        }
    }

    /** An ordering nobody asked for is a name sort, not an exception. */
    @Test
    void anUnknownOrderingFallsBackToName() {
        List<DesignPattern> byName = PatternOrder.sorted(PatternLibrary.all(),
                PatternOrder.BY_NAME);
        List<DesignPattern> nonsense = PatternOrder.sorted(PatternLibrary.all(), "By nothing");

        assertEquals(byName.get(0).getId(), nonsense.get(0).getId());
    }

    /** Sorting does not modify what it was given. */
    @Test
    void theInputIsNotTouched() {
        List<DesignPattern> all = PatternLibrary.all();
        String firstBefore = all.get(0).getId();

        PatternOrder.sorted(all, PatternOrder.BY_PUBLISHER);

        assertEquals(firstBefore, PatternLibrary.all().get(0).getId());
    }
}
