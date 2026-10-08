package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The second row of a ROBOT template, read as columns.
 *
 * <p>The specs here are not invented. Every one of them is taken from a template that a real
 * project ships: {@code A rdfs:label@en} and {@code I <prop> SPLIT=,} from the MatWerk
 * knowledge graph, {@code AL rdfs:label@en} and {@code C 'has disposition' some %} from PMDco,
 * {@code AT owl:deprecated^^xsd:boolean} and {@code AI IAO:0100001} from ECTO. A parser
 * reviewed on three made-up specs is a parser reviewed on nothing.
 */
class TemplateColumnsTest {

    private static TemplateColumns parse(String... specs) {
        String[] headings = new String[specs.length];
        for (int at = 0; at < specs.length; at++) {
            headings[at] = "heading " + (at + 1);
        }
        return TemplateColumns.of(Arrays.asList(headings), Arrays.asList(specs));
    }

    // ---------- reading the row ----------

    @Test
    void theOrdinaryColumnsAreRecognised() {
        TemplateColumns columns = parse("ID", "LABEL", "TYPE", "A IAO:0000115");

        assertEquals(TemplateColumns.Kind.ID, columns.at(1).getKind());
        assertEquals(TemplateColumns.Kind.LABEL, columns.at(2).getKind());
        assertEquals(TemplateColumns.Kind.TYPE, columns.at(3).getKind());
        assertEquals(TemplateColumns.Kind.ANNOTATION, columns.at(4).getKind());
        assertEquals("IAO:0000115", columns.at(4).getProperty());
        assertTrue(columns.getFaults().isEmpty(), columns.getFaults().toString());
    }

    @Test
    void aLanguageTaggedAnnotationKeepsItsPropertyAndItsTagApart() {
        TemplateColumns columns = parse("AL rdfs:label@en");

        TemplateColumns.Column column = columns.at(1);
        assertEquals(TemplateColumns.Kind.ANNOTATION_LANGUAGE, column.getKind());
        assertEquals("rdfs:label", column.getProperty());
        assertEquals("en", column.getLanguage());
        assertTrue(columns.getFaults().isEmpty(), columns.getFaults().toString());
    }

    @Test
    void aTypedAnnotationKeepsItsDatatype() {
        TemplateColumns columns = parse("AT owl:deprecated^^xsd:boolean");

        assertEquals(TemplateColumns.Kind.ANNOTATION_TYPED, columns.at(1).getKind());
        assertEquals("owl:deprecated", columns.at(1).getProperty());
        assertEquals("xsd:boolean", columns.at(1).getDatatype());
        assertTrue(columns.getFaults().isEmpty(), columns.getFaults().toString());
    }

    @Test
    void aSplitSeparatorIsReadAndDoesNotEndUpInTheProperty() {
        TemplateColumns columns = parse("I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,");

        assertEquals(TemplateColumns.Kind.INDIVIDUAL, columns.at(1).getKind());
        assertEquals("http://purl.obolibrary.org/obo/RO_0001025", columns.at(1).getProperty());
        assertEquals(",", columns.at(1).getSplit());
    }

    /** {@code >A} annotates the axiom the previous column made, and {@code >>A} that annotation. */
    @Test
    void aLeadingAngleBracketIsCountedRatherThanIgnored() {
        TemplateColumns columns = parse("A IAO:0000115", ">A IAO:0000119", ">>A dcterms:source");

        assertEquals(0, columns.at(1).getAnnotationDepth());
        assertEquals(1, columns.at(2).getAnnotationDepth());
        assertEquals(2, columns.at(3).getAnnotationDepth());
        assertEquals("IAO:0000119", columns.at(2).getProperty());
    }

    @Test
    void aClassExpressionColumnIsRecognisedWithItsExpressionIntact() {
        TemplateColumns columns = parse("SC %", "C 'has disposition' some %", "EC %");

        for (int at = 1; at <= 3; at++) {
            assertEquals(TemplateColumns.Kind.CLASS_EXPRESSION, columns.at(at).getKind(),
                    "column " + at + " was read as " + columns.at(at).getKind());
        }
        assertEquals("'has disposition' some %", columns.at(2).getProperty());
    }

    /**
     * {@code DOMAIN} and {@code RANGE} are in real use - the MatWerk sheets have two of each -
     * and their cells hold a class, so they complete the way a parent column does.
     */
    @Test
    void domainAndRangeColumnsAreRecognisedAsNamingATerm() {
        TemplateColumns columns = parse("DOMAIN", "RANGE");

        assertEquals(TemplateColumns.Kind.CLASS_EXPRESSION, columns.at(1).getKind());
        assertEquals(TemplateColumns.Kind.CLASS_EXPRESSION, columns.at(2).getKind());
        assertTrue(columns.at(1).namesATerm());
        assertTrue(columns.getFaults().isEmpty(), columns.getFaults().toString());
    }

    @Test
    void anEmptyTemplateCellMeansTheColumnIsIgnored() {
        TemplateColumns columns = parse("ID", "", "   ");

        assertEquals(TemplateColumns.Kind.UNUSED, columns.at(2).getKind());
        assertEquals(TemplateColumns.Kind.UNUSED, columns.at(3).getKind());
        assertEquals(3, columns.getColumns().size(),
                "an ignored column must still take up its position, or every column number "
                        + "after it is reported wrongly");
    }

    /**
     * ROBOT gains column types between versions, and this plugin must not reject a sheet that
     * works. Anything unrecognised is OTHER and carries no fault.
     */
    @Test
    void anUnrecognisedColumnIsNotTreatedAsAMistake() {
        TemplateColumns columns = parse("ZZ something", "CI IAO:0100001");

        assertEquals(TemplateColumns.Kind.OTHER, columns.at(1).getKind());
        assertTrue(columns.getFaults().isEmpty(),
                "an unknown column type was reported as wrong: " + columns.getFaults());
    }

