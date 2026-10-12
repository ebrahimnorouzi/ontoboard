package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Minting the identifier a new row needs.
 *
 * <p>The ID ranges here are MatWerk's own, copied from
 * {@code src/ontology/mwo-idranges.owl}: prefix
 * {@code http://purls.helmholtz-metadaten.de/mwo/MWO_}, seven digits, and three allocated
 * blocks. Its sheets hold full IRIs like {@code .../MWO_0001225}, so that is what minting has
 * to produce - to the digit.
 */
class IriGeneratorTest {

    private static final String MWO = "http://purls.helmholtz-metadaten.de/mwo/MWO_";

    private static final String RANGES = "## ID Ranges File\n"
            + "Prefix: idsfor: <http://purl.obolibrary.org/obo/IAO_0000598>\n"
            + "Prefix: allocatedto: <http://purl.obolibrary.org/obo/IAO_0000597>\n"
            + "Prefix: idprefix: <http://purl.obolibrary.org/obo/IAO_0000599>\n"
            + "Prefix: iddigits: <http://purl.obolibrary.org/obo/IAO_0000596>\n"
            + "Prefix: idrange: <http://purl.obolibrary.org/obo/ro/idrange/>\n"
            + "Prefix: xsd: <http://www.w3.org/2001/XMLSchema#>\n"
            + "Ontology: <http://purls.helmholtz-metadaten.de/mwo/mwo-idranges.owl>\n"
            + "Annotations: \n"
            + "    idsfor: \"MWO\",\n"
            + "    idprefix: \"" + MWO + "\",\n"
            + "    iddigits: 7\n"
            + "AnnotationProperty: idprefix:\n"
            + "AnnotationProperty: iddigits:\n"
            + "AnnotationProperty: idsfor:\n"
            + "AnnotationProperty: allocatedto:\n"
            + "Datatype: idrange:1\n"
            + "    Annotations: \n"
            + "        allocatedto: \"HosseinBeygiNasrabadi\"\n"
            + "    EquivalentTo: \n"
            + "        xsd:integer[>= 1000 , <= 9999]\n"
            + "Datatype: idrange:2\n"
            + "    Annotations: \n"
            + "        allocatedto: \"joergwa\"\n"
            + "    EquivalentTo: \n"
            + "        xsd:integer[>= 10000 , <= 19999]\n"
            + "Datatype: xsd:integer\n";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** A sheet shaped like the MWO terms template, with seven-digit identifiers. */
    private static SheetBook mwoSheets() {
        SheetBook book = SheetBook.empty();
        book.add("mwo-terms", null, rows(
                new String[] {"ID", "Label", "Parent"},
                new String[] {"ID", "LABEL", "SC %"},
                new String[] {MWO + "0001225", "sintering process", "'annealing process'"},
                new String[] {MWO + "0001226", "laboratory sintering process",
                              "sintering process"}), '\t');
        return book;
    }

    private static File rangesBeside(File dir) throws Exception {
        File ontology = new File(dir, "mwo-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));
        Files.write(new File(dir, "mwo-idranges.owl").toPath(),
                RANGES.getBytes(Charset.forName("UTF-8")));
        return ontology;
    }

    // ---------- from the project's ID ranges ----------

    /** The identifier a project's own ranges produce, to the digit. */
    @Test
    void anAllocatedBlockMintsTheShapeTheProjectUses(@TempDir File dir) throws Exception {
        File ontology = rangesBeside(dir);

        IriGenerator minting = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(),
                null, ontology, "joergwa");

        assertTrue(minting.canMint(), minting.getRefusal());
        assertEquals(IriGenerator.Source.ID_RANGES, minting.getSource());
        assertEquals(MWO + "0010000", minting.mint(1).get(0),
                "joergwa's block starts at 10000 and the width is seven");
        assertEquals(7, minting.identifierWidth());
    }

    @Test
    void eachEditorMintsFromTheirOwnBlockSoTwoOfThemCannotCollide(@TempDir File dir)
            throws Exception {
        File ontology = rangesBeside(dir);

        String hossein = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(), null,
                ontology, "HosseinBeygiNasrabadi").mint(1).get(0);
        String joerg = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(), null,
                ontology, "joergwa").mint(1).get(0);

        assertEquals(MWO + "0001000", hossein);
        assertEquals(MWO + "0010000", joerg);
        assertFalse(hossein.equals(joerg));
    }

    @Test
    void severalAtOnceAreConsecutiveAndDistinct(@TempDir File dir) throws Exception {
        List<String> minted = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(),
                null, rangesBeside(dir), "joergwa").mint(3);

        assertEquals(Arrays.asList(MWO + "0010000", MWO + "0010001", MWO + "0010002"), minted);
    }

    /**
     * An identifier already in a sheet is taken, even when it is in the middle of the block.
     * Minting over it would not create a term; it would quietly edit one.
     */
    @Test
    void whatASheetAlreadyUsesIsSkipped(@TempDir File dir) throws Exception {
        SheetBook book = mwoSheets();
        book.setCell("mwo-terms", 3, 1, MWO + "0010000");

        assertEquals(MWO + "0010001", IriGenerator.using(IriGenerator.Source.ID_RANGES, book,
                null, rangesBeside(dir), "joergwa").mint(1).get(0));
    }

    /**
     * And so is an identifier the ontology holds - including one that came in through an import,
     * where minting over it adds axioms to somebody else's term and the build succeeds.
     */
    @Test
    void whatTheOntologyAlreadyHoldsIsSkipped(@TempDir File dir) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create(MWO + "0010000"))));

        assertEquals(MWO + "0010001", IriGenerator.using(IriGenerator.Source.ID_RANGES,
                mwoSheets(), ontology, rangesBeside(dir), "joergwa").mint(1).get(0));
    }

    /** No block, no minting - and the refusal has to say what to do, because it is fixable. */
    @Test
    void anEditorWithNoBlockIsRefusedWithTheRemedy(@TempDir File dir) throws Exception {
        IriGenerator minting = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(),
                null, rangesBeside(dir), "somebody-new");

        assertFalse(minting.canMint());
        assertTrue(minting.getRefusal().contains("somebody-new"), minting.getRefusal());
        assertTrue(minting.getRefusal().contains("ID ranges"), minting.getRefusal());
        assertTrue(minting.getRefusal().contains("joergwa"),
                "it should say who does have one: " + minting.getRefusal());
        assertThrows(IllegalStateException.class, () -> minting.mint(1),
                "minting in spite of the refusal is the collision it exists to prevent");
    }

    @Test
    void aProjectWithNoRangesFileSaysSo(@TempDir File dir) throws Exception {
        File ontology = new File(dir, "mwo-edit.owl");
        Files.write(ontology.toPath(), "<rdf:RDF/>".getBytes("UTF-8"));

        IriGenerator minting = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(),
                null, ontology, "joergwa");

        assertFalse(minting.canMint());
        assertTrue(minting.getRefusal().contains("-idranges.owl"), minting.getRefusal());
    }

    // ---------- from the sheets themselves ----------

    /**
     * THE DEFECT THIS CLASS EXISTS FOR. The previous rule took the stem as everything before the
     * trailing digits and appended the next number unpadded, so a sheet of seven-digit
     * identifiers got {@code MWO_1227} as its next row - four digits wide, sorting before every
     * identifier it follows, matching no project's iddigits, and building perfectly happily so
     * that nothing ever said so.
     */
    @Test
    void continuingASheetKeepsItsPadding() {
        IriGenerator minting = IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, mwoSheets(),
                null, null, "anybody");

        assertTrue(minting.canMint(), minting.getRefusal());
        assertEquals(MWO + "0001227", minting.mint(1).get(0),
                "the width of the identifiers already there is the width of the next one");
        assertEquals(7, minting.identifierWidth());
    }

    @Test
    void itContinuesFromTheHighestNumberAndNotFromTheLastRow() {
        SheetBook book = mwoSheets();
        book.setCell("mwo-terms", 3, 1, MWO + "0009000");

        assertEquals(MWO + "0009001", IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book,
                null, null, "anybody").mint(1).get(0));
    }

    /**
     * The commonest stem, not the longest common prefix. One identifier of a different shape - a
     * bare class IRI among five thousand - drags a common prefix back to the host name, after
     * which no remainder is all digits and there is no next number to find. Measured: this is
     * all 123 of the real cases.
     */
    @Test
    void oneOddIdentifierDoesNotDefeatTheWholeSheet() {
        SheetBook book = SheetBook.empty();
        book.add("terms", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {MWO + "0001225", "one"},
                new String[] {MWO + "0001226", "two"},
                new String[] {"http://purl.obolibrary.org/obo/BFO_0000015", "process"}), '\t');

        assertEquals(MWO + "0001227", IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book,
                null, null, "anybody").mint(1).get(0));
    }

    @Test
    void sheetsWithNoNumberedIdentifierAtAllAreRefusedRatherThanGuessedAt() {
        SheetBook book = SheetBook.empty();
        book.add("terms", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"obo:BFO_", "nothing numeric here"}), '\t');

        IriGenerator minting =
                IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book, null, null, "x");

        assertFalse(minting.canMint());
        assertTrue(minting.getRefusal().contains("no numbering to continue")
                || minting.getRefusal().contains("no identifier ending"), minting.getRefusal());
    }

    // ---------- choosing between the two ----------

    @Test
    void theRangesAreUsedWhenTheyCanBe(@TempDir File dir) throws Exception {
        IriGenerator chosen =
                IriGenerator.forSheets(mwoSheets(), null, rangesBeside(dir), "joergwa");

        assertEquals(IriGenerator.Source.ID_RANGES, chosen.getSource());
    }

    /**
     * And the sheets are the fallback. A project with no ranges, or an editor with no block, must
     * still be able to add a row - refusing outright would make the feature unusable for
     * everybody not already set up in an ODK project.
     */
    @Test
    void theSheetsAreTheFallbackWhenTheRangesCannotBeUsed(@TempDir File dir) throws Exception {
        IriGenerator chosen =
                IriGenerator.forSheets(mwoSheets(), null, rangesBeside(dir), "somebody-new");

        assertEquals(IriGenerator.Source.SHEET_PATTERN, chosen.getSource());
        assertTrue(chosen.canMint());
        assertEquals(MWO + "0001227", chosen.mint(1).get(0));
    }

    @Test
    void everySourceHasALabelThatRoundTrips() {
        for (IriGenerator.Source source : IriGenerator.Source.values()) {
            assertFalse(source.getLabel().trim().isEmpty());
            assertEquals(source, IriGenerator.Source.byLabel(source.getLabel()));
        }
        org.junit.jupiter.api.Assertions.assertNull(IriGenerator.Source.byLabel("something"));
    }

    // ---------- filling a column ----------

    private static SheetBook withBlankIdentifiers() {
        SheetBook book = SheetBook.empty();
        book.add("mwo-terms", null, rows(
                new String[] {"ID", "Label", "Parent"},
                new String[] {"ID", "LABEL", "SC %"},
                new String[] {MWO + "0001225", "sintering process", ""},
                new String[] {"", "hot pressing", "sintering process"},
                new String[] {"", "spark plasma sintering", "sintering process"}), '\t');
        return book;
    }

    @Test
    void everyRowThatHasContentButNoIdentifierIsFound() {
        assertEquals(Arrays.asList(4, 5),
                IriGenerator.rowsNeedingAnIdentifier(withBlankIdentifiers(), "mwo-terms"));
    }

    /**
     * An empty row is not a row waiting for an identifier. Minting one for it would put a
     * declaration of nothing into the graph, which builds and means nothing.
     */
    @Test
    void anEmptyRowIsNotWaitingForAnIdentifier() {
        SheetBook book = withBlankIdentifiers();
        book.addRow("mwo-terms");

        assertEquals(Arrays.asList(4, 5),
                IriGenerator.rowsNeedingAnIdentifier(book, "mwo-terms"));
    }

    @Test
    void fillingWritesConsecutiveIdentifiersDownTheSheet() {
        SheetBook book = withBlankIdentifiers();

        int filled = IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book, null, null, "x")
                .fillBlanks("mwo-terms");

        assertEquals(2, filled);
        assertEquals(MWO + "0001226", book.cell("mwo-terms", 4, 1));
        assertEquals(MWO + "0001227", book.cell("mwo-terms", 5, 1));
    }

    /** Through the book, so it is undoable and nothing reaches the file until a save. */
    @Test
    void fillingIsUndoable() {
        SheetBook book = withBlankIdentifiers();
        IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book, null, null, "x")
                .fillBlanks("mwo-terms");

        book.undo();

        assertEquals("", book.cell("mwo-terms", 5, 1));
    }

    @Test
    void fillingASheetThatNeedsNothingWritesNothing() {
        SheetBook book = mwoSheets();

        assertEquals(0, IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, book, null, null,
                "x").fillBlanks("mwo-terms"));
    }

    @Test
    void aSheetWithNoIdentifierColumnNeedsNothing() {
        SheetBook book = SheetBook.empty();
        book.add("notes", null, rows(
                new String[] {"Label", "Note"},
                new String[] {"LABEL", "A rdfs:comment"},
                new String[] {"something", "a note"}), '\t');

        assertTrue(IriGenerator.rowsNeedingAnIdentifier(book, "notes").isEmpty());
    }

    @Test
    void aSheetThatIsNotThereNeedsNothing() {
        assertTrue(IriGenerator.rowsNeedingAnIdentifier(mwoSheets(), "no-such-sheet").isEmpty());
    }

    // ---------- what it tells the user ----------

    @Test
    void theDescriptionNamesTheBlockAndTheNextIdentifier(@TempDir File dir) throws Exception {
        String described = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(), null,
                rangesBeside(dir), "joergwa").describe();

        assertTrue(described.contains("10000"), described);
        assertTrue(described.contains("joergwa"), described);
        assertTrue(described.contains(MWO + "0010000"), described);
    }

    @Test
    void theDescriptionOfASheetPatternSaysWhereItCameFrom() {
        String described = IriGenerator.using(IriGenerator.Source.SHEET_PATTERN, mwoSheets(),
                null, null, "x").describe();

        assertTrue(described.contains("sheets"), described);
        assertTrue(described.contains(MWO + "0001227"), described);
    }

    /** The real MatWerk ranges file, when this machine has the project checked out. */
    @Test
    void theRealMatWerkRangesFileMintsTheShapeItsOwnSheetsHold() throws Exception {
        File ontology = new File("C:/Users/eno/ontology-projects/mwo/src/ontology/mwo-edit.owl");
        if (!new File(ontology.getParentFile(), "mwo-idranges.owl").isFile()) {
            return;
        }
        IriGenerator minting = IriGenerator.using(IriGenerator.Source.ID_RANGES, mwoSheets(),
                null, ontology, "joergwa");

        assertTrue(minting.canMint(), minting.getRefusal());
        assertEquals(MWO, minting.getPrefix(),
                "the prefix in the real file is not what this test assumed");
        assertEquals(7, minting.identifierWidth());
        assertTrue(minting.mint(1).get(0).matches(
                "http://purls\\.helmholtz-metadaten\\.de/mwo/MWO_\\d{7}"),
                minting.mint(1).get(0));
    }
}
