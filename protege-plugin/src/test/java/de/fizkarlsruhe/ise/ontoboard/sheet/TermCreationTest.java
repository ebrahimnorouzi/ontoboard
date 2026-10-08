package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Turning a name somebody typed into a thing that exists.
 *
 * <p>The step the workflow otherwise has no answer for: a curator types an institute's name
 * into a column that wants another term, and nothing happens because no term has that name.
 * Measured on the MatWerk sheets, 123 references name something that does not exist.
 *
 * <p>Most of these tests are about <em>refusing</em>. Inventing a row in the wrong sheet, with
 * the wrong type, or with an identifier that collides, puts a wrong thing into somebody's
 * published graph - so where the sheets do not say what the answer is, the answer is a
 * sentence explaining that rather than a guess.
 */
class TermCreationTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** An organization sheet referring to cities, and a city sheet holding two of them. */
    private static SheetBook twoSheets() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Label", "City"},
                new String[] {"ID", "TYPE", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025"},
                new String[] {MW + "1", "obo:OBI_0000245", "Fraunhofer", "Berlin"},
                new String[] {MW + "2", "obo:OBI_0000245", "Max Planck", "Hamburg"}), '\t');
        book.add("city", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "10", "obo:ENVO_00000856", "Berlin"},
                new String[] {MW + "11", "obo:ENVO_00000856", "Hamburg"}), '\t');
        return book;
    }

    // ---------- it works out where, what and which identifier ----------

    /**
     * The sheet is chosen from evidence, not from the column's name.
     *
     * <p>If the other cells in a column resolve to rows in {@code city}, a new city belongs
     * there whatever the heading says.
     */
    @Test
    void aNewNameGoesInTheSheetTheOtherCellsAlreadyPointInto() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", row, 4);

        assertTrue(proposal.isPossible(), proposal.getRefusal());
        assertEquals("city", proposal.getTargetSheet());
        assertEquals("Dresden", proposal.getLabel());
        assertTrue(proposal.getWhy().contains("already name rows in city"), proposal.getWhy());
    }

    /** The type its new neighbours have, which is the commonest in the target sheet. */
    @Test
    void theNewRowGetsTheTypeItsNeighboursHave() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        assertEquals("obo:ENVO_00000856",
                TermCreation.propose(book, "organization", row, 4).getType());
    }

    /** The shape its neighbours use: their prefix, then one past the highest number. */
    @Test
    void theIdentifierContinuesTheSheetsOwnNumbering() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        assertEquals(MW + "12", TermCreation.propose(book, "organization", row, 4).getIri());
    }

    /**
     * One identifier of another shape must not spoil the numbering.
     *
     * <p>The first version took the longest common prefix across the whole sheet, and a single
     * bare class IRI among five thousand {@code .../msekg/<digits>} dragged it back to the
     * host name - after which no remainder was all digits and all 123 real cases refused.
     */
    @Test
    void anOddIdentifierAmongTheOthersDoesNotStopTheNumbering() {
        SheetBook book = twoSheets();
        int odd = book.addRow("city");
        book.setCell("city", odd, 1, "https://nfdi.fiz-karlsruhe.de/ontology/NFDI_0000142");
        book.setCell("city", odd, 3, "a differently shaped row");
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", row, 4);

        assertTrue(proposal.isPossible(), proposal.getRefusal());
        assertEquals(MW + "12", proposal.getIri());
    }

    /** The identifier must be free everywhere, because these namespaces are shared. */
    @Test
    void anIdentifierAlreadyUsedInAnotherSheetIsSkipped() {
        SheetBook book = twoSheets();
        book.setCell("organization", 4, 1, MW + "12");
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        assertEquals(MW + "13", TermCreation.propose(book, "organization", row, 4).getIri());
    }

    @Test
    void creatingItAddsOneRowThatResolves() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");
        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", row, 4);

        int created = TermCreation.create(book, proposal);

        assertEquals(5, created);
        assertEquals(MW + "12", book.cell("city", created, 1));
        assertEquals("obo:ENVO_00000856", book.cell("city", created, 2));
        assertEquals("Dresden", book.cell("city", created, 3));
        assertNotNull(book.index().resolve("Dresden"));
    }

    /** And the cell that started it still holds the name, which now resolves. */
    @Test
    void theCellThatStartedItIsLeftAloneAndStopsBeingAProblem() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");
        TermCreation.create(book, TermCreation.propose(book, "organization", row, 4));

        assertEquals("Dresden", book.cell("organization", row, 4));
        for (SheetAudit.Finding finding : book.audit("organization")) {
            assertFalse(finding.getRow() == row
                            && finding.getKind() == SheetAudit.Kind.UNRESOLVED_REFERENCE,
                    "the reference should resolve now: " + finding);
        }
    }

    // ---------- a cell can hold several names ----------

    /**
     * A {@code SPLIT=} column holds several things, and they are separate things.
     *
     * <p>The first version read the whole cell as one name, so the real {@code dataportal} cell
     * {@code Albert-Ludwigs-University of Freiburg, Fraunhofer Institute for Mechanics of
     * Materials} would have become one individual labelled with both institutes joined by a
     * comma. It also meant the already-exists check never fired on such a cell.
     */
    @Test
    void eachNameInASplitCellGetsItsOwnProposal() {
        SheetBook book = SheetBook.empty();
        book.add("dataportal", null, rows(
                new String[] {"#", "Name", "Institutes"},
                new String[] {"ID", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,"},
                new String[] {MW + "1", "a portal", "Freiburg,Fraunhofer IWM"}), '\t');
        book.add("organization", null, rows(
                new String[] {"#", "TYPE", "Label"},
                new String[] {"ID", "TYPE", "A rdfs:label"},
                new String[] {MW + "20", "obo:OBI_0000245", "Freiburg"}), '\t');

        List<TermCreation.Proposal> proposals =
                TermCreation.proposeAll(book, "dataportal", 3, 3);

        assertEquals(2, proposals.size(), proposals.toString());
        assertFalse(proposals.get(0).isPossible(),
                "Freiburg already exists, so it must be refused");
        assertTrue(proposals.get(1).isPossible(), proposals.get(1).getRefusal());
        assertEquals("Fraunhofer IWM", proposals.get(1).getLabel(),
                "the label must be the one name, not the whole cell");
    }

    // ---------- and it refuses rather than guesses ----------

    /** A TYPE names the class a row instantiates, which belongs to an ontology. */
    @Test
    void aTypeColumnIsNotSomethingToCreateARowFor() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 2, "abbreviation textual entity");

        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", row, 2);

        assertFalse(proposal.isPossible());
        assertTrue(proposal.getRefusal().contains("class in the ontology"),
                proposal.getRefusal());
        assertTrue(proposal.getRefusal().contains("Import terms"), proposal.getRefusal());
    }

    @Test
    void aColumnOfTextHasNothingToCreate() {
        SheetBook book = twoSheets();

        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", 3, 3);

        assertFalse(proposal.isPossible());
        assertTrue(proposal.getRefusal().contains("already the value"), proposal.getRefusal());
    }

    @Test
    void aNameThatAlreadyExistsIsRefusedBecauseASecondOneWouldBeAmbiguous() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Berlin");

        TermCreation.Proposal proposal = TermCreation.propose(book, "organization", row, 4);

        assertFalse(proposal.isPossible());
        assertTrue(proposal.getRefusal().contains("ambiguous"), proposal.getRefusal());
    }

    @Test
    void withNoEvidenceAndNoMatchingSheetNameTheChoiceIsLeftToThePerson() {
        SheetBook book = SheetBook.empty();
        book.add("s", null, rows(
                new String[] {"#", "Label", "Points at"},
                new String[] {"ID", "A rdfs:label",
                              "I http://purl.obolibrary.org/obo/RO_0001025"},
                new String[] {MW + "1", "a thing", "something unknown"}), '\t');

        TermCreation.Proposal proposal = TermCreation.propose(book, "s", 3, 3);

        assertFalse(proposal.isPossible());
        assertTrue(proposal.getRefusal().contains("the choice is yours"),
                proposal.getRefusal());
    }

    @Test
    void anEmptyCellAndAMissingSheetAreRefusedPlainly() {
        SheetBook book = twoSheets();

        assertTrue(TermCreation.propose(book, "organization", 3, 4).isPossible()
                || TermCreation.propose(book, "organization", 3, 4).getRefusal()
                        .contains("ambiguous"));
        int row = book.addRow("organization");
        assertFalse(TermCreation.propose(book, "organization", row, 4).isPossible());
        assertTrue(TermCreation.propose(book, "organization", row, 4).getRefusal()
                .contains("empty"));
        assertFalse(TermCreation.propose(book, "no such sheet", 3, 1).isPossible());
        assertEquals(0, TermCreation.create(book, null));
        assertEquals(0, TermCreation.create(null, null));
    }

    /** A proposal that cannot be made must not be creatable by calling create anyway. */
    @Test
    void creatingARefusedProposalDoesNothing() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Berlin");
        TermCreation.Proposal refused = TermCreation.propose(book, "organization", row, 4);
        int before = book.get("city").getDataRows();

        assertEquals(0, TermCreation.create(book, refused));
        assertEquals(before, book.get("city").getDataRows());
    }

    @Test
    void theProposalExplainsItselfInOneLine() {
        SheetBook book = twoSheets();
        int row = book.addRow("organization");
        book.setCell("organization", row, 4, "Dresden");

        String described = TermCreation.propose(book, "organization", row, 4).describe();

        assertTrue(described.contains("Dresden"), described);
        assertTrue(described.contains("city"), described);
        assertTrue(described.contains(MW + "12"), described);
    }
}
