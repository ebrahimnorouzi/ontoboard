package de.fizkarlsruhe.ise.ontoboard.robot;

/**
 * One ROBOT report violation, flattened into something a table can render.
 *
 * <p>ROBOT's own {@code Report} exposes three parallel maps of rule to violation list, with
 * violations carrying loosely-typed statement maps. Flattening at the boundary keeps that
 * shape out of the UI and, more usefully, makes the whole reporting path testable without a
 * window.
 */
public final class QualityFinding {

    /** ROBOT's three levels, ordered most severe first so a table sorts sensibly. */
    public enum Severity {
        ERROR,
        WARN,
        INFO;

        /**
         * The severity named by a ROBOT profile line, defaulting to {@code WARN}.
         *
         * <p>A level this code does not recognise becomes a warning rather than an exception. A
         * project is entitled to write something odd in its own profile.txt, and losing the whole
         * report over one unparseable word would be a worse answer than reporting the violation at
         * a level somebody has to look at.
         */
        public static Severity of(String level) {
            if (level != null) {
                String normalised = level.trim().toUpperCase(java.util.Locale.ROOT);
                for (Severity candidate : values()) {
                    // startsWith, not equals: ROBOT writes "WARN" in a profile and "Warning" in a
                    // report table, and both mean the same level.
                    if (normalised.startsWith(candidate.name())) {
                        return candidate;
                    }
                }
            }
            return WARN;
        }
    }

    private final Severity severity;
    private final String rule;
    private final String subject;
    private final String message;

    public QualityFinding(Severity severity, String rule, String subject, String message) {
        this.severity = severity;
        this.rule = rule;
        this.subject = subject;
        this.message = message;
    }

    public Severity getSeverity() {
        return severity;
    }

    /** The ROBOT rule that fired, e.g. {@code missing_label}. */
    public String getRule() {
        return rule;
    }

    /** The entity the rule fired on, as an IRI or label. Never null; may be empty. */
    public String getSubject() {
        return subject;
    }

    /** Human-readable detail, assembled from the violation's statements. May be empty. */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return severity + " " + rule + " " + subject;
    }
}
