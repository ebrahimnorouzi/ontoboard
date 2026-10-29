package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a reasoner found in a bundled pattern, told to the person about to import it.
 *
 * <p>Four of the patterns in this library are ones a reasoner objects to - two fall outside OWL 2
 * DL so no DL reasoner can answer about them, one declares classes that cannot have instances,
 * and one cannot be exemplified at all. Those findings were measured, written to
 * {@code patterns/soundness.tsv} and published on the web pages, and the web pages are the one
 * place a curator working inside Protégé is not looking. Importing a pattern whose class is
 * unsatisfiable makes their own ontology unsatisfiable, and the first they would hear of it is
 * their own reasoner refusing to classify.
 *
 * <p><b>Read, not computed.</b> The verdicts come from the table that
 * {@code PatternSoundnessTest} generates and then checks on every build - it fails when the file
 * and the patterns disagree - so what is shown here is what HermiT said about the file that
 * shipped. Running a reasoner over 162 patterns to open a dialog would take twenty seconds and
 * answer a question already answered.
 *
 * <p>Absence is not a finding. A pattern with no row - a contributed one from the user's own
 * folder, or a shipped one in an older jar whose table predates it - gets {@link #UNKNOWN}, and
 * the dialog says nothing rather than implying it was checked and passed.
 */
public final class ReasonerVerdict {

    /** Where the table sits, inside the jar and beside the pattern directories it describes. */
    public static final String RESOURCE = PatternLibrary.DIRECTORY + "soundness.tsv";

    /** No row for this pattern: it was never checked, which is different from passing. */
    public static final ReasonerVerdict UNKNOWN =
            new ReasonerVerdict("", true, "", "", "", "");

    private static Map<String, ReasonerVerdict> loaded;

    private final String id;
    private final boolean patternConsistent;
    private final String unsatisfiable;
    private final String mergedConsistent;
    private final String mergedUnsatisfiable;
    private final String exampleAxioms;

    ReasonerVerdict(String id, boolean patternConsistent, String unsatisfiable,
            String mergedConsistent, String mergedUnsatisfiable, String exampleAxioms) {
        this.id = id;
        this.patternConsistent = patternConsistent;
        this.unsatisfiable = unsatisfiable;
        this.mergedConsistent = mergedConsistent;
        this.mergedUnsatisfiable = mergedUnsatisfiable;
        this.exampleAxioms = exampleAxioms;
    }

    /** The verdict for one pattern, or {@link #UNKNOWN} when the table does not mention it. */
    public static ReasonerVerdict forPattern(String patternId) {
        if (patternId == null) {
            return UNKNOWN;
        }
        ReasonerVerdict found = table().get(patternId);
        return found == null ? UNKNOWN : found;
    }

    /** Whether this verdict says anything a curator needs to know before importing. */
    public boolean isWorthSaying() {
        return !this.id.isEmpty() && !isClean();
    }

    /** Whether a reasoner found nothing wrong with the pattern or with its example. */
    public boolean isClean() {
        return patternConsistent && unsatisfiable.isEmpty()
                && !"false".equals(mergedConsistent) && mergedUnsatisfiable.isEmpty();
    }

    /**
     * A short label for a list, where there is room for three words and no more.
     *
     * <p>Empty when there is nothing to say, so a caller can test it rather than test a flag.
     */
    public String getShortLabel() {
        if (!isWorthSaying()) {
            return "";
        }
        if (!patternConsistent) {
            return isOutsideOwl2Dl() ? "not OWL 2 DL" : "inconsistent";
        }
        if (!unsatisfiable.isEmpty()) {
            return "unsatisfiable class" + (unsatisfiable.indexOf(';') < 0 ? "" : "es");
        }
        return "example disagrees";
    }

    /**
     * The sentence a curator reads before importing.
     *
     * <p>Outside OWL 2 DL is NOT the same as inconsistent, and the two must not be run together:
     * one is a contradiction, the other a question no DL reasoner can be asked. Telling somebody
     * their pattern is inconsistent when it is merely outside the profile would send them looking
     * for a clash that is not there.
     */
    public String describe() {
        if (!isWorthSaying()) {
            return "";
        }
        if (!patternConsistent) {
            return isOutsideOwl2Dl()
                    ? "This pattern falls outside OWL 2 DL, so no DL reasoner can draw "
                      + "conclusions from it. You can borrow its terms and its shape, but a "
                      + "reasoner will refuse the pattern as published."
                    : "A reasoner finds this pattern inconsistent as published. Importing it "
                      + "would make your ontology inconsistent too.";
        }
        if (!unsatisfiable.isEmpty()) {
            boolean one = unsatisfiable.indexOf(';') < 0;
            return "A reasoner finds " + unsatisfiable + (one ? " unsatisfiable" : " unsatisfiable")
                    + ": as published, nothing can belong to " + (one ? "that class" : "those "
                    + "classes") + ". Anything you assert into " + (one ? "it" : "them")
                    + " makes your ontology inconsistent.";
        }
        return "A reasoner finds this pattern consistent, but the example shipped with it "
                + "contradicts the pattern. Treat the example as wrong, not the pattern.";
    }

    /**
     * Whether the reasoner refused the ontology rather than answering about it.
     *
     * <p>{@code NOT_OWL_2_DL} is the token the soundness table records for that, written by the
     * generator rather than by a reasoner - HermiT signals it by throwing.
     */
    private boolean isOutsideOwl2Dl() {
        return unsatisfiable.contains("NOT_OWL_2_DL") || mergedUnsatisfiable.contains(
                "NOT_OWL_2_DL");
    }

    /** Whether the pattern ships without a worked example. Not a defect on its own. */
    public boolean hasNoExample() {
        return "-".equals(exampleAxioms);
    }

    private static synchronized Map<String, ReasonerVerdict> table() {
        if (loaded == null) {
            InputStream stream =
                    ReasonerVerdict.class.getClassLoader().getResourceAsStream(RESOURCE);
            if (stream == null) {
                loaded = Collections.emptyMap();
            } else {
                try {
                    loaded = read(new InputStreamReader(stream, StandardCharsets.UTF_8));
                } catch (IOException cannotRead) {      // NOPMD - absence is not a finding
                    loaded = Collections.emptyMap();
                } finally {
                    try {
                        stream.close();
                    } catch (IOException ignored) {      // NOPMD - nothing to do about it
                        loaded = loaded == null ? Collections.<String, ReasonerVerdict>emptyMap()
                                : loaded;
                    }
                }
            }
        }
        return loaded;
    }

    /**
     * Parses the soundness table.
     *
     * <p>Its columns are id, imports, pattern_axioms, pattern_consistent, pattern_unsat,
     * example_axioms, merged_consistent, merged_unsat, notes - the order
     * {@code PatternSoundness.HEADER} writes. A short row is skipped rather than guessed at.
     */
    static Map<String, ReasonerVerdict> read(Reader source) throws IOException {
        Map<String, ReasonerVerdict> found = new LinkedHashMap<String, ReasonerVerdict>();
        BufferedReader lines = new BufferedReader(source);
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            if (line.isEmpty() || line.charAt(0) == '#' || line.startsWith("id\t")) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (cells.length < 8) {
                continue;
            }
            String unsatisfiable = "none".equals(cells[4]) ? "" : cells[4];
            String mergedUnsatisfiable =
                    "none".equals(cells[7]) || "-".equals(cells[7]) ? "" : cells[7];
            found.put(cells[0], new ReasonerVerdict(cells[0], "true".equals(cells[3]),
                    unsatisfiable, cells[6], mergedUnsatisfiable, cells[5]));
        }
        return found;
    }

}
