package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateColumns;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A comment is text about a row, not a name for it (1.135.2).
 *
 * <p>Found on a knowledge graph built from sheets: two rows share a comment, and Check sheets...
 * reported the comment as a name that means two things. The column is written
 * {@code AL rdfs:comment@en}, and ROBOT never resolves a name by a comment - but every {@code AL}
 * column was taken for a label column, whatever its property.
 */
class ACommentIsNotANameTest {

    private static final String EX = "http://example.org/kg/";

    private static List<List<String>> sheet(String[]... rows) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] row : rows) {
            table.add(Arrays.asList(row));
        }
        return table;
    }

    /** Two specimens with names of their own and one comment between them. */
    private static List<List<String>> specimens() {
        return sheet(
                new String[] {"ID", "Name", "Name (de)", "Comment"},
                new String[] {"ID", "AL rdfs:label@en", "AL rdfs:label@de", "AL rdfs:comment@en"},
                new String[] {EX + "s1", "specimen 1", "Probe 1", "cut from the same plate"},
                new String[] {EX + "s2", "specimen 2", "Probe 2", "cut from the same plate"});
    }

    @Test
    void onlyItsLabelColumnsGiveARowAName() {
        List<TemplateColumns.Column> labels = TemplateColumns.of(specimens()).labelColumns();

        List<String> headings = new ArrayList<String>();
        for (TemplateColumns.Column column : labels) {
            headings.add(column.getHeading());
        }
        assertEquals(Arrays.asList("Name", "Name (de)"), headings,
                "a label in each language is a name; the comment is not");
    }

    @Test
    void aCommentTwoRowsShareNamesNothing() {
        LabelIndex index = LabelIndex.empty().plus("specimens", specimens());

        assertTrue(index.ambiguous().isEmpty(), index.ambiguous().toString());
        assertFalse(index.knows("cut from the same plate"), "a comment is no name to resolve");
        assertTrue(index.knows("Probe 1"), "a label in another language is");
    }

    @Test
    void checkSheetsFindsNothingWrongWithASharedComment() {
        LabelIndex index = LabelIndex.empty().plus("specimens", specimens());

        List<SheetAudit.Finding> found = SheetAudit.of("specimens", specimens(), index);

        assertTrue(found.isEmpty(), found.toString());
    }
}
