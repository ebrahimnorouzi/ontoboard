package de.fizkarlsruhe.ise.ontoboard.prov;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;

/**
 * Provenance for edits made in Protege's own editors, not only on the canvas.
 *
 * <p>The gap this closes was ours: a term dragged on the canvas recorded who and when, and the same
 * term re-parented in the class hierarchy recorded nothing. A reader then sees dates on some terms
 * and none on others and concludes the undated ones were never touched, which was false.
 *
 * <p>Most of what is worth testing here is what it declines to stamp. Stamping too much is the
 * failure mode that destroys the value of provenance without ever looking broken.
 */
class EditWatcherTest {

    private static final String NS = "http://example.org/o#";
    private static final String AGENT = "https://orcid.org/0000-0002-1825-0097";
    private static final String TODAY = "2026-08-31";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntology() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private IRI iri(String name) {
        return IRI.create(NS + name);
    }

    /** Applies the axioms and hands back the change list Protege's listener would have seen. */
    private List<OWLOntologyChange> applied(OWLAxiom... axioms) {
        List<OWLOntologyChange> changes = new ArrayList<OWLOntologyChange>();
        for (OWLAxiom axiom : axioms) {
            changes.add(new AddAxiom(ontology, axiom));
        }
        manager.applyChanges(changes);
        return changes;
    }

    private void apply(List<OWLOntologyChange> changes) {
        manager.applyChanges(changes);
    }

    // ---------- which term an edit is about ----------

