package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The order patterns are listed in, which is the "separated by the source" part of the request.
 *
 * <p>Its own class because the orderings carry a judgement that is worth stating and testing.
 * Publisher first is the default: grouping by the <em>collection</em> would be truthful and
 * useless, since all 123 came from one harvest of the ODP portal and would land in a single
 * bucket. The portal is a catalogue of submissions, and the thirteen publishers behind them are
 * the division that tells a user whose modelling they are about to adopt.
 */
public final class PatternOrder {

    /** The orderings, by the label the chooser shows. */
    public static final String BY_PUBLISHER = "By publisher";
    public static final String BY_CATEGORY = "By category";
    public static final String BY_NAME = "By name";

    private PatternOrder() {
    }

    /**
     * A copy of {@code patterns} in the chosen order. An unknown label sorts by name.
     *
     * <p>Within a group, and for an unknown label, by name - never by id. The ids are directory
     * names and several are run together, so {@code collectionentity} would sort away from
     * {@code Collection Entity Pattern} for a reason the reader cannot see.
     */
    public static List<DesignPattern> sorted(List<DesignPattern> patterns, String ordering) {
        List<DesignPattern> copy = new ArrayList<DesignPattern>(patterns);
        Collections.sort(copy, comparatorFor(ordering));
        return Collections.unmodifiableList(copy);
    }

    static Comparator<DesignPattern> comparatorFor(String ordering) {
        if (BY_PUBLISHER.equals(ordering)) {
            return keyThenName(true);
        }
        if (BY_CATEGORY.equals(ordering)) {
            return keyThenName(false);
        }
        return byName();
    }

    private static Comparator<DesignPattern> keyThenName(final boolean byPublisher) {
        return new Comparator<DesignPattern>() {
            @Override
            public int compare(DesignPattern left, DesignPattern right) {
                String leftKey = byPublisher ? left.getPublisher() : left.getCategory();
                String rightKey = byPublisher ? right.getPublisher() : right.getCategory();
                // "unknown" last. Three patterns have no publisher because their IRI is an
                // absolute path from the machine the harvest ran on, and a bucket named for an
                // absence does not belong among the real ones.
                boolean leftUnknown = PatternIndex.UNKNOWN_PUBLISHER.equals(leftKey);
                boolean rightUnknown = PatternIndex.UNKNOWN_PUBLISHER.equals(rightKey);
                if (leftUnknown != rightUnknown) {
                    return leftUnknown ? 1 : -1;
                }
                int byKey = leftKey.compareToIgnoreCase(rightKey);
                return byKey != 0 ? byKey : byName().compare(left, right);
            }
        };
    }

    private static Comparator<DesignPattern> byName() {
        return new Comparator<DesignPattern>() {
            @Override
            public int compare(DesignPattern left, DesignPattern right) {
                int byName = left.getName().toLowerCase(Locale.ROOT)
                        .compareTo(right.getName().toLowerCase(Locale.ROOT));
                // Ties broken by id, so the order never depends on which came out of the index
                // first. Two patterns do share a name.
                return byName != 0 ? byName : left.getId().compareTo(right.getId());
            }
        };
    }
}
