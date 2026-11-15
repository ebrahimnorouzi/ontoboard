package de.fizkarlsruhe.ise.ontoboard.odk.seed;

/**
 * An ODK configuration that ODK itself would not take: YAML that does not parse, a value of the
 * wrong kind, a product without an id.
 *
 * <p>Said before anything is rendered. ODK fails the same configs with a Python traceback from
 * inside {@code dacite}; this says which key, and for a syntax error which line, because the
 * person reading it is in Protégé and the file is one they edited by hand.
 */
public class OdkConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;

    public OdkConfigException(String message) {
        this(message, 0);
    }

    public OdkConfigException(String message, int line) {
        super(message);
        this.line = line;
    }

    /** The line of the config the problem is on, counted from 1, or 0 when it has none. */
    public int getLine() {
        return line;
    }
}
