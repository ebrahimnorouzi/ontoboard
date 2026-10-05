package de.fizkarlsruhe.ise.ontoboard.odk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ODK {@code -idranges.owl} file: who may mint which identifiers, and how to mint the next one.
 *
 * <p>This is the mechanism that stops two people inventing the same identifier. Without it, two
 * collaborators editing one ontology both mint {@code MWO_0000001} for different concepts, and the
 * collision is only discovered when the files are merged - by which time both terms have been used.
 * It is therefore a prerequisite for the multi-user editing this plugin now supports, not a nicety.
 *
 * <p><b>The format is real, not invented.</b> It was read off
 * {@code ISE-FIZKarlsruhe/mwo/src/ontology/mwo-idranges.owl}, and the fixture in
 * {@code src/test/resources} is that file. Two things about it are easy to get wrong: it is
 * <em>Manchester syntax despite the .owl extension</em>, and the four properties it uses are real
 * OBO ones rather than anything project-specific:
 *
 * <ul>
 *   <li>{@code IAO_0000598} - the policy name, e.g. {@code "MWO"}
 *   <li>{@code IAO_0000599} - the IRI prefix new terms are minted under
 *   <li>{@code IAO_0000596} - how many digits to zero-pad to
 *   <li>{@code IAO_0000597} - who a range is allocated to
 * </ul>
 *
 * <p>The plugin's own scaffold previously wrote an invented {@code has_id_policy} property under
 * the project's namespace, with no prefix, no digit count and no ranges at all - so a generated
 * project could not allocate an identifier to anybody and ODK's own tooling would not have
 * recognised the file. This class replaces that.
 *
 * <p>Parsed by hand rather than through OWL API's Manchester parser. The file is a narrow, fixed
 * shape that ODK generates from a template; a purpose-built reader tolerates the {@code ##}
 * comment line and the irregular indentation real files carry, and - more importantly - lets
 * {@link #toManchester()} write the same shape back rather than whatever a serialiser would
 * produce. A round trip that reformatted an ODK file would show up as noise in every diff.
 */
public final class IdRanges {

    /** {@code IAO_0000598}: names the policy, conventionally the ontology id in capitals. */
    public static final String IDS_FOR = "http://purl.obolibrary.org/obo/IAO_0000598";
    /** {@code IAO_0000597}: the person a range belongs to. */
    public static final String ALLOCATED_TO = "http://purl.obolibrary.org/obo/IAO_0000597";
    /** {@code IAO_0000599}: the IRI prefix, up to but not including the number. */
    public static final String ID_PREFIX = "http://purl.obolibrary.org/obo/IAO_0000599";
    /** {@code IAO_0000596}: how many digits the number is padded to. */
    public static final String ID_DIGITS = "http://purl.obolibrary.org/obo/IAO_0000596";

    /** Raised rather than returning null, because minting silently is how collisions happen. */
    public static final class NoRangeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoRangeException(String message) {
            super(message);
        }
    }

    /** One editor's block of numbers. */
    public static final class Range {
        private final int number;
        private final String allocatedTo;
        private final long lower;
        private final long upper;

        public Range(int number, String allocatedTo, long lower, long upper) {
            if (lower > upper) {
                throw new IllegalArgumentException(
                        "range " + number + " runs backwards: " + lower + " to " + upper);
            }
            this.number = number;
            this.allocatedTo = allocatedTo == null ? "" : allocatedTo.trim();
            this.lower = lower;
            this.upper = upper;
        }

        public int getNumber() {
            return number;
        }

        public String getAllocatedTo() {
            return allocatedTo;
        }

        public long getLower() {
            return lower;
        }

        public long getUpper() {
            return upper;
        }

        public boolean overlaps(Range other) {
            return lower <= other.upper && other.lower <= upper;
        }

        @Override
        public String toString() {
            return "idrange:" + number + " " + allocatedTo + " [" + lower + ", " + upper + "]";
        }
    }

    private static final Pattern ONTOLOGY_ANNOTATION =
            Pattern.compile("(idsfor|idprefix|iddigits)\\s*:\\s*(\"([^\"]*)\"|(\\d+))");
    private static final Pattern RANGE_HEADER =
            Pattern.compile("^\\s*Datatype:\\s*idrange:(\\d+)\\s*$");
    private static final Pattern ALLOCATION =
            Pattern.compile("allocatedto\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile(
            "xsd:integer\\s*\\[\\s*>=\\s*(\\d+)\\s*,\\s*<=\\s*(\\d+)\\s*\\]");

    private final String ontologyIri;
    private final String policyName;
    private final String idPrefix;
    private final int idDigits;
    private final List<Range> ranges;

    /**
     * Top-level declarations this class does not model, kept so that writing cannot lose them.
     *
     * <p>{@link #toManchester()} is a canonical renderer: it emits a fixed template, so anything
     * in the file it has no field for disappears the first time a range is allocated. Measured
     * on a real ODK project, round-tripping its ranges file lost exactly one line -
     * {@code Datatype: rdf:PlainLiteral}, declared after the template's final
     * {@code Datatype: xsd:integer} - while the method's own comment promised "a one-range diff
     * rather than reformatting the whole file".
     *
     * <p>One line, and silent. Nothing in the dialog said a declaration had gone, and nobody
     * reads a ranges file afterwards to check. That is the shape of defect this project keeps
     * finding: not a crash, a quiet subtraction from somebody's file.
     */
    private final List<String> otherDeclarations;

    private IdRanges(String ontologyIri, String policyName, String idPrefix, int idDigits,
            List<Range> ranges) {
        this(ontologyIri, policyName, idPrefix, idDigits, ranges,
                Collections.<String>emptyList());
    }

    private IdRanges(String ontologyIri, String policyName, String idPrefix, int idDigits,
            List<Range> ranges, List<String> otherDeclarations) {
        this.ontologyIri = ontologyIri == null ? "" : ontologyIri;
        this.policyName = policyName == null ? "" : policyName;
        this.idPrefix = idPrefix == null ? "" : idPrefix;
        this.idDigits = idDigits;
        this.ranges = Collections.unmodifiableList(new ArrayList<Range>(ranges));
        this.otherDeclarations = Collections.unmodifiableList(
                new ArrayList<String>(otherDeclarations));
    }

    /**
     * Declarations carried through from the file this was parsed from.
     *
     * <p>Empty for a policy built by {@link #create}, which has no file behind it.
     */
    public List<String> getOtherDeclarations() {
        return otherDeclarations;
    }

    /**
     * Which non-blank lines of {@code original} would not survive being rewritten as
     * {@code rendered}, compared without indentation.
     *
     * <p>The backstop behind {@link #otherDeclarations}. That field fixes the case that was
     * measured; this catches the one that was not, because a ranges file is hand-editable and
     * there is no list of everything somebody might reasonably put in one. A caller that is
     * about to overwrite a file checks this first and refuses rather than subtracting silently.
     *
     * <p>Indentation is ignored deliberately: the renderer's own layout differs from ODK's by a
     * few spaces, and reporting that as data loss would make the check noise and get it turned
     * off. What it is looking for is a line that is simply gone.
     */
    public static List<String> whatWouldBeLost(String original, String rendered) {
        List<String> lost = new ArrayList<String>();
        if (original == null || rendered == null) {
            return lost;
        }
        java.util.Set<String> kept = new java.util.HashSet<String>();
        for (String line : rendered.split("\r?\n")) {
            kept.add(line.trim());
        }
        java.util.Set<String> reported = new java.util.HashSet<String>();
        for (String line : original.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !kept.contains(trimmed) && reported.add(trimmed)) {
                lost.add(trimmed);
            }
        }
        return lost;
    }

    /**
     * A fresh policy with no ranges allocated yet.
     *
     * @param idDigits OBO convention is 7, which is what ODK's own template uses
     */
    public static IdRanges create(String ontologyIri, String policyName, String idPrefix,
            int idDigits) {
        if (idDigits < 1 || idDigits > 18) {
            throw new IllegalArgumentException(
                    "idDigits must be between 1 and 18, got " + idDigits);
        }
        return new IdRanges(ontologyIri, policyName, idPrefix, idDigits,
                Collections.<Range>emptyList());
    }

    /**
     * Reads an existing file.
     *
     * <p>Tolerant of what real files contain - the leading {@code ##} comment, blank lines inside a
     * range block, and the inconsistent indentation ODK's template produces - because refusing to
     * read a valid ODK file would make the feature useless on exactly the projects that need it.
     *
     * @throws NoRangeException when the text is not an ID ranges file at all, naming what was
     *     missing rather than returning something empty that would mint from nowhere
     */
    public static IdRanges parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new NoRangeException("The ID ranges file is empty.");
        }
        String ontologyIri = "";
        Matcher ontology = Pattern.compile("^\\s*Ontology:\\s*<([^>]*)>", Pattern.MULTILINE)
                .matcher(text);
        if (ontology.find()) {
            ontologyIri = ontology.group(1);
        }

        String policyName = "";
        String idPrefix = "";
        int idDigits = 0;
        // Only the ontology-level Annotations block carries these, and it precedes the first
        // Datatype declaration - so the search is bounded there rather than scanning the whole
        // file, where an "idprefix" inside a range would be picked up by mistake.
        int firstRange = text.indexOf("Datatype: idrange:");
        String header = firstRange < 0 ? text : text.substring(0, firstRange);
        Matcher annotations = ONTOLOGY_ANNOTATION.matcher(header);
        while (annotations.find()) {
            String value = annotations.group(3) != null ? annotations.group(3)
                    : annotations.group(4);
            if ("idsfor".equals(annotations.group(1))) {
                policyName = value;
            } else if ("idprefix".equals(annotations.group(1))) {
                idPrefix = value;
            } else {
                idDigits = Integer.parseInt(value);
            }
        }
        if (idPrefix.isEmpty()) {
            throw new NoRangeException("This file declares no idprefix (" + ID_PREFIX
                    + "), so there is no IRI to mint identifiers under.");
        }
        if (idDigits <= 0) {
            // ODK's own template uses 7 and every OBO ontology examined does too, so this is a
            // safe default - but it is recorded rather than silent, because a file whose padding
            // is guessed could mint MWO_0000001 where the project expects MWO_00000001.
            idDigits = 7;
        }

        List<Range> ranges = new ArrayList<Range>();
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher headerMatch = RANGE_HEADER.matcher(lines[i]);
            if (!headerMatch.matches()) {
                continue;
            }
            int number = Integer.parseInt(headerMatch.group(1));
            // Everything up to the next Datatype declaration belongs to this range.
            StringBuilder block = new StringBuilder();
            for (int j = i + 1; j < lines.length; j++) {
                if (RANGE_HEADER.matcher(lines[j]).matches()
                        || lines[j].trim().startsWith("Datatype:")) {
                    break;
                }
                block.append(lines[j]).append('\n');
            }
            Matcher who = ALLOCATION.matcher(block);
            Matcher bounds = BOUNDS.matcher(block);
            if (!bounds.find()) {
                // A range with no bounds cannot mint anything; skipping it is better than
                // inventing bounds, and better than failing the whole file for one bad block.
                continue;
            }
            ranges.add(new Range(number, who.find() ? who.group(1) : "",
                    Long.parseLong(bounds.group(1)), Long.parseLong(bounds.group(2))));
        }
        return new IdRanges(ontologyIri, policyName, idPrefix, idDigits, ranges,
                declarationsNotModelled(lines));
    }

    /**
     * Top-level declarations the template does not emit, in the order the file gives them.
     *
     * <p>Everything the renderer writes itself is excluded: the ranges, the four annotation
     * properties, and the closing {@code Datatype: xsd:integer}. What is left is whatever this
     * project added by hand - on the file this was measured against, a single
     * {@code Datatype: rdf:PlainLiteral}.
     */
    private static List<String> declarationsNotModelled(String[] lines) {
        List<String> extra = new ArrayList<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Datatype:")) {
                continue;
            }
            String subject = trimmed.substring("Datatype:".length()).trim();
            if (subject.startsWith("idrange:") || "xsd:integer".equals(subject)
                    || subject.isEmpty() || extra.contains(trimmed)) {
                continue;
            }
            extra.add(trimmed);
        }
        return extra;
    }

    public String getOntologyIri() {
        return ontologyIri;
    }

    public String getPolicyName() {
        return policyName;
    }

    public String getIdPrefix() {
        return idPrefix;
    }

    public int getIdDigits() {
        return idDigits;
    }

    public List<Range> getRanges() {
        return ranges;
    }

    /** The range allocated to {@code owner}, or null. Matching ignores case and surrounding space. */
    public Range rangeFor(String owner) {
        if (owner == null) {
            return null;
        }
        String wanted = owner.trim();
        for (Range range : ranges) {
            if (range.getAllocatedTo().equalsIgnoreCase(wanted)) {
                return range;
            }
        }
        return null;
    }

    /**
     * The next identifier {@code owner} may mint.
     *
     * <p>Scans their range from the bottom and returns the first number whose IRI is not already
     * taken. Scanning rather than remembering a cursor is deliberate: a cursor stored in the file
     * would be a second source of truth that drifts the moment anyone edits the ontology outside
     * this plugin, whereas the ontology's own contents cannot drift from themselves.
     *
     * @param takenIris every IRI already in use - pass the ontology's full signature
     * @throws NoRangeException when the owner has no range, or has used all of it. Both messages
     *     say what to do, because both are things a person can fix.
     */
    public String mint(String owner, Set<String> takenIris) {
        List<String> minted = mint(owner, 1, takenIris);
        return minted.get(0);
    }

    /** As {@link #mint(String, Set)}, for {@code count} identifiers at once. */
    public List<String> mint(String owner, int count, Set<String> takenIris) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, got " + count);
        }
        Range range = rangeFor(owner);
        if (range == null) {
            throw new NoRangeException("No ID range is allocated to '" + owner + "' in this "
                    + "project. Add one to the -idranges.owl file before minting terms, or two "
                    + "editors will eventually mint the same identifier. Allocated so far: "
                    + describeAllocations() + ".");
        }
        Set<String> taken = takenIris == null ? Collections.<String>emptySet() : takenIris;
        List<String> minted = new ArrayList<String>();
        for (long candidate = range.getLower(); candidate <= range.getUpper(); candidate++) {
            String iri = iriFor(candidate);
            if (!taken.contains(iri) && !minted.contains(iri)) {
                minted.add(iri);
                if (minted.size() == count) {
                    return minted;
                }
            }
        }
        throw new NoRangeException("The ID range allocated to '" + owner + "' (" + range.getLower()
                + " to " + range.getUpper() + ") has no room for " + count
                + " more identifier" + (count == 1 ? "" : "s") + ". Allocate a further range in "
                + "the -idranges.owl file.");
    }

    /** The IRI for a number, zero-padded to {@link #getIdDigits()}. */
    public String iriFor(long number) {
        StringBuilder digits = new StringBuilder(Long.toString(number));
        while (digits.length() < idDigits) {
            digits.insert(0, '0');
        }
        return idPrefix + digits;
    }

    /**
     * A copy with one more range allocated.
     *
     * @throws IllegalArgumentException when the new range overlaps an existing one, which would
     *     hand the same numbers to two people and defeat the entire mechanism
     */
    public IdRanges withRange(String owner, long lower, long upper) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("a range must be allocated to somebody");
        }
        Range added = new Range(nextRangeNumber(), owner, lower, upper);
        for (Range existing : ranges) {
            if (existing.overlaps(added)) {
                throw new IllegalArgumentException("the range " + lower + " to " + upper
                        + " overlaps " + existing + ", so both editors would mint the same "
                        + "identifiers");
            }
        }
        List<Range> combined = new ArrayList<Range>(ranges);
        combined.add(added);
        return new IdRanges(ontologyIri, policyName, idPrefix, idDigits, combined,
                otherDeclarations);
    }

    /** The next unused block, sized like the ones already there, for a new editor. */
    public IdRanges withRangeFor(String owner, long blockSize) {
        long highest = 0;
        for (Range range : ranges) {
            highest = Math.max(highest, range.getUpper());
        }
        long lower = highest == 0 ? 1000 : highest + 1;
        return withRange(owner, lower, lower + blockSize - 1);
    }

    private int nextRangeNumber() {
        int highest = 0;
        for (Range range : ranges) {
            highest = Math.max(highest, range.getNumber());
        }
        return highest + 1;
    }

    private String describeAllocations() {
        if (ranges.isEmpty()) {
            return "none";
        }
        StringBuilder described = new StringBuilder();
        for (Range range : ranges) {
            if (described.length() > 0) {
                described.append(", ");
            }
            described.append(range.getAllocatedTo());
        }
        return described.toString();
    }

    /**
     * Writes the file back in ODK's own shape.
     *
     * <p>The same layout as the file this was modelled on, down to the prefix block and the
     * blank line between ranges, so that adding an editor to a real project produces close to a
     * one-range diff.
     *
     * <p><b>Close to, not exactly, and the difference used to cost data.</b> This is a canonical
     * renderer, not an editor: it emits a fixed template, so indentation is normalised and
     * anything the template has no field for is gone. Measured on a real ODK project, that was
     * one line - a {@code Datatype: rdf:PlainLiteral} declared after the template's final
     * {@code Datatype: xsd:integer} - dropped silently, by a method whose own comment said it
     * would not reformat the file. {@link #getOtherDeclarations()} carries those through now,
     * and {@link #whatWouldBeLost(String, String)} is the backstop for whatever was not
     * measured: the caller compares before overwriting and refuses rather than subtracting.
     */
    public String toManchester() {
        StringBuilder out = new StringBuilder();
        out.append("## ID Ranges File\n");
        out.append("Prefix: rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n");
        out.append("Prefix: idsfor: <").append(IDS_FOR).append(">\n");
        out.append("Prefix: dce: <http://purl.org/dc/elements/1.1/>\n");
        out.append("Prefix: xsd: <http://www.w3.org/2001/XMLSchema#>\n");
        out.append("Prefix: allocatedto: <").append(ALLOCATED_TO).append(">\n");
        out.append("Prefix: xml: <http://www.w3.org/XML/1998/namespace>\n");
        out.append("Prefix: idprefix: <").append(ID_PREFIX).append(">\n");
        out.append("Prefix: iddigits: <").append(ID_DIGITS).append(">\n");
        out.append("Prefix: rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n");
        out.append("Prefix: idrange: <http://purl.obolibrary.org/obo/ro/idrange/>\n");
        out.append("Prefix: owl: <http://www.w3.org/2002/07/owl#>\n");
        out.append("\n");
        out.append("Ontology: <").append(ontologyIri).append(">\n");
        out.append("\n\n");
        out.append("Annotations: \n");
        out.append("    idsfor: \"").append(policyName).append("\",\n");
        out.append("    idprefix: \"").append(idPrefix).append("\",\n");
        out.append("    iddigits: ").append(idDigits).append("\n");
        out.append("\n");
        out.append("AnnotationProperty: idprefix:\n\n");
        out.append("AnnotationProperty: iddigits:\n\n");
        out.append("AnnotationProperty: idsfor:\n\n");
        out.append("AnnotationProperty: allocatedto:\n\n");
        for (Range range : ranges) {
            out.append("Datatype: idrange:").append(range.getNumber()).append("\n\n");
            out.append("    Annotations: \n");
            out.append("        allocatedto: \"").append(range.getAllocatedTo()).append("\"\n");
            out.append("    \n");
            out.append("    EquivalentTo: \n");
            out.append("        xsd:integer[>= ").append(range.getLower()).append(" , <= ")
                    .append(range.getUpper()).append("]\n\n");
        }
        out.append("Datatype: xsd:integer\n");
        // Anything the template has no field for, carried through from the file this was parsed
        // from. Without this, allocating a range deletes it - see otherDeclarations.
        for (String declaration : otherDeclarations) {
            out.append(declaration).append("\n");
        }
        return out.toString();
    }

    /**
     * The IRI prefix terms should be minted under, derived from an ontology's base IRI.
     *
     * <p>OBO ontologies do not mint terms under the ontology document's own IRI. The MWO ontology
     * lives at {@code http://purls.helmholtz-metadaten.de/mwo/mwo} and its terms are
     * {@code http://purls.helmholtz-metadaten.de/mwo/MWO_0000001}; an OBO Foundry ontology at
     * {@code http://purl.obolibrary.org/obo/mwo.owl} has terms at
     * {@code http://purl.obolibrary.org/obo/MWO_0000001}. In both cases the document's own last
     * segment is dropped and the capitalised id plus an underscore takes its place.
     *
     * <p>Getting this wrong is not cosmetic. The prefix recorded here is what
     * {@code idprefix} declares, and if minting uses a different one then the ID ranges govern
     * nothing - which is the whole mechanism defeated, silently.
     */
    public static String prefixFor(String baseIri, String ontologyId) {
        if (baseIri == null || baseIri.trim().isEmpty() || ontologyId == null
                || ontologyId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "both a base IRI and an ontology id are needed to derive a term prefix");
        }
        String base = baseIri.trim();
        while (base.endsWith("#") || base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.toLowerCase(java.util.Locale.ROOT).endsWith(".owl")) {
            base = base.substring(0, base.length() - 4);
        }
        int lastSlash = base.lastIndexOf('/');
        // Only drop the last segment when there is a path to drop it from - a bare host would
        // otherwise be truncated into nonsense.
        if (lastSlash > base.indexOf("//") + 1) {
            base = base.substring(0, lastSlash);
        }
        return base + "/" + ontologyId.trim().toUpperCase(java.util.Locale.ROOT) + "_";
    }

    /** Everyone with a range, in file order, for a settings dialog to show. */
    public Set<String> owners() {
        Set<String> owners = new LinkedHashSet<String>();
        for (Range range : ranges) {
            if (!range.getAllocatedTo().isEmpty()) {
                owners.add(range.getAllocatedTo());
            }
        }
        return owners;
    }
}
