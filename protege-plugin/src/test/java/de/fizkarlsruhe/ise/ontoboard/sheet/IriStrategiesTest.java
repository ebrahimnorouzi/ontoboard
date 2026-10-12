package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The ways of forming an identifier that are not a number.
 *
 * <p>Designed against measurement rather than taste. In the live MatWerk workbook 8,406 of 8,471
 * identifiers are a 13-digit epoch-millisecond base followed by a counter, so a timestamp is not
 * a new idea here - it is the incumbent scheme, and these tests hold the new one to its shape.
 * The label-derived ones are measured against that data's hazards too: its {@code city} tab
 * distinguishes a city from the city's name by a trailing underscore, which a careless slug
 * merges on half the sheet.
 *
 * <p>Every test fixes the instant with {@link IriGenerator.Moment#at}, which is the project's
 * rule about clocks ({@code Provenance.today()}: "the ones under test pass their own so the
 * output is reproducible") and is also what keeps {@code mint} idempotent in production.
 */
class IriStrategiesTest {

    /** 2026-10-12T08:45:30.000Z, which is what the stamps below are of. */
    private static final long WHEN = 1791103530000L;

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** Shaped like a live MatWerk tab: full IRIs, epoch-millisecond local parts, mixed widths. */
    private static SheetBook matwerk() {
        SheetBook book = SheetBook.empty();
        book.add("agent", null, rows(
                new String[] {"#", "TYPE", "Label", "City"},
                new String[] {"ID", "TYPE", "A rdfs:label", "I obo:RO_0001025"},
                new String[] {MW + "17451651928121", "obo:OBI_0000245", "Fraunhofer", "Berlin"},
                new String[] {MW + "174516519281210", "obo:OBI_0000245", "Max Planck",
                              "Hamburg"},
                new String[] {MW + "1745165192813100", "obo:OBI_0000245", "Jülich", "Jülich"},
                new String[] {MW + "17451651928131", "obo:OBI_0000245", "Karlsruhe_", ""}),
                '\t');
        return book;
    }

    private static IriGenerator minting(IriGenerator.Source source, SheetBook book) {
        return IriGenerator.using(source, book, null, null, "joergwa", "agent",
                IriGenerator.Moment.at(WHEN));
    }

    // ---------- the timestamp, in the shape the data already has ----------

    @Test
    void aTimestampIsTheEpochMillisecondBaseAndACounter() {
        String minted = minting(IriGenerator.Source.TIMESTAMP, matwerk()).mint(1).get(0);

        assertEquals(MW + "1791103530000001", minted);
        assertTrue(minted.substring(MW.length()).matches("\\d{16}"),
                "the live data is 14 to 16 digits; this is 13 + a padded counter: " + minted);
    }

    /**
     * THE REGRESSION A TIMESTAMP INTRODUCES. The old loop needed no duplicate guard because a
     * counter cannot produce the same number twice. A clock can: five rows filled in the same
     * millisecond would all have got the same identifier, written into all five rows with no
     * error anywhere - {@code SheetAudit} only reports duplicates within one sheet, and
     * {@code fillBlanks} checks nothing after it writes.
     */
    @Test
    void fiveAtOnceWithTheClockStoppedAreStillFiveDifferentIdentifiers() {
        List<String> minted = minting(IriGenerator.Source.TIMESTAMP, matwerk()).mint(5);

        assertEquals(5, new LinkedHashSet<String>(minted).size(), minted.toString());
        assertEquals(Arrays.asList(
                MW + "1791103530000001", MW + "1791103530000002", MW + "1791103530000003",
                MW + "1791103530000004", MW + "1791103530000005"), minted);
    }

    /** Padded, so that sorting them as text gives the same order as sorting them as numbers. */
    @Test
    void theCounterIsPaddedSoTheIdentifiersSortTheWayTheyWereMade() {
        List<String> minted = minting(IriGenerator.Source.TIMESTAMP, matwerk()).mint(12);

        List<String> sorted = new ArrayList<String>(minted);
        java.util.Collections.sort(sorted);
        assertEquals(minted, sorted,
                "unpadded, ...0002 would sort after ...00010, which is the ordering defect the "
                        + "existing data has");
    }

    @Test
    void aTimestampCanCarryWhoMadeIt() {
        String minted =
                minting(IriGenerator.Source.TIMESTAMP_AND_EDITOR, matwerk()).mint(1).get(0);

        assertEquals(MW + "1791103530000001-joergwa", minted);
    }

    /**
     * And refuses when nobody has said who is editing, rather than putting the fallback name into
     * everybody's identifiers.
     */
    @Test
    void aTimestampWithNoEditorToNameIsRefused() {
        IriGenerator minting = IriGenerator.using(IriGenerator.Source.TIMESTAMP_AND_EDITOR,
                matwerk(), null, null, IriGenerator.ANONYMOUS, "agent",
                IriGenerator.Moment.at(WHEN));

        assertFalse(minting.canMint());
        assertTrue(minting.getRefusal().contains("who made the row"), minting.getRefusal());
        assertEquals(IriGenerator.Source.TIMESTAMP_AND_EDITOR, minting.getSource(),
                "a refusal must name the source that refused, or a dialog blames the wrong one");
    }

    /** A display name with spaces or non-ASCII is not IRI-safe and must be made so. */
    @Test
    void theEditorsNameIsMadeSafeForAnIri() {
        String minted = IriGenerator.using(IriGenerator.Source.TIMESTAMP_AND_EDITOR, matwerk(),
                null, null, "Jörg Waitelonis", "agent", IriGenerator.Moment.at(WHEN))
                .mint(1).get(0);

        assertEquals(MW + "1791103530000001-joerg_waitelonis", minted);
    }

    // ---------- UUIDs ----------

    @Test
    void aRandomUuidIsAUuidUnderTheSheetsOwnNamespace() {
        String minted = minting(IriGenerator.Source.UUID_RANDOM, matwerk()).mint(1).get(0);

        assertTrue(minted.startsWith(MW), minted);
        assertTrue(minted.substring(MW.length()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), minted);
    }

    @Test
    void severalRandomUuidsAreAllDifferent() {
        List<String> minted = minting(IriGenerator.Source.UUID_RANDOM, matwerk()).mint(6);

        assertEquals(6, new LinkedHashSet<String>(minted).size(), minted.toString());
    }

    /**
     * The purity the class promises, which a random source is the obvious way to break:
     * {@code describe()} previews by minting, and the dialog writes later.
     */
    @Test
    void mintingTwiceFromOneGeneratorGivesTheSameUuidsSoThePreviewIsNotALie() {
        IriGenerator minting = minting(IriGenerator.Source.UUID_RANDOM, matwerk());

        assertEquals(minting.mint(3), minting.mint(3));
        assertTrue(minting.describe().contains(minting.mint(1).get(0)),
                "the dialog shows what gets written: " + minting.describe());
    }

    /** But two generators built separately are genuinely random, not a fixed sequence. */
    @Test
    void twoGeneratorsBuiltForRealDoNotAgree() {
        String one = IriGenerator.using(IriGenerator.Source.UUID_RANDOM, matwerk(), null, null,
                "joergwa", "agent", IriGenerator.Moment.now()).mint(1).get(0);
        String other = IriGenerator.using(IriGenerator.Source.UUID_RANDOM, matwerk(), null, null,
                "joergwa", "agent", IriGenerator.Moment.now()).mint(1).get(0);

        assertNotEquals(one, other);
    }

    /**
     * The label-derived UUID is version 5 under the namespace MatWerk's own harvester uses
     * ({@code template_builder.py}: {@code uuid5(12345678-1234-5678-1234-567812345678, key)}), so
     * a row filled in by hand and the same row harvested later are the same thing rather than
     * two. Reproduced here against the value Python's {@code uuid.uuid5} gives for that key.
     */
    @Test
    void aLabelDerivedUuidMatchesTheHarvestersOwnScheme() {
        SheetBook book = matwerk();
        List<String> minted = IriGenerator.using(IriGenerator.Source.UUID_FROM_LABEL, book, null,
                null, "joergwa", "agent", IriGenerator.Moment.at(WHEN))
                .mintFor(Arrays.asList("written-name|M.Sc. Sarath Menon"));

        assertEquals(MW + "53802ee2-fb2f-5eba-b946-387baa0c4091", minted.get(0),
                "this is uuid5 of that key under the harvester's namespace");
    }

    @Test
    void theSameLabelAlwaysGivesTheSameIdentifier() {
        IriGenerator minting = minting(IriGenerator.Source.UUID_FROM_LABEL, matwerk());

        assertEquals(minting.mintFor(Arrays.asList("sintering process")),
                minting.mintFor(Arrays.asList("sintering process")));
    }

    @Test
    void aRowWithNoLabelCannotHaveALabelDerivedIdentifier() {
        IriGenerator minting = minting(IriGenerator.Source.UUID_FROM_LABEL, matwerk());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> minting.mintFor(Arrays.asList("")));
        assertTrue(refused.getMessage().contains("no label"), refused.getMessage());
    }

    @Test
    void theLabelDerivedSourcesCannotMintWithoutKnowingTheRows() {
        assertThrows(IllegalStateException.class,
                () -> minting(IriGenerator.Source.UUID_FROM_LABEL, matwerk()).mint(2));
        assertThrows(IllegalStateException.class,
                () -> minting(IriGenerator.Source.LABEL_SLUG, matwerk()).mint(2));
    }

    // ---------- the readable slug, and the trap in it ----------

    @Test
    void aSlugIsTheLabelMadeSafe() {
        List<String> minted = minting(IriGenerator.Source.LABEL_SLUG, matwerk())
                .mintFor(Arrays.asList("sintering process"));

        assertEquals(MW + "sintering_process", minted.get(0));
    }

    /**
     * THE MEASURED TRAP. The {@code city} tab distinguishes a city from the city's name by a
     * trailing underscore: {@code Karlsruhe} is typed NFDI_0000106 and {@code Karlsruhe_} is
     * typed IAO_0000303, and they are different rows. 19 of that sheet's 38 rows are such pairs.
     * A slug that dropped or collapsed the underscore would merge half the sheet.
     */
    @Test
    void aTrailingUnderscoreIsPartOfTheNameAndSurvives() {
        List<String> minted = minting(IriGenerator.Source.LABEL_SLUG, matwerk())
                .mintFor(Arrays.asList("Karlsruhe", "Karlsruhe_"));

        assertEquals(MW + "karlsruhe", minted.get(0));
        assertEquals(MW + "karlsruhe_", minted.get(1));
        assertNotEquals(minted.get(0), minted.get(1));
    }

    /**
     * And runs are not collapsed, for the same reason: {@code "... and more…"} and
     * {@code "... and more..."} are both real labels in one real sheet.
     */
    @Test
    void twoLabelsThatDifferOnlyInPunctuationStayDifferent() {
        List<String> minted = minting(IriGenerator.Source.LABEL_SLUG, matwerk())
                .mintFor(Arrays.asList("and more…", "and more..."));

        assertNotEquals(minted.get(0), minted.get(1), minted.toString());
    }

    @Test
    void germanVowelsAreTransliteratedRatherThanThrownAway() {
        assertEquals("juelich", IriGenerator.slug("Jülich", 80));
        assertEquals("saarbruecken", IriGenerator.slug("Saarbrücken", 80));
        assertEquals("strasse", IriGenerator.slug("Straße", 80));
        assertEquals("190_c", IriGenerator.slug("190°C", 80));
    }

    @Test
    void aVeryLongLabelIsCutRatherThanBecomingAnIdentifierNobodyCanRead() {
        StringBuilder enormous = new StringBuilder();
        for (int at = 0; at < 300; at++) {
            enormous.append("word ");
        }

        assertEquals(80, IriGenerator.slug(enormous.toString(), 80).length());
    }

    /**
     * Two rows whose labels really are the same cannot both have the identifier. It refuses
     * rather than quietly appending a 2, because which of two things with one name should keep
     * it is not a tool's decision.
     */
    @Test
    void twoRowsWithTheSameLabelAreRefusedRatherThanSilentlyDisambiguated() {
        IriGenerator minting = minting(IriGenerator.Source.LABEL_SLUG, matwerk());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> minting.mintFor(Arrays.asList("Fraunhofer", "Fraunhofer")));
        assertTrue(refused.getMessage().contains("cannot share"), refused.getMessage());
    }

    // ---------- every source, every time ----------

    @Test
    void noSourceEverMintsOverSomethingThatExists() {
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            SheetBook book = matwerk();
            IriGenerator minting = minting(source, book);
            if (!minting.canMint()) {
                continue;
            }
            List<String> minted = source.needsALabel()
                    ? minting.mintFor(Arrays.asList("something new", "something else new"))
                    : minting.mint(2);
            Set<String> existing = IriGenerator.everyIdentifierIn(book);
            for (String each : minted) {
                assertFalse(existing.contains(each), source + " minted over " + each);
            }
            assertEquals(2, new LinkedHashSet<String>(minted).size(), source + ": " + minted);
        }
    }

    @Test
    void everySourceMintsAFullIriBecauseACurieBuildsToNothing() {
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            IriGenerator minting = minting(source, matwerk());
            if (!minting.canMint()) {
                continue;
            }
            String minted = source.needsALabel()
                    ? minting.mintFor(Arrays.asList("something new")).get(0)
                    : minting.mint(1).get(0);
            assertTrue(minted.startsWith("http://") || minted.startsWith("https://"),
                    source + " minted " + minted + ", which is not a full IRI - a CURIE in the "
                            + "ID column builds to nothing and says nothing");
        }
    }

    @Test
    void everySourceHasALabelThatRoundTripsAndSaysWhatItDoes() {
        Set<String> labels = new LinkedHashSet<String>();
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            assertFalse(source.getLabel().trim().isEmpty());
            assertTrue(labels.add(source.getLabel()), "two sources share a label");
            assertEquals(source, IriGenerator.Source.byLabel(source.getLabel()));

            IriGenerator minting = minting(source, matwerk());
            assertFalse(minting.describe().trim().isEmpty(), source + " describes itself as "
                    + "nothing");
        }
    }

    // ---------- knowing a timestamped sheet when it sees one ----------

    /**
     * "Continue the numbering" is not continuing anything on a timestamped column: on 22 of the
     * 26 live tabs the numerically largest identifier belongs to an older row than the lexically
     * largest, so highest+1 mints into the middle of the range and reads as a date it was not
     * made on.
     */
    @Test
    void aSheetOfTimestampsIsRecognisedAsOne() {
        assertTrue(IriGenerator.looksLikeTimestamps(matwerk(), "agent"));
    }

    @Test
    void aSheetOfOrdinaryNumbersIsNot() {
        SheetBook book = SheetBook.empty();
        book.add("terms", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"http://purls.helmholtz-metadaten.de/mwo/MWO_0001225", "one"},
                new String[] {"http://purls.helmholtz-metadaten.de/mwo/MWO_0001226", "two"},
                new String[] {"http://purls.helmholtz-metadaten.de/mwo/MWO_0001227", "three"},
                new String[] {"http://purls.helmholtz-metadaten.de/mwo/MWO_0001228", "four"}),
                '\t');

        assertFalse(IriGenerator.looksLikeTimestamps(book, "terms"));
    }

    @Test
    void aSheetWithTooLittleToGoOnIsNotGuessedAt() {
        SheetBook book = SheetBook.empty();
        book.add("terms", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MW + "1745165192812", "one"}), '\t');

        assertFalse(IriGenerator.looksLikeTimestamps(book, "terms"));
    }

    @Test
    void theSourceOfferedFirstFollowsWhatTheSheetAlreadyUses() {
        assertEquals(IriGenerator.Source.TIMESTAMP,
                IriGenerator.preferredFor(matwerk(), "agent", null));
    }

    // ---------- filling a column ----------

    @Test
    void fillingWithTimestampsWritesOneIdentifierPerRow() {
        SheetBook book = SheetBook.empty();
        book.add("agent", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "17451651928121", "Fraunhofer"},
                new String[] {"", "Max Planck"},
                new String[] {"", "Helmholtz"}), '\t');

        int filled = IriGenerator.using(IriGenerator.Source.TIMESTAMP, book, null, null,
                "joergwa", "agent", IriGenerator.Moment.at(WHEN)).fillBlanks("agent");

        assertEquals(2, filled);
        assertEquals(MW + "1791103530000001", book.cell("agent", 4, 1));
        assertEquals(MW + "1791103530000002", book.cell("agent", 5, 1));
    }

    /** The label a row has is found whether the sheet says LABEL or "A rdfs:label". */
    @Test
    void fillingFromLabelsReadsTheLabelColumnWhicheverWayItIsSpelled() {
        SheetBook book = SheetBook.empty();
        book.add("agent", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                // One identified row, because the namespace is derived from what is already
                // there - a sheet with no identifier at all has nothing to copy, and the
                // generator refuses rather than inventing a namespace nobody owns.
                new String[] {MW + "17451651928121", "Fraunhofer"},
                new String[] {"", "Fraunhofer Society"}), '\t');

        IriGenerator.using(IriGenerator.Source.LABEL_SLUG, book, null, null, "joergwa", "agent",
                IriGenerator.Moment.at(WHEN)).fillBlanks("agent");

        assertEquals("https://nfdi.fiz-karlsruhe.de/matwerk/msekg/fraunhofer_society",
                book.cell("agent", 4, 1),
                "the namespace comes from the sheet itself when there are no ranges");
    }

    /**
     * The ID column is re-resolved when the fill happens, not trusted from when the generator was
     * built - the template row is editable now, so it can move or vanish in between.
     */
    @Test
    void fillingASheetWhoseIdColumnHasGoneSaysSoRatherThanWritingSomewhereElse() {
        SheetBook book = SheetBook.empty();
        book.add("agent", null, rows(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label"},
                new String[] {MW + "17451651928121", "Fraunhofer"},
                new String[] {"", "Max Planck"}), '\t');
        IriGenerator minting = IriGenerator.using(IriGenerator.Source.TIMESTAMP, book, null, null,
                "joergwa", "agent", IriGenerator.Moment.at(WHEN));

        book.setCell("agent", 2, 1, "A rdfs:comment");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> minting.fillBlanks("agent"));
        assertTrue(refused.getMessage().contains("no ID column"), refused.getMessage());
    }
}
