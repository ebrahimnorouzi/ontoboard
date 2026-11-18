package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TermSearch;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JTable;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * F8: Find terms has no box to tick for a term that is already the project's - imported again,
 * it would be a second copy of what the project has. The guard is the table's own, a cell
 * nobody can edit; here the mistake is tried through a real table, as a click would try it.
 */
class FindTermsAlreadyYoursTest {

    private static final String MINI = "https://example.org/mini/";

    private static int rowOf(FindTermsAction.FoundModel model, String iri) {
        for (int row = 0; row < model.getRowCount(); row++) {
            if (model.termAt(row).getIri().equals(iri)) {
                return row;
            }
        }
        throw new AssertionError(iri + " was not found");
    }

    @Test
    void aTermThatIsAlreadyYoursCannotBeTicked() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology source = manager.createOntology(IRI.create("https://example.org/mini.owl"));
        for (String[] term : new String[][] {{"EGG", "egg"}, {"PAN", "egg pan"}}) {
            IRI iri = IRI.create(MINI + term[0]);
            manager.addAxiom(source, df.getOWLDeclarationAxiom(df.getOWLClass(iri)));
            manager.addAxiom(source, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri,
                    df.getOWLLiteral(term[1])));
        }
        List<TermSearch.Term> found = TermSearch.of(source).find("egg", 10, false);
        Map<String, TermSearch.Term> chosen = new LinkedHashMap<String, TermSearch.Term>();
        FindTermsAction.FoundModel model = new FindTermsAction.FoundModel(
                Collections.singleton(MINI + "EGG"), chosen, () -> "egg", () -> { });
        model.show(found);
        JTable table = new JTable(model);
        int yours = rowOf(model, MINI + "EGG");
        int other = rowOf(model, MINI + "PAN");

        assertFalse(table.editCellAt(yours, 0), "no box to tick for a term already yours");
        assertFalse(model.isCellEditable(yours, 0));
        assertTrue(String.valueOf(model.getValueAt(yours, 1)).endsWith(" - already yours"),
                String.valueOf(model.getValueAt(yours, 1)));
        assertTrue(table.editCellAt(other, 0), "another term's box is there");
        table.getCellEditor().cancelCellEditing();
        model.setValueAt(Boolean.TRUE, other, 0);
        assertEquals(Collections.singleton(MINI + "PAN"), chosen.keySet());
    }
}
