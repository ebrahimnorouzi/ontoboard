package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.obolibrary.robot.DiffOperation;
import org.obolibrary.robot.IOHelper;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * ROBOT's {@code diff} - every axiom that differs between two ontologies.
 *
 * <p>Deliberately a companion to {@link de.fizkarlsruhe.ise.ontoboard.odk.ReleaseDiff} rather than a
 * replacement for it. {@code ReleaseDiff} answers the question a release note is written from -
 * which <em>terms</em> were added, obsoleted, redefined or moved - and it exists because an axiom
 * diff alone does not answer it: a term that gained a definition and a term that changed parents
 * both show up there as "some axioms went, some axioms came".
 *
 * <p>But the axiom diff is the one that answers "what exactly changed", and for reviewing a release
 * before publishing it that is the question. So both are offered, and this one is opt-in.
 *
 * <p>Safe in the bundle: {@code DiffOperation}'s constant pool contains no reference to RDF4J, to
 * {@code openrdf} or to Apache POI - checked with {@code javap -v}, which is the check that matters
 * here after {@code ReportOperation} and {@code QueryOperation} both turned out to be unusable for
 * reasons no signature revealed.
 *
 * <p>Pure OWL API and robot-core; no Protege types and no Swing.
 */
public final class AxiomDiff {

    /** ROBOT's own option keys. */
    public static final String OPTION_FORMAT = "format";
    public static final String OPTION_LABELS = "labels";

    private AxiomDiff() {
    }

    /** The formats ROBOT's diff can write. */
    public static List<String> formats() {
        return Collections.unmodifiableList(
                java.util.Arrays.asList("plain", "pretty", "markdown", "html"));
    }

    /** What the diff found. */
    public static final class Result {
        private final boolean identical;
        private final String text;

        Result(boolean identical, String text) {
            this.identical = identical;
            this.text = text;
        }

        /**
         * True when the two ontologies have the same axioms.
         *
         * <p>ROBOT's own verdict, not a count of zero: {@code compare} returns it directly, and a
         * diff whose text happens to be empty is not the same claim.
         */
        public boolean isIdentical() {
            return identical;
        }

        /** ROBOT's rendering, in the requested format. */
        public String getText() {
            return text;
        }

        /** The diff as lines, for a caller putting it in a table. */
        public List<String> getLines() {
            java.util.List<String> lines = new java.util.ArrayList<String>();
            for (String line : text.split("\\r?\\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
            return Collections.unmodifiableList(lines);
        }
    }

    /** ROBOT's defaults, with labels turned on because an IRI-only diff is unreadable. */
    public static Map<String, String> defaultOptions() {
        Map<String, String> options =
                new LinkedHashMap<String, String>(DiffOperation.getDefaultOptions());
        // ROBOT defaults labels to false. A diff of forty axioms written as bare IRIs is something
        // nobody reads, and the whole point of running it here is to review a release.
        options.put(OPTION_LABELS, "true");
        options.put(OPTION_FORMAT, "markdown");
        return options;
    }

    /**
     * Compares {@code left} with {@code right}.
     *
     * @param options ROBOT's own option map - see {@link #defaultOptions()}
     * @throws RobotException if ROBOT cannot produce the diff
     */
    public static Result between(OWLOntology left, OWLOntology right, Map<String, String> options) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("two ontologies are needed to compare");
        }
        Map<String, String> effective = options == null
                ? defaultOptions()
                : new LinkedHashMap<String, String>(options);
        StringWriter into = new StringWriter();
        try {
            boolean identical = DiffOperation.compare(left, right, new IOHelper(), into, effective);
            return new Result(identical, into.toString());
        } catch (IOException cannotWrite) {
            throw new RobotException("ROBOT could not write the diff: " + cannotWrite.getMessage(),
                    cannotWrite);
        } catch (RuntimeException | LinkageError failure) {
            throw new RobotException("ROBOT could not compare these ontologies: "
                    + describe(failure), failure);
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getName()
                : message;
    }
}
