package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Claiming one of the ID ranges ODK seeds a project with.
 *
 * <p>ODK v1.6.1 writes {@code <id>-idranges.owl} with two ranges allocated to placeholders,
 * {@code ONTOLOGY-CREATOR} and {@code ADDITIONAL EDITOR}, for the people who start the project to
 * put their names in. Nobody mints under a placeholder's name, so until somebody does, every new
 * term in such a project was refused with "No ID range is allocated to you". OntoBoard now asks,
 * once, which range is yours and writes your name into it.
 *
 * <p>The file is ODK's and is committed, so the claim changes the one literal and nothing else -
 * not the indentation ODK's template leaves, not the {@code Datatype: rdf:PlainLiteral} after the
 * last range, not the line endings a Windows checkout gave it. Rewriting it through
 * {@link IdRanges#toManchester()} would change all three. Tested on ODK's own seeded file, from the
 * goldens, not on one written here.
 */
class IdRangesClaimTest {

    private static String seed() {
        return new String(Goldens.seedTree("eggboil").get("src/ontology/eggboil-idranges.owl"),
                StandardCharsets.UTF_8);
    }

    /** The lines of {@code after} that differ from {@code before}'s, as "before -> after". */
    private static List<String> changedLines(String before, String after) {
        String[] was = before.split("\n", -1);
        String[] is = after.split("\n", -1);
        assertEquals(was.length, is.length, "the claim added or removed lines");
        List<String> changed = new ArrayList<String>();
        for (int i = 0; i < was.length; i++) {
            if (!was[i].equals(is[i])) {
                changed.add(was[i] + " -> " + is[i]);
            }
        }
        return changed;
    }

    @Test
    void theSeedsRangesArePlaceholders() {
        assertTrue(IdRanges.parse(seed()).isOdkPlaceholders());
    }

    @Test
    void claimingChangesOneLine() {
        String text = seed();
        List<String> changed = changedLines(text, IdRanges.claim(text, "ONTOLOGY-CREATOR", "eno"));
        assertEquals(1, changed.size(), changed.toString());
        assertEquals("        allocatedto: \"ONTOLOGY-CREATOR\" -> "
                + "        allocatedto: \"eno\"", changed.get(0));
    }

    /** A Windows checkout of the project has the file with CRLF; the claim keeps every one. */
    @Test
    void claimingKeepsTheFilesLineEndings() {
        String crlf = seed().replace("\n", "\r\n");
        String claimed = IdRanges.claim(crlf, "ADDITIONAL EDITOR", "eno");
        assertEquals(IdRanges.claim(seed(), "ADDITIONAL EDITOR", "eno").replace("\n", "\r\n"),
                claimed);
        List<String> changed = changedLines(crlf, claimed);
        assertEquals(1, changed.size(), changed.toString());
        assertEquals("        allocatedto: \"ADDITIONAL EDITOR\"\r -> "
                + "        allocatedto: \"eno\"\r", changed.get(0));
    }

    @Test
    void theClaimedRangeIsTheEditorsAndTheOtherStaysAPlaceholder() {
        IdRanges claimed = IdRanges.parse(IdRanges.claim(seed(), "ONTOLOGY-CREATOR", "eno"));
        assertFalse(claimed.isOdkPlaceholders());
        IdRanges.Range mine = claimed.rangeFor("eno");
        assertEquals(1, mine.getNumber());
        assertEquals(0, mine.getLower());
        assertEquals(999999, mine.getUpper());
        assertEquals(1000000, claimed.rangeFor("ADDITIONAL EDITOR").getLower());
        assertEquals(IdRanges.parse(seed()).getOtherDeclarations(),
                claimed.getOtherDeclarations());
    }

    /** MatWerk's ranges carry people's names: nothing there is anybody's to claim. */
    @Test
    void rangesWithNamesAreNotPlaceholders() throws Exception {
        String named = new String(Files.readAllBytes(
                new File("src/test/resources/fixture-mwo-idranges.owl").toPath()),
                StandardCharsets.UTF_8);
        assertFalse(IdRanges.parse(named).isOdkPlaceholders());
    }

    /** No ranges at all is not ODK's seed, and there is nothing in it to claim. */
    @Test
    void aFileWithNoRangesIsNotPlaceholders() {
        assertFalse(IdRanges.create("https://example.org/x/x-idranges.owl", "X",
                "https://example.org/X_", 7).isOdkPlaceholders());
    }

    /** Somebody claimed it first - their name is in the file now, and it stays. */
    @Test
    void aRangeAlreadyClaimedCannotBeClaimedAgain() {
        String once = IdRanges.claim(seed(), "ONTOLOGY-CREATOR", "eno");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> IdRanges.claim(once, "ONTOLOGY-CREATOR", "someone else"));
        assertTrue(refused.getMessage().contains("ONTOLOGY-CREATOR"), refused.getMessage());
    }

    @Test
    void onlyOdksPlaceholdersCanBeClaimed() {
        String named = IdRanges.claim(seed(), "ONTOLOGY-CREATOR", "eno");
        assertThrows(IllegalArgumentException.class,
                () -> IdRanges.claim(named, "eno", "someone else"));
    }

    /** A quote would end the literal early, and the file would no longer read back. */
    @Test
    void aNameThatWouldBreakTheLiteralIsRefused() {
        for (String name : new String[] {"", "  ", "Jo \"JJ\" Smith", "back\\slash", "two\nlines"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> IdRanges.claim(seed(), "ONTOLOGY-CREATOR", name), name);
        }
    }

    /** An editor who has a range already does not take a second by claiming. */
    @Test
    void anEditorWhoHasARangeDoesNotClaimAnother() {
        String once = IdRanges.claim(seed(), "ONTOLOGY-CREATOR", "eno");
        assertThrows(IllegalArgumentException.class,
                () -> IdRanges.claim(once, "ADDITIONAL EDITOR", "eno"));
    }

    // ------------------------------------------------------------------ under whose name

    /**
     * Protege's own ID ranges (5.6 and later) find the person's range by the user name Protege
     * has for them, and say "No Id Range Found For Current User" when none is allocated to it.
     * Read from protege-editor-owl 5.6.9 (IdPolicyEntityCreationPreferencesUpdater,
     * DefaultUserNameProvider, UserNamePreferencesManager): git's user name for the ontology's
     * repository where Preferences &gt; User details asks for it (off unless ticked), else the
     * user name set there, else the account's; a range is theirs when its allocatedto equals
     * that name, case aside.
     */
    @Test
    void protegeTakesItsUserDetailsNameThenTheAccounts() {
        assertEquals("Ada Lovelace",
                ProtegeUserName.decided(false, "ada-git", "Ada Lovelace", "ada"));
        assertEquals("ada", ProtegeUserName.decided(false, "ada-git", null, "ada"));
        assertEquals("ada", ProtegeUserName.decided(false, null, "  ", "ada"));
        assertEquals("ada-git", ProtegeUserName.decided(true, "ada-git", "Ada Lovelace", "ada"));
        assertEquals("Ada Lovelace", ProtegeUserName.decided(true, " ", "Ada Lovelace", "ada"),
                "git has no name to give: as without it");
    }

    /**
     * The board's claim is written under that name - not under the collaboration display name,
     * which Protege does not know - so after the claim Protege finds the range, and does not ask.
     */
    @Test
    void theBoardClaimsUnderTheNameProtegeLooksUp() {
        String protege = ProtegeUserName.decided(false, null, "Ada Lovelace", "ada");
        String editor = ProtegeUserName.forRanges(protege, "Countess of Lovelace",
                IdRanges.parse(seed()));

        String claimed = IdRanges.claim(seed(), "ONTOLOGY-CREATOR", editor);

        assertTrue(claimed.contains("allocatedto: \"Ada Lovelace\""), claimed);
        assertFalse(claimed.contains("Countess"), claimed);
        assertEquals("Ada Lovelace", IdRanges.parse(claimed).rangeFor("ADA LOVELACE")
                .getAllocatedTo(), "found as Protege finds it: case aside");
    }

    /**
     * A range an earlier OntoBoard claimed under the display name stays the one the board
     * mints from while none is allocated to Protege's name; where both have one, Protege's.
     */
    @Test
    void aRangeClaimedUnderTheDisplayNameBeforeIsStillMintedFrom() {
        String before = IdRanges.claim(seed(), "ONTOLOGY-CREATOR", "Countess of Lovelace");

        assertEquals("Countess of Lovelace", ProtegeUserName.forRanges("Ada Lovelace",
                "Countess of Lovelace", IdRanges.parse(before)));
        assertEquals("Ada Lovelace", ProtegeUserName.forRanges("Ada Lovelace",
                "Countess of Lovelace", IdRanges.parse(IdRanges.claim(before,
                        "ADDITIONAL EDITOR", "Ada Lovelace"))), "Protege's own comes first");
        assertEquals("Ada Lovelace", ProtegeUserName.forRanges("Ada Lovelace",
                "Countess of Lovelace", null), "a project without ranges");
        assertEquals("Ada Lovelace", ProtegeUserName.forRanges("Ada Lovelace", null,
                IdRanges.parse(before)), "no display name");
    }

    /** The ranges a project's name is decided by are the ones beside its ontology. */
    @Test
    void theRangesBesideTheOntologyDecide(@org.junit.jupiter.api.io.TempDir File folder)
            throws Exception {
        File ontology = new File(folder, "eggboil-edit.owl");
        Files.write(ontology.toPath(), "Ontology()\n".getBytes(StandardCharsets.UTF_8));
        File ranges = new File(folder, "eggboil-idranges.owl");
        Files.write(ranges.toPath(), IdRanges.claim(seed(), "ONTOLOGY-CREATOR",
                "Countess of Lovelace").getBytes(StandardCharsets.UTF_8));

        assertEquals("Countess of Lovelace",
                ProtegeUserName.forProjectOf(ontology, "Countess of Lovelace"));

        Files.write(ranges.toPath(), seed().getBytes(StandardCharsets.UTF_8));
        assertFalse("Countess of Lovelace".equals(ProtegeUserName.forProjectOf(ontology,
                "Countess of Lovelace")), "no range of the display name's: Protege's name");
    }
}
