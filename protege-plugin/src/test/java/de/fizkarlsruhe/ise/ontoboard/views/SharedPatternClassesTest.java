package de.fizkarlsruhe.ise.ontoboard.views;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A class two patterns share is drawn once, and the second pattern's frame says where.
 *
 * <p>Found by every tester who took the study's last task: the second pattern's frame held two
 * classes, the class the task was about stood in the first pattern's frame, a thousand pixels away,
 * and the status line counted it as in the new frame.
 */
class SharedPatternClassesTest {

    private static final String O = "http://example.org/o#";

    @Test
    void oneFrameIsNamedOnce() {
        Map<String, String> elsewhere = new LinkedHashMap<String, String>();
        elsewhere.put(O + "value", "Measurement");
        elsewhere.put(O + "scalar", "Measurement");
        Map<String, String> names = new HashMap<String, String>();
        names.put(O + "value", "value specification\nex:value");
        names.put(O + "scalar", "scalar value specification\nex:scalar");
        assertEquals("Also part of this pattern, drawn in Measurement: value specification and"
                + " scalar value specification. Type a name in Find to go there.",
                SchemaCanvasView.whereTheRestIs(elsewhere, names));
    }

    @Test
    void severalFramesNameEachClasssFrame() {
        Map<String, String> elsewhere = new LinkedHashMap<String, String>();
        elsewhere.put(O + "a", "First");
        elsewhere.put(O + "b", "Second");
        elsewhere.put(O + "c", "");
        assertEquals("Also part of this pattern, drawn elsewhere: a (First), b (Second) and c."
                + " Type a name in Find to go there.",
                SchemaCanvasView.whereTheRestIs(elsewhere, new HashMap<String, String>()));
    }

    @Test
    void aClassInNoFrameIsSaidToBeOnTheBoard() {
        Map<String, String> elsewhere = new LinkedHashMap<String, String>();
        elsewhere.put(O + "report", "");
        assertEquals("Also part of this pattern, already on the board: report. Type a name in"
                + " Find to go there.",
                SchemaCanvasView.whereTheRestIs(elsewhere, new HashMap<String, String>()));
    }

    @Test
    void manyAreCounted() {
        Map<String, String> elsewhere = new LinkedHashMap<String, String>();
        for (String name : Arrays.asList("a", "b", "c", "d", "e", "f", "g")) {
            elsewhere.put(O + name, "F");
        }
        assertEquals("Also part of this pattern, drawn in F: a, b, c, d and 3 more. Type a name"
                + " in Find to go there.",
                SchemaCanvasView.whereTheRestIs(elsewhere, new HashMap<String, String>()));
    }

    @Test
    void framesKeepTheirOrderAndNewOnesComeAfterInTheirOwn() {
        Map<String, Integer> rows = new LinkedHashMap<String, Integer>();
        rows.put("Measurement", 0);
        rows.put("dataset", 0);
        List<String> ordered = SchemaCanvasView.inBoardOrder(
                Arrays.asList("dataset", "Another", "Measurement", "Zeta"),
                (String label) -> label, rows);
        assertEquals(Arrays.asList("Measurement", "dataset", "Another", "Zeta"), ordered);
    }

    @Test
    void aNameAlreadyTakenIsFoundInAnImportToo() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/imported"));
        manager.addAxiom(imported, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                IRI.create(O + "Report"), df.getOWLLiteral("report", "en")));
        OWLOntology mine = manager.createOntology(IRI.create("http://example.org/mine"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(mine,
                df.getOWLImportsDeclaration(IRI.create("http://example.org/imported"))));

        assertEquals("Report", SchemaCanvasView.termNamed(mine, "Report "));
        assertEquals("Report", SchemaCanvasView.termNamed(mine, "REPORT"));
        assertNull(SchemaCanvasView.termNamed(mine, "steel plate"));
    }
}
