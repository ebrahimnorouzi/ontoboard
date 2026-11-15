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

    /**
     * The three terms a rule's query bound in one row - {@code ?entity}, {@code ?property},
     * {@code ?value} - as ROBOT's report reads them: each as Jena writes the RDF term
     * ({@code RDFNode.toString()}, which is what ROBOT's {@code getQueryResultOrNull} returns),
     * null when the row left it unbound. An IRI is the IRI, a blank node its label, a literal its
     * text with every quotation mark as \", then {@code @language}, or {@code ^^} and the
     * datatype's IRI unless it is xsd:string.
     */
    public static final class Bound {
        private final String entity;
        private final String property;
        private final String value;

        public Bound(String entity, String property, String value) {
            this.entity = entity;
            this.property = property;
            this.value = value;
        }

        /** {@code ?entity} as Jena writes it; null when unbound. */
        public String entity() {
            return entity;
        }

        /** {@code ?property} as Jena writes it; null when unbound. */
        public String property() {
            return property;
        }

        /** {@code ?value} as Jena writes it; null when unbound. */
        public String value() {
            return value;
        }
    }

    private final Severity severity;
    private final String rule;
    private final String subject;
    private final String message;
    private final String property;
    private final String value;
    private final Bound bound;

    public QualityFinding(Severity severity, String rule, String subject, String message) {
        this(severity, rule, subject, message, "", "");
    }

    /**
     * A finding with what ROBOT's query bound beside the entity: the property and the value,
     * which are the fourth and fifth columns of ROBOT's own report file.
     */
    public QualityFinding(Severity severity, String rule, String subject, String message,
            String property, String value) {
        this(severity, rule, subject, message, property, value, null);
    }

    /**
     * A finding made from one row of a rule's query, with the terms the row bound as ROBOT's
     * report reads them ({@link Bound}); for writing the row as ROBOT writes it.
     */
    public QualityFinding(Severity severity, String rule, String subject, String message,
            String property, String value, Bound bound) {
        this.severity = severity;
        this.rule = rule;
        this.subject = subject;
        this.message = message;
        this.property = property == null ? "" : property;
        this.value = value == null ? "" : value;
        this.bound = bound;
    }

    /** The terms the query's row bound, as ROBOT reads them; null for a finding not made so. */
    public Bound getBound() {
        return bound;
    }

    /** The property the rule's query bound, as an IRI; empty when it bound none. */
    public String getProperty() {
        return property;
    }

    /** The value the rule's query bound; empty when it bound none. */
    public String getValue() {
        return value;
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
