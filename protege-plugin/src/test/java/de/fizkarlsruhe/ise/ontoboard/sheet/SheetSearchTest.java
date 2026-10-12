package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Finding data in a folder of sheets.
 *
 * <p>"Which tab is Fraunhofer in" is the question somebody actually has, and on the graph this
 * was built against the answer is one of twenty-six files. So the search is across the book
 * rather than per sheet, and it wraps, so that pressing it repeatedly walks every match instead
 * of stopping at the end of whichever sheet happened to be open.
 */
class SheetSearchTest {

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    private static SheetBook twoSheets() {
        SheetBook book = SheetBook.empty();
        book.add("organization", null, rows(
                new String[] {"ID", "Label", "City"},
                new String[] {"ID", "LABEL", "I obo:RO_0001025"},
                new String[] {"ex:1", "Fraunhofer", "Berlin"},
                new String[] {"ex:2", "Max Planck", "Hamburg"}), '\t');
        book.add("city", null, rows(
                new String[] {"ID", "Label"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:10", "Berlin"},
                new String[] {"ex:11", "Hamburg"}), '\t');
        return book;
    }

    @Test
    void aNameIsFoundInTheSheetItIsIn() {
        SheetBook.Match found = twoSheets().findNext("fraunhofer", null, 0, 0);

        assertNotNull(found);
        assertEquals("organization", found.getSheet());
        assertEquals(3, found.getRow());
        assertEquals(2, found.getColumn());
    }

    @Test
    void theSearchIsCaseInsensitiveAndMatchesPartOfACell() {
        assertNotNull(twoSheets().findNext("PLANCK", null, 0, 0));
        assertNotNull(twoSheets().findNext("plan", null, 0, 0));
    }

    /** Pressing it again moves on rather than finding the same cell for ever. */
    @Test
    void thesecondPressFindsTheNextOne() {
        SheetBook book = twoSheets();

        SheetBook.Match first = book.findNext("berlin", null, 0, 0);
        SheetBook.Match second = book.findNext("berlin", first.getSheet(), first.getRow(),
                first.getColumn());

        assertEquals("organization", first.getSheet());
        assertEquals("city", second.getSheet(), "the second Berlin is in the other sheet");
        assertEquals(3, second.getRow());
    }

    /** And the press after the last one comes back to the first, rather than giving up. */
    @Test
    void itWrapsRoundToTheBeginning() {
        SheetBook book = twoSheets();
        SheetBook.Match last = book.findNext("berlin", "city", 3, 2);

        assertNotNull(last, "a search that stops at the end of the last sheet finds nothing "
                + "on the second press");
        assertEquals("organization", last.getSheet());
    }

    @Test
    void somethingThatIsNowhereIsNotFound() {
        assertNull(twoSheets().findNext("Tokyo", null, 0, 0));
    }

    @Test
    void anEmptySearchFindsNothingRatherThanEverything() {
        assertNull(twoSheets().findNext("", null, 0, 0));
        assertNull(twoSheets().findNext(null, null, 0, 0));
        assertNull(twoSheets().findNext("   ", null, 0, 0));
    }

    @Test
    void anEmptyBookFindsNothing() {
        assertNull(SheetBook.empty().findNext("anything", null, 0, 0));
    }

    /**
     * The count is what the box shows beside the field, so it has to agree with how many times
     * pressing next moves.
     */
    @Test
    void theCountAgreesWithWhatRepeatedPressesVisit() {
        SheetBook book = twoSheets();

        assertEquals(2, book.countMatches("berlin"),
                "the city, and the one organization that references it");

        List<String> visited = new ArrayList<String>();
        SheetBook.Match at = book.findNext("berlin", null, 0, 0);
        for (int step = 0; step < 2; step++) {
            visited.add(at.toString());
            at = book.findNext("berlin", at.getSheet(), at.getRow(), at.getColumn());
        }
        assertEquals(2, new java.util.LinkedHashSet<String>(visited).size(),
                "two presses should visit two different cells: " + visited);
        assertEquals(visited.get(0), at.toString(),
                "and the press after the last one comes back to the first");
    }

    /** Headings and ROBOT specs are searchable too; excluding them would make the count lie. */
    @Test
    void theTemplateRowsAreSearchedAsWell() {
        SheetBook.Match found = twoSheets().findNext("RO_0001025", null, 0, 0);

        assertNotNull(found);
        assertEquals(2, found.getRow(), "the ROBOT template row");
    }

    @Test
    void nothingMatchesNothingInTheCount() {
        assertEquals(0, twoSheets().countMatches("Tokyo"));
        assertEquals(0, twoSheets().countMatches(""));
        assertEquals(0, twoSheets().countMatches(null));
    }
}
