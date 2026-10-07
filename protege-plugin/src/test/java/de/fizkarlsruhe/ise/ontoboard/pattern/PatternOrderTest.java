package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(159, sorted.size());
    }

    /**
     * The patterns with no publisher come last.
     *
     * <p>Three of them, because their only IRI is an absolute path from the machine the harvest
     * ran on. A bucket named for an absence should not sit between two real ones.
     */
    @Test
    void theUnknownPublisherSortsLast() {
        List<DesignPattern> sorted = PatternOrder.sorted(PatternLibrary.all(),
                PatternOrder.BY_PUBLISHER);

        int firstUnknown = -1;
        for (int at = 0; at < sorted.size(); at++) {
            boolean unknown = PatternIndex.UNKNOWN_PUBLISHER.equals(sorted.get(at).getPublisher());
            if (unknown && firstUnknown < 0) {
                firstUnknown = at;
            }
            if (!unknown) {
                assertTrue(firstUnknown < 0,
                        sorted.get(at).getId() + " is after an unknown-publisher pattern");
            }
        }
        assertTrue(firstUnknown > 0, "there are three of them");
        assertEquals(sorted.size(), firstUnknown + 3);
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
