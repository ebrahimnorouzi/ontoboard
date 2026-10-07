package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.IOException;
import java.io.StringReader;

/**
 * Builds a {@link DesignPattern} for a test, since its constructor is the index's business.
 *
 * <p>In the same package as the thing it builds, and nowhere else, so that nothing outside the
 * index can make a pattern that is not in it.
 */
final class PatternIndexTestSupport {

    private PatternIndexTestSupport() {
    }

    /** A pattern with that id and nothing else filled in. */
    static DesignPattern patternNamed(String id) {
        try {
            return PatternIndex.read(new StringReader(
                    id + "\t" + id + "\todp\texample.org\tstructural\tgeneral\t\t\t\t\n"))
                    .get(0);
        } catch (IOException impossible) {
            throw new IllegalStateException("reading a string cannot fail", impossible);
        }
    }
}
