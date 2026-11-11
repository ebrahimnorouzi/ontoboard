package de.fizkarlsruhe.ise.ontoboard.odk.seed;

/**
 * Jinja2's {@code Undefined}: what a name or an attribute that is not there evaluates to.
 *
 * <p>The lenient kind ODK renders with: it prints as nothing, is false, iterates as empty, is
 * "not defined" and is not {@code None} - but asking it for an attribute raises, so a template
 * that reaches two levels into something missing fails rather than printing a hole.
 *
 * <p>It remembers what was asked for, so that failure can say it.
 */
public final class PyUndefined {

    private final String hint;

    PyUndefined(String hint) {
        this.hint = hint == null ? "" : hint;
    }

    /** What was looked up and not found, for an error message. */
    public String hint() {
        return hint;
    }

    @Override
    public String toString() {
        return "";
    }
}
