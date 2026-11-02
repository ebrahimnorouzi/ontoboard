package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * The order ROBOT's command line and ODK need a folder's sheets in, and the sheets that have
 * none: Template... in Protege reads them all first, these builds read one after another.
 */
class SheetOrderTest {

    private static List<List<String>> rows(String... lines) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String line : lines) {
            rows.add(new ArrayList<String>(Arrays.asList(line.split("\\|", -1))));
        }
        return rows;
    }

    private static SheetOrder orderOf(Map<String, List<List<String>>> sheets,
            OWLOntology ontology) {
        LabelIndex index = LabelIndex.empty().plus(ontology, "the open ontology");
        for (Map.Entry<String, List<List<String>>> each : sheets.entrySet()) {
            index = index.plus(each.getKey(), each.getValue());
        }
        return SheetOrder.of(sheets, index);
    }

    /** Sheets named in the order a file listing gives them, which is not the order that works. */
    private static Map<String, List<List<String>>> aFolder() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("bakings", rows(
                "ID|Type|Name|Pizza|Baker",
                "ID|TYPE|A rdfs:label|I ex:produces|I ex:bakedBy",
                "ex:b1|ex:Baking|baking one|margherita|ex:luigi"));
        sheets.put("bakers", rows(
                "ID|Type|Name|Works at",
                "ID|TYPE|A rdfs:label|I ex:worksAt",
                "ex:luigi|ex:Baker|Luigi|Da Luigi"));
        sheets.put("pizzas", rows(
                "ID|Type|Name",
                "ID|TYPE|A rdfs:label",
                "ex:m1|ex:Pizza|margherita"));
        sheets.put("pizzerias", rows(
                "ID|Type|Name",
                "ID|TYPE|A rdfs:label",
                "ex:p1|ex:Pizzeria|Da Luigi"));
        return sheets;
    }

    @Test
    void eachSheetComesAfterTheSheetsItNamesByLabel() {
        SheetOrder order = orderOf(aFolder(), null);

        assertTrue(order.hasReferences());
        assertEquals(Arrays.asList("pizzas", "pizzerias", "bakings", "bakers"), order.getOrder());
        assertEquals(Collections.singleton("pizzas"), order.needsOf("bakings"),
                "margherita by label; ex:luigi by ID needs no order, ROBOT expands an ID itself");
        assertTrue(order.getCycles().isEmpty(), order.getCycles().toString());
    }

    @Test
    void aParentNamedByLabelInAClassExpressionColumnCounts() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("classes", rows("ID|Label|Parent", "ID|LABEL|SC %",
                "ex:Pizza|pizza|'food'"));
        sheets.put("food", rows("ID|Label", "ID|LABEL", "ex:Food|food"));

        assertEquals(Arrays.asList("food", "classes"), orderOf(sheets, null).getOrder());
    }

    @Test
    void aNameTheSheetDefinesItselfNeedsNoOtherSheet() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("organisations", rows("ID|Type|Name|Part of",
                "ID|TYPE|A rdfs:label|I ex:partOf",
                "ex:o1|ex:Org|Some Institute|", "ex:o2|ex:Org|Some Lab|Some Institute"));
        sheets.put("people", rows("ID|Type|Name|Member of",
                "ID|TYPE|A rdfs:label|I ex:memberOf",
                "ex:o1|ex:Org|Some Institute|", "ex:p1|ex:Person|Alice|Some Institute"));

        SheetOrder order = orderOf(sheets, null);

        assertFalse(order.hasReferences(), "each sheet defines the name it uses: "
                + order.getCycles());
    }

    @Test
    void aSheetThatNamesASheetOfACycleComesAfterTheWholeCycle() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("a", rows("ID|Type|Name|Uses", "ID|TYPE|A rdfs:label|I ex:uses",
                "ex:a1|ex:Thing|first|second"));
        sheets.put("b", rows("ID|Type|Name|Uses", "ID|TYPE|A rdfs:label|I ex:uses",
                "ex:b1|ex:Thing|second|third"));
        sheets.put("c", rows("ID|Type|Name|Uses", "ID|TYPE|A rdfs:label|I ex:uses",
                "ex:c1|ex:Thing|third|second"));

        SheetOrder order = orderOf(sheets, null);

        assertEquals(Collections.singletonList(Arrays.asList("b", "c")), order.getCycles());
        assertEquals(Arrays.asList("b", "c", "a"), order.getOrder());
    }

    @Test
    void aSplitOnLineBreaksIsReadAsRobotReadsIt() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        List<List<String>> affiliations = rows("ID|Type|Name|Member of",
                "ID|TYPE|A rdfs:label|I ex:memberOf SPLIT=\\n", "ex:p1|ex:Person|Alice|x");
        affiliations.get(2).set(3, "Org A\nOrg B");
        sheets.put("affiliations", affiliations);
        sheets.put("orgs", rows("ID|Type|Name", "ID|TYPE|A rdfs:label",
                "ex:o1|ex:Org|Org A", "ex:o2|ex:Org|Org B"));

        assertEquals(Collections.singleton("orgs"),
                orderOf(sheets, null).needsOf("affiliations"));
    }

    @Test
    void twoSheetsThatNameEachOtherHaveNoOrderAndAreNamed() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("people", rows(
                "ID|Type|Name|Member of",
                "ID|TYPE|A rdfs:label|I ex:memberOf",
                "ex:alice|ex:Person|Alice|Some Institute"));
        sheets.put("institutes", rows(
                "ID|Type|Name|Headed by",
                "ID|TYPE|A rdfs:label|I ex:headedBy",
                "ex:i1|ex:Institute|Some Institute|Alice"));
        sheets.put("cities", rows(
                "ID|Type|Name",
                "ID|TYPE|A rdfs:label",
                "ex:c1|ex:City|Karlsruhe"));

        SheetOrder order = orderOf(sheets, null);

        assertEquals(Collections.singletonList(Arrays.asList("people", "institutes")),
                order.getCycles());
        assertEquals(Arrays.asList("people", "institutes", "cities"), order.getOrder(),
                "the cycle stands together, where the folder's order puts it");
    }

    @Test
    void aNameTheOntologyDefinesNeedsNoSheetFirst() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology;
        try {
            ontology = manager.createOntology(IRI.create("http://example.org/o"));
        } catch (Exception cannot) {
            throw new IllegalStateException(cannot);
        }
        IRI pizzeria = IRI.create("http://example.org/o#DaLuigi");
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLNamedIndividual(pizzeria)));
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                pizzeria, factory.getOWLLiteral("Da Luigi")));
        Map<String, List<List<String>>> sheets = aFolder();

        SheetOrder order = orderOf(sheets, ontology);

        assertFalse(order.needsOf("bakers").contains("pizzerias"),
                "Da Luigi is in the ontology, so the bakers need no sheet for it");
    }

    @Test
    void sheetsThatNameNoOtherSheetHaveNoOrderToGive() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("pizzas", rows("ID|Name", "ID|A rdfs:label", "ex:m1|margherita"));
        sheets.put("cities", rows("ID|Name", "ID|A rdfs:label", "ex:c1|Karlsruhe"));

        assertFalse(orderOf(sheets, null).hasReferences());
    }

    /** A quoted ID is looked up by name, so unlike a bare one it needs its sheet first. */
    @Test
    void anIdInQuotesNeedsItsSheetFirstAndABareOneDoesNot() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("people", rows("ID|Type|Name|Member of|Visits",
                "ID|TYPE|A rdfs:label|I ex:memberOf|I ex:visits",
                "ex:alice|ex:Person|Alice|'ex:i1'|ex:c1"));
        sheets.put("institutes", rows("ID|Type|Name", "ID|TYPE|A rdfs:label",
                "ex:i1|ex:Institute|Some Institute"));
        sheets.put("cities", rows("ID|Type|Name", "ID|TYPE|A rdfs:label",
                "ex:c1|ex:City|Karlsruhe"));

        assertEquals(Collections.singleton("institutes"),
                orderOf(sheets, null).needsOf("people"));
    }
}
