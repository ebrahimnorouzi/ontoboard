package de.fizkarlsruhe.ise.ontoboard.robot;

/**
 * A ROBOT operation could not run - as distinct from running and finding nothing.
 *
 * <p>The distinction is the point. An empty quality report means the ontology is clean; an empty
 * extraction means the module is small. Both are answers. A tool that returned them when it had in
 * fact failed would be telling a user their ontology is fine when nothing looked at it, and that
 * is a worse outcome than any error message.
 *
 * <p>Unchecked, because there is exactly one sensible place to handle it - the menu action, which
 * turns it into a failed result a user can read - and threading a checked exception through every
 * operation to reach that one place buys nothing.
 */
public class RobotException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RobotException(String message) {
        super(message);
    }

    public RobotException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * True when the host's OWL API is too old, rather than something transient.
     *
     * <p>Worth separating because the two need different advice: one is "upgrade to Protege 5.6",
     * the other is "look at your ontology". Several ROBOT operations route through an RDF layer
     * that changed from Sesame to RDF4J at OWL API 4.5.25, and Protege 5.5.0 ships 4.5.9 - so they
     * fail with {@link LinkageError} rather than an exception, on an installation where nothing is
     * wrong except the version.
     */
    public boolean isHostIncompatibility() {
        return getCause() instanceof LinkageError;
    }
}
