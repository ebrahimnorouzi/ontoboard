package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import de.fizkarlsruhe.ise.ontoboard.sheet.SheetBook;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * A heading committed in the sheet editor - even unchanged - or a column added, and Protege
 * froze, with nothing in its log (1.155.0, the user's nfdicore): the view answered every change
 * of the header table's own model by changing it again, without end, building every column's
 * editors at each turn on Swing's thread.
 */
class SheetEditorViewHeaderEditTest {

    private static List<List<String>> rows(String[]... lines) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] line : lines) {
            table.add(new ArrayList<String>(Arrays.asList(line)));
        }
        return table;
    }

    /** The starter sheet New sheet... writes. */
    private static SheetBook book() {
        SheetBook book = SheetBook.empty();
        book.add("terms", null, rows(
                new String[] {"ID", "Label", "Parent", "Definition"},
                new String[] {"ID", "LABEL", "SC %", "A IAO:0000115"},
                new String[] {"https://nfdi.fiz-karlsruhe.de/ontology/NFDI_0030000",
                              "an example term", "owl:Thing", "What this term means."}), '\t');
        return book;
    }

    /**
     * The sheet's header table, as the view keeps it. Without Protege the view's tabs are not
     * put into the view, so it is read where the view keeps it.
     */
    @SuppressWarnings("unchecked")
    private static SheetHeaderTable opened(SheetEditorView view, SheetBook book)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> view.open(book));
        java.lang.reflect.Field kept = SheetEditorView.class.getDeclaredField("headers");
        kept.setAccessible(true);
        SheetHeaderTable header = ((java.util.Map<String, SheetHeaderTable>) kept.get(view))
                .get("terms");
        assertNotNull(header, "no header table for the sheet");
        return header;
    }

    @Test
    void aHeadingCommittedReturnsAndIsKept() throws Exception {
        SheetBook book = book();
        SheetHeaderTable header = opened(new SheetEditorView(), book);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> SwingUtilities.invokeAndWait(
                () -> header.setValueAt("Name", 0, 1)));

        assertEquals("Name", book.cell("terms", 1, 2));
    }

    @Test
    void aHeadingCommittedUnchangedReturns() throws Exception {
        SheetBook book = book();
        SheetHeaderTable header = opened(new SheetEditorView(), book);

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> SwingUtilities.invokeAndWait(
                () -> header.setValueAt("Label", 0, 1)));

        assertEquals("Label", book.cell("terms", 1, 2));
    }

    /** What Add column... does to the header once the heading is written. */
    @Test
    void theHeaderRebuiltForANewColumnReturns() throws Exception {
        SheetBook book = book();
        SheetHeaderTable header = opened(new SheetEditorView(), book);
        book.setCell("terms", 1, 5, "Note");

        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> SwingUtilities.invokeAndWait(
                header::refreshStructure));
    }
}
