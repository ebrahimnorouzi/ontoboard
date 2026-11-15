package de.fizkarlsruhe.ise.ontoboard.odk;

/**
 * Who builds a release of an ODK project: ODK's own tools installed on the machine, ODK in its
 * container, or OntoBoard itself - and, when nobody does, why not.
 *
 * <p>The order is Build's: a native ODK environment first, since it is ODK itself with nothing
 * in between; then the container; then OntoBoard's own engine, which runs ODK's Makefile inside
 * Protege. What the person settled ({@link OdkRoute.Use}) is kept to: with "always in a
 * container" nothing else builds, and without one nothing is built.
 *
 * <p>OntoBoard itself builds what ODK's Makefile builds. A release script of the project's own
 * is a shell script and is not built this way; and where the full release - the one that
 * fetches the imported ontologies again first - cannot run here for a project, the release is
 * built on the import modules as they are, which is ODK's {@code prepare_release_fast}.
 */
public final class ReleaseRoute {

    /** Who builds it. */
    public enum Kind {
        /** ODK's own tools, installed on the machine (ODK Core). */
        NATIVE,
        /** ODK in its container. */
        CONTAINER,
        /** OntoBoard itself: ODK's Makefile run by OntoBoard's engine. */
        ITSELF
    }

    private final Kind kind;
    private final String refusal;
    private final String noContainer;
    private final String whyNotFull;

    private ReleaseRoute(Kind kind, String refusal, String noContainer, String whyNotFull) {
        this.kind = kind;
        this.refusal = refusal;
        this.noContainer = noContainer;
        this.whyNotFull = whyNotFull;
    }

    /**
     * @param nativeEnvironment whether a native ODK environment is set
     * @param container         whether a container program answers and ODK's image is there
     * @param use               what the person settled about containers
     * @param noContainer       why no container builds it, as the start of a sentence; null
     *                          when one can
     * @param howToGetOne       what to do to have one, said with a refusal; or null
     * @param script            the name of the project's own release script, or null
     * @param whyNotFast        why OntoBoard itself cannot build the release on the imports as
     *                          they are, or null when it can
     * @param whyNotFull        why it cannot build the release with the imports fetched again,
     *                          or null when it can
     */
    public static ReleaseRoute decide(boolean nativeEnvironment, boolean container,
            OdkRoute.Use use, String noContainer, String howToGetOne, String script,
            String whyNotFast, String whyNotFull) {
        boolean onlyAContainer = !OdkRoute.mayRunWithoutContainer(use);
        // A script of the project's own calls ODK through run.sh, which starts a container
        // whatever is installed: a native environment is for ODK's own target only.
        if (nativeEnvironment && !onlyAContainer && script == null) {
            return new ReleaseRoute(Kind.NATIVE, null, null, null);
        }
        if (container) {
            return new ReleaseRoute(Kind.CONTAINER, null, null, null);
        }
        if (onlyAContainer) {
            return refused(noContainer + ", and OntoBoard's setting for ODK's targets is \""
                    + use.label() + "\": nothing is built."
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne), noContainer);
        }
        if (script != null) {
            return refused(noContainer + ", and this project is released by its own script, "
                    + "src/ontology/" + script + ". OntoBoard itself builds what ODK's Makefile "
                    + "builds; a script is run by a shell, and its lines call ODK through the "
                    + "project's run.sh, which starts ODK's container."
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne), noContainer);
        }
        if (whyNotFast != null) {
            return refused(noContainer + ", and OntoBoard itself does not build this project's "
                    + "release either: " + whyNotFast
                    + (howToGetOne == null ? "" : "\n\n" + howToGetOne), noContainer);
        }
        return new ReleaseRoute(Kind.ITSELF, null, noContainer, whyNotFull);
    }

    private static ReleaseRoute refused(String why, String noContainer) {
        return new ReleaseRoute(null, why, noContainer, null);
    }

    /** Who builds the release; null when nobody does. */
    public Kind kind() {
        return kind;
    }

    /** Why nobody builds it, for a person to read; null when somebody does. */
    public String refusal() {
        return refusal;
    }

    /**
     * Whether the release may fetch the imported ontologies again first. Always so where ODK
     * builds it; where OntoBoard itself does, only when nothing has to be fetched.
     */
    public boolean refreshesImports() {
        return kind != null && (kind != Kind.ITSELF || whyNotFull == null);
    }

    /** Why OntoBoard itself builds on the imports as they are; null when it need not. */
    public String whyNotFull() {
        return whyNotFull;
    }

    /** Why no container builds it, as the start of a sentence; null when one does. */
    public String noContainer() {
        return noContainer;
    }
}
