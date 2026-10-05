package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;

/**
 * The format ODK and ROBOT actually write term lists in.
 *
 * <p>Each line is an IRI, optionally followed by a comment naming the term, because a bare
 * column of {@code IAO_0000109} is unreviewable in a pull request:
 *
 * <pre>
 *   http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum
 * </pre>
 *
 * <p>Until 1.77.0 OntoBoard rejected every one of those lines. The test for "is this an IRI"
 * included "contains no space" and was applied to the whole line, so a commented list read as
 * entirely malformed. On a real ODK project the effect was total - five term lists, 49 terms,
 * none readable - and invisible, because the Imports table went on reporting all five as having
 * a term list, so refusing to rebuild looked like nothing happening.
 *
 * <p>The fixtures here are the real thing: the lines come from an actual project's
 * {@code iao_terms.txt}.
 */
class TermListFormatTest {

    private static File listOf(File directory, String... lines) throws Exception {
        File file = new File(directory, "iao_terms.txt");
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(file.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static List<String> asStrings(List<IRI> terms) {
        List<String> strings = new ArrayList<String>();
        for (IRI term : terms) {
            strings.add(term.toString());
        }
        return strings;
    }

    /** The case that was wholly broken, in the shape a real file has it. */
    @Test
    void aTermWithATrailingCommentIsRead(@TempDir File directory) throws Exception {
        File list = listOf(directory,
                "# Terms to extract from IAO",
                "http://purl.obolibrary.org/obo/IAO_0000109 # measurement datum",
                "http://purl.obolibrary.org/obo/IAO_0000003 # Measurement unit label",
                "http://purl.obolibrary.org/obo/IAO_0000429 # email address");

        List<IRI> terms = ImportModules.readTerms(list);

        assertEquals(3, terms.size(), asStrings(terms).toString());
        assertTrue(asStrings(terms).contains("http://purl.obolibrary.org/obo/IAO_0000109"),
                asStrings(terms).toString());
        assertTrue(ImportModules.malformedIn(list).isEmpty(),
                "a commented term is the normal format, not a malformation: "
                        + ImportModules.malformedIn(list));
    }

    /**
     * A hash inside an IRI is a fragment, not a comment.
     *
     * <p>The reason the comment is stripped by splitting on whitespace rather than on the first
     * {@code #}: that would turn {@code http://example.org/o#Thing} into
     * {@code http://example.org/o}, which is a different term and would extract the wrong thing
     * without failing.
     */
    @Test
    void aFragmentIsNotAComment(@TempDir File directory) throws Exception {
        File list = listOf(directory,
                "http://example.org/o#Thing",
                "http://example.org/o#Other # with a comment as well");

        assertEquals(
                java.util.Arrays.asList("http://example.org/o#Thing",
                        "http://example.org/o#Other"),
                asStrings(ImportModules.readTerms(list)));
    }

    /** A plain list still reads exactly as it did. */
    @Test
    void anUncommentedListIsUnaffected(@TempDir File directory) throws Exception {
        File list = listOf(directory,
                "http://purl.obolibrary.org/obo/IAO_0000109",
                "http://purl.obolibrary.org/obo/IAO_0000003");

        assertEquals(2, ImportModules.readTerms(list).size());
        assertTrue(ImportModules.malformedIn(list).isEmpty());
    }

    /** Tabs separate a comment as well as spaces do. */
    @Test
    void aTabSeparatedCommentIsAlsoStripped(@TempDir File directory) throws Exception {
        File list = listOf(directory, "http://purl.obolibrary.org/obo/IAO_0000109\t# datum");

        assertEquals(java.util.Arrays.asList("http://purl.obolibrary.org/obo/IAO_0000109"),
                asStrings(ImportModules.readTerms(list)));
    }

    /**
     * Something that is not an IRI is still reported, which is the half that must not regress.
     *
     * <p>Loosening the reader is only safe if it stays able to say "this line is wrong". The
     * whole point of {@code malformedIn} is that nothing is dropped in silence.
     */
    @Test
    void aLineThatIsNotATermIsStillReportedAsMalformed(@TempDir File directory) throws Exception {
        File list = listOf(directory,
                "http://purl.obolibrary.org/obo/IAO_0000109 # fine",
                "measurement datum",
                "IAO_0000109");

        assertEquals(1, ImportModules.readTerms(list).size());
        assertEquals(java.util.Arrays.asList("measurement datum", "IAO_0000109"),
                ImportModules.malformedIn(list),
                "a label with no IRI, and a bare local name, are both still wrong");
    }

    /** Comment-only and blank lines are neither terms nor malformations. */
    @Test
    void commentsAndBlankLinesAreNeither(@TempDir File directory) throws Exception {
        File list = listOf(directory,
                "# Source: http://purl.obolibrary.org/obo/iao.owl",
                "",
                "   ",
                "http://purl.obolibrary.org/obo/IAO_0000109");

        assertEquals(1, ImportModules.readTerms(list).size());
        assertTrue(ImportModules.malformedIn(list).isEmpty(),
                ImportModules.malformedIn(list).toString());
    }

    /** What OntoBoard writes, it can read back - with or without comments in between. */
    @Test
    void whatIsWrittenCanBeRead(@TempDir File directory) throws Exception {
        File written = ImportModules.writeTerms(directory, "iao",
                java.util.Arrays.asList(IRI.create("http://purl.obolibrary.org/obo/IAO_0000109"),
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000003")),
                "http://purl.obolibrary.org/obo/iao.owl");

        assertEquals(2, ImportModules.readTerms(written).size());
        assertTrue(ImportModules.malformedIn(written).isEmpty());
    }
}
