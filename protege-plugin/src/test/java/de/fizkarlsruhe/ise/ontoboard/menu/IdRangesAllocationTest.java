package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.IdRanges;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Allocating an identifier block to a new editor, on a real ODK ranges file.
 *
 * <p>The mechanism is worth nothing if adding a person means hand-editing an OWL file, which is
 * what it meant until now: the write half of {@code IdRanges} had been written and tested since it
 * was created and its only caller was the project scaffold. Meanwhile the plugin's own view could
 * tell you that you had no block and offer no way to get one.
 *
 * <p>What the stakes are: without per-editor ranges two collaborators mint {@code MWO_0000001}
 * independently and both are right, so one identifier names two concepts in two working copies and
 * the collision surfaces at merge time as a conflict nobody can resolve without throwing one
 * meaning away.
 */
class IdRangesAllocationTest {

    /** The reference file, from a working project - comment style, prefix block and all. */
    private static File realRangesFile() {
        File fixture = new File("src/test/resources/fixture-mwo-idranges.owl");
        assertTrue(fixture.isFile(), "missing fixture: " + fixture.getAbsolutePath());
        return fixture;
    }

    private static IdRanges parse(File file) throws Exception {
        return IdRanges.parse(new String(Files.readAllBytes(file.toPath()),
                Charset.forName("UTF-8")));
    }

    // ---------- the block size offered ----------

    /**
     * Everybody should get the same allowance, so the default is what the project already uses
     * rather than a number this plugin invented.
     */
    @Test
    void theSuggestedBlockMatchesWhatTheProjectAlreadyAllocates() throws Exception {
        IdRanges ranges = parse(realRangesFile());
        IdRanges.Range first = ranges.getRanges().get(0);

        assertEquals(first.getUpper() - first.getLower() + 1,
                IdRangesAction.suggestedBlockSize(realRangesFile()));
    }

    @Test
    void aFileWithNoRangesFallsBackToTheOdkDefault(@TempDir File directory) throws Exception {
        File empty = new File(directory, "abc-idranges.owl");
        Files.write(empty.toPath(), IdRanges.create("http://x.org/abc-idranges.owl", "ABC",
                "http://x.org/ABC_", 7).toManchester().getBytes("UTF-8"));

        assertEquals(1000, IdRangesAction.suggestedBlockSize(empty));
    }

    @Test
    void somethingThatIsNotARangesFileFallsBackRatherThanThrowing(@TempDir File directory)
            throws Exception {
        File junk = new File(directory, "junk.owl");
        Files.write(junk.toPath(), "this is not an ID ranges file".getBytes("UTF-8"));

        assertEquals(1000, IdRangesAction.suggestedBlockSize(junk));
        assertEquals(1000, IdRangesAction.suggestedBlockSize(new File(directory, "absent.owl")));
    }

    // ---------- the allocation itself, through the file ----------

    /**
     * The whole point: a new editor gets a block, the file is written, and reading it back gives
     * them one. This is the round trip the feature is, end to end.
     */
    @Test
    void anewEditorGetsABlockThatSurvivesBeingWrittenAndReadBack(@TempDir File directory)
            throws Exception {
        File working = new File(directory, "mwo-idranges.owl");
        Files.copy(realRangesFile().toPath(), working.toPath());
        IdRanges before = parse(working);
        assertNull(before.rangeFor("newcomer@example.org"),
                "the fixture should not already know this editor");

        IdRanges updated = before.withRangeFor("newcomer@example.org", 1000);
        Files.write(working.toPath(), updated.toManchester().getBytes("UTF-8"));

        IdRanges reread = parse(working);
        IdRanges.Range allocated = reread.rangeFor("newcomer@example.org");
        assertNotNull(allocated, "the new block did not survive the round trip");
        assertEquals(1000, allocated.getUpper() - allocated.getLower() + 1);
    }

