package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A cell that writes a datatype its column's template string already gives (1.135.2).
 *
 * <p>Found on a knowledge graph built from sheets: a date written {@code 2026-01-15^^xsd:date} in a
 * column {@code AT dcterms:date^^xsd:date}. The column gives every cell its datatype, so ROBOT does
 * not read one in the cell: the suffix stays in the text, and the date came out as
 * {@code "2026-01-15^^xsd:date"^^xsd:date}, which is no date. Neither Check sheets... nor
 * Template... said a word. In a column whose template string does not type it - an {@code I}
 * column for a data property - {@code 8^^xsd:decimal} is how a cell is typed, and is right.
 */
class ADatatypeInATypedColumnTest {

    private static final String EX = "http://example.org/kg/";

    /** Runs, each with a date in a column typed by {@code spec}. */
    private static List<List<String>> runs(String spec, String... dates) {
        List<List<String>> table = new ArrayList<List<String>>();
        table.add(Arrays.asList("ID", "Name", "Date"));
        table.add(Arrays.asList("ID", "LABEL", spec));
        for (int at = 0; at < dates.length; at++) {
            table.add(Arrays.asList("ex:r" + (at + 1), "run " + (at + 1), dates[at]));
        }
        return table;
    }

    private static List<SheetAudit.Finding> twice(List<SheetAudit.Finding> all) {
        List<SheetAudit.Finding> found = new ArrayList<SheetAudit.Finding>();
        for (SheetAudit.Finding finding : all) {
            if (finding.getKind() == SheetAudit.Kind.DATATYPE_TWICE) {
                found.add(finding);
            }
        }
        return found;
    }

    /** What the builder does with such a cell: why it is a finding at all. */
    @Test
    void robotKeepsTheSuffixInTheText() throws Exception {
        OWLOntology context = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/kg"));
        Map<String, String> prefixes = new LinkedHashMap<String, String>();
        prefixes.put("ex:", EX);
        prefixes.put("dcterms:", "http://purl.org/dc/terms/");

        TemplateSheet.Result built = TemplateSheet.run("runs.tsv",
                runs("AT dcterms:date^^xsd:date", "2026-01-15^^xsd:date"), context, prefixes,
                null);

        String written = null;
        for (OWLAnnotationAssertionAxiom axiom
                : built.getOntology().getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (axiom.getProperty().getIRI().toString().equals("http://purl.org/dc/terms/date")) {
                written = ((OWLLiteral) axiom.getValue()).getLiteral();
            }
        }
        assertEquals("2026-01-15^^xsd:date", written, built.getOntology().getAxioms().toString());
    }

    @Test
    void checkSheetsSaysSoAndOffersTheValueAlone() {
        List<SheetAudit.Finding> found = twice(SheetAudit.of("runs",
                runs("AT dcterms:date^^xsd:date", "2026-01-15", "2026-01-15^^xsd:date"),
                LabelIndex.empty()));

        assertEquals(1, found.size(), found.toString());
        assertEquals(4, found.get(0).getRow(), "the second run's row");
        assertEquals("Date", found.get(0).getHeading());
        assertEquals("2026-01-15", found.get(0).getSuggestion());
        assertTrue(found.get(0).getMessage().contains("xsd:date"), found.get(0).getMessage());
    }

    @Test
    void eachValueOfASplitCellLosesItsSuffix() {
        List<SheetAudit.Finding> found = twice(SheetAudit.of("runs",
                runs("AT dcterms:date^^xsd:date SPLIT=|", "2026-01-15^^xsd:date|2026-01-16"),
                LabelIndex.empty()));

        assertEquals(1, found.size(), found.toString());
        assertEquals("2026-01-15|2026-01-16", found.get(0).getSuggestion());
    }

    /** Sheets type their numbers this way, and ROBOT reads it: no finding. */
    @Test
    void aDatatypeInACellOfAnUntypedColumnIsHowTheCellIsTyped() {
        List<List<String>> table = new ArrayList<List<String>>();
        table.add(Arrays.asList("ID", "Name", "Number"));
        table.add(Arrays.asList("ID", "LABEL", "I obo:OBI_0001937"));
        table.add(Arrays.asList("ex:v1", "value 1", "8^^xsd:decimal"));

        assertTrue(twice(SheetAudit.of("values", table, LabelIndex.empty())).isEmpty());
    }
}
