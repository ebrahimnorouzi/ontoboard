package de.fizkarlsruhe.ise.ontoboard.assistant.connect;

import java.io.IOException;

/**
 * A model call that did not produce an answer, with a message a person can act on. Never carries
 * the key: {@link ModelCalls#translate(IOException, Connection, String)} blanks it out.
 */
public final class ModelCallException extends IOException {

    private static final long serialVersionUID = 1L;

    /** What went wrong, for code that reacts differently to each. */
    public enum Kind {
        UNAUTHORIZED,
        MODEL_NOT_FOUND,
        RATE_LIMITED,
        UNREACHABLE,
        REFUSED_BY_POLICY,
        SERVER,
        BAD_REPLY,
        STOPPED,
        TOO_LARGE
    }

    private final Kind kind;

    public ModelCallException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ModelCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