    // ---------- the one mistake worth reporting ----------

    /**
     * {@code A rdfs:label@en} writes onto a property called {@code rdfs:label@en}.
     *
     * <p>Measured on robot-core 1.9.8: the annotation's property IRI comes back as
     * {@code http://www.w3.org/2000/01/rdf-schema#label@en}, {@code isLabel()} is false, and the
     * literal is an untagged {@code xsd:string}. ROBOT reports nothing. The MatWerk
     * {@code organization} sheet is written this way in two columns over 81 filled cells, so
     * none of its organizations has a label any reader or tool can see.
     */
    @Test
    void anAnnotationColumnWithALanguageTagIsReportedBecauseOnlyAlTakesOne() {
        TemplateColumns columns = parse("A rdfs:label@en");

        assertEquals(1, columns.getFaults().size(), columns.getFaults().toString());
        TemplateColumns.Fault fault = columns.getFaults().get(0);
        assertEquals("AL rdfs:label@en", fault.getSuggestion());
        assertTrue(fault.getMessage().contains("rdfs:label@en"),
                "the message does not show what the property actually becomes: "
                        + fault.getMessage());
        assertTrue(fault.getMessage().contains("AL"), fault.getMessage());
    }

    @Test
    void theSuggestionKeepsAnySplitSeparator() {
        TemplateColumns columns = parse("A rdfs:label@de SPLIT=|");

        assertEquals("AL rdfs:label@de SPLIT=|", columns.getFaults().get(0).getSuggestion());
    }

    /** The same mistake on AT and AI, which take a datatype and an IRI but not a tag either. */
    @Test
    void theSameMistakeOnTheOtherAnnotationColumnsIsReported() {
        assertEquals(1, parse("AT rdfs:label@en^^xsd:string").getFaults().size());
        assertEquals(1, parse("AI skos:exactMatch@en").getFaults().size());
    }

    /** A bare @ with no language is the same silent failure, so it is named too. */
    @Test
    void aTrailingAtSignWithNoLanguageIsReported() {
        TemplateColumns columns = parse("A rdfs:label@");

        assertEquals(1, columns.getFaults().size(), columns.getFaults().toString());
        assertTrue(columns.getFaults().get(0).getMessage().contains("no language"),
                columns.getFaults().get(0).getMessage());
    }

    /**
     * And the forms that are correct stay silent. This is the half that matters most: a check
     * that fires on a working sheet is worse than no check, because every real project here
     * uses these.
     */
    @Test
    void theCorrectFormsCarryNoFault() {
        String[] correct = {
            "ID", "LABEL", "TYPE", "A rdfs:label", "AL rdfs:label@en", "AL rdfs:label@de",
            "AT owl:deprecated^^xsd:boolean", "AI IAO:0100001", "SC %", "EC %",
            "C 'has disposition' some %", "I http://purl.obolibrary.org/obo/RO_0001025 SPLIT=,",
            ">A IAO:0000119", "A IAO:0000115",
        };
        for (String spec : correct) {
            assertTrue(parse(spec).getFaults().isEmpty(),
                    "\"" + spec + "\" is a real column from a real project and was reported as "
                            + "wrong: " + parse(spec).getFaults());
        }
    }

    /**
     * {@code AL} with no tag is left to ROBOT, which already rejects it.
     *
     * <p>Reporting it here as well would give a user two problems for one mistake, and the two
     * would be worded differently.
     */
    @Test
    void anAlColumnWithNoTagIsLeftToRobotWhichAlreadyRefusesIt() {
        assertTrue(parse("AL rdfs:label").getFaults().isEmpty(),
                "duplicated a problem ROBOT already reports");
    }

    // ---------- what the rest of the spreadsheet work needs ----------

    @Test
    void aColumnKnowsWhetherItsCellsNameATermOrCarryText() {
        TemplateColumns columns = parse("ID", "A rdfs:label", "I RO:0001025", "TYPE",
                "AI IAO:0100001", "SC %", "AL rdfs:label@en");

        assertFalse(columns.at(2).namesATerm());
        assertTrue(columns.at(2).holdsALiteral());
        assertTrue(columns.at(3).namesATerm());
        assertTrue(columns.at(4).namesATerm());
        assertTrue(columns.at(5).namesATerm());
        assertTrue(columns.at(6).namesATerm());
        assertTrue(columns.at(7).holdsALiteral());
    }

    @Test
    void theReferenceColumnsCanBeAskedForAtOnce() {
        TemplateColumns columns = parse("ID", "A rdfs:label", "I RO:0001025", "TYPE", "SC %");

        List<TemplateColumns.Column> references = columns.referenceColumns();

        assertEquals(3, references.size(), references.toString());
        assertEquals(3, references.get(0).getNumber());
    }

    @Test
    void theIdColumnCanBeFoundWhereverItIs() {
        TemplateColumns columns = parse("A rdfs:label", "ID", "LABEL");

        assertNotNull(columns.firstOf(TemplateColumns.Kind.ID));
        assertEquals(2, columns.firstOf(TemplateColumns.Kind.ID).getNumber());
        assertEquals("heading 2", columns.firstOf(TemplateColumns.Kind.ID).getHeading());
    }

    @Test
    void aSheetWithNoTemplateRowParsesToNothingRatherThanThrowing() {
        assertTrue(TemplateColumns.of(null).getColumns().isEmpty());
        assertTrue(TemplateColumns.of(Arrays.asList(Arrays.asList("only", "one", "row")))
                .getColumns().isEmpty());
    }
}
