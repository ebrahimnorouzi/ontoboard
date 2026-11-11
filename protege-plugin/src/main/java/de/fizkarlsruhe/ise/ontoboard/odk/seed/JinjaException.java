package de.fizkarlsruhe.ise.ontoboard.odk.seed;

/**
 * A template that cannot be rendered as Jinja2 would render it, with where.
 *
 * <p>Either the template is broken, or - the case this exists for - it uses something the
 * interpreter does not implement. ODK v1.6.1's templates use a small part of Jinja2, and the
 * interpreter does exactly that part with Python's rules. Anything else stops here with the file
 * and the line rather than being printed as nothing: a later ODK that uses a new filter must fail
 * a test, not seed a project that is quietly different from ODK's.
 */
public class JinjaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String template;
    private final int line;

    public JinjaException(String template, int line, String message) {
        super(template + ":" + line + ": " + message);
        this.template = template;
        this.line = line;
    }

    /** The template's path inside ODK's template folder. */
    public String getTemplate() {
        return template;
    }

    /** The line, counted from 1. */
    public int getLine() {
        return line;
    }
}