    /** The whole problem. An edit is about its subject, not about everything it mentions. */
    @Test
    void reparentingStampsTheChildAndNotTheParent() {
        List<OWLOntologyChange> edit = applied(
                factory.getOWLDeclarationAxiom(cls("Dog")),
                factory.getOWLDeclarationAxiom(cls("Mammal")),
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));

        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertEquals(Collections.singletonList(AGENT),
                Provenance.contributorsOf(ontology, iri("Dog")));
        assertEquals(Collections.singletonList(AGENT),
                Provenance.contributorsOf(ontology, iri("Mammal")),
                "Mammal was declared in this batch, so it is genuinely new");
    }

    /**
     * The failure mode that would quietly destroy the value of provenance: every re-parenting
     * marking the parent modified too, until a week's work leaves every term dated today.
     */
    @Test
    void anExistingParentIsNotMarkedModifiedByItsNewChild() {
        applied(factory.getOWLDeclarationAxiom(cls("Mammal")));
        List<OWLOntologyChange> edit = applied(
                factory.getOWLDeclarationAxiom(cls("Dog")),
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));

        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertFalse(Provenance.contributorsOf(ontology, iri("Dog")).isEmpty());
        assertTrue(Provenance.contributorsOf(ontology, iri("Mammal")).isEmpty(),
                "the parent was mentioned, not edited");
    }

    /** A restriction typed into the Manchester editor is an edit to the class it is on. */
    @Test
    void aRestrictionIsAnEditToTheClassItIsOn() {
        applied(factory.getOWLDeclarationAxiom(cls("Parent")),
                factory.getOWLDeclarationAxiom(cls("Person")),
                factory.getOWLDeclarationAxiom(property("hasChild")));
        Provenance.stampNew(ontology, iri("Parent"), AGENT, "2026-01-01")
                .forEach(change -> manager.applyChange(change));

        List<OWLOntologyChange> edit = applied(factory.getOWLSubClassOfAxiom(cls("Parent"),
                factory.getOWLObjectSomeValuesFrom(property("hasChild"), cls("Person"))));
        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertEquals(TODAY, modifiedOf(iri("Parent")));
        assertTrue(modifiedOf(iri("Person")).isEmpty(), "Person is the filler, not the subject");
    }

    /** Equivalence is symmetric, so it really is an edit to both sides. */
    @Test
    void equivalenceIsAnEditToBothSides() {
        assertTrue(EditWatcher.subjectsOf(factory.getOWLEquivalentClassesAxiom(
                cls("Dog"), cls("Hound"))).containsAll(Arrays.asList(iri("Dog"), iri("Hound"))));
    }

    /** A general class inclusion has no term to put the annotation on, and says so. */
    @Test
    void anAnonymousSubjectAttributesNothing() {
        assertTrue(EditWatcher.subjectsOf(factory.getOWLSubClassOfAxiom(
                factory.getOWLObjectSomeValuesFrom(property("r"), cls("B")), cls("A")))
                .isEmpty());
    }

    // ---------- what it declines to stamp ----------

    /**
     * The termination condition. Applying a stamp fires the listener again; if the stamp counted
     * as an edit, the ontology would grow annotations for as long as Protege stayed open.
     */
    @Test
    void aStampIsNotAnEdit() {
        applied(factory.getOWLDeclarationAxiom(cls("Dog")));
        List<OWLOntologyChange> first = EditWatcher.stampsFor(ontology,
                applied(factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        iri("Dog"), factory.getOWLLiteral("dog"))),
                AGENT, TODAY);
        apply(first);
        assertFalse(first.isEmpty());

        assertTrue(EditWatcher.stampsFor(ontology, first, AGENT, TODAY).isEmpty(),
                "stamping a stamp does not terminate");
        assertFalse(EditWatcher.isWorthStamping(ontology, first));
    }

    /**
     * A stamp brings its own annotation property declarations with it, so that a stamped project
     * does not fail ROBOT report on an undeclared property. Counting those declarations as term
     * creation had the plugin recording itself as the author of {@code dcterms:contributor} - and
     * writing a stamp on the declarations that a stamp had just added. Found by the test above.
     */
    @Test
    void theStampsOwnDeclarationsAreNotTermCreation() {
        applied(factory.getOWLDeclarationAxiom(cls("Dog")));
        List<OWLOntologyChange> stamps = EditWatcher.stampsFor(ontology,
                applied(factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        iri("Dog"), factory.getOWLLiteral("dog"))),
                AGENT, TODAY);
        apply(stamps);

        assertTrue(Provenance.contributorsOf(ontology, Provenance.CONTRIBUTOR).isEmpty(),
                "the plugin recorded itself as the author of dcterms:contributor");
        assertTrue(EditWatcher.subjectsOf(factory.getOWLDeclarationAxiom(
                factory.getOWLAnnotationProperty(EditorNotes.EDITOR_NOTE))).isEmpty(),
                "declaring IAO:0000116 is saying the ontology uses notes, not making a term");
    }

    /** A class declaration still is term creation - the rule above must not swallow that. */
    @Test
    void aClassDeclarationIsStillTermCreation() {
        assertEquals(Collections.singleton(iri("Dog")),
                EditWatcher.subjectsOf(factory.getOWLDeclarationAxiom(cls("Dog"))));
        assertEquals(Collections.singleton(iri("partOf")),
                EditWatcher.subjectsOf(factory.getOWLDeclarationAxiom(property("partOf"))));
    }

    /** Deleting a term must not resurrect it with a contributor annotation. */
    @Test
    void aDeletedTermIsNotStamped() {
        applied(factory.getOWLDeclarationAxiom(cls("Gone")),
                factory.getOWLSubClassOfAxiom(cls("Gone"), cls("Mammal")));

        List<OWLOntologyChange> deletion = new ArrayList<OWLOntologyChange>();
        deletion.add(new RemoveAxiom(ontology,
                factory.getOWLSubClassOfAxiom(cls("Gone"), cls("Mammal"))));
        deletion.add(new RemoveAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Gone"))));
        apply(deletion);

        apply(EditWatcher.stampsFor(ontology, deletion, AGENT, TODAY));

        assertTrue(Provenance.contributorsOf(ontology, iri("Gone")).isEmpty());
        assertTrue(ontology.getAnnotationAssertionAxioms(iri("Gone")).isEmpty(),
                "a deleted term came back carrying provenance");
    }

    /**
     * Recording yourself as a contributor to a term you merely annotated in your own file is a
     * claim about somebody else's work.
     */
    @Test
    void anImportedTermIsNotClaimed() {
        IRI foreign = IRI.create("http://purl.obolibrary.org/obo/CHEBI_15377");
        List<OWLOntologyChange> edit = applied(factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(EditorNotes.EDITOR_NOTE), foreign,
                factory.getOWLLiteral("check this")));

        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertTrue(Provenance.contributorsOf(ontology, foreign).isEmpty(),
                "this ontology does not declare CHEBI:15377, so it may not claim a contributor");
    }

    /** Changes to another ontology are not this ontology's business. */
    @Test
    void anEditToAnotherOntologyIsIgnored() throws Exception {
        OWLOntology other = manager.createOntology(IRI.create("http://example.org/other"));
        List<OWLOntologyChange> edit = Collections.<OWLOntologyChange>singletonList(
                new AddAxiom(other, factory.getOWLDeclarationAxiom(cls("Elsewhere"))));
        manager.applyChanges(edit);

        assertTrue(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY).isEmpty());
        assertFalse(EditWatcher.isWorthStamping(ontology, edit));
    }

    @Test
    void noAgentMeansNoStamp() {
        List<OWLOntologyChange> edit = applied(factory.getOWLDeclarationAxiom(cls("Dog")));

        assertTrue(EditWatcher.stampsFor(ontology, edit, "", TODAY).isEmpty());
        assertTrue(EditWatcher.stampsFor(ontology, edit, null, TODAY).isEmpty());
        assertTrue(EditWatcher.stampsFor(null, edit, AGENT, TODAY).isEmpty());
        assertTrue(EditWatcher.stampsFor(ontology, null, AGENT, TODAY).isEmpty());
    }

    // ---------- not fighting the canvas over the same term ----------

    /**
     * The canvas already stamps what it creates. The watcher then sees the same batch, and must
     * not add a "last modified today" beside a "created today" - two dates saying one thing, and a
     * second diff line for every new term.
     */
    @Test
    void aTermCreatedTodayGetsNoModificationDateToday() {
        List<OWLOntologyChange> edit = applied(factory.getOWLDeclarationAxiom(cls("Fresh")));
        apply(Provenance.stampNew(ontology, iri("Fresh"), AGENT, TODAY));

        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertEquals(TODAY, Provenance.createdOn(ontology, iri("Fresh")));
        assertTrue(modifiedOf(iri("Fresh")).isEmpty(),
                "created today and modified today is one fact written twice");
    }

    /** The day after, the same edit is a genuine modification. */
    @Test
    void editingTheNextDayDoesRecordAModification() {
        applied(factory.getOWLDeclarationAxiom(cls("Fresh")));
        apply(Provenance.stampNew(ontology, iri("Fresh"), AGENT, "2026-08-30"));

        List<OWLOntologyChange> edit = applied(factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), iri("Fresh"), factory.getOWLLiteral("fresh")));
        apply(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY));

        assertEquals(TODAY, modifiedOf(iri("Fresh")));
        assertEquals("2026-08-30", Provenance.createdOn(ontology, iri("Fresh")));
    }

    /**
     * Re-adding a declaration - which an undo does - must not rewrite the creation date, or the
     * term's history would say it was made today by whoever pressed Ctrl+Z.
     */
    @Test
    void anUndoDoesNotRewriteTheCreationDate() {
        applied(factory.getOWLDeclarationAxiom(cls("Old")));
        apply(Provenance.stampNew(ontology, iri("Old"), "somebody-else", "2019-04-02"));

        List<OWLOntologyChange> readd = applied(factory.getOWLDeclarationAxiom(cls("Old")));
        apply(EditWatcher.stampsFor(ontology, readd, AGENT, TODAY));

        assertEquals("2019-04-02", Provenance.createdOn(ontology, iri("Old")));
        assertTrue(Provenance.contributorsOf(ontology, iri("Old")).contains("somebody-else"));
    }

    // ---------- the cheap pre-check ----------

    @Test
    void theCheapCheckAgreesWithTheExpensiveOne() {
        List<OWLOntologyChange> edit = applied(factory.getOWLDeclarationAxiom(cls("Dog")));

        assertTrue(EditWatcher.isWorthStamping(ontology, edit));
        assertFalse(EditWatcher.stampsFor(ontology, edit, AGENT, TODAY).isEmpty());
        assertFalse(EditWatcher.isWorthStamping(ontology, null));
        assertFalse(EditWatcher.isWorthStamping(null, edit));
    }

    private String modifiedOf(IRI entity) {
        for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(entity)) {
            if (Provenance.MODIFIED.equals(axiom.getProperty().getIRI())) {
                return ((org.semanticweb.owlapi.model.OWLLiteral) axiom.getValue()).getLiteral();
            }
        }
        return "";
    }
}
