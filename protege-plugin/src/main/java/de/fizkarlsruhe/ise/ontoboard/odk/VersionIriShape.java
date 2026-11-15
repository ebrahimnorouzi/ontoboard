package de.fizkarlsruhe.ise.ontoboard.odk;

/**
 * The shape of a release's version IRI.
 *
 * <p>ODK writes {@code <ONTBASE>/releases/<version>/<file>}, each product of a release with its
 * own. That is not the only shape in use: nfdicore and mwo publish
 * {@code <ONTBASE>/<version>/<file>}, and OntoBoard 1.135.x wrote {@code <ONTBASE>/<version>} -
 * one IRI for every file of the release. A version IRI is how a release is cited and imported,
 * and it cannot be taken back, so the shape is the project's to choose, release by release, and
 * a project is offered the one its last release has.
 *
 * <p>The shape reaches ODK's build as nfdicore's own release script hands it over: as the make
 * variable {@code ANNOTATE_ONTOLOGY_VERSION}, on make's command line. ODK's Makefile uses that
 * variable in the recipe of every product, so the file's place in the pattern is make's
 * {@code $@}. Nothing is written into the project's Makefile.
 *
 * <p>A pattern is written with {@code {ontbase}}, {@code {version}} and {@code {file}}. It ends up
 * in an IRI, and in a command line that a shell inside ODK's container reads: anything that
 * cannot be part of an IRI, or would mean something to make or to that shell, is refused here.
 */
public final class VersionIriShape {

    /** The shapes that have a name. */
    public enum Kind {
        /** {@code <ONTBASE>/releases/<version>/<file>}: what ODK's Makefile writes. */
        ODK("{ontbase}/releases/{version}/{file}"),
        /** {@code <ONTBASE>/<version>/<file>}: nfdicore's and mwo's. */
        VERSION_AND_FILE("{ontbase}/{version}/{file}"),
        /** {@code <ONTBASE>/<version>}: one IRI for every file of the release. */
        VERSION_ONLY("{ontbase}/{version}"),
        /** A pattern of the project's own. */
        CUSTOM(null);

        private final String pattern;

        Kind(String pattern) {
            this.pattern = pattern;
        }
    }

    private static final String ONTBASE = "{ontbase}";
    private static final String VERSION = "{version}";
    private static final String FILE = "{file}";

    /** What an IRI here may be made of: nothing a shell or make reads as anything but itself. */
    private static final String SAFE = "[A-Za-z0-9._~:/#@%+=-]";

    private final Kind kind;
    private final String pattern;

    private VersionIriShape(Kind kind, String pattern) {
        this.kind = kind;
        this.pattern = pattern;
    }

    /** One of the three named shapes. */
    public static VersionIriShape of(Kind kind) {
        if (kind == Kind.CUSTOM) {
            throw new IllegalArgumentException("a shape of the project's own is made from its "
                    + "pattern: VersionIriShape.custom(pattern)");
        }
        return new VersionIriShape(kind, kind.pattern);
    }

    /**
     * A shape from a pattern, such as {@code {ontbase}/v{version}/{file}}. One of the three
     * named shapes typed out is that shape.
     *
     * @throws IllegalArgumentException when the pattern does not name the version, would not
     *                                  give an http(s) IRI, or holds a character that is not
     *                                  safe in one - in words for the Release dialog
     */
    public static VersionIriShape custom(String pattern) {
        String typed = pattern == null ? "" : pattern.trim();
        // Filled in with plain values, the pattern has to be an IRI of plain characters.
        String sample = typed.replace(ONTBASE, "https://example.org/x").replace(VERSION, "1.0.0")
                .replace(FILE, "x.owl");
        if (!typed.contains(VERSION) || !sample.matches("https?://" + SAFE + "+")
                || !sample.matches(".*[A-Za-z0-9]")) {
            throw new IllegalArgumentException("A version IRI is written as an http or https "
                    + "address with {ontbase}, {version} and {file} where those go, for example "
                    + "{ontbase}/{version}/{file}. It has to name {version}, and may hold "
                    + "letters, digits and . _ ~ : / # @ % + = - only. Got: '" + typed + "'.");
        }
        for (Kind named : Kind.values()) {
            if (typed.equals(named.pattern)) {
                return new VersionIriShape(named, typed);
            }
        }
        return new VersionIriShape(Kind.CUSTOM, typed);
    }

    /**
     * The shape a release has, read off its version IRI: one of the three named shapes, or null
     * when it is none of them - nothing is guessed from an IRI of another make.
     *
     * @param versionIri the version IRI of the released file, or null when it has none
     * @param file       that file's name, as ODK's Makefile names the product: {@code eggboil.owl}
     */
    public static VersionIriShape detect(String versionIri, String ontbase, String version,
            String file) {
        if (versionIri == null) {
            return null;
        }
        for (Kind named : new Kind[] {Kind.ODK, Kind.VERSION_AND_FILE, Kind.VERSION_ONLY}) {
            VersionIriShape shape = of(named);
            try {
                if (versionIri.equals(shape.iriFor(ontbase, version, file))) {
                    return shape;
                }
            } catch (IllegalArgumentException notComparable) {
                return null;
            }
        }
        return null;
    }

    public Kind kind() {
        return kind;
    }

    /** The shape as a pattern: {@code {ontbase}/releases/{version}/{file}}. */
    public String pattern() {
        return pattern;
    }

    /** The version IRI of one file of a release. */
    public String iriFor(String ontbase, String version, String file) {
        return filled(ontbase, version, checked("file name", file, "[A-Za-z0-9._/-]+"));
    }

    /**
     * The value of {@code ANNOTATE_ONTOLOGY_VERSION} that makes ODK's build write this shape,
     * or null for ODK's own shape, which is the Makefile's default.
     *
     * <p>For make's command line as one argument: {@code ANNOTATE_ONTOLOGY_VERSION=<this>}.
     * The file's place holds {@code $@}, which make fills in recipe by recipe.
     */
    public String annotateArgument(String ontbase, String version) {
        if (kind == Kind.ODK) {
            return null;
        }
        return "annotate -V " + filled(ontbase, version, "$@")
                + " --annotation owl:versionInfo " + version;
    }

    private String filled(String ontbase, String version, String file) {
        String base = checked("base address", ontbase, "https?://" + SAFE + "*[A-Za-z0-9]");
        String number = checked("version", version, "[A-Za-z0-9][A-Za-z0-9._-]*");
        return pattern.replace(ONTBASE, base).replace(VERSION, number).replace(FILE, file);
    }

    private static String checked(String what, String value, String allowed) {
        if (value == null || !value.matches(allowed)) {
            throw new IllegalArgumentException("The " + what + " '" + value + "' cannot be part "
                    + "of a version IRI: it is empty, or holds a blank or a character that is "
                    + "not safe in one.");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VersionIriShape
                && pattern.equals(((VersionIriShape) other).pattern);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode();
    }

    @Override
    public String toString() {
        return pattern;
    }
}
