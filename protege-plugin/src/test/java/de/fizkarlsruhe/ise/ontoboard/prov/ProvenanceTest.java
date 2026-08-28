package de.fizkarlsruhe.ise.ontoboard.prov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Term-level provenance: who added something, and when.
 *
 * <p>The property choice is the part most likely to be wrong, and the intuitive answer is wrong:
 * {@code dcterms:creator} is what one reaches for and released OBO ontologies do not use it at
 * term level at all. These tests pin the properties that {@code ro.owl} and {@code obi.owl}
 * actually carry, so a well-meaning change to the "obvious" ones fails here rather than producing
 * an ontology that looks annotated and is not conventional.
 *
 * <p>The other bug class is churn. {@code dcterms:date} means last-modified, so an implementation
 * that appends rather than replaces turns a single fact into a growing list, and one that writes
 * second precision puts a diff in the ontology every time anyone touches a term.
 */
class ProvenanceTest {

    private static final String NS = "http://example.org/o#";
    private static final IRI PERSON = IRI.create(NS + "Person");
    private static final String ORCID = "https://orcid.org/0000-0001-9625-1899";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology,
                factory.getOWLDeclarationAxiom(factory.getOWLClass(PERSON)));
    }

    private void apply(List<OWLOntologyChange> changes) {
        if (!changes.isEmpty()) {
            manager.applyChanges(changes);
        }
    }

    private List<String> annotationsOn(IRI subject, IRI property) {
        List<String> values = new ArrayList<String>();
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(subject)) {
            if (property.equals(axiom.getProperty().getIRI())) {
                values.add(axiom.getValue() instanceof IRI ? axiom.getValue().toString()
                        : ((OWLLiteral) axiom.getValue()).getLiteral());
            }
        }
        java.util.Collections.sort(values);
        return values;
    }

    private String datatypeOf(IRI property) {
        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(PERSON)) {
            if (property.equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral) {
                return ((OWLLiteral) axiom.getValue()).getDatatype().getIRI().getShortForm();
            }
        }
        return null;
    }

    // ---------- the properties are the ones OBO actually uses ----------

    @Test
    void aNewTermRecordsContributorAndCreated() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));

        assertEquals(Arrays.asList(ORCID), annotationsOn(PERSON, Provenance.CONTRIBUTOR));
        assertEquals(Arrays.asList("2026-08-28"), annotationsOn(PERSON, Provenance.CREATED));
    }

    /**
     * dcterms:creator is the intuitive choice and the wrong one - zero term-level uses across
     * released obi.owl, pato.owl and omo.owl. If someone "corrects" this to creator, this fails.
     */
    @Test
    void theContributorPropertyIsTheOneReleasedOboOntologiesCarry() {
        assertEquals("http://purl.org/dc/terms/contributor", Provenance.CONTRIBUTOR.toString());
        assertEquals("http://purl.org/dc/terms/created", Provenance.CREATED.toString());
        assertEquals("http://purl.org/dc/terms/date", Provenance.MODIFIED.toString());
    }

    /**
     * Day precision, not second. dcterms:date is rewritten on every edit, so seconds would put a
     * diff in the ontology each time anyone touched a term and would record what hours each
     * contributor keeps.
     */
    @Test
    void datesAreRecordedAtDayPrecision() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));

        assertEquals("date", datatypeOf(Provenance.CREATED),
                "xsd:date, as obi.owl carries; dateTime would add second precision to a value "
                        + "that is rewritten on every edit");
    }

    // ---------- ORCIDs are IRIs, names are literals ----------

    @Test
    void anOrcidIsRecordedAsAnIriSoTheAttributionResolves() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));

        for (OWLAnnotationAssertionAxiom axiom : ontology.getAnnotationAssertionAxioms(PERSON)) {
            if (Provenance.CONTRIBUTOR.equals(axiom.getProperty().getIRI())) {
                assertTrue(axiom.getValue() instanceof IRI,
                        "an ORCID recorded as a string is not resolvable and two people can "
                                + "share a name: " + axiom.getValue());
            }
        }
    }

    @Test
    void theThreeWaysPeoplePasteAnOrcidAreAllRecognised() {
        assertEquals(ORCID, Provenance.normaliseOrcid("0000-0001-9625-1899"));
        assertEquals(ORCID, Provenance.normaliseOrcid("https://orcid.org/0000-0001-9625-1899"));
        assertEquals(ORCID, Provenance.normaliseOrcid("http://orcid.org/0000-0001-9625-1899"));
        assertEquals(ORCID, Provenance.normaliseOrcid("  orcid.org/0000-0001-9625-1899  "));
    }

    /** The last character of an ORCID may be X, and it is a checksum digit, not a letter. */
    @Test
    void anOrcidEndingInXIsAccepted() {
        assertEquals("https://orcid.org/0000-0002-1825-009X",
                Provenance.normaliseOrcid("0000-0002-1825-009x"));
    }

    @Test
    void somethingThatIsNotAnOrcidIsNotTreatedAsOne() {
        assertNull(Provenance.normaliseOrcid("Alice Smith"));
        assertNull(Provenance.normaliseOrcid("0000-0001-9625"));
        assertNull(Provenance.normaliseOrcid(""));
        assertNull(Provenance.normaliseOrcid(null));
    }

    /**
     * Insisting on an ORCID would leave anyone without one unable to record provenance at all,
     * which is worse than a less resolvable attribution.
     */
    @Test
    void aPlainNameIsRecordedAsALiteralRatherThanRefused() {
        apply(Provenance.stampNew(ontology, PERSON, "Alice Smith", "2026-08-28"));

        assertEquals(Arrays.asList("Alice Smith"),
                annotationsOn(PERSON, Provenance.CONTRIBUTOR));
    }

    // ---------- modification does not accumulate ----------

    @Test
    void theModifiedDateIsReplacedRatherThanAccumulated() {
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-28"));
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-29"));

        assertEquals(Arrays.asList("2026-08-29"), annotationsOn(PERSON, Provenance.MODIFIED),
                "last-modified is one fact, not a history");
    }

    /** Two edits in a day must produce no change at all, or the diff reports work that was not done. */
    @Test
    void editingTwiceInOneDayChangesNothingTheSecondTime() {
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-28"));

        assertTrue(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-28").isEmpty(),
                "an unchanged stamp must not dirty the ontology");
    }

    /** Several people genuinely do contribute to one term, so contributors accumulate. */
    @Test
    void aSecondContributorIsAddedRatherThanReplacingTheFirst() {
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-28"));
        apply(Provenance.stampModified(ontology, PERSON, "Bob Jones", "2026-08-29"));

        assertEquals(Arrays.asList("Bob Jones", ORCID),
                annotationsOn(PERSON, Provenance.CONTRIBUTOR));
    }

    @Test
    void theSameContributorIsNotRecordedTwice() {
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-28"));
        apply(Provenance.stampModified(ontology, PERSON, ORCID, "2026-08-29"));

        assertEquals(1, annotationsOn(PERSON, Provenance.CONTRIBUTOR).size());
    }

    // ---------- following what the ontology already does ----------

    /**
     * Stamping an ontology that has never carried provenance introduces a convention its
     * maintainers did not choose, on every term anyone adds, showing up as unexplained churn.
     */
    @Test
    void anOntologyWithNoProvenanceIsRecognisedAsSuch() {
        assertFalse(Provenance.isUsedIn(ontology));
        assertFalse(Provenance.isUsedIn(null));
    }

    @Test
    void anOntologyThatAlreadyRecordsProvenanceIsRecognised() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));

        assertTrue(Provenance.isUsedIn(ontology));
    }

    @Test
    void anOntologyWithOnlyLabelsIsNotMistakenForOneWithProvenance() {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), PERSON, factory.getOWLLiteral("Person")));

        assertFalse(Provenance.isUsedIn(ontology));
    }

    // ---------- the properties are declared ----------

    /** ROBOT report flags an undeclared annotation property, so omitting these fails the project's own check. */
    @Test
    void theAnnotationPropertiesAreDeclaredSoRobotReportDoesNotFlagThem() {
        apply(Provenance.declareProperties(ontology));

        assertTrue(ontology.isDeclared(
                factory.getOWLAnnotationProperty(Provenance.CONTRIBUTOR)));
        assertTrue(ontology.isDeclared(factory.getOWLAnnotationProperty(Provenance.CREATED)));
        assertTrue(ontology.isDeclared(factory.getOWLAnnotationProperty(Provenance.MODIFIED)));
    }

    @Test
    void declaringTwiceAddsNothingTheSecondTime() {
        apply(Provenance.declareProperties(ontology));

        assertTrue(Provenance.declareProperties(ontology).isEmpty());
    }

    // ---------- nothing is done without an agent ----------

    @Test
    void withNoAgentNothingIsRecorded() {
        assertTrue(Provenance.stampNew(ontology, PERSON, null, "2026-08-28").isEmpty());
        assertTrue(Provenance.stampNew(ontology, PERSON, "   ", "2026-08-28").isEmpty());
        assertTrue(Provenance.creationAnnotations(factory, "", "2026-08-28").isEmpty());
    }

    @Test
    void aMissingDateStillRecordsWhoDidIt() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, null));

        assertEquals(Arrays.asList(ORCID), annotationsOn(PERSON, Provenance.CONTRIBUTOR));
        assertTrue(annotationsOn(PERSON, Provenance.CREATED).isEmpty());
    }

    @Test
    void contributorsCanBeListedForAPanel() {
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));
        apply(Provenance.stampModified(ontology, PERSON, "Bob Jones", "2026-08-29"));

        assertEquals(Arrays.asList("Bob Jones", ORCID),
                Provenance.contributorsOf(ontology, PERSON));
        assertTrue(Provenance.contributorsOf(null, PERSON).isEmpty());
    }

    /** The stamp on a term and on the axioms introducing it must be identical, not merely similar. */
    @Test
    void theAxiomAnnotationsMatchTheEntityStamp() {
        assertEquals(2, Provenance.creationAnnotations(factory, ORCID, "2026-08-28").size());
        apply(Provenance.stampNew(ontology, PERSON, ORCID, "2026-08-28"));

        assertEquals(annotationsOn(PERSON, Provenance.CONTRIBUTOR).size()
                + annotationsOn(PERSON, Provenance.CREATED).size(),
                Provenance.creationAnnotations(factory, ORCID, "2026-08-28").size());
    }
}
