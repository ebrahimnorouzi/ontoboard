package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.sheet.LabelIndex;
import de.fizkarlsruhe.ise.ontoboard.sheet.SheetAudit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Template... and Check sheets... count the names that mean more than one thing the same way,
 * and as many as Check sheets... lists (1.135.2).
 *
 * <p>Found on a knowledge graph built from sheets: Template... said that more names meant more
 * than one thing across these sheets than Check sheets..., where it sent the reader, listed.
 * Both warnings counted every label the index holds twice - comments taken for names, and labels
 * the imported ontologies give two terms each, which no sheet uses - while the list held only the
 * names the sheets' own rows are given.
 */
class TemplateSaysWhatCheckSheetsListsTest {

    private static final String EX = "http://example.org/kg/";
    private static final Pattern COUNT =
            Pattern.compile("(\\d+) names? means? more than one thing");

    private OWLOntology ontology;

    /** An ontology that, with what it imports, gives two terms the label "area". */
    @BeforeEach
    void anOntologyWithOneNameForTwoTerms() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        for (String iri : new String[] {"http://example.org/one#area",
            "http://example.org/two#area", "http://example.org/o#plan"}) {
            OWLClass cls = df.getOWLClass(IRI.create(iri));
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                    cls.getIRI(), df.getOWLLiteral(iri.endsWith("plan") ? "plan" : "area")));
        }
    }

    private static List<List<String>> sheet(String[]... rows) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] row : rows) {
            table.add(Arrays.asList(row));
        }
        return table;
    }

    /** Two rows given one name, and one comment between them. */
    private static Map<String, List<List<String>>> sheets() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("specimens.tsv", sheet(
                new String[] {"ID", "Type", "Name", "Comment"},
                new String[] {"ID", "TYPE", "AL rdfs:label@en", "AL rdfs:comment@en"},
                new String[] {EX + "s1", "plan", "Ada", "the same comment"},
                new String[] {EX + "s2", "plan", "Ada", "the same comment"}));
        return sheets;
    }

    private LabelIndex indexOf(Map<String, List<List<String>>> sheets) {
        LabelIndex index = LabelIndex.empty().plus(ontology, "the open ontology");
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            index = index.plus(sheet.getKey(), sheet.getValue());
        }
        return index;
    }

    /** The names Check sheets... lists as meaning more than one thing. */
    private Set<String> listed(Map<String, List<List<String>>> sheets) {
        LabelIndex index = indexOf(sheets);
        Set<String> names = new LinkedHashSet<String>();
        for (Map.Entry<String, List<List<String>>> sheet : sheets.entrySet()) {
            for (SheetAudit.Finding finding
                    : SheetAudit.of(sheet.getKey(), sheet.getValue(), index)) {
                if (finding.getKind() == SheetAudit.Kind.AMBIGUOUS_NAME) {
                    names.add(finding.getName());
                }
            }
        }
        return names;
    }

    /** The number a warning gives, or 0 when no warning was given. */
    private static int counted(OperationResult.Builder result) {
        for (String line : result.summary("done").build().getLog()) {
            Matcher number = COUNT.matcher(line);
            if (number.find()) {
                return Integer.parseInt(number.group(1));
            }
        }
        return 0;
    }

    private int checkSheetsSays(Map<String, List<List<String>>> sheets) {
        OperationResult.Builder result = OperationResult.of("Check sheets");
        SheetCheckAction.warnAmbiguous(sheets, indexOf(sheets), result);
        return counted(result);
    }

    private int templateSays(Map<String, List<List<String>>> sheets) {
        OperationResult.Builder result = OperationResult.of("Template");
        TemplateAction.warnAboutTheSheets(sheets, null, ontology, result);
        return counted(result);
    }

    @Test
    void bothCountTheNamesTheSheetsUseAsCheckSheetsListsThem() {
        Map<String, List<List<String>>> sheets = sheets();

        assertEquals(new LinkedHashSet<String>(Arrays.asList("Ada")), listed(sheets));
        assertEquals(1, checkSheetsSays(sheets), "Check sheets... says what it lists");
        assertEquals(1, templateSays(sheets), "and so does Template...");
    }

    /** A cell naming "area" gets whichever of the two terms ROBOT picks: that is the sheets'. */
    @Test
    void aCellNamingANameTheOntologyGivesTwoThingsIsListedAndCounted() {
        Map<String, List<List<String>>> sheets = sheets();
        sheets.put("measurements.tsv", sheet(
                new String[] {"ID", "Type", "Name"},
                new String[] {"ID", "TYPE", "AL rdfs:label@en"},
                new String[] {EX + "m1", "area", "front face"}));

        assertEquals(new LinkedHashSet<String>(Arrays.asList("Ada", "area")), listed(sheets));
        assertEquals(2, checkSheetsSays(sheets));
        assertEquals(2, templateSays(sheets));
    }

    /**
     * A name in a class expression - a parent, a filler, a domain - gets whichever term ROBOT
     * picks as well (1.135.2 review). Both actions said nothing of them.
     */
    @Test
    void aNameInAClassExpressionIsListedAndCounted() {
        Map<String, List<List<String>>> sheets = sheets();
        sheets.put("faces.tsv", sheet(
                new String[] {"ID", "Name", "Parent", "Measured as"},
                new String[] {"ID", "AL rdfs:label@en", "SC %", "SC 'has quality' some %"},
                new String[] {EX + "f1", "front face", "area", ""},
                new String[] {EX + "f2", "back face", "plan", "'area'"},
                new String[] {EX + "f3", "side face", "(plan and not area)", ""}));

        assertEquals(new LinkedHashSet<String>(Arrays.asList("Ada", "area")), listed(sheets));
        assertEquals(2, checkSheetsSays(sheets));
        assertEquals(2, templateSays(sheets));
    }

    /**
     * A cell is read as ROBOT reads it (1.135.2 review): as an expression only when it starts with
     * a quote or a bracket, or is one word; any other cell is one name, its spaces, keywords and
     * all. "measured value" was cut at "value", and "plan and not area", which ROBOT takes for one
     * name that nothing has, was listed for "area".
     */
    @Test
    void aCellOfSeveralWordsIsOneNameAsRobotReadsIt() {
        label("http://example.org/one#measured", "measured value");
        label("http://example.org/two#measured", "measured value");
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("faces.tsv", sheet(
                new String[] {"ID", "Name", "Parent"},
                new String[] {"ID", "AL rdfs:label@en", "SC %"},
                new String[] {EX + "f1", "front face", "measured value"},
                new String[] {EX + "f2", "back face", "plan and not area"}));

        assertEquals(new LinkedHashSet<String>(Arrays.asList("measured value")), listed(sheets));
    }

    /** An expression's own words, a literal and an IRI name no term. */
    @Test
    void anExpressionsKeywordsLiteralsAndIrisAreNoNames() {
        Map<String, List<List<String>>> sheets = new LinkedHashMap<String, List<List<String>>>();
        sheets.put("faces.tsv", sheet(
                new String[] {"ID", "Name", "Parent"},
                new String[] {"ID", "AL rdfs:label@en", "SC %"},
                new String[] {EX + "f1", "front face",
                    "(plan and ('has part' min 2 <http://example.org/one#area>))"},
                new String[] {EX + "f2", "back face", "'has label' value \"area\""}));

        assertTrue(listed(sheets).isEmpty(), listed(sheets).toString());
    }

    /** One more class with this label in the open ontology. */
    private void label(String iri, String text) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass cls = df.getOWLClass(IRI.create(iri));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(),
                cls.getIRI(), df.getOWLLiteral(text)));
    }

    @Test
    void templateWarnsOfADatatypeTheColumnAlreadyGives() {
        Map<String, List<List<String>>> sheets = sheets();
        sheets.put("runs.tsv", sheet(
                new String[] {"ID", "Name", "Date"},
                new String[] {"ID", "AL rdfs:label@en", "AT dcterms:date^^xsd:date"},
                new String[] {EX + "r1", "run 1", "2026-01-15^^xsd:date"}));

        OperationResult.Builder building = OperationResult.of("Template");
        TemplateAction.warnAboutTheSheets(sheets, "runs.tsv", ontology, building);
        String said = String.join("\n", building.summary("done").build().getLog());
        assertTrue(said.contains("runs.tsv row 3") && said.contains("2026-01-15^^xsd:date"),
                said);

        OperationResult.Builder another = OperationResult.of("Template");
        TemplateAction.warnAboutTheSheets(sheets, "specimens.tsv", ontology, another);
        String other = String.join("\n", another.summary("done").build().getLog());
        assertFalse(other.contains("^^"), "not a word about a sheet it does not build: " + other);
    }
}
