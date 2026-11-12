package de.fizkarlsruhe.ise.ontoboard.odk.make;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

/** What the make goldens asked of make, taken apart for the parity tests. */
final class MakeRuns {

    private MakeRuns() {
    }

    /** Every seed with every label: the 96 runs make's answers were recorded for. */
    static Stream<Arguments> all() {
        List<Arguments> runs = new ArrayList<Arguments>();
        for (String name : Goldens.seedNames()) {
            for (String label : Goldens.makeLabels().keySet()) {
                runs.add(Arguments.of(name, label));
            }
        }
        return runs.stream();
    }

    /** The one goal a label asked for. */
    static String goal(String label) {
        String goal = null;
        for (String word : Goldens.makeLabels().get(label)) {
            if (!word.contains("=")) {
                if (goal != null) {
                    throw new IllegalStateException(label + " asks for more than one goal");
                }
                goal = word;
            }
        }
        if (goal == null) {
            throw new IllegalStateException(label + " asks for no goal");
        }
        return goal;
    }

    /**
     * The variables on make's command line for a label, in their order there: the label's own,
     * then {@code VERSION}, {@code TODAY} and {@code OBODATE}.
     */
    static Map<String, String> commandLine(String label) {
        Map<String, String> variables = new LinkedHashMap<String, String>();
        for (String word : Goldens.makeLabels().get(label)) {
            int equals = word.indexOf('=');
            if (equals > 0) {
                variables.put(word.substring(0, equals), word.substring(equals + 1));
            }
        }
        variables.putAll(Goldens.makeFixedVariables());
        return variables;
    }

    /** Where two texts first part, line by line, with the three lines before it. */
    static String firstDifference(String expected, String actual) {
        String[] theirs = expected.split("\n", -1);
        String[] ours = actual.split("\n", -1);
        int at = 0;
        while (at < theirs.length && at < ours.length && theirs[at].equals(ours[at])) {
            at++;
        }
        StringBuilder said = new StringBuilder("differs at line " + (at + 1) + " (make's has "
                + theirs.length + " lines, ours " + ours.length + ")\n");
        for (int before = Math.max(0, at - 3); before < at; before++) {
            said.append("      ").append(theirs[before]).append('\n');
        }
        said.append("  make ").append(at < theirs.length ? visible(theirs[at]) : "<end>")
                .append('\n');
        said.append("  ours ").append(at < ours.length ? visible(ours[at]) : "<end>").append('\n');
        return said.toString();
    }

    private static String visible(String line) {
        return "[" + line.replace("\t", "\\t") + "]";
    }
}
