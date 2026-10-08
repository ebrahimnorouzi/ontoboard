package de.fizkarlsruhe.ise.ontoboard.sheet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TemplateSheet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Who added a row, when, and who changed it last - recorded in the sheet.
 *
 * <p>It has to be the sheet rather than the generated OWL, because the OWL is rebuilt from the
 * sheet on every run: provenance written only into the graph is provenance that disappears at
 * the next build. The last test here follows it all the way through ROBOT to prove it arrives.
 */
class SheetProvenanceTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";
    private static final String ORCID = "https://orcid.org/0000-0003-1619-1408";
    private static final String OTHER = "https://orcid.org/0000-0002-1825-0097";

    private static SheetBook organisations() {
        SheetBook book = SheetBook.empty();
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList("#", "TYPE", "Label")));
        rows.add(new ArrayList<String>(Arrays.asList("ID", "TYPE", "A rdfs:label")));
        rows.add(new ArrayList<String>(Arrays.asList(MW + "1", "owl:NamedIndividual",
                "Fraunhofer")));
        rows.add(new ArrayList<String>(Arrays.asList(MW + "2", "owl:NamedIndividual",
                "Max Planck")));
        book.add("organization", null, rows, '\t');
        return book;
    }

    // ---------- the columns ----------

    @Test
    void theThreeColumnsAreAddedWithTheSpecsRobotNeeds() {
        SheetBook book = organisations();
        assertFalse(SheetProvenance.hasColumns(book, "organization"));

        assertEquals(3, SheetProvenance.ensureColumns(book, "organization", true));

        assertTrue(SheetProvenance.hasColumns(book, "organization"));
        assertEquals(6, book.width("organization"),
                "three columns were appended to the three that were there");
        assertEquals("AI http://purl.org/dc/terms/contributor SPLIT=|",
                book.cell("organization", 2, 4));
        assertEquals("AT http://purl.org/dc/terms/created^^xsd:date",
                book.cell("organization", 2, 5));
        assertEquals("AT http://purl.org/dc/terms/date^^xsd:date",
                book.cell("organization", 2, 6));
        assertEquals("Added by", book.cell("organization", 1, 4));
    }

    /**
     * An ORCID is a thing with an IRI and a name is a string, so the column kind differs.
     *
     * <p>An {@code AI} column holding a plain name is exactly the defect 1.106.0 reports, so
     * writing one here would make this feature generate its own findings.
     */
    @Test
    void withoutAnOrcidTheContributorColumnHoldsAStringInstead() {
        SheetBook book = organisations();

        SheetProvenance.ensureColumns(book, "organization", false);

        assertEquals("A http://purl.org/dc/terms/contributor SPLIT=|",
                book.cell("organization", 2, 4));
    }

    @Test
    void addingTheColumnsTwiceAddsThemOnce() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);
        int width = book.width("organization");

        assertEquals(0, SheetProvenance.ensureColumns(book, "organization", true));
        assertEquals(width, book.width("organization"));
    }

    /** A sheet that already records provenance under its own heading is left alone. */
    @Test
    void aSheetThatAlreadyHasTheColumnsUnderItsOwnHeadingsIsRecognised() {
        SheetBook book = SheetBook.empty();
        List<List<String>> rows = new ArrayList<List<String>>();
        rows.add(new ArrayList<String>(Arrays.asList("#", "Label", "Curator")));
        rows.add(new ArrayList<String>(Arrays.asList("ID", "LABEL", "A dcterms:contributor")));
        rows.add(new ArrayList<String>(Arrays.asList("ex:1", "steel", "")));
        book.add("s", null, rows, '\t');

        assertTrue(SheetProvenance.hasColumns(book, "s"),
                "matched on the property, not on the heading");
        assertEquals(2, SheetProvenance.ensureColumns(book, "s", false),
                "only the two date columns were missing");
    }

    /** Appending rather than inserting, so no existing column number moves. */
    @Test
    void theExistingColumnsKeepTheirNumbers() {
        SheetBook book = organisations();

        SheetProvenance.ensureColumns(book, "organization", true);

        assertEquals("ID", book.cell("organization", 2, 1));
        assertEquals("TYPE", book.cell("organization", 2, 2));
        assertEquals("A rdfs:label", book.cell("organization", 2, 3));
        assertEquals("Fraunhofer", book.cell("organization", 3, 3));
    }

    // ---------- stamping ----------

    @Test
    void stampingARowRecordsWhoAndWhen() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);

        assertTrue(SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-10-08"));

        SheetProvenance.Record record = SheetProvenance.of(book, "organization", 3);
        assertEquals("2026-10-08", record.getCreated());
        assertEquals("2026-10-08", record.getModified());
        assertEquals(1, record.getContributors().size());
        assertEquals(ORCID, record.getContributors().get(0));
        assertTrue(record.describe().contains("0000-0003-1619-1408"), record.describe());
    }

    /**
     * Creation is written once. That is what makes it creation.
     *
     * <p>And a second person is added rather than replacing the first: two people editing one
     * row is the normal case in a shared sheet, and whoever introduced it does not stop being
     * its author because somebody else fixed a typo.
     */
    @Test
    void aLaterEditChangesTheDateButNotTheCreationOrTheFirstPerson() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);
        SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-10-08");

        SheetProvenance.stamp(book, "organization", 3, OTHER, "2026-11-20");

        SheetProvenance.Record record = SheetProvenance.of(book, "organization", 3);
        assertEquals("2026-10-08", record.getCreated(), "creation must not move");
        assertEquals("2026-11-20", record.getModified());
        assertEquals(2, record.getContributors().size(), record.getContributors().toString());
        assertEquals(ORCID, record.getContributors().get(0));
        assertTrue(record.describe().contains("last changed 2026-11-20"), record.describe());
    }

    @Test
    void thesamePersonTwiceIsNamedOnce() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);
        SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-10-08");

        SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-11-20");

        assertEquals(1, SheetProvenance.of(book, "organization", 3).getContributors().size());
    }

    @Test
    void stampingEveryRowCoversTheOnesWithDataAndCountsWhatIsLeft() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);
        book.addRow("organization");

        assertEquals(2, SheetProvenance.rowsWithNoProvenance(book, "organization"),
                "the blank row has no data, so it is not missing anything");
        assertEquals(2, SheetProvenance.stampEveryRow(book, "organization", ORCID,
                "2026-10-08"));
        assertEquals(0, SheetProvenance.rowsWithNoProvenance(book, "organization"));
    }

    @Test
    void aSheetWithNoProvenanceColumnsCannotBeStamped() {
        SheetBook book = organisations();

        assertFalse(SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-10-08"));
        assertTrue(SheetProvenance.of(book, "organization", 3).isEmpty());
        assertEquals("Nothing recorded about who added this row or when.",
                SheetProvenance.of(book, "organization", 3).describe());
    }

    @Test
    void theHeaderRowsCannotBeStamped() {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);

        assertFalse(SheetProvenance.stamp(book, "organization", 1, ORCID, "2026-10-08"));
        assertFalse(SheetProvenance.stamp(book, "organization", 2, ORCID, "2026-10-08"));
    }

    @Test
    void nullsAndMissingSheetsAreSurvivable() {
        assertEquals(0, SheetProvenance.ensureColumns(null, "x", true));
        assertEquals(0, SheetProvenance.ensureColumns(SheetBook.empty(), "x", true));
        assertFalse(SheetProvenance.stamp(SheetBook.empty(), "x", 3, "me", "2026-10-08"));
        assertTrue(SheetProvenance.of(SheetBook.empty(), "x", 3).isEmpty());
        assertFalse(SheetProvenance.hasColumns(SheetBook.empty(), "x"));
        assertEquals(0, SheetProvenance.rowsWithNoProvenance(SheetBook.empty(), "x"));
    }

    // ---------- and it has to arrive in the graph ----------

    /**
     * The whole point: it goes through ROBOT and comes out as annotations on the individual.
     *
     * <p>Provenance that only exists in the spreadsheet would be provenance nobody querying the
     * knowledge graph can see.
     */
    @Test
    void theProvenanceArrivesInTheGraphAsDctermsAnnotations() throws Exception {
        SheetBook book = organisations();
        SheetProvenance.ensureColumns(book, "organization", true);
        SheetProvenance.stamp(book, "organization", 3, ORCID, "2026-10-08");
        SheetProvenance.stamp(book, "organization", 3, OTHER, "2026-11-20");

        OWLOntology context = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/context"));
        TemplateSheet.Result result = TemplateSheet.run("organization.tsv",
                book.get("organization").getRows(), context,
                new LinkedHashMap<String, String>(), null);

        assertNotNull(result.getOntology(), result.getProblems().toString());
        IRI subject = IRI.create(MW + "1");
        int contributors = 0;
        String created = "";
        String modified = "";
        for (OWLAnnotationAssertionAxiom axiom
                : result.getOntology().getAnnotationAssertionAxioms(subject)) {
            String property = axiom.getProperty().getIRI().toString();
            if (SheetProvenance.CONTRIBUTOR.equals(property)) {
                contributors++;
            } else if (SheetProvenance.CREATED.equals(property)) {
                created = axiom.getValue().toString();
            } else if (SheetProvenance.MODIFIED.equals(property)) {
                modified = axiom.getValue().toString();
            }
        }
        assertEquals(2, contributors, "both people should reach the graph");
        assertTrue(created.contains("2026-10-08"), "created was " + created);
        assertTrue(modified.contains("2026-11-20"), "modified was " + modified);
        assertTrue(created.contains("date"), "the date should be typed: " + created);
    }
}
