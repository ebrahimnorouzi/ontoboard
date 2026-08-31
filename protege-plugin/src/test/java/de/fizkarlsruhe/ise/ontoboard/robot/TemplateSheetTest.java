package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * A spreadsheet of terms, turned into axioms.
 *
 * <p>The single most used ROBOT feature in OBO projects, and the one that decides whether somebody
 * who knows the subject but not Manchester syntax can contribute at all.
 *
 * <p>Most of these tests are about the failure path, because that is where the value is. ROBOT
 * stops at the first bad row, so twelve mistakes in a spreadsheet means twelve runs - and each
 * round trip interrupts somebody who was thinking about polymers rather than about tooling.
 */
class TemplateSheetTest {

    private static final String NS = "http://example.org/o#";

    @TempDir
    File dir;

    private OWLOntology context;
    private Map<String, String> prefixes;

    @BeforeEach
    void anOntologyToWriteInto() throws Exception {
        context = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/o"));
        prefixes = new LinkedHashMap<String, String>();
        prefixes.put("ex:", NS);
    }

    private List<List<String>> sheet(String... lines) throws Exception {
        File file = new File(dir, "terms.tsv");
        Files.write(file.toPath(), String.join("\n", lines).getBytes(Charset.forName("UTF-8")));
        return TemplateSheet.read(file);
    }

    private TemplateSheet.Result run(String... lines) throws Exception {
        return TemplateSheet.run("terms.tsv", sheet(lines), context, prefixes, null);
    }

    private static String tabs(String... cells) {
        return String.join("\t", cells);
    }

    // ---------- it does the thing ----------

