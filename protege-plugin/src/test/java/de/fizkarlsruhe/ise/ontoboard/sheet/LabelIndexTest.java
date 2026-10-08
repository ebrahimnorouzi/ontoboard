package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Every name in play, and what each one points at.
 *
 * <p>The cases here are the ones a real knowledge graph produced. The MatWerk graph's 26 sheets
 * index to 6,058 distinct labels of which 30 name more than one thing - including one that
 * names three - so ambiguity is the normal case here rather than a corner, and these tests are
 * mostly about it.
 */
class LabelIndexTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";

    private static List<List<String>> sheet(String[]... rows) {
        List<List<String>> table = new ArrayList<List<String>>();
        for (String[] row : rows) {
            table.add(Arrays.asList(row));
        }
        return table;
    }

    /** The shape of the real organization sheet: an IRI, a type, a label. */
    private static List<List<String>> organisations(String... pairs) {
        List<List<String>> table = new ArrayList<List<String>>();
        table.add(Arrays.asList("#", "TYPE", "Label"));
        table.add(Arrays.asList("ID", "TYPE", "A rdfs:label"));
        for (int at = 0; at + 1 < pairs.length; at += 2) {
            table.add(Arrays.asList(pairs[at], "owl:NamedIndividual", pairs[at + 1]));
        }
        return table;
    }

    // ---------- the ontology side ----------

    @Test
    void anOntologysOwnLabelsAreFound() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        IRI material = IRI.create("http://purl.obolibrary.org/obo/BFO_0000040");
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), material, factory.getOWLLiteral("material entity")));

        LabelIndex index = LabelIndex.empty().plus(ontology, "BFO");

        assertEquals(material, index.resolve("material entity"));
        assertEquals("BFO", index.lookup("material entity").get(0).getSource());
        assertFalse(index.lookup("material entity").get(0).isFromASheet());
    }

    /** An annotation that is not a label must not become one. */
    @Test
    void aCommentIsNotIndexedAsAName() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSComment(), IRI.create("http://example.org/o#x"),
                factory.getOWLLiteral("a remark")));

        assertEquals(0, LabelIndex.empty().plus(ontology, "o").size());
    }

    // ---------- the sheet side, which is where the names actually are ----------

    /**
     * Every reference in the real {@code dataportal} sheet is a plain label whose target is
     * defined in another sheet, so an index built from the ontology alone would call all of
     * them unresolvable.
     */
    @Test
    void aSheetsOwnRowsAreNamesToo() {
        LabelIndex index = LabelIndex.empty().plus("organization",
                organisations(MW + "1", "Fraunhofer-Gesellschaft"));

        assertEquals(IRI.create(MW + "1"), index.resolve("Fraunhofer-Gesellschaft"));
        LabelIndex.Entry entry = index.lookup("Fraunhofer-Gesellschaft").get(0);
        assertTrue(entry.isFromASheet());
        assertEquals(3, entry.getRow(), "the first data row is row 3 as a spreadsheet counts");
        assertEquals("organization row 3", entry.where());
    }

    @Test
    void aLabelColumnWrittenAsLabelRatherThanAnAnnotationWorksToo() {
        LabelIndex index = LabelIndex.empty().plus("terms", sheet(
                new String[] {"#", "Name"},
                new String[] {"ID", "LABEL"},
                new String[] {"ex:1", "steel"}));

        assertEquals(IRI.create("ex:1"), index.resolve("steel"));
    }

    @Test
    void aLanguageTaggedLabelColumnIsIndexed() {
        LabelIndex index = LabelIndex.empty().plus("terms", sheet(
                new String[] {"#", "Name"},
                new String[] {"ID", "AL rdfs:label@en"},
                new String[] {"ex:1", "steel"}));

        assertEquals(IRI.create("ex:1"), index.resolve("steel"));
    }

    /**
     * Including the broken spelling, which 1.108.0 reports but which exists in real sheets.
     * Somebody typing a name wants it completed whether or not the column holding it is right.
     */
    @Test
    void aLabelInTheBrokenLanguageColumnStillContributesItsName() {
        LabelIndex index = LabelIndex.empty().plus("organization", sheet(
                new String[] {"#", "Label"},
                new String[] {"ID", "A rdfs:label@en"},
                new String[] {MW + "1", "Fraunhofer-Gesellschaft"}));

        assertEquals(IRI.create(MW + "1"), index.resolve("Fraunhofer-Gesellschaft"));
    }

    @Test
    void aSheetWithNoIdColumnContributesNothingRatherThanThrowing() {
        LabelIndex index = LabelIndex.empty().plus("terms", sheet(
                new String[] {"Name"},
                new String[] {"LABEL"},
                new String[] {"steel"}));

        assertEquals(0, index.size());
    }

    @Test
    void aRowWithNoIdIsSkipped() {
        LabelIndex index = LabelIndex.empty().plus("terms", sheet(
                new String[] {"#", "Name"},
                new String[] {"ID", "LABEL"},
                new String[] {"", "steel"},
                new String[] {"ex:2", "iron"}));

        assertEquals(1, index.size());
        assertNotNull(index.resolve("iron"));
    }

    // ---------- ambiguity, which is the finding rather than the edge case ----------

    /**
     * The real case: {@code National Institute for Materials Science (NIMS)} is declared in
     * {@code req_2} twice with two different IRIs and in {@code organization} once, and nine
     * references in {@code dataportal} name it.
     */
    @Test
    void aNameThatMeansTwoThingsResolvesToNeither() {
        LabelIndex index = LabelIndex.empty()
                .plus("req_2", organisations(MW + "17461154901201", "NIMS",
                        MW + "17463841074141", "NIMS"))
                .plus("organization", organisations(MW + "17463841074141", "NIMS"));

        assertEquals(3, index.lookup("NIMS").size(), "every place it was found must be kept");
        assertNull(index.resolve("NIMS"),
                "resolving to one of two IRIs silently is the behaviour this class exists to "
                        + "expose, not to reproduce");
        assertEquals(1, index.ambiguous().size());
        assertEquals(2, index.ambiguous().get(0).getCandidates().size(),
                "two IRIs across three entries");
    }

    @Test
    void anAmbiguityNamesEveryCandidateAndWhereItCameFrom() {
        LabelIndex index = LabelIndex.empty()
                .plus("req_2", organisations(MW + "1", "NIMS"))
                .plus("organization", organisations(MW + "2", "NIMS"));

        String described = index.ambiguous().get(0).describe();

        assertTrue(described.contains(MW + "1"), described);
        assertTrue(described.contains(MW + "2"), described);
        assertTrue(described.contains("req_2 row 3"), described);
        assertTrue(described.contains("organization row 3"), described);
    }

    /** One IRI with two labels is not ambiguity - it is a thing with two names. */
    @Test
    void twoNamesForOneThingIsNotAnAmbiguity() {
        LabelIndex index = LabelIndex.empty().plus("organization", sheet(
                new String[] {"#", "Label", "DE label"},
                new String[] {"ID", "AL rdfs:label@en", "AL rdfs:label@de"},
                new String[] {MW + "1", "Humboldt University", "Humboldt-Universitaet"}));

        assertTrue(index.ambiguous().isEmpty(), index.ambiguous().toString());
        assertEquals(IRI.create(MW + "1"), index.resolve("Humboldt University"));
        assertEquals(IRI.create(MW + "1"), index.resolve("Humboldt-Universitaet"));
        assertEquals(2, index.forIri(IRI.create(MW + "1")).size());
    }

    /** The same label and the same IRI in two sheets is agreement, not a problem. */
    @Test
    void thesameNameForTheSameThingInTwoSheetsIsNotAnAmbiguity() {
        LabelIndex index = LabelIndex.empty()
                .plus("req_2", organisations(MW + "1", "Fraunhofer-Gesellschaft"))
                .plus("organization", organisations(MW + "1", "Fraunhofer-Gesellschaft"));

        assertTrue(index.ambiguous().isEmpty(), index.ambiguous().toString());
        assertEquals(IRI.create(MW + "1"), index.resolve("Fraunhofer-Gesellschaft"));
    }

    @Test
    void theWorstAmbiguityComesFirst() {
        LabelIndex index = LabelIndex.empty().plus("req_2", organisations(
                MW + "1", "twice", MW + "2", "twice",
                MW + "3", "thrice", MW + "4", "thrice", MW + "5", "thrice"));

        assertEquals("thrice", index.ambiguous().get(0).getLabel());
        assertEquals(3, index.ambiguous().get(0).getCandidates().size());
    }

    // ---------- completion, which is what somebody typing gets ----------

    @Test
    void completionIgnoresCaseAndPunctuation() {
        LabelIndex index = LabelIndex.empty().plus("organization", organisations(
                MW + "1", "National Institute for Materials Science (NIMS)",
                MW + "2", "Fraunhofer-Gesellschaft"));

        List<LabelIndex.Entry> nims = index.suggest("nims", 5);
        assertEquals(1, nims.size(), nims.toString());
        assertEquals("National Institute for Materials Science (NIMS)",
                nims.get(0).getLabel());
        assertEquals(1, index.suggest("fraunhofer gesellschaft", 5).size(),
                "a hyphen in the name must not stop a space from matching it");
    }

    @Test
    void anExactMatchIsOfferedBeforeOneThatMerelyContainsTheText() {
        LabelIndex index = LabelIndex.empty().plus("organization", organisations(
                MW + "1", "MatNavi: NIMS Materials Database",
                MW + "2", "NIMS"));

        assertEquals("NIMS", index.suggest("NIMS", 5).get(0).getLabel());
    }

    @Test
    void aShorterNameIsOfferedFirstWithinTheSameRank() {
        LabelIndex index = LabelIndex.empty().plus("organization", organisations(
                MW + "1", "steel alloy of some considerable length",
                MW + "2", "steel alloy"));

        assertEquals("steel alloy", index.suggest("steel", 5).get(0).getLabel());
    }

    @Test
    void completionIsCappedAndEmptyInputOffersNothing() {
        LabelIndex index = LabelIndex.empty().plus("organization", organisations(
                MW + "1", "steel one", MW + "2", "steel two", MW + "3", "steel three"));

        assertEquals(2, index.suggest("steel", 2).size());
        assertTrue(index.suggest("", 5).isEmpty(), "an empty box must not offer everything");
        assertTrue(index.suggest("   ", 5).isEmpty());
        assertTrue(index.suggest("steel", 0).isEmpty());
    }

    @Test
    void completionOffersBothCandidatesWhenANameIsAmbiguous() {
        LabelIndex index = LabelIndex.empty().plus("req_2", organisations(
                MW + "1", "NIMS", MW + "2", "NIMS"));

        assertEquals(2, index.suggest("nims", 5).size(),
                "somebody choosing between two things with one name needs to see both");
    }

    // ---------- the plumbing ----------

    @Test
    void anEmptyIndexAnswersEverythingWithoutThrowing() {
        LabelIndex index = LabelIndex.empty();

        assertEquals(0, index.size());
        assertTrue(index.lookup("anything").isEmpty());
        assertTrue(index.lookup(null).isEmpty());
        assertNull(index.resolve("anything"));
        assertFalse(index.knows("anything"));
        assertTrue(index.ambiguous().isEmpty());
        assertTrue(index.suggest("x", 5).isEmpty());
        assertTrue(index.forIri(IRI.create("ex:1")).isEmpty());
        assertEquals(0, LabelIndex.empty().plus((OWLOntology) null, "x").size());
        assertEquals(0, LabelIndex.empty().plus("x", null).size());
    }

    @Test
    void surroundingSpaceDoesNotMakeASecondName() {
        LabelIndex index = LabelIndex.empty().plus("organization", organisations(
                MW + "1", "  Fraunhofer-Gesellschaft  "));

        assertEquals(1, index.size());
        assertEquals(IRI.create(MW + "1"), index.resolve("Fraunhofer-Gesellschaft"),
                "a pasted cell with spaces around it must still resolve, as ROBOT resolves it");
    }
}
