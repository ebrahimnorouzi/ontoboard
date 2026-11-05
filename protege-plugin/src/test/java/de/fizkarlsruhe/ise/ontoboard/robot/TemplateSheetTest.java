package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
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

    // ---------- the file is UTF-8, whatever the machine thinks ----------

    /**
     * A spreadsheet is read as UTF-8 and not as whatever the machine's default charset is.
     *
     * <p>{@code IOHelper.readTable(String)} opens the file with the platform default. Measured
     * on a Windows machine, where {@code Charset.defaultCharset()} is {@code windows-1252}: the
     * MatWerk city sheet holds the bytes {@code 4A C3 BC} for J&uuml;, and the reader returned
     * the <b>seven</b>-character string {@code J U+00C3 U+00BC lich} - which is exactly UTF-8
     * decoded as ISO-8859-1. So every German name in those sheets was entering the ontology as
     * mojibake, and nothing said so.
     *
     * <p>It hid because it is platform-dependent. On a machine whose default is already UTF-8
     * the old code is correct, so neither the suite nor a release build would show it. This
     * test is written in code points for the same reason: a console can mangle mojibake back
     * into the right-looking character.
     */
    @Test
    void aSheetIsReadAsUtf8WhateverTheMachineDefaultIs() throws Exception {
        String umlaut = "J" + ((char) 0x00fc) + "lich";
        String dash = "298" + ((char) 0x2014) + "305";
        File file = new File(dir, "city.tsv");
        Files.write(file.toPath(), ("#\tLabel\tRange\nID\tLABEL\tA rdfs:comment\n"
                + "ex:0001\t" + umlaut + "\t" + dash + "\n")
                .getBytes(Charset.forName("UTF-8")));

        List<List<String>> rows = TemplateSheet.read(file);

        assertEquals(umlaut, rows.get(2).get(1),
                "got " + rows.get(2).get(1).length() + " characters; 7 means the UTF-8 bytes "
                        + "were decoded one byte at a time");
        assertEquals(6, rows.get(2).get(1).length(),
                "J, u-umlaut, l, i, c, h - and 7 is the mojibake");
        assertEquals(dash, rows.get(2).get(2));
        assertEquals(7, rows.get(2).get(2).length());
    }

    @Test
    void aCsvIsReadAsUtf8Too() throws Exception {
        String umlaut = "Saarbr" + ((char) 0x00fc) + "cken";
        File file = new File(dir, "city.csv");
        Files.write(file.toPath(), ("#,Label\nID,LABEL\nex:0001," + umlaut + "\n")
                .getBytes(Charset.forName("UTF-8")));

        assertEquals(umlaut, TemplateSheet.read(file).get(2).get(1));
    }

    /**
     * A byte order mark is three bytes that decode to one invisible character. Left in place it
     * sits in front of the first heading, so {@code ID} is not recognised as {@code ID} and
     * nothing on screen shows why.
     */
    @Test
    void aByteOrderMarkDoesNotHideTheIdColumn() throws Exception {
        File file = new File(dir, "withbom.tsv");
        Files.write(file.toPath(), ("﻿#\tLabel\nID\tLABEL\nex:0001\tsteel\n")
                .getBytes(Charset.forName("UTF-8")));

        List<List<String>> rows = TemplateSheet.read(file);

        assertEquals("#", rows.get(0).get(0),
                "the mark is still in front of the first heading");
        TemplateSheet.Result result = TemplateSheet.run("withbom.tsv", rows, context,
                prefixes, null);
        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertEquals(1, result.getDataRows());
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

    // ---------- a prefix the ontology does not declare ----------

    /**
     * Found by rebuilding pizza-bfo's knowledge graph: its nine sheets write IDs as kg:PB_0000001,
     * while kg/pizzabfo-kg.ttl declares that address only as the empty prefix ":". Every row of
     * every sheet failed with ROBOT's "could not interpret 'kg:PB_0000001'", and nothing said that
     * one prefix declaration would fix all 25 of them, or which address it should have.
     */
    @Test
    void anUndeclaredIdPrefixSaysWhereToDeclareItAndWhichAddressFits() throws Exception {
        prefixes.put(":", "https://example.org/pizza/kg/");

        TemplateSheet.Result result = run(
                tabs("ID", "TYPE", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("kg:PB_0000001", "owl:NamedIndividual", "Karlsruhe"));

        assertEquals(1, result.getProblems().size(), result.getProblems().toString());
        String message = result.getProblems().get(0).getMessage();
        assertTrue(message.contains("Ontology Prefixes"),
                "says where in Protege a prefix is declared: " + message);
        assertTrue(message.contains("https://example.org/pizza/kg/"),
                "names the address the empty prefix already stands for: " + message);
        assertTrue(message.contains("\"kg:\""), "names the prefix: " + message);
    }

    @Test
    void theSheetWarningCarriesTheSameAdvice() {
        prefixes.put(":", "https://example.org/pizza/kg/");
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("01_cities.tsv", rows(
                tabs("ID", "TYPE", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("kg:PB_0000001", "owl:NamedIndividual", "Karlsruhe")));

        List<String> unavailable = TemplateSheet.namesOf(folder, context, prefixes)
                .getUnavailable();

        assertEquals(1, unavailable.size(), unavailable.toString());
        assertTrue(unavailable.get(0).contains("Ontology Prefixes")
                        && unavailable.get(0).contains("https://example.org/pizza/kg/"),
                unavailable.get(0));
    }

    @Test
    void withNoAddressThatFitsTheAdviceDoesNotGuess() {
        String advice = TemplateSheet.prefixAdvice("zz:0001", prefixes,
                java.util.Collections.<String>emptySet());

        assertTrue(advice.contains("Ontology Prefixes"), advice);
        assertFalse(advice.contains("stands for"), "nothing fits, so nothing is suggested: "
                + advice);
    }

    @Test
    void aDeclaredOrBuiltInPrefixGetsNoPrefixAdvice() {
        java.util.Set<String> builtIn = new java.util.HashSet<String>(Arrays.asList("obo"));

        assertEquals("", TemplateSheet.prefixAdvice("ex:thing", prefixes, builtIn));
        assertEquals("", TemplateSheet.prefixAdvice("obo:BFO_0000030", prefixes, builtIn));
        assertEquals("", TemplateSheet.prefixAdvice("https://example.org/x", prefixes, builtIn));
        assertEquals("", TemplateSheet.prefixAdvice("order 1, a margherita", prefixes, builtIn));
        assertEquals("", TemplateSheet.prefixAdvice("", prefixes, builtIn));
        assertEquals("", TemplateSheet.prefixAdvice(null, prefixes, builtIn));
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

    /**
     * ROBOT does not apply a data property's range: a date written plainly in a column over a
     * property whose values are dates is built as text, silently. Said, with the spelling that
     * works, and the typed spelling gives a typed value.
     */
    @Test
    void aDateWrittenAsPlainTextForADatePropertyIsSaid() throws Exception {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        org.semanticweb.owlapi.model.OWLDataProperty at =
                factory.getOWLDataProperty(IRI.create(NS + "at"));
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(at));
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDataPropertyRangeAxiom(at,
                factory.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#dateTime"))));
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Event"))));

        TemplateSheet.Result result = run(tabs("#", "TYPE", "When"),
                tabs("ID", "TYPE", "I ex:at"),
                tabs("ex:e1", "ex:Event", "2026-11-01T18:00:00"),
                tabs("ex:e2", "ex:Event", "2026-11-01T19:00:00^^xsd:dateTime"));

        assertTrue(result.getProblems().isEmpty(), result.getProblems().toString());
        assertEquals(1, result.getWarnings().size(), result.getWarnings().toString());
        assertEquals(3, result.getWarnings().get(0).getRow());
        assertTrue(result.getWarnings().get(0).getMessage().contains(
                "2026-11-01T18:00:00^^xsd:dateTime"), result.getWarnings().get(0).getMessage());
        assertTrue(result.getOntology().containsAxiom(factory.getOWLDataPropertyAssertionAxiom(at,
                factory.getOWLNamedIndividual(IRI.create(NS + "e2")),
                factory.getOWLLiteral("2026-11-01T19:00:00", factory.getOWLDatatype(
                        IRI.create("http://www.w3.org/2001/XMLSchema#dateTime"))))));
    }

    // ---------- names from the other sheets in the folder ----------

    private void anOntologyOfPortalsPeopleAndOrganisations() {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        for (String property : new String[] {"hostedBy", "memberOf", "headedBy"}) {
            context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                    factory.getOWLObjectProperty(IRI.create(NS + property))));
        }
        for (String type : new String[] {"Portal", "Organisation", "Person"}) {
            context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                    factory.getOWLClass(IRI.create(NS + type))));
        }
    }

    private static List<List<String>> rows(String... lines) {
        List<List<String>> rows = new ArrayList<List<String>>();
        for (String line : lines) {
            rows.add(new ArrayList<String>(Arrays.asList(line.split("\t", -1))));
        }
        return rows;
    }

    private static boolean asserts(OWLOntology out, String property, String subject,
            String object) {
        OWLDataFactory factory = out.getOWLOntologyManager().getOWLDataFactory();
        return out.containsAxiom(factory.getOWLObjectPropertyAssertionAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + property)),
                factory.getOWLNamedIndividual(IRI.create(NS + subject)),
                factory.getOWLNamedIndividual(IRI.create(NS + object))));
    }

    private static List<List<String>> organisations() {
        return rows(
                tabs("#", "TYPE", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("ex:org1", "ex:Organisation", "Fraunhofer-Gesellschaft"));
    }

    /**
     * What Template... failed on: a row of another sheet, named by its label. The sheet editor
     * offered the name and Check sheets... accepted it, and the build alone did not know it,
     * because ROBOT knows only the rows of the templates it is given. A row named by its ID
     * never needed the other sheet - ROBOT expands an ID by its prefix - and still does not.
     */
    @Test
    void aSheetNamesTheRowsOfAnotherSheetByLabelOrById() {
        anOntologyOfPortalsPeopleAndOrganisations();
        List<List<String>> portals = rows(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:p1", "ex:Portal", "portal one", "Fraunhofer-Gesellschaft"),
                tabs("ex:p2", "ex:Portal", "portal two", "ex:org1"));
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        // Portals first: it names a sheet that is read after it.
        folder.put("portals.tsv", portals);
        folder.put("organisations.tsv", organisations());

        TemplateSheet.Result alone = TemplateSheet.run("portals.tsv", portals, context, prefixes,
                null);
        TemplateSheet.Result inTheFolder = TemplateSheet.run("portals.tsv", portals, context,
                prefixes, null, TemplateSheet.namesOf(folder, context, prefixes));

        assertEquals(1, alone.getProblems().size(),
                "on its own the sheet cannot know a label another sheet defines: "
                        + alone.getProblems());
        assertTrue(asserts(alone.getOntology(), "hostedBy", "p2", "org1"),
                "by ID it never needed the folder");
        assertTrue(inTheFolder.getProblems().isEmpty(), inTheFolder.getProblems().toString());
        assertTrue(asserts(inTheFolder.getOntology(), "hostedBy", "p1", "org1"), "by label");
        assertTrue(asserts(inTheFolder.getOntology(), "hostedBy", "p2", "org1"), "by ID");
    }

    /** Two sheets that name each other: no order of building one after the other can work. */
    @Test
    void twoSheetsThatNameEachOtherAreBuiltTogether() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("people.tsv", rows(
                tabs("#", "TYPE", "Name", "Member of"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:memberOf"),
                tabs("ex:alice", "ex:Person", "Alice", "Fraunhofer-Gesellschaft")));
        folder.put("organisations.tsv", rows(
                tabs("#", "TYPE", "Name", "Headed by"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:headedBy"),
                tabs("ex:org1", "ex:Organisation", "Fraunhofer-Gesellschaft", "Alice")));

        Map<String, TemplateSheet.Result> built =
                TemplateSheet.runAll(folder, context, prefixes, null);

        TemplateSheet.Result people = built.get("people.tsv");
        TemplateSheet.Result organisations = built.get("organisations.tsv");
        assertTrue(people.getProblems().isEmpty(), people.getProblems().toString());
        assertTrue(organisations.getProblems().isEmpty(), organisations.getProblems().toString());
        assertTrue(asserts(people.getOntology(), "memberOf", "alice", "org1"));
        assertTrue(asserts(organisations.getOntology(), "headedBy", "org1", "alice"));
    }

    /** The folder's names make more cells right, not every cell: a typo is still a typo. */
    @Test
    void aNameNoSheetDefinesIsStillReportedAtItsCell() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("organisations.tsv", organisations());
        folder.put("portals.tsv", rows(
                tabs("#", "TYPE", "Name", "Host institute"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:pX", "ex:Portal", "a portal", "Fraunhofer-Gesellschafft")));

        TemplateSheet.Result portals =
                TemplateSheet.runAll(folder, context, prefixes, null).get("portals.tsv");

        assertEquals(1, portals.getProblems().size(), portals.getProblems().toString());
        TemplateSheet.Problem problem = portals.getProblems().get(0);
        assertEquals(3, problem.getRow());
        assertEquals(4, problem.getColumn());
        assertTrue(problem.getMessage().contains("other sheets in this folder"),
                problem.getMessage());
    }

    /** A sheet ROBOT cannot read lends no names, says so, and the others still build. */
    @Test
    void aSheetRobotCannotReadLendsNoNamesAndSaysSo() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("broken.tsv", rows(
                tabs("#", "Name"),
                tabs("ID", "NOT A TEMPLATE STRING"),
                tabs("ex:x1", "something")));
        folder.put("organisations.tsv", organisations());

        TemplateSheet.Names names = TemplateSheet.namesOf(folder, context, prefixes);
        Map<String, TemplateSheet.Result> built =
                TemplateSheet.runAll(folder, context, prefixes, null);

        assertEquals(Arrays.asList("organisations.tsv"), names.getSheets());
        assertEquals(1, names.getUnavailable().size(), names.getUnavailable().toString());
        assertTrue(names.getUnavailable().get(0).startsWith("broken.tsv: none of its rows"),
                names.getUnavailable().get(0));
        assertTrue(built.get("organisations.tsv").getProblems().isEmpty(),
                built.get("organisations.tsv").getProblems().toString());
        assertEquals(1, built.get("organisations.tsv").getOntology()
                .getIndividualsInSignature().size(), "the readable sheet still builds");
    }

    /**
     * A row whose ID uses a prefix nothing defines is refused when its own sheet is built. Lent to
     * other sheets anyway, ROBOT would register it under the unexpanded ID, and they would link to
     * an IRI the row itself never gets - with no problem reported anywhere.
     */
    @Test
    void aRowWhoseIdCannotBeExpandedLendsNoNameAndIsNamed() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("datasets.tsv", rows(
                tabs("#", "TYPE", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("zz:0000123", "ex:Portal", "Dataset A")));
        List<List<String>> portals = rows(
                tabs("#", "TYPE", "Name", "Host"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:p1", "ex:Portal", "portal one", "Dataset A"));
        folder.put("portals.tsv", portals);

        TemplateSheet.Names names = TemplateSheet.namesOf(folder, context, prefixes);
        TemplateSheet.Result built = TemplateSheet.run("portals.tsv", portals, context, prefixes,
                null, names);

        assertEquals(1, names.getUnavailable().size(), names.getUnavailable().toString());
        assertTrue(names.getUnavailable().get(0).contains("zz:0000123"),
                names.getUnavailable().get(0));
        assertEquals(1, built.getProblems().size(), "the reference is reported, not linked to "
                + "<zz:0000123>: " + built.getProblems());
    }

    /** ROBOT takes a sheet's last rdfs:label column only; the sheet editor offers every one. */
    @Test
    void everyLabelColumnOfASheetCanBeNamed() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("organisations.tsv", rows(
                tabs("#", "TYPE", "English", "German"),
                tabs("ID", "TYPE", "AL rdfs:label@en", "AL rdfs:label@de"),
                tabs("ex:org1", "ex:Organisation", "Fraunhofer Society",
                        "Fraunhofer-Gesellschaft")));
        folder.put("portals.tsv", rows(
                tabs("#", "TYPE", "Name", "Host"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:hostedBy"),
                tabs("ex:p1", "ex:Portal", "portal one", "Fraunhofer Society"),
                tabs("ex:p2", "ex:Portal", "portal two", "Fraunhofer-Gesellschaft")));

        TemplateSheet.Result portals =
                TemplateSheet.runAll(folder, context, prefixes, null).get("portals.tsv");

        assertTrue(portals.getProblems().isEmpty(), portals.getProblems().toString());
        assertTrue(asserts(portals.getOntology(), "hostedBy", "p1", "org1"), "the English one");
        assertTrue(asserts(portals.getOntology(), "hostedBy", "p2", "org1"), "the German one");
    }

    /**
     * ROBOT binds a blank label too, and an empty part of a SPLIT cell would then name that row.
     * Across a folder that turned "Acme||Beta" into a link to whichever row had no label.
     */
    @Test
    void aRowWithoutALabelIsNotWhatAnEmptyPartOfACellNames() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("organisations.tsv", rows(
                tabs("#", "TYPE", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("ex:acme", "ex:Organisation", "Acme"),
                tabs("ex:beta", "ex:Organisation", "Beta"),
                tabs("ex:o9", "ex:Organisation", "")));
        folder.put("people.tsv", rows(
                tabs("#", "TYPE", "Name", "Member of"),
                tabs("ID", "TYPE", "A rdfs:label", "I ex:memberOf SPLIT=|"),
                tabs("ex:alice", "ex:Person", "Alice", "Acme||Beta")));

        TemplateSheet.Result people =
                TemplateSheet.runAll(folder, context, prefixes, null).get("people.tsv");

        OWLOntology out = people.getOntology();
        assertFalse(out != null && asserts(out, "memberOf", "alice", "o9"),
                "the empty part named the row without a label: " + people.getProblems());
    }

    /** A file beside the sheets that is not a template is set aside after one try. */
    @Test
    void aFileThatIsNotATemplateIsSetAsideAtOnce() {
        anOntologyOfPortalsPeopleAndOrganisations();
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        List<List<String>> export = new ArrayList<List<String>>();
        export.add(Arrays.asList("date", "value"));
        export.add(Arrays.asList("2026-01-01", "not a template string"));
        for (int i = 0; i < 2000; i++) {
            export.add(Arrays.asList("2026-01-02", String.valueOf(i)));
        }
        folder.put("export.csv", export);
        folder.put("organisations.tsv", organisations());

        long started = System.nanoTime();
        TemplateSheet.Names names = TemplateSheet.namesOf(folder, context, prefixes);
        long millis = (System.nanoTime() - started) / 1000000;

        assertEquals(Arrays.asList("organisations.tsv"), names.getSheets());
        assertTrue(names.getUnavailable().get(0).startsWith("export.csv: none of its rows"),
                names.getUnavailable().toString());
        assertTrue(millis < 5000, "one try, not one per row: " + millis + " ms");
    }

    /**
     * ROBOT decides what a row without TYPE is by what its label already names. A class row
     * "Margherita" next to another sheet's menu item "Margherita" was built as that item - and
     * failed - once the other sheet's names were lent; the sheet's own rows answer to its labels.
     */
    @Test
    void aClassRowWithoutTypeStaysAClassBesideAnIndividualOfTheSameName() {
        OWLDataFactory factory = context.getOWLOntologyManager().getOWLDataFactory();
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Pizza"))));
        context.getOWLOntologyManager().addAxiom(context, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "MenuItem"))));
        Map<String, List<List<String>>> folder = new LinkedHashMap<String, List<List<String>>>();
        folder.put("classes.tsv", rows(
                tabs("ID", "Label", "Parent"),
                tabs("ID", "LABEL", "SC %"),
                tabs("ex:Margherita", "Margherita", "ex:Pizza")));
        folder.put("menu.tsv", rows(
                tabs("ID", "Type", "Name"),
                tabs("ID", "TYPE", "A rdfs:label"),
                tabs("ex:item7", "ex:MenuItem", "Margherita")));

        Map<String, TemplateSheet.Result> built =
                TemplateSheet.runAll(folder, context, prefixes, null);

        TemplateSheet.Result classes = built.get("classes.tsv");
        assertTrue(classes.getProblems().isEmpty(), classes.getProblems().toString());
        assertTrue(classes.getOntology().containsAxiom(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create(NS + "Margherita")),
                factory.getOWLClass(IRI.create(NS + "Pizza")))), "built as a class");
        assertTrue(built.get("menu.tsv").getProblems().isEmpty(),
                built.get("menu.tsv").getProblems().toString());
    }
}