    /** Everybody who had a block before must still have the same one. */
    @Test
    void allocatingDoesNotDisturbTheBlocksAlreadyThere(@TempDir File directory) throws Exception {
        File working = new File(directory, "mwo-idranges.owl");
        Files.copy(realRangesFile().toPath(), working.toPath());
        IdRanges before = parse(working);

        IdRanges updated = before.withRangeFor("newcomer@example.org", 1000);
        Files.write(working.toPath(), updated.toManchester().getBytes("UTF-8"));
        IdRanges reread = parse(working);

        for (IdRanges.Range original : before.getRanges()) {
            IdRanges.Range still = reread.rangeFor(original.getAllocatedTo());
            assertNotNull(still, original.getAllocatedTo() + " lost their block");
            assertEquals(original.getLower(), still.getLower(),
                    original.getAllocatedTo() + "'s block moved");
            assertEquals(original.getUpper(), still.getUpper(),
                    original.getAllocatedTo() + "'s block changed size");
        }
    }

    /** The prefix and padding decide what every minted IRI looks like; they must not shift. */
    @Test
    void allocatingPreservesThePrefixAndThePadding(@TempDir File directory) throws Exception {
        File working = new File(directory, "mwo-idranges.owl");
        Files.copy(realRangesFile().toPath(), working.toPath());
        IdRanges before = parse(working);

        Files.write(working.toPath(),
                before.withRangeFor("newcomer@example.org", 1000).toManchester()
                        .getBytes("UTF-8"));
        IdRanges reread = parse(working);

        assertEquals(before.getIdPrefix(), reread.getIdPrefix());
        assertEquals(before.getIdDigits(), reread.getIdDigits());
        assertEquals(before.getOntologyIri(), reread.getOntologyIri());
    }

    /**
     * The one invariant that matters. Two overlapping blocks hand the same numbers to two people,
     * which is the exact failure the file exists to prevent.
     */
    @Test
    void noTwoEditorsEverShareANumber(@TempDir File directory) throws Exception {
        File working = new File(directory, "mwo-idranges.owl");
        Files.copy(realRangesFile().toPath(), working.toPath());
        IdRanges ranges = parse(working);

        for (String newcomer : new String[] {"alice@example.org", "bob@example.org",
                "carol@example.org"}) {
            ranges = ranges.withRangeFor(newcomer, 500);
        }
        Files.write(working.toPath(), ranges.toManchester().getBytes("UTF-8"));
        IdRanges reread = parse(working);

        for (IdRanges.Range one : reread.getRanges()) {
            for (IdRanges.Range other : reread.getRanges()) {
                if (one != other && one.getNumber() != other.getNumber()) {
                    assertFalse(one.overlaps(other),
                            one + " overlaps " + other + ", so two editors would mint the "
                                    + "same identifiers");
                }
            }
        }
    }

    /** The block a newcomer gets has to actually mint something. */
    @Test
    void theNewEditorCanMintFromTheirBlock(@TempDir File directory) throws Exception {
        File working = new File(directory, "mwo-idranges.owl");
        Files.copy(realRangesFile().toPath(), working.toPath());

        IdRanges updated = parse(working).withRangeFor("newcomer@example.org", 10);
        Files.write(working.toPath(), updated.toManchester().getBytes("UTF-8"));

        String minted = parse(working).mint("newcomer@example.org",
                java.util.Collections.<String>emptySet());

        assertNotNull(minted);
        assertTrue(minted.startsWith(updated.getIdPrefix()), minted);
    }

    /** Case is not the distinguishing feature of a person's name. */
    @Test
    void anEditorWhoAlreadyHasABlockIsRecognisedWhateverTheCase() throws Exception {
        IdRanges ranges = parse(realRangesFile());
        IdRanges.Range any = ranges.getRanges().get(0);

        assertNotNull(ranges.rangeFor(any.getAllocatedTo().toUpperCase()));
        assertNotNull(ranges.rangeFor(any.getAllocatedTo().toLowerCase()));
    }
}
