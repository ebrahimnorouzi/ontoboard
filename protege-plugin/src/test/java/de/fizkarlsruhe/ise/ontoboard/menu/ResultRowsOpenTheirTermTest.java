package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A row of a result that names a term opens it: the user saw "a label is missing" in the
 * quality report of their egg project and could not get from the row to the term on the board
 * (1.155.0).
 */
class ResultRowsOpenTheirTermTest {

    private static OperationResult report() {
        return OperationResult.of("Quality report").columns("Severity", "Rule", "Subject",
                "Message")
                .rowAbout("https://example.org/egg/Yolk", "ERROR", "missing_label", "Yolk",
                        "no label")
                .row("INFO", "note", "", "about the ontology")
                .rowAbout("https://example.org/egg/Albumen", "WARN", "missing_definition",
                        "Albumen", "no definition")
                .build();
    }

    @Test
    void theReportKeepsEachRowsTerm() {
        OperationResult result = report();

        assertEquals(Arrays.asList("https://example.org/egg/Yolk", null,
                "https://example.org/egg/Albumen"), result.getRowTerms());
        assertEquals(3, result.getRows().size());
        assertEquals(Arrays.asList("ERROR", "missing_label", "Yolk", "no label"),
                result.getRows().get(0));
        assertEquals(result.getRowTerms(), result.toBuilder().build().getRowTerms());
    }

    /** A click on a row hands its term on - the row it is, also when the table is sorted. */
    @Test
    void aClickOpensTheTerm() {
        List<String> opened = new ArrayList<String>();
        JTable table = ResultDialog.table(report(), opened::add);
        table.setSize(600, 200);

        click(table, 2);
        click(table, 1);
        table.getRowSorter().setSortKeys(java.util.Collections.singletonList(
                new RowSorter.SortKey(1, SortOrder.ASCENDING)));
        // Sorted by rule: missing_definition, missing_label, note.
        click(table, 0);

        assertEquals(Arrays.asList("https://example.org/egg/Albumen",
                "https://example.org/egg/Albumen"), opened);
    }

    @Test
    void aRowsTermIsSaidWhereThePointerIs() {
        JTable table = ResultDialog.table(report(), iri -> { });
        table.setSize(600, 200);

        String tip = table.getToolTipText(pointedAt(table, 0));
        assertTrue(tip != null && tip.contains("Show on the board"), String.valueOf(tip));
        assertNull(table.getToolTipText(pointedAt(table, 1)));
    }

    @Test
    void anImportedTermIsFound() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology module = manager.createOntology(IRI.create("https://example.org/module"));
        manager.addAxiom(module, factory.getOWLDeclarationAxiom(factory.getOWLClass(
                IRI.create("http://purl.obolibrary.org/obo/BFO_0000040"))));
        OWLOntology edit = manager.createOntology(IRI.create("https://example.org/edit"));
        manager.applyChange(new AddImport(edit, factory.getOWLImportsDeclaration(
                IRI.create("https://example.org/module"))));

        OWLEntity found = TermLink.find(edit, "http://purl.obolibrary.org/obo/BFO_0000040");

        assertEquals("http://purl.obolibrary.org/obo/BFO_0000040", found.getIRI().toString());
        assertTrue(found.isOWLClass());
        assertNull(TermLink.find(edit, "https://example.org/nowhere"));
        assertNull(TermLink.find(edit, ""));
    }

    private static java.awt.Point at(JTable table, int row) {
        java.awt.Rectangle cell = table.getCellRect(row, 2, false);
        return new java.awt.Point(cell.x + 2, cell.y + 2);
    }

    private static MouseEvent pointedAt(JTable table, int row) {
        java.awt.Point point = at(table, row);
        return new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, point.x, point.y, 0, false);
    }

    private static void click(JTable table, int row) {
        java.awt.Point point = at(table, row);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, point.x, point.y, 1, false,
                MouseEvent.BUTTON1));
    }
}
