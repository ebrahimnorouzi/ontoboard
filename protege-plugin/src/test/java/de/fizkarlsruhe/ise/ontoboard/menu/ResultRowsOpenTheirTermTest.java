package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.views.OntoBoardTab;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import org.junit.jupiter.api.Test;
import org.protege.editor.core.ui.workspace.WorkspaceTab;
import org.protege.editor.owl.ui.OWLWorkspaceViewsTab;
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

    /**
     * The hand check of 1.155.0: on the tab a window opens with, Active ontology, a click on a row
     * made the term Protege's selection and changed nothing to be seen - and the row says "Show on
     * the board". The term is selected, then the board is brought forward: a board shown for the
     * first time opens on the term selected (BoardOpensOnTheSelectionTest).
     */
    @Test
    void aClickSelectsTheTermAndThenBringsTheBoardForward() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology edit = manager.createOntology(IRI.create("https://example.org/egg"));
        manager.addAxiom(edit, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create("https://example.org/egg/Yolk"))));
        List<String> done = new ArrayList<String>();

        assertTrue(TermLink.open(edit, "https://example.org/egg/Yolk",
                entity -> done.add("selected " + entity.getIRI()), () -> done.add("board")));
        assertFalse(TermLink.open(edit, "https://example.org/egg/Shell",
                entity -> done.add("selected " + entity.getIRI()), () -> done.add("board")));

        assertEquals(Arrays.asList("selected https://example.org/egg/Yolk", "board"), done);
    }

    /** The board's tab among a window's tabs is OntoBoard's own; a window without it has none. */
    @Test
    void theBoardsTabIsOntoBoards() {
        WorkspaceTab entities = new OWLWorkspaceViewsTab();
        WorkspaceTab board = new OntoBoardTab();

        assertSame(board, TermLink.boardTab(Arrays.asList(entities, board)));
        assertNull(TermLink.boardTab(java.util.Collections.singletonList(entities)));
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
