package de.fizkarlsruhe.ise.ontoboard.odk.seed;

/**
 * The ODK that OntoBoard's projects are identical to, said once.
 *
 * <p>A project OntoBoard creates is what this version's {@code odk.py seed} writes: through this
 * image when a container runtime has it, and otherwise from this version's templates, which the
 * bundle carries under {@link #RESOURCES} exactly as the image holds them. Moving to another ODK
 * means changing this, copying that version's templates out of its image
 * ({@code tools/odk-goldens/extract_templates.py}) and regenerating the goldens the tests compare
 * with - the tests then say what the interpreter or the config model has to learn.
 */
public final class OdkPin {

    /** ODK's own name for the version, as its Makefile and its image's ODK_VERSION write it. */
    public static final String VERSION = "v1.6.1";

    /** The image every ODK project is built in, without a tag. */
    public static final String IMAGE_REPOSITORY = "obolibrary/odkfull";

    /** The image a project OntoBoard creates is seeded, updated and by default built in. */
    public static final String IMAGE = IMAGE_REPOSITORY + ":" + VERSION;

    /** Where the bundle keeps this version's files, as a class-path prefix. */
    public static final String RESOURCES = "odk/" + VERSION + "/";

    /**
     * The ODK ROBOT plugin this version's image carries as {@code /tools/robot-plugins/odk.jar},
     * whose commands ({@code odk:normalize}, {@code odk:import}, {@code odk:subset}) its Makefile
     * calls. The bundle carries the same jar; the pom's {@code odk.robot.plugin.version} is this.
     */
    public static final String ROBOT_PLUGIN_VERSION = "0.3.1";

    /** That jar's SHA-256, as read from the image. */
    public static final String ROBOT_PLUGIN_SHA256 =
            "35f8ea55185bebd0c3c6bef17620975803aeb98d095d99cc2b75182bdd705312";

    private OdkPin() {
    }
}
