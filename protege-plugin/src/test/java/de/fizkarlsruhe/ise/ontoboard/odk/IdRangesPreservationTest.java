package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Allocating a range must not subtract anything from the file.
 *
 * <p>{@code toManchester} is a canonical renderer: it emits a fixed template, so whatever the
 * template has no field for is gone the moment a range is allocated. Measured by round-tripping
 * a real ODK project's ranges file, that was exactly one line -
 * {@code Datatype: rdf:PlainLiteral}, declared after the template's closing
 * {@code Datatype: xsd:integer} - dropped silently, three lines below a comment promising "a
 * one-range diff rather than reformatting the whole file".
 *
 * <p>One line, and nobody reads a ranges file afterwards to check. Two defences now: the
 * declarations are carried through, and anything that still would not survive stops the write
 * instead of being lost.
 */
class IdRangesPreservationTest {

    /** A ranges file in ODK's shape, with a declaration the template does not model. */
    private static String fileWithAnExtraDeclaration() {
        return IdRanges.create("http://purl.obolibrary.org/obo/mwo/mwo-idranges.owl",
                        "mwo", "http://purl.obolibrary.org/obo/MWO_", 7)
                .withRangeFor("Alice", 1000)
                .toManchester()
                + "Datatype: rdf:PlainLiteral\n";
    }

    /** The measured loss, and the measured fix. */
    @Test
    void aDeclarationTheTemplateDoesNotModelSurvivesAllocation() {
        IdRanges parsed = IdRanges.parse(fileWithAnExtraDeclaration());

        assertEquals(java.util.Arrays.asList("Datatype: rdf:PlainLiteral"),
                parsed.getOtherDeclarations(),
                "the parser has to keep what the renderer cannot reconstruct");

        String after = parsed.withRangeFor("Bob", 1000).toManchester();

        assertTrue(after.contains("Datatype: rdf:PlainLiteral"),
                "allocating a range dropped a declaration:\n" + after);
        assertTrue(after.contains("Bob"), "and it still did the thing it was asked to do");
        assertTrue(after.contains("Alice"), "without losing the range that was already there");
    }

    /** The declaration comes after the template's own closing datatype, where ODK puts it. */
    @Test
    void theCarriedDeclarationComesLast() {
        String after = IdRanges.parse(fileWithAnExtraDeclaration()).toManchester();

        assertTrue(after.indexOf("Datatype: rdf:PlainLiteral")
                        > after.indexOf("Datatype: xsd:integer"),
                "order matters for a diff to stay small:\n" + after);
    }

    /** The template's own lines are not mistaken for extras and emitted twice. */
    @Test
    void theTemplatesOwnDatatypesAreNotDuplicated() {
        String after = IdRanges.parse(fileWithAnExtraDeclaration()).toManchester();

        assertEquals(1, countOf(after, "Datatype: xsd:integer"), after);
        assertEquals(1, countOf(after, "Datatype: rdf:PlainLiteral"), after);
        assertEquals(1, countOf(after, "Datatype: idrange:1"), after);
    }

    /** A file with nothing unusual in it gains nothing. */
    @Test
    void anOrdinaryFileCarriesNothingExtra() {
        IdRanges plain = IdRanges.parse(IdRanges.create("http://example.org/o-idranges.owl",
                "o", "http://example.org/O_", 7).withRangeFor("Alice", 1000).toManchester());

        assertTrue(plain.getOtherDeclarations().isEmpty(),
                plain.getOtherDeclarations().toString());
    }

    // ---------- the backstop ----------

    /**
     * The guard notices a line that would go, and ignores one that merely moved.
     *
     * <p>Indentation is excluded on purpose: the renderer's layout differs from ODK's by a few
     * spaces in the annotation blocks, and reporting that as data loss would make the check
     * noise - and a noisy safety check is one somebody turns off.
     */
    @Test
    void theGuardSeesALostLineAndNotAReindentedOne() {
        String original = "Datatype: idrange:1\n    Annotations: \n        allocatedto: \"Alice\"\n"
                + "Datatype: xsd:integer\nDatatype: rdf:PlainLiteral\n";
        String reindented = "Datatype: idrange:1\n  Annotations: \n  allocatedto: \"Alice\"\n"
                + "Datatype: xsd:integer\nDatatype: rdf:PlainLiteral\n";
        String lossy = "Datatype: idrange:1\n    Annotations: \n        allocatedto: \"Alice\"\n"
                + "Datatype: xsd:integer\n";

        assertTrue(IdRanges.whatWouldBeLost(original, reindented).isEmpty(),
                "whitespace is not data loss: "
                        + IdRanges.whatWouldBeLost(original, reindented));

        List<String> lost = IdRanges.whatWouldBeLost(original, lossy);
        assertEquals(java.util.Arrays.asList("Datatype: rdf:PlainLiteral"), lost);
    }

    /** A real allocation passes its own guard, or the feature would refuse itself. */
    @Test
    void aRealAllocationIsNotReportedAsLossy() {
        String original = fileWithAnExtraDeclaration();
        String after = IdRanges.parse(original).withRangeFor("Bob", 1000).toManchester();

        assertTrue(IdRanges.whatWouldBeLost(original, after).isEmpty(),
                "the fix must satisfy the backstop: "
                        + IdRanges.whatWouldBeLost(original, after));
    }

    /** Blank lines are not content, so losing one is not a loss. */
    @Test
    void blankLinesAreNotCounted() {
        assertTrue(IdRanges.whatWouldBeLost("a\n\n\nb\n", "a\nb\n").isEmpty());
    }

    /** Nulls do not throw: the guard runs on a path that is already handling a failure. */
    @Test
    void nullsAreNotAnError() {
        assertTrue(IdRanges.whatWouldBeLost(null, "x").isEmpty());
        assertTrue(IdRanges.whatWouldBeLost("x", null).isEmpty());
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
