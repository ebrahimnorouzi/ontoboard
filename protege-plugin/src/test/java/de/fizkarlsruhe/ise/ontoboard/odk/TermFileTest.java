package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;

/**
 * Editing a term list without subtracting from it.
 *
 * <p>The measurement that forced this design: on a real repository's {@code iao_terms.txt} - 18
 * lines, 13 terms - reading the terms and writing them back through
 * {@link ImportModules#writeTerms} leaves 15 of those lines gone. Every trailing {@code # label}
 * comment and both of the file's own headers, replaced by a template and re-sorted. Across that
 * project all 50 terms in five populated lists carry a trailing comment, so there is nothing
 * unusual about the file that was mangled.
 */
class TermFileTest {

    /** A term list shaped the way a real one is: headers, labels, a blank line. */
    private static final String REAL = String.join("\n",
            "# Terms extracted into iao_import.owl",
            "# Source: http://purl.obolibrary.org/obo/iao.owl",
            "http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum",
            "http://purl.obolibrary.org/obo/IAO_0000003 # Measurement unit label",
            "",
            "http://purl.obolibrary.org/obo/IAO_0000429 # email address",
            "");

    private static List<String> linesOf(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    /** Every line is classified, and the classification is what drives the editing. */
    @Test
    void everyLineIsClassified() {
        List<TermFile.Line> lines = TermFile.linesIn(REAL);

        assertEquals(6, lines.size(), lines.toString());
        assertEquals(TermFile.Kind.COMMENT, lines.get(0).getKind());
        assertEquals(TermFile.Kind.COMMENT, lines.get(1).getKind());
        assertEquals(TermFile.Kind.TERM, lines.get(2).getKind());
        assertEquals(TermFile.Kind.BLANK, lines.get(4).getKind());
        assertEquals(TermFile.Kind.TERM, lines.get(5).getKind());

        assertEquals("http://purl.obolibrary.org/obo/IAO_0000109",
                lines.get(2).getTerm().toString());
        assertEquals("measurement datum", lines.get(2).getComment());
        assertEquals(3, lines.get(2).getNumber(), "1-based, as an editor shows it");
    }

    /** The terms, in the file's own order - which is not sorted. */
    @Test
    void theTermsKeepTheFilesOrder() {
        List<IRI> terms = TermFile.termsIn(REAL);

        assertEquals(3, terms.size());
        assertEquals("http://purl.obolibrary.org/obo/IAO_0000109", terms.get(0).toString());
        assertEquals("http://purl.obolibrary.org/obo/IAO_0000003", terms.get(1).toString(),
                "0000003 follows 0000109 in the real file; sorting would reorder the diff");
    }

    /**
     * Adding a term keeps every existing line, and adds exactly one.
     *
     * <p>Measured as a subsequence rather than positionally: an insert shifts every following
     * line, so comparing line N to line N answers the wrong question. My first measurement of
     * this reported "5 lines differing" for a one-line insert for exactly that reason.
     */
    @Test
    void addingKeepsEveryExistingLine() {
        String after = TermFile.with(REAL,
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");

        List<String> before = new ArrayList<String>(linesOf(REAL));
        List<String> now = new ArrayList<String>(linesOf(after));
        for (String line : new ArrayList<String>(before)) {
            if (now.remove(line)) {
                before.remove(line);
            }
        }
        assertEquals("[]", before.toString(), "an original line went missing");
        assertEquals(1, now.size(), "exactly one line added: " + now);
    }

    /**
     * The new entry is annotated when the file annotates everything, and bare when it does not.
     *
     * <p>A file whose convention is a label on every line should not acquire a bare entry, and a
     * file with none should not acquire the project's first.
     */
    @Test
    void theNewEntryFollowsTheFilesOwnConvention() {
        String annotated = TermFile.with(REAL,
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");
        assertTrue(annotated.contains("IAO_0000027 # data item"), annotated);

        String bare = "http://purl.obolibrary.org/obo/IAO_0000109\n";
        String added = TermFile.with(bare,
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");
        assertTrue(added.contains("IAO_0000027\n"), added);
        assertFalse(added.contains("# data item"),
                "this file annotates nothing, so neither should the new line: " + added);
    }

    /**
     * A new term goes after the last term, not at the end of the file.
     *
     * <p>A term list commonly ends with a comment, and an entry appended under one would read as
     * though that comment introduced it.
     */
    @Test
    void aNewTermGoesAfterTheLastTermNotTheLastLine() {
        String trailing = String.join("\n",
                "http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum",
                "# everything below here is deliberate",
                "") + "\n";

        String after = TermFile.with(trailing,
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");

        List<TermFile.Line> lines = TermFile.linesIn(after);
        assertEquals(TermFile.Kind.TERM, lines.get(1).getKind(), after);
        assertEquals(TermFile.Kind.COMMENT, lines.get(2).getKind(),
                "the closing comment stays last: " + after);
    }

    /** Removing a line removes that line and nothing else. */
    @Test
    void removingTakesOnlyTheLineAsked() {
        String after = TermFile.without(REAL, 3);

        assertFalse(after.contains("IAO_0000109"), after);
        assertTrue(after.contains("# Terms extracted into iao_import.owl"), "the header stays");
        assertTrue(after.contains("# Source: http://purl.obolibrary.org/obo/iao.owl"));
        assertTrue(after.contains("IAO_0000003 # Measurement unit label"),
                "the other terms keep their labels");
        assertEquals(5, TermFile.linesIn(after).size());
    }

    /** A line number outside the file is refused rather than silently doing nothing. */
    @Test
    void anImpossibleLineIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TermFile.without(REAL, 0));
        assertThrows(IllegalArgumentException.class, () -> TermFile.without(REAL, 99));
    }

    /** A term already present is refused, with the line it is on. */
    @Test
    void aDuplicateIsRefusedAndSaysWhere() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TermFile.with(REAL,
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000109"), "again"));

        assertTrue(refused.getMessage().contains("line 3"), refused.getMessage());
    }

    /**
     * A line that was meant to be a term and is not is reported, not skipped.
     *
     * <p>Skipping means the module is quietly missing it, and the person who typed it is the
     * only one who can say what it should have been.
     */
    @Test
    void aMalformedLineIsReported() {
        String broken = String.join("\n",
                "http://purl.obolibrary.org/obo/IAO_0000109 # fine",
                "this is not an iri at all",
                "") + "\n";

        List<TermFile.Line> bad = TermFile.malformedIn(broken);

        assertEquals(1, bad.size(), bad.toString());
        assertEquals(2, bad.get(0).getNumber());
        assertEquals(1, TermFile.termsIn(broken).size(), "the good line still reads");
    }

    /** A comment written without a hash still leaves the term readable - ODK's own format. */
    @Test
    void aTermFollowedByBareTextStillReads() {
        String loose = "http://purl.obolibrary.org/obo/IAO_0000109 measurement datum\n";

        assertEquals(1, TermFile.termsIn(loose).size());
        assertEquals("http://purl.obolibrary.org/obo/IAO_0000109",
                TermFile.termsIn(loose).get(0).toString());
    }

    /** Line endings are preserved, because changing them makes the whole file a diff. */
    @Test
    void windowsLineEndingsSurvive() {
        String crlf = "# head\r\nhttp://purl.obolibrary.org/obo/IAO_0000109 # one\r\n";

        String added = TermFile.with(crlf,
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");

        assertTrue(added.contains("\r\n"), "CRLF kept");
        assertFalse(added.replace("\r\n", "").contains("\n"), "no bare LF introduced: " + added);
    }

    /** An empty file takes its first term. */
    @Test
    void anEmptyFileTakesATerm() {
        assertEquals("http://purl.obolibrary.org/obo/IAO_0000027\n",
                TermFile.with("", IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"),
                        "data item"));
        assertTrue(TermFile.linesIn("").isEmpty());
        assertTrue(TermFile.linesIn(null).isEmpty());
    }

    /** A file of only comments takes its first term at the end. */
    @Test
    void aFileOfOnlyCommentsTakesATermAtTheEnd() {
        String after = TermFile.with("# nothing here yet\n",
                IRI.create("http://purl.obolibrary.org/obo/IAO_0000027"), "data item");

        assertEquals(2, TermFile.linesIn(after).size(), after);
        assertEquals(TermFile.Kind.TERM, TermFile.linesIn(after).get(1).getKind());
    }

    /** Nothing to add is refused rather than writing an empty line. */
    @Test
    void noTermIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> TermFile.with(REAL, null, "x"));
    }
}
