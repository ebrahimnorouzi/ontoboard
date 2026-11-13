package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;

/**
 * How a project's files are brought in line with its configuration after OntoBoard changed it -
 * Import terms, the pattern library, a question's check, Project configuration: ODK's own update
 * ({@code odk.py update}, what ODK's {@code update_repo} runs) in the project's own image; or
 * OntoBoard's own update for a project on the pinned ODK; or, where neither can run, the
 * configuration alone - and that is said, with what brings the rest in line later.
 *
 * <p>A sync never moves a project to another ODK. OntoBoard's own update is the pinned ODK's,
 * so it runs only for a project on that version, and a container runs the project's own image.
 * Moving a project to the pinned version is <em>Update project files</em>', when asked.
 */
public final class OdkSync {

    /** Who brings the files in line. */
    public enum How {
        /** ODK's own update, in the project's own image. */
        CONTAINER,
        /** OntoBoard's own update, for a project on the pinned ODK. */
        ITSELF,
        /** Nobody now: the configuration alone is written. */
        CONFIG_ONLY
    }

    private static final String LATER = "The configuration was written; the Makefile, the "
            + "catalog and the edit file's imports follow it at the next "
            + "OntoBoard > Project > Update project files...";

    /** How a sync runs - or why it does not. */
    public static final class Way {
        private final How how;
        private final String runtime;
        private final String image;
        private final String said;
        private final String why;

        private Way(How how, String runtime, String image, String said, String why) {
            this.how = how;
            this.runtime = runtime;
            this.image = image;
            this.said = said;
            this.why = why;
        }

        public How how() {
            return how;
        }

        /** The program that runs the container; null when no container runs it. */
        public String runtime() {
            return runtime;
        }

        /** The project's own image; null when no container runs it. */
        public String image() {
            return image;
        }

        /** Who brings the files in line, as the end of a sentence; empty when nobody does. */
        public String said() {
            return said;
        }

        /** Why nobody does, and what to do; null when somebody does. */
        public String why() {
            return why;
        }
    }

    private OdkSync() {
    }

    /**
     * @param projectVersion the ODK version the project's Makefile was written by
     *                       ({@code ODK_VERSION_MAKEFILE}), or null when it does not say
     * @param use            what the person settled about containers
     * @param runtime        the program that runs containers here, or null
     * @param ownImage       the project's own image, where it is on this machine; else null -
     *                       nothing is fetched for a sync
     * @param noContainer    why no container runs it, as the start of a sentence; null when
     *                       one does
     */
    public static Way decide(String projectVersion, OdkRoute.Use use, String runtime,
            String ownImage, String noContainer) {
        if (runtime != null && ownImage != null && use != OdkRoute.Use.NEVER_CONTAINER) {
            return new Way(How.CONTAINER, runtime, ownImage, "ODK's own update, in its container ("
                    + ownImage + ", through " + runtime + ")", null);
        }
        if (!OdkRoute.mayRunWithoutContainer(use)) {
            return new Way(How.CONFIG_ONLY, null, null, "", noContainer + ", and OntoBoard's "
                    + "setting for ODK's targets is \"" + use.label() + "\". " + LATER);
        }
        if (OdkPin.VERSION.equals(projectVersion)) {
            return new Way(How.ITSELF, null, null, "OntoBoard itself, as ODK's own update does it ("
                    + noContainer + ")", null);
        }
        return new Way(How.CONFIG_ONLY, null, null, "", noContainer + ", and this project is "
                + (projectVersion == null ? "made with an ODK whose version its Makefile does "
                        + "not say" : "made with ODK " + projectVersion) + ". OntoBoard's own "
                + "update is ODK " + OdkPin.VERSION + "'s and would move the project to that "
                + "version, which only OntoBoard > Project > Update project files... does, when "
                + "asked. " + LATER);
    }

    /** No sync now, for a reason the caller says: the configuration alone was written. */
    public static Way later(String why) {
        return new Way(How.CONFIG_ONLY, null, null, "", why.trim()
                + (why.trim().endsWith(".") ? " " : ". ") + LATER);
    }
}
