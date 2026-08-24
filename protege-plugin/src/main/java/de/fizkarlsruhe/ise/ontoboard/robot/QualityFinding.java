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
        INFO
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
