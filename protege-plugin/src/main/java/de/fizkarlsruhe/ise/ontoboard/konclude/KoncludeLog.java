package de.fizkarlsruhe.ise.ontoboard.konclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Konclude's log: how to read it, and why it is the only way to know whether the run worked.
 *
 * <p><b>Konclude exits 0 when it has failed.</b> Measured, not inferred. Run against an input file
 * that does not exist, it prints
 *
 * <pre>{error} &lt;2026-10-08T05:12:44.901&gt; [::Konclude::CLIBatchProcessor]&gt;&gt; File 'NOPE.owl' not found.</pre>
 *
 * <p>returns <b>exit code 0</b>, and writes an 896-byte, well-formed, parseable
 * {@code <Ontology>} holding exactly two declarations - {@code owl:Thing} and {@code owl:Nothing}.
 * So a caller that checks the exit code and then checks that the output parses will conclude the
 * reasoner ran and found nothing, about an ontology Konclude never opened.
 *
 * <p>That makes {@link #failed} the one sound test, and it is why this class exists rather than a
 * line of {@code if (outcome.getExitCode() != 0)} at the call site.
 *
 * <p>Two line formats, because Konclude has two observers. With {@code -u}, which
 * {@link Konclude#commandLine} always passes:
 *
 * <pre>{ info } &lt;2026-10-08T05:12:44.901&gt; [::Konclude::Classifier]&gt;&gt; Classification finished.</pre>
 *
 * <p>and without it a shorter form with no domain:
 *
 * <pre>{ info } 05:12:44:901 &gt;&gt; Classification finished.</pre>
 *
 * <p>The level token is padded to six characters, which is why it is trimmed before comparison.
 * A line matching neither shape is kept verbatim rather than dropped: an unparsed line from a
 * future Konclude is still something the user may need to read.
 */
public final class KoncludeLog {

    private KoncludeLog() {
    }

    /** One line of Konclude's output, split up. */
    public static final class Line {
        private final String level;
        private final String timestamp;
        private final String domain;
        private final String message;
        private final String raw;

        Line(String level, String timestamp, String domain, String message, String raw) {
            this.level = level;
            this.timestamp = timestamp;
            this.domain = domain;
            this.message = message;
            this.raw = raw;
        }

        /** Lower case and trimmed: info, notice, warn, error, and the two worse ones. */
        public String getLevel() {
            return level;
        }

        public String getTimestamp() {
            return timestamp;
        }

        /** The {@code ::Konclude::...} tag, or empty when the line carried none. */
        public String getDomain() {
            return domain;
        }

        public String getMessage() {
            return message;
        }

        /** Exactly what Konclude printed. */
        public String getRaw() {
            return raw;
        }

        /** Whether this line means the run did not do what was asked. */
        public boolean isError() {
            return level.contains("error");
        }

        public boolean isWarning() {
            return level.contains("warn");
        }

        /** Which family of the run this line belongs to. */
        public Stage getStage() {
            return Stage.of(this);
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    // {level} <timestamp> [domain]>> message
    private static final Pattern TAGGED = Pattern.compile(
            "^\\{\\s*([^}]*?)\\s*\\}\\s*<([^>]*)>\\s*\\[([^\\]]*)\\]>>\\s?(.*)$");
    // {level} timestamp >> message
    private static final Pattern PLAIN = Pattern.compile(
            "^\\{\\s*([^}]*?)\\s*\\}\\s*(\\S+)\\s*>>\\s?(.*)$");

    /** Splits Konclude's output into lines. Never drops one. */
    public static List<Line> parse(List<String> output) {
        List<Line> lines = new ArrayList<Line>();
        if (output == null) {
            return lines;
        }
        for (String raw : output) {
            if (raw == null) {
                continue;
            }
            Matcher tagged = TAGGED.matcher(raw);
            if (tagged.matches()) {
                lines.add(new Line(tagged.group(1).toLowerCase(Locale.ROOT), tagged.group(2),
                        tagged.group(3), tagged.group(4), raw));
                continue;
            }
            Matcher plain = PLAIN.matcher(raw);
            if (plain.matches()) {
                lines.add(new Line(plain.group(1).toLowerCase(Locale.ROOT), plain.group(2), "",
                        plain.group(3), raw));
                continue;
            }
            // Neither shape. Kept with an empty level so it can never be mistaken for an error,
            // and never discarded, because a line this does not understand is still output the
            // user may need.
            lines.add(new Line("", "", "", raw, raw));
        }
        return lines;
    }

    /**
     * Whether the run failed. The <em>only</em> sound test - see this class's own note.
     *
     * <p>Matches on the level containing "error", which covers Konclude's {@code error},
     * {@code exceptional error} and {@code catastrophic error} in one comparison rather than three
     * literals that a fourth spelling would slip past.
     */
    public static boolean failed(List<Line> lines) {
        for (Line line : lines) {
            if (line.isError()) {
                return true;
            }
        }
        return false;
    }

    /** The first error, for a failure message that quotes Konclude rather than paraphrasing it. */
    public static String firstError(List<Line> lines) {
        for (Line line : lines) {
            if (line.isError()) {
                return line.getMessage().trim();
            }
        }
        return "";
    }

    /** How many lines carry each level, for the result to summarise without printing 248 lines. */
    public static String summarise(List<Line> lines) {
        int errors = 0;
        int warnings = 0;
        for (Line line : lines) {
            if (line.isError()) {
                errors++;
            } else if (line.isWarning()) {
                warnings++;
            }
        }
        return lines.size() + " log lines, " + errors + " error" + (errors == 1 ? "" : "s")
                + " and " + warnings + " warning" + (warnings == 1 ? "" : "s");
    }

    /**
     * The families a log line can belong to, which are what the dialog's checkboxes select.
     *
     * <p>Four families rather than Konclude's nineteen domain tags. The tags are internal names -
     * {@code ::Konclude::Control::Interface::OWLlink::OWLlinkProcessor} is not a thing to put on a
     * checkbox - and there are too many of them to choose from in a dialog that has room for six
     * rows. These group them by what somebody reading the log is actually looking for.
     */
    public enum Stage {
        /** Did it start, read my file, and finish. */
        PROGRESS("Progress", "Starting, parsing the input, and finishing. The shape of the run."),
        /** What the reasoner did, step by step. */
        STAGES("Reasoning stages", "Preprocessing, precomputation, classification - the work "
                + "itself, and where it spent its time."),
        /** How long it took. */
        TIMINGS("Timings", "How long parsing and the whole run took. Adds Konclude's -v flag."),
        /** Caches, indexes and revisions. */
        INTERNALS("Internals", "Caches, indexes and thread bookkeeping. Rarely what you want, and "
                + "the place a strange failure shows up.");

        private final String label;
        private final String help;

        Stage(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public String getLabel() {
            return label;
        }

        public String getHelp() {
            return help;
        }

        /**
         * Which family a line belongs to.
         *
         * <p>Anything unrecognised falls into {@link #INTERNALS} rather than being dropped, so a
         * domain added by a future Konclude still reaches somebody who asked for everything.
         */
        static Stage of(Line line) {
            String message = line.getMessage().toLowerCase(Locale.ROOT);
            // Matched on the message, not the domain: measured, the two -v timing lines arrive
            // under OWLlinkProcessor and CLIBatchProcessor, which are otherwise progress.
            if (message.startsWith("ontology parsed in") || message.startsWith("total processing")) {
                return TIMINGS;
            }
            String domain = line.getDomain().toLowerCase(Locale.ROOT);
            if (domain.isEmpty()) {
                return PROGRESS;
            }
            for (String tag : new String[] {"main", "clibatchprocessor", "parser", "command",
                "owllinkprocessor"}) {
                if (domain.contains(tag)) {
                    return PROGRESS;
                }
            }
            for (String tag : new String[] {"preprocess", "precomputator", "classifier",
                "structureinspection", "absorber", "processdataextender", "mappingupdater"}) {
                if (domain.contains(tag)) {
                    // Classifier::Factory is bookkeeping, not a reasoning stage.
                    return domain.contains("factory") ? INTERNALS : STAGES;
                }
            }
            return INTERNALS;
        }
    }

    /** Every stage, for building the checkboxes. */
    public static List<Stage> stages() {
        return Collections.unmodifiableList(java.util.Arrays.asList(Stage.values()));
    }

    /**
     * The lines the user asked to see.
     *
     * <p><b>Errors and warnings are always kept</b>, whatever the checkboxes say. A log filter
     * that can hide the reason a run failed is a filter that will hide it on the day it matters;
     * the checkboxes choose how much detail to add, not whether to be told about a problem.
     */
    public static List<String> filter(List<Line> lines, Set<Stage> wanted) {
        Set<Stage> selected = wanted == null ? new LinkedHashSet<Stage>()
                : new LinkedHashSet<Stage>(wanted);
        List<String> kept = new ArrayList<String>();
        for (Line line : lines) {
            if (line.isError() || line.isWarning() || selected.contains(line.getStage())) {
                kept.add(line.getRaw());
            }
        }
        return kept;
    }
}
