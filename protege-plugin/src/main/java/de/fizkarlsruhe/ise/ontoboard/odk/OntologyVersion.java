package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a release is called, in either of the two conventions real projects use.
 *
 * <p>OntoBoard shipped one: a date, because that is what OBO publishes and what the ODK
 * Makefile defaults to ({@code VERSION = $(TODAY)}). That is not what every ODK project does.
 * MatWerk's own {@code src/ontology/release.sh} reads
 *
 * <pre>
 *   VERSION=3.0.2
 *   PRIOR_VERSION=3.0.1
 *   ONTBASE=http://purls.helmholtz-metadaten.de/mwo
 * </pre>
 *
 * <p>and the published {@code mwo.owl} carries
 * {@code owl:versionIRI http://purls.helmholtz-metadaten.de/mwo/3.0.2} with
 * {@code owl:versionInfo "3.0.2"}. A release cut from the menu using the dated pattern would not
 * continue that history - it would start a second, parallel one under a different IRI shape,
 * which is the one mistake a version IRI cannot recover from once anybody has imported it.
 *
 * <p>Hence both, chosen rather than guessed. The two differ in more than the text:
 *
 * <table>
 *   <caption>The two conventions</caption>
 *   <tr><th></th><th>Dated (OBO)</th><th>Semantic (MatWerk)</th></tr>
 *   <tr><td>version IRI</td><td>{@code <base>/releases/2026-10-12/mwo.owl}</td>
 *       <td>{@code <base>/3.0.2}</td></tr>
 *   <tr><td>next release</td><td>whatever day it is</td>
 *       <td>a decision: patch, minor or major</td></tr>
 *   <tr><td>ordering</td><td>lexical, because ISO</td>
 *       <td>numeric per field - 3.10.0 is after 3.9.0, and sorting as text gets that wrong</td>
 *   </tr>
 * </table>
 *
 * <p>Immutable, with no clock in it: {@link #suggestNext(String)} takes today as a parameter so
 * that proposing the next version is testable and so that a release rerun for a given version
 * produces the same artefact.
 */
public final class OntologyVersion implements Comparable<OntologyVersion> {

    /** Which convention a version is written in. */
    public enum Scheme {
        /** {@code 2026-10-12}, as OBO publishes and as the generated Makefile defaults to. */
        DATE("Dated, the way OBO publishes", "2026-10-12"),
        /** {@code 1.2.0}: MAJOR.MINOR.PATCH, as semver.org defines it. */
        SEMANTIC("Semantic version (MAJOR.MINOR.PATCH)", "1.2.0");

        private final String label;
        private final String example;

        Scheme(String label, String example) {
            this.label = label;
            this.example = example;
        }

        /** What the dialog offers. */
        public String getLabel() {
            return label;
        }

        /** One of this scheme's versions, for a help line or a prompt. */
        public String getExample() {
            return example;
        }

        /** The labels, in the order they are offered. */
        public static List<String> labels() {
            List<String> labels = new ArrayList<String>();
            for (Scheme scheme : values()) {
                labels.add(scheme.getLabel());
            }
            return labels;
        }

        /** The scheme a label names, or {@link #DATE}, which is the ODK default. */
        public static Scheme byLabel(String label) {
            for (Scheme scheme : values()) {
                if (scheme.getLabel().equals(label)) {
                    return scheme;
                }
            }
            return DATE;
        }
    }

    /** {@code YYYY-MM-DD}, which is what OBO release directories are named. */
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

    /**
     * {@code MAJOR.MINOR[.PATCH][-suffix]}.
     *
     * <p>The patch field is optional because {@code 3.0} is a version people write, and a suffix
     * is accepted so that {@code 3.1.0-rc1} can be released without a second mechanism for it.
     */
    private static final Pattern SEMANTIC_VERSION = Pattern.compile(
            "^(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?(?:-([0-9A-Za-z.-]{1,32}))?$");

    private final Scheme scheme;
    private final String text;
    private final int[] fields;
    private final String suffix;

    private OntologyVersion(Scheme scheme, String text, int[] fields, String suffix) {
        this.scheme = scheme;
        this.text = text;
        this.fields = fields;
        this.suffix = suffix;
    }

    /**
     * The version {@code text} names, in whichever convention it is written.
     *
     * @throws IllegalArgumentException when it is neither, because a release directory named
     *     "today" or "v3" is one nothing can sort and nothing can parse
     */
    public static OntologyVersion of(String text) {
        OntologyVersion parsed = parseOrNull(text);
        if (parsed == null) {
            throw new IllegalArgumentException("a release version must be a date (YYYY-MM-DD) or "
                    + "a semantic version (1.2.0), not '" + text + "' - release directories are "
                    + "sorted and parsed by one of those two shapes");
        }
        return parsed;
    }

    /** As {@link #of}, returning null instead of throwing. */
    public static OntologyVersion parseOrNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Matcher date = ISO_DATE.matcher(trimmed);
        if (date.matches()) {
            return new OntologyVersion(Scheme.DATE, trimmed, new int[] {
                Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)),
                Integer.parseInt(date.group(3))}, "");
        }
        // A leading v is how the git tag is written - ODK's own GHVERSION is v$(VERSION) - so
        // somebody pasting the tag means the version. It is normalised away rather than kept:
        // the published IRI carries 3.0.2, and a versionIRI ending /v3.0.2 beside a history of
        // /3.0.2 is a break nobody intended.
        String withoutTagPrefix = trimmed.length() > 1
                && (trimmed.charAt(0) == 'v' || trimmed.charAt(0) == 'V')
                && Character.isDigit(trimmed.charAt(1)) ? trimmed.substring(1) : trimmed;
        Matcher semantic = SEMANTIC_VERSION.matcher(withoutTagPrefix);
        if (semantic.matches()) {
            return new OntologyVersion(Scheme.SEMANTIC, withoutTagPrefix, new int[] {
                Integer.parseInt(semantic.group(1)), Integer.parseInt(semantic.group(2)),
                semantic.group(3) == null ? 0 : Integer.parseInt(semantic.group(3))},
                    semantic.group(4) == null ? "" : semantic.group(4));
        }
        return null;
    }

    /** The version {@code text} names, which must be a date. */
    public static OntologyVersion dated(String text) {
        OntologyVersion parsed = of(text);
        if (parsed.scheme != Scheme.DATE) {
            throw new IllegalArgumentException("'" + text + "' is not a date (YYYY-MM-DD)");
        }
        return parsed;
    }

    /** The version {@code text} names, which must be a semantic version. */
    public static OntologyVersion semantic(String text) {
        OntologyVersion parsed = of(text);
        if (parsed.scheme != Scheme.SEMANTIC) {
            throw new IllegalArgumentException("'" + text + "' is not a semantic version (3.0.2)");
        }
        return parsed;
    }

    /** Whether {@code text} is a version written in {@code scheme}. */
    public static boolean isWritten(String text, Scheme scheme) {
        OntologyVersion parsed = parseOrNull(text);
        return parsed != null && parsed.scheme == scheme;
    }

    public Scheme getScheme() {
        return scheme;
    }

    /** The version as it is written in {@code owl:versionInfo} and in the directory name. */
    public String getText() {
        return text;
    }

    /** Whether this is a pre-release, such as {@code 3.1.0-rc1}. */
    public boolean isPreRelease() {
        return !suffix.isEmpty();
    }

    /**
     * The version IRI this release should carry, under the ontology's own IRI.
     *
     * <p>Two shapes, because the two conventions genuinely publish differently:
     *
     * <ul>
     *   <li><b>Dated:</b> {@code <stem>/releases/<date>/<name>.owl}, OBO's pattern, where a
     *       consumer reads the date out of the IRI without fetching anything.</li>
     *   <li><b>Semantic:</b> {@code <stem>/<version>}, which is what MatWerk's published
     *       {@code mwo.owl} carries - release.sh annotates {@code -V $ONTBASE/$VERSION} and the
     *       custom Makefile re-annotates the same way.</li>
     * </ul>
     *
     * @return the IRI as text, or null when {@code ontologyIri} has no name to build one from
     */
    public String versionIriUnder(String ontologyIri) {
        if (scheme == Scheme.DATE) {
            return ProjectIri.releaseIri(ontologyIri, text);
        }
        String stem = ProjectIri.stemOf(ontologyIri);
        return stem == null ? null : stem + "/" + text;
    }

    /**
     * What to propose as the next release.
     *
     * <p>A dated release is whatever day it is, so there is nothing to decide. A semantic one is
     * a decision, and a patch is the only increment safe to propose: it says "nothing moved that
     * anybody imported", which is true of most releases and obviously wrong when it is not.
     *
     * @param today {@code YYYY-MM-DD}, passed in rather than read from the clock so this is
     *     testable and so a rerun is reproducible
     */
    public OntologyVersion suggestNext(String today) {
        return scheme == Scheme.DATE ? of(today) : nextPatch();
    }

    /** {@code 3.0.2} to {@code 3.0.3}: nothing moved that anybody imported. */
    public OntologyVersion nextPatch() {
        requireSemantic("a patch increment");
        return semantic(fields[0] + "." + fields[1] + "." + (fields[2] + 1));
    }

    /** {@code 3.0.2} to {@code 3.1.0}: terms were added. */
    public OntologyVersion nextMinor() {
        requireSemantic("a minor increment");
        return semantic(fields[0] + "." + (fields[1] + 1) + ".0");
    }

    /** {@code 3.0.2} to {@code 4.0.0}: something that was published is gone or has moved. */
    public OntologyVersion nextMajor() {
        requireSemantic("a major increment");
        return semantic((fields[0] + 1) + ".0.0");
    }

    private void requireSemantic(String what) {
        if (scheme != Scheme.SEMANTIC) {
            throw new IllegalStateException(what + " only means something for a semantic version;"
                    + " this release is dated (" + text + ")");
        }
    }

    /**
     * Ordering, which is the whole reason the fields are parsed rather than kept as text.
     *
     * <p>{@code 3.10.0} comes after {@code 3.9.0} and sorting as text gets that backwards - so a
     * project on its tenth minor release would compare every new release against the wrong
     * predecessor and report everything added since 3.9.0 as new again.
     *
     * <p>A pre-release sorts below the same numbers without a suffix, as semver requires:
     * {@code 3.1.0-rc1} is before {@code 3.1.0}.
     *
     * <p>Across schemes the order is by scheme, which is arbitrary but stable. It is never used
     * to choose a predecessor: a dated release and a semantic one are not two points on one
     * line, and comparing across them would diff two unrelated things.
     */
    @Override
    public int compareTo(OntologyVersion other) {
        if (scheme != other.scheme) {
            return scheme.compareTo(other.scheme);
        }
        for (int at = 0; at < fields.length; at++) {
            if (fields[at] != other.fields[at]) {
                return fields[at] < other.fields[at] ? -1 : 1;
            }
        }
        if (suffix.equals(other.suffix)) {
            return 0;
        }
        if (suffix.isEmpty()) {
            return 1;
        }
        if (other.suffix.isEmpty()) {
            return -1;
        }
        return suffix.compareToIgnoreCase(other.suffix);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OntologyVersion)) {
            return false;
        }
        OntologyVersion that = (OntologyVersion) other;
        return scheme == that.scheme && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return scheme.hashCode() * 31 + text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }

    /** One line for a result or a prompt, naming the convention as well as the version. */
    public String describe() {
        return scheme == Scheme.DATE ? text + " (dated)" : text + " (semantic version)";
    }

    /** The same versions, newest first. */
    public static List<OntologyVersion> newestFirst(List<OntologyVersion> versions) {
        List<OntologyVersion> sorted = new ArrayList<OntologyVersion>(versions);
        java.util.Collections.sort(sorted, java.util.Collections.reverseOrder());
        return sorted;
    }

    /**
     * The version an ontology currently declares, which is the scheme its next release should
     * use by default.
     *
     * <p>Read from the artefact rather than from a preference, so it is right the first time
     * somebody opens a project they did not create. {@code owl:versionInfo} is checked first
     * because it holds the version plainly; the version IRI's last segments are the fallback,
     * since an ontology can carry one without the other.
     *
     * @return the version declared, or null when none is
     */
    public static OntologyVersion declaredBy(String versionInfo, String versionIri) {
        OntologyVersion fromInfo = parseOrNull(versionInfo);
        if (fromInfo != null) {
            return fromInfo;
        }
        if (versionIri == null) {
            return null;
        }
        String iri = versionIri.trim();
        while (iri.endsWith("/")) {
            iri = iri.substring(0, iri.length() - 1);
        }
        // .../releases/2026-10-12/mwo.owl ends in the file name, so the date is the segment
        // before it; .../mwo/3.0.2 ends in the version itself. Try the last two segments,
        // innermost first, and take whichever parses.
        String[] segments = iri.split("/");
        for (int at = segments.length - 1; at >= 0 && at >= segments.length - 2; at--) {
            String segment = segments[at];
            if (segment.toLowerCase(Locale.ROOT).endsWith(".owl")) {
                segment = segment.substring(0, segment.length() - ".owl".length());
            }
            OntologyVersion parsed = parseOrNull(segment);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }
}