    @Test
    void aSpreadsheetBecomesAxioms() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent", "Definition"),
                tabs("ID", "LABEL", "SC %", "A IAO:0000115"),
                tabs("ex:0001", "polymer", "owl:Thing", "a big molecule"),
                tabs("ex:0002", "copolymer", "ex:0001", "made of two monomers"));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertEquals(2, result.getDataRows());
        OWLOntology out = result.getOntology();
        assertNotNull(out);

        OWLDataFactory factory = out.getOWLOntologyManager().getOWLDataFactory();
        assertTrue(out.containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "0002")),
                factory.getOWLClass(IRI.create(NS + "0001")))),
                "the parent column did not become a SubClassOf axiom: " + out.getAxioms());
        assertTrue(out.containsAxiom(factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000115")),
                IRI.create(NS + "0001"), factory.getOWLLiteral("a big molecule"))),
                "the definition column did not become IAO:0000115: " + out.getAxioms());
    }

    /** A parent named by its label, which is how a domain expert writes one. */
    @Test
    void aParentCanBeNamedByItsLabel() throws Exception {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        IRI material = IRI.create(NS + "Material");
        context.getOWLOntologyManager().addAxiom(context,
                factory.getOWLDeclarationAxiom(factory.getOWLClass(material)));
        context.getOWLOntologyManager().addAxiom(context,
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(), material,
                        factory.getOWLLiteral("material")));

        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0003", "steel", "material"));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertTrue(result.getOntology().containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "0003")),
                factory.getOWLClass(material))));
    }

    // ---------- every problem at once, which is the point ----------

    /**
     * The reason this class exists rather than a direct call to ROBOT. ROBOT stops at the first
     * bad row; a spreadsheet with three mistakes would take three runs to find them all.
     */
    @Test
    void everyBadRowIsReportedNotJustTheFirst() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0001", "fine", "owl:Thing"),
                tabs("ex:0002", "bad one", "no such term"),
                tabs("ex:0003", "also fine", "owl:Thing"),
                tabs("ex:0004", "bad two", "another missing term"));

        assertEquals(2, result.getProblems().size(),
                "only the first bad row was reported: " + result.getProblems());
        assertEquals(4, result.getProblems().get(0).getRow());
        assertEquals(6, result.getProblems().get(1).getRow());
    }

    /** A row number that matches what the spreadsheet shows, or it is no help at all. */
    @Test
    void aProblemSaysWhereItIsInTheSpreadsheet() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0002", "bad one", "no such term"));

        TemplateSheet.Problem problem = result.getProblems().get(0);
        assertEquals(3, problem.getRow(), "the first data row is row 3, as a spreadsheet shows it");
        assertEquals(3, problem.getColumn());
        assertEquals("Parent", problem.getColumnName(),
                "the heading the author wrote is how they will find the column");
        assertTrue(problem.getCell().contains("no such term"), problem.getCell());
        assertTrue(problem.where().contains("row 3"), problem.where());
        assertTrue(problem.where().contains("Parent"), problem.where());
    }

    /**
     * The trap in checking rows separately: a row whose parent is introduced two rows later would
     * be reported as broken. A confident, precise, wrong finding is worse than the one real error
     * ROBOT gives.
     */
    @Test
    void aRowMayReferToATermTheSheetItselfIntroduces() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0001", "child", "ex:0002"),
                tabs("ex:0002", "parent defined later", "owl:Thing"),
                tabs("ex:0003", "broken", "no such term"));

        assertEquals(1, result.getProblems().size(),
                "a row referring forward within the sheet was wrongly blamed: "
                        + result.getProblems());
        assertEquals(5, result.getProblems().get(0).getRow());
    }

    /** Even by label, which is how somebody building a small hierarchy in a sheet writes it. */
    @Test
    void aRowMayReferToASiblingRowByLabel() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0001", "child", "parent defined later"),
                tabs("ex:0002", "parent defined later", "owl:Thing"));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
    }

    /** The good rows still arrive. Losing forty correct rows over one typo is not acceptable. */
    @Test
    void theGoodRowsSurviveTheBadOnes() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0001", "fine", "owl:Thing"),
                tabs("ex:0002", "broken", "no such term"));

        OWLDataFactory factory = result.getOntology().getOWLOntologyManager().getOWLDataFactory();
        assertTrue(result.getOntology().containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "0001")), factory.getOWLThing())),
                "the good row was lost with the bad one: " + result.getOntology().getAxioms());
        assertFalse(result.getProblems().isEmpty());
    }

    // ---------- the template row is a different kind of problem ----------

    /**
     * A bad template row means nothing was produced and no row is salvageable, which is different
     * in kind from a bad data row - and blaming a row for it would send somebody looking in the
     * wrong place.
     */
    @Test
    void aBrokenTemplateRowIsNotBlamedOnARow() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label"),
                tabs("ID", "NONSENSE"),
                tabs("ex:0001", "thing"));

        assertTrue(result.isTemplateUnusable());
        assertNull(result.getOntology());
        assertEquals(1, result.getProblems().size());
        assertEquals(0, result.getProblems().get(0).getRow());
        assertTrue(result.getProblems().get(0).getMessage().contains("NONSENSE"),
                result.getProblems().get(0).getMessage());
    }

    @Test
    void aSheetWithNoTemplateRowSaysWhatIsMissing() throws Exception {
        TemplateSheet.Result result = run(tabs("ID", "Label"));

        assertTrue(result.isTemplateUnusable());
        assertTrue(result.getProblems().get(0).getMessage().contains("two rows"),
                result.getProblems().get(0).getMessage());
    }

    /** ROBOT's own message is kept, but its documentation anchor and layout are not. */
    @Test
    void robotsMessageIsReadableInATableCell() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:0001", "thing", "no such term"));

        String message = result.getProblems().get(0).getMessage();
        assertFalse(message.contains("\n"), message);
        assertFalse(message.contains("\t"), message);
        assertFalse(message.contains("template#"),
                "a link to ROBOT's docs site means nothing in a dialog: " + message);
        assertTrue(message.toLowerCase().contains("no such term"), message);
    }

    // ---------- the project's own prefixes ----------

    @Test
    void anUnknownPrefixIsReportedAgainstItsRow() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label"),
                tabs("ID", "LABEL"),
                tabs("nope:0001", "thing"));

        assertFalse(result.getProblems().isEmpty());
        assertEquals(3, result.getProblems().get(0).getRow());
    }

    /** Protege spells a prefix "ex:"; ROBOT wants "ex". Getting that wrong breaks every row. */
    @Test
    void protegesPrefixSpellingIsAccepted() throws Exception {
        Map<String, String> withColon = Collections.singletonMap("ex:", NS);
        TemplateSheet.Result result = TemplateSheet.run("terms.tsv",
                sheet(tabs("ID", "Label"), tabs("ID", "LABEL"), tabs("ex:0001", "thing")),
                context, withColon, null);

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
    }

    // ---------- odds and ends ----------

    @Test
    void aBlankTrailingRowIsNotAProblem() throws Exception {
        TemplateSheet.Result result = run(
                tabs("ID", "Label"),
                tabs("ID", "LABEL"),
                tabs("ex:0001", "thing"),
                tabs("", ""));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
    }

    @Test
    void aMissingFileSaysSo() {
        assertThrows(RobotException.class,
                () -> TemplateSheet.read(new File(dir, "not-there.tsv")));
        assertThrows(RobotException.class, () -> TemplateSheet.read(null));
    }

    @Test
    void aCsvWorksToo() throws Exception {
        File file = new File(dir, "terms.csv");
        Files.write(file.toPath(), String.join("\n",
                "ID,Label", "ID,LABEL", "ex:0001,thing").getBytes(Charset.forName("UTF-8")));

        TemplateSheet.Result result = TemplateSheet.run("terms.csv",
                TemplateSheet.read(file), context, prefixes, null);

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertEquals(1, result.getDataRows());
    }

    // ---------- the starter, which is what gets somebody past a blank page ----------

    /** The starter has to be a working template, not a picture of one. */
    @Test
    void theStarterTemplateItselfRuns() throws Exception {
        File file = new File(dir, "starter.tsv");
        Files.write(file.toPath(), TemplateSheet.starter("ex").getBytes(Charset.forName("UTF-8")));

        TemplateSheet.Result result = TemplateSheet.run("starter.tsv",
                TemplateSheet.read(file), context, prefixes, null);

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertFalse(result.isTemplateUnusable());
        assertTrue(result.getAxiomCount() > 0);
    }

    @Test
    void theStarterUsesTheProjectsOwnPrefix() {
        assertTrue(TemplateSheet.starter("mwo").contains("mwo:0000001"));
        assertTrue(TemplateSheet.starter(null).contains("ex:0000001"));
        assertTrue(TemplateSheet.starter("  ").contains("ex:0000001"));
    }

    /** The columns an OBO term needs anyway, so the file doubles as an explanation. */
    @Test
    void theStarterCarriesTheColumnsATermActuallyNeeds() {
        String starter = TemplateSheet.starter("ex");
        assertTrue(starter.contains("IAO:0000115"), "a definition");
        assertTrue(starter.contains("IAO:0000119"), "where the definition came from");
        assertTrue(starter.contains("SC %"), "a parent");
        assertTrue(starter.startsWith("ID\t"), starter.substring(0, 20));
    }
}
