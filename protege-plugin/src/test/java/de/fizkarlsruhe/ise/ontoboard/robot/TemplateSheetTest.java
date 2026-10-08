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

    // ---------- a column that is wrong in a way ROBOT does not mind ----------

    /**
     * A sheet that ROBOT accepts, produces axioms from, and gets wrong.
     *
     * <p>Every other failure in this class is one ROBOT objects to somehow. This one it does
     * not: {@code A rdfs:label@en} succeeds in strict mode, so none of the machinery for
     * placing problems is ever entered, and the sheet is reported as perfect. What it actually
     * writes is an annotation on a property named
     * {@code http://www.w3.org/2000/01/rdf-schema#label@en} - the language tag glued onto the
     * property IRI - carrying an untagged {@code xsd:string}. So the cell is not a label in any
     * language, and {@code isLabel()} is false.
     *
     * <p>Measured on the MatWerk knowledge graph: its {@code organization} sheet has two such
     * columns across 81 filled cells, which is why none of its organizations shows a name.
     */
    @Test
    void aLanguageTagOnTheWrongColumnKindIsReportedEvenThoughTheSheetSucceeds() throws Exception {
        TemplateSheet.Result result = run(
                tabs("#", "Label", "DE label"),
                tabs("ID", "A rdfs:label@en", "A rdfs:label@de"),
                tabs("ex:0001", "a name", "ein Name"));

        assertNotNull(result.getOntology(),
                "the sheet still produces its axioms; the point is that they are wrong");
        assertEquals(2, result.getProblems().size(), result.getProblems().toString());
        for (TemplateSheet.Problem problem : result.getProblems()) {
            assertEquals(0, problem.getRow(),
                    "this is a fault in the template row, not in a data row");
            assertTrue(problem.getColumn() > 0,
                    "the problem must name its column: " + problem);
            assertTrue(problem.getMessage().contains("AL"), problem.getMessage());
        }
        assertEquals("Label", result.getProblems().get(0).getColumnName());
        assertEquals("DE label", result.getProblems().get(1).getColumnName());
    }

    /** And the correct spelling produces a real label, with its tag, and no complaint. */
    @Test
    void theCorrectedSpellingProducesARealLabelWithItsLanguage() throws Exception {
        TemplateSheet.Result result = run(
                tabs("#", "Label"),
                tabs("ID", "AL rdfs:label@en"),
                tabs("ex:0001", "a name"));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        boolean found = false;
        for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                : result.getOntology().getAnnotationAssertionAxioms(IRI.create(NS + "0001"))) {
            assertTrue(axiom.getProperty().isLabel(),
                    "not rdfs:label but " + axiom.getProperty().getIRI());
            org.semanticweb.owlapi.model.OWLLiteral value =
                    (org.semanticweb.owlapi.model.OWLLiteral) axiom.getValue();
            assertEquals("a name", value.getLiteral());
            assertEquals("en", value.getLang());
            found = true;
        }
        assertTrue(found, "no annotation was produced at all");
    }

    /**
     * The 25 other MatWerk sheets carry no such fault, and nor do the real templates in PMDco
     * and ECTO. A check that fires on a working sheet would be worse than no check.
     */
    @Test
    void theColumnShapesRealProjectsUseAreNotReported() throws Exception {
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Label", "Definition", "Source", "Parent", "Located in"),
                tabs("ID", "TYPE", "AL rdfs:label@en", "A IAO:0000115", ">A IAO:0000119",
                        "SC %", "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,"),
                tabs("ex:0001", "owl:Class", "a name", "What it means.", "https://doi.org/x",
                        "owl:Thing", ""));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
    }

    // ---------- a column that points at another term ----------

    /**
     * An {@code I} column - one whose cells name another term - is the kind a knowledge graph is
     * mostly made of. The MatWerk KG spreadsheet this was measured against is built from 26 such
     * sheets, where six of {@code organization}'s twelve columns and eight of
     * {@code dataportal}'s are {@code I} columns.
     *
     * <p>robot-core 1.9.8 raises a bare {@code NullPointerException("object cannot be null")} for
     * a cell in one of those columns that is neither an IRI, nor a CURIE with a known prefix, nor
     * the label of a term that exists - and it does so in partial mode as well as strict. These
     * tests pin what OntoBoard does with that, because what it used to do was discard the entire
     * sheet and report "object cannot be null" against no row and no column.
     */
    private void anIndividualCalled(String localName, String label) {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        IRI iri = IRI.create(NS + localName);
        context.getOWLOntologyManager().addAxiom(context,
                factory.getOWLDeclarationAxiom(factory.getOWLNamedIndividual(iri)));
        context.getOWLOntologyManager().addAxiom(context,
                factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(), iri,
                        factory.getOWLLiteral(label)));
    }

    private void aVocabularyOfOrganisations() {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + "hostedBy"))));
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Portal"))));
        anIndividualCalled("org1", "Fraunhofer-Gesellschaft");
    }

    /** Nine good rows and one typo. Losing the nine is not an acceptable answer. */
    @Test
    void theGoodRowsSurviveACellThatPointsAtNothing() throws Exception {
        aVocabularyOfOrganisations();
        String[] lines = new String[12];
        lines[0] = tabs("#", "TYPE", "Name", "Host institute");
        lines[1] = tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy");
        for (int row = 1; row <= 9; row++) {
            lines[row + 1] = tabs("ex:p" + row, "ex:Portal", "portal " + row,
                    "Fraunhofer-Gesellschaft");
        }
        lines[11] = tabs("ex:pX", "ex:Portal", "the one with a typo", "Fraunhofer-Gesellschafft");

        TemplateSheet.Result result = run(lines);

        assertNotNull(result.getOntology(),
                "one bad cell discarded the whole sheet: " + result.getProblems());
        OWLDataFactory factory = result.getOntology().getOWLOntologyManager().getOWLDataFactory();
        assertTrue(result.getOntology().containsAxiom(factory.getOWLObjectPropertyAssertionAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + "hostedBy")),
                factory.getOWLNamedIndividual(IRI.create(NS + "p1")),
                factory.getOWLNamedIndividual(IRI.create(NS + "org1")))),
                "a good row's assertion was lost with the bad row");
        assertEquals(1, result.getProblems().size(), result.getProblems().toString());
    }

    /**
     * The fault is in the fourth column, and the fourth column is what must be named.
     *
     * <p>This test exists because the first implementation of the search blamed {@code TYPE}. It
     * looked for a cell that, when emptied, let the row build - and emptying {@code TYPE} does
     * exactly that, by creating no individual at all, so the real fault is never reached. A
     * precise and confident wrong answer is worse than the vague one it replaced.
     */
    @Test
    void theColumnAtFaultIsTheOneNamedAndNotTheTypeColumn() throws Exception {
        aVocabularyOfOrganisations();
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:pX", "ex:Portal", "a portal", "Fraunhofer-Gesellschafft"));

        assertEquals(1, result.getProblems().size(), result.getProblems().toString());
        TemplateSheet.Problem problem = result.getProblems().get(0);
        assertEquals(3, problem.getRow());
        assertEquals(4, problem.getColumn(),
                "blamed the wrong column: " + problem.getColumnName());
        assertEquals("Host institute", problem.getColumnName());
        assertEquals("Fraunhofer-Gesellschafft", problem.getCell());
    }

    /** "object cannot be null" describes a variable. This says what to do about it. */
    @Test
    void theReasonSaysWhatThatColumnNeeds() throws Exception {
        aVocabularyOfOrganisations();
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:pX", "ex:Portal", "a portal", "Fraunhofer-Gesellschafft"));

        String message = result.getProblems().get(0).getMessage();
        assertTrue(message.contains("Fraunhofer-Gesellschafft"),
                "the reason does not quote the cell: " + message);
        assertTrue(message.contains("label"), message);
        assertFalse(message.contains("object cannot be null"),
                "ROBOT's internal message reached the user: " + message);
    }

    /** A CURIE whose prefix nothing defines is a different mistake, and gets a different answer. */
    @Test
    void anUndefinedPrefixInAReferenceColumnNamesThePrefix() throws Exception {
        aVocabularyOfOrganisations();
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:pX", "ex:Portal", "a portal", "zz:0001"));

        String message = result.getProblems().get(0).getMessage();
        assertTrue(message.contains("zz"), "the undefined prefix is not named: " + message);
    }

    /**
     * A row may point at a term another row of the same sheet introduces, in an {@code I} column
     * too - not only in a parent column. Rows are built in as many passes as the chain is long.
     */
    @Test
    void aReferenceColumnMayPointAtATermAnotherRowIntroduces() throws Exception {
        aVocabularyOfOrganisations();
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:p1", "ex:Portal", "a portal", "an institute defined below"),
                tabs("ex:org2", "ex:Portal", "an institute defined below", ""),
                tabs("ex:pX", "ex:Portal", "the broken one", "nothing is called this"));

        assertEquals(1, result.getProblems().size(),
                "a row pointing forward within the sheet was wrongly blamed: "
                        + result.getProblems());
        assertEquals(5, result.getProblems().get(0).getRow());
        OWLDataFactory factory = result.getOntology().getOWLOntologyManager().getOWLDataFactory();
        assertTrue(result.getOntology().containsAxiom(factory.getOWLObjectPropertyAssertionAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + "hostedBy")),
                factory.getOWLNamedIndividual(IRI.create(NS + "p1")),
                factory.getOWLNamedIndividual(IRI.create(NS + "org2")))),
                "the forward reference did not resolve: " + result.getOntology().getAxioms());
    }

    /** Two bad cells in two rows are two problems, not one and a shrug. */
    @Test
    void everyCellThatPointsAtNothingIsReported() throws Exception {
        aVocabularyOfOrganisations();
        TemplateSheet.Result result = run(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:p1", "ex:Portal", "fine", "Fraunhofer-Gesellschaft"),
                tabs("ex:p2", "ex:Portal", "first typo", "Fraunhofer-Gesellschafft"),
                tabs("ex:p3", "ex:Portal", "second typo", "Fraunhofer Gesellschaft e.V."));

        assertEquals(2, result.getProblems().size(), result.getProblems().toString());
        assertEquals(4, result.getProblems().get(0).getRow());
        assertEquals(5, result.getProblems().get(1).getRow());
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
