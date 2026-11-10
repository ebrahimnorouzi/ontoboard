package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * What changed between two versions, by term.
 *
 * <p>All five expert panels asked for this and none of them asked for an axiom diff. A list of
 * axiom strings is what {@code robot diff} already produces: a release that renamed one term runs
 * to dozens of lines of it and never says the word "renamed".
 *
 * <p>The distinction the whole class exists for is <b>obsoleted versus removed</b>. Obsoleting
 * keeps the term, marked deprecated, so everything that referenced it still resolves; removing it
 * breaks every consumer that used it, silently, and the two are indistinguishable in an axiom diff
 * unless you already know what you are looking at.
 */
class ReleaseDiffTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLDataFactory factory;
    private OWLOntology before;
    private OWLOntology after;

    @BeforeEach
    void twoVersions() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        before = manager.createOntology(IRI.create("http://example.org/before"));
        after = manager.createOntology(IRI.create("http://example.org/after"));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private void declare(OWLOntology ontology, String name) {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls(name)));
    }

    private void label(OWLOntology ontology, String name, String text) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), cls(name).getIRI(), factory.getOWLLiteral(text)));
    }

    private void define(OWLOntology ontology, String name, String text) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(ReleaseDiff.DEFINITION),
                cls(name).getIRI(), factory.getOWLLiteral(text)));
    }

    private void parent(OWLOntology ontology, String child, String parent) {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls(child), cls(parent)));
    }

    private void deprecate(OWLOntology ontology, String name) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(OWLRDFVocabulary.OWL_DEPRECATED.getIRI()),
                cls(name).getIRI(), factory.getOWLLiteral(true)));
    }

    // ---------- the distinction that matters most ----------

    /**
     * The one outcome that cannot be undone for somebody downstream. A release that drops a
     * published term breaks every ontology that imported it, and nothing tells them.
     */
    @Test
    void aTermThatIsGoneIsReportedAsRemovedRatherThanObsoleted() {
        declare(before, "Polymer");
        label(before, "Polymer", "polymer");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(1, diff.count(ReleaseDiff.Change.REMOVED));
        assertEquals(0, diff.count(ReleaseDiff.Change.OBSOLETED));
        assertEquals("polymer", diff.removals().get(0).getBefore());
    }

    /** Obsoleting is the correct retirement: the term stays and references still resolve. */
    @Test
    void aDeprecatedTermIsObsoletedNotRemoved() {
        declare(before, "Polymer");
        declare(after, "Polymer");
        deprecate(after, "Polymer");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(1, diff.count(ReleaseDiff.Change.OBSOLETED));
        assertEquals(0, diff.count(ReleaseDiff.Change.REMOVED));
        assertTrue(diff.removals().isEmpty());
    }

    /** A reader of the notes needs to know where the term went. */
    @Test
    void anObsoletedTermCarriesWhatReplacedIt() {
        declare(before, "Polymer");
        declare(after, "Polymer");
        deprecate(after, "Polymer");
        manager.addAxiom(after, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0100001")),
                cls("Polymer").getIRI(), factory.getOWLLiteral(NS + "Macromolecule")));

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertTrue(diff.of(ReleaseDiff.Change.OBSOLETED).get(0).getAfter()
                .contains("Macromolecule"));
    }

    /** A term deprecated in both versions is not news. */
    @Test
    void aTermAlreadyObsoleteBeforeIsNotReportedAgain() {
        declare(before, "Polymer");
        deprecate(before, "Polymer");
        declare(after, "Polymer");
        deprecate(after, "Polymer");

        assertEquals(0, ReleaseDiff.between(before, after)
                .count(ReleaseDiff.Change.OBSOLETED));
    }

    // ---------- the other four kinds ----------

    @Test
    void aNewTermIsAdded() {
        declare(after, "Polymer");
        label(after, "Polymer", "polymer");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(1, diff.count(ReleaseDiff.Change.ADDED));
        assertEquals("polymer", diff.of(ReleaseDiff.Change.ADDED).get(0).getAfter());
    }

    @Test
    void achangedLabelIsRelabelledAndCarriesBothNames() {
        declare(before, "Polymer");
        label(before, "Polymer", "polymer");
        declare(after, "Polymer");
        label(after, "Polymer", "macromolecule");

        ReleaseDiff.TermChange change =
                ReleaseDiff.between(before, after).of(ReleaseDiff.Change.RELABELLED).get(0);

        assertEquals("polymer", change.getBefore());
        assertEquals("macromolecule", change.getAfter());
    }

    /** A changed definition may mean the term now means something else, which is the alarm. */
    @Test
    void achangedDefinitionIsRedefined() {
        declare(before, "Polymer");
        define(before, "Polymer", "A large molecule.");
        declare(after, "Polymer");
        define(after, "Polymer", "A molecule of repeating subunits.");

        ReleaseDiff.TermChange change =
                ReleaseDiff.between(before, after).of(ReleaseDiff.Change.REDEFINED).get(0);

        assertEquals("A large molecule.", change.getBefore());
        assertEquals("A molecule of repeating subunits.", change.getAfter());
    }

    @Test
    void achangedParentIsMoved() {
        declare(before, "Polymer");
        parent(before, "Polymer", "Substance");
        declare(after, "Polymer");
        parent(after, "Polymer", "Material");

        ReleaseDiff.TermChange change =
                ReleaseDiff.between(before, after).of(ReleaseDiff.Change.MOVED).get(0);

        assertEquals("Substance", change.getBefore());
        assertEquals("Material", change.getAfter());
    }

    /** A restriction is not a place in the hierarchy, so it must not read as a move. */
    @Test
    void anAnonymousSuperclassIsNotAMove() {
        declare(before, "Polymer");
        declare(after, "Polymer");
        manager.addAxiom(after, factory.getOWLSubClassOfAxiom(cls("Polymer"),
                factory.getOWLObjectSomeValuesFrom(
                        factory.getOWLObjectProperty(IRI.create(NS + "madeOf")),
                        cls("Monomer"))));

        assertEquals(0, ReleaseDiff.between(before, after).count(ReleaseDiff.Change.MOVED));
    }

    // ---------- nothing said twice, nothing said wrongly ----------

    @Test
    void twoIdenticalVersionsHaveNothingToReport() {
        declare(before, "Polymer");
        label(before, "Polymer", "polymer");
        declare(after, "Polymer");
        label(after, "Polymer", "polymer");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertTrue(diff.isEmpty(), diff.getChanges().toString());
        assertTrue(diff.summary().contains("Nothing changed"), diff.summary());
    }

    /**
     * A term with two labels must not appear to change between two runs over identical files,
     * purely because a hash set iterated differently.
     */
    @Test
    void aTermWithTwoLabelsComparesTheSameWayEveryTime() {
        for (OWLOntology ontology : new OWLOntology[] {before, after}) {
            declare(ontology, "Polymer");
            label(ontology, "Polymer", "zeta");
            label(ontology, "Polymer", "alpha");
        }

        for (int run = 0; run < 5; run++) {
            assertEquals(0, ReleaseDiff.between(before, after)
                    .count(ReleaseDiff.Change.RELABELLED), "run " + run);
        }
    }

    @Test
    void owlThingIsNotATermThatCameOrWent() {
        manager.addAxiom(after, factory.getOWLSubClassOfAxiom(cls("Polymer"),
                factory.getOWLThing()));

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        for (ReleaseDiff.TermChange change : diff.getChanges()) {
            assertFalse(change.getIri().toString().endsWith("owl#Thing"), change.toString());
        }
    }

    @Test
    void comparingAgainstNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseDiff.between(null, after));
        assertThrows(IllegalArgumentException.class, () -> ReleaseDiff.between(before, null));
    }

    // ---------- what a person reads ----------

    @Test
    void theSummaryCountsEachKindInWords() {
        declare(after, "New");
        declare(before, "Gone");
        declare(before, "Kept");
        declare(after, "Kept");
        label(before, "Kept", "old name");
        label(after, "Kept", "new name");

        String summary = ReleaseDiff.between(before, after).summary();

        assertTrue(summary.contains("1 added"), summary);
        assertTrue(summary.contains("1 removed"), summary);
        assertTrue(summary.contains("1 relabelled"), summary);
    }

    /**
     * The notes are generated rather than remembered, and the removals go first with an
     * explanation - because that is the section somebody has to read before publishing.
     */
    @Test
    void theReleaseNotesPutRemovalsFirstAndSayWhyTheyMatter() {
        declare(before, "Gone");
        label(before, "Gone", "gone term");
        declare(after, "New");
        label(after, "New", "new term");

        String notes = ReleaseDiff.between(before, after).asReleaseNotes("2026-08-31", after);

        assertTrue(notes.startsWith("# 2026-08-31"), notes);
        assertTrue(notes.indexOf("## Removed") < notes.indexOf("## Added"),
                "removals must come before additions:\\n" + notes);
        assertTrue(notes.contains("references nothing"), notes);
        assertTrue(notes.contains("gone term"), notes);
    }

    @Test
    void theNotesLeaveOutSectionsWithNothingInThem() {
        declare(after, "New");

        String notes = ReleaseDiff.between(before, after).asReleaseNotes("2026-08-31", after);

        assertTrue(notes.contains("## Added"), notes);
        assertFalse(notes.contains("## Removed"), notes);
        assertFalse(notes.contains("## Obsoleted"), notes);
    }

    @Test
    void notesForAnUnchangedReleaseSaySoRatherThanBeingEmpty() {
        declare(before, "Kept");
        declare(after, "Kept");

        String notes = ReleaseDiff.between(before, after).asReleaseNotes("2026-08-31", after);

        assertTrue(notes.contains("Nothing changed"), notes);
    }

    @Test
    void axiomCountsAreCarriedForScale() {
        declare(before, "A");
        declare(after, "A");
        declare(after, "B");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(before.getAxiomCount(), diff.getAxiomsBefore());
        assertEquals(after.getAxiomCount(), diff.getAxiomsAfter());
    }

    /**
     * One retirement is one row. Obsoleting a term the OBO way always prefixes its label with
     * "obsolete " and always takes it out of the hierarchy, so a diff that reported those as well
     * turned one decision into three - and a curator reading the release note could not tell that
     * the three were the same event. Found by running a release history end to end.
     */
    @Test
    void obsoletingATermIsReportedOnceNotThreeTimes() throws Exception {
        for (OWLOntology version : new OWLOntology[] {before, after}) {
            declare(version, "Olive");
            declare(version, "Vegetable");
            label(version, "Olive", "olive topping");
            parent(version, "Olive", "Vegetable");
        }
        // The real thing, not a hand-rolled imitation: obsoletion is what prefixes the label and
        // strips the hierarchy, so a test that faked it would not exercise the interaction.
        manager.applyChanges(Obsoletion.obsolete(after, IRI.create(NS + "Olive"),
                IRI.create(NS + "Caper"), false, "too specific for this ontology"));

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        int mentions = 0;
        for (ReleaseDiff.TermChange change : diff.getChanges()) {
            if (change.getIri().toString().equals(NS + "Olive")) {
                mentions++;
                assertEquals(ReleaseDiff.Change.OBSOLETED, change.getChange(),
                        "a retirement was also reported as " + change.getChange());
            }
        }
        assertEquals(1, mentions, "one retirement should be one row: " + diff.getChanges());
    }

    // ---------- properties are terms too ----------

    private void declareProperty(OWLOntology ontology, EntityType<?> type, String name) {
        IRI iri = IRI.create(NS + name);
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                type == EntityType.OBJECT_PROPERTY ? factory.getOWLObjectProperty(iri)
                        : type == EntityType.DATA_PROPERTY ? factory.getOWLDataProperty(iri)
                                : factory.getOWLAnnotationProperty(iri)));
    }

    /**
     * A tester's release added the object property "specified by value" and two classes, and the
     * notes said "2 added".
     */
    @Test
    void anAddedPropertyIsReported() {
        declareProperty(after, EntityType.OBJECT_PROPERTY, "specifiedByValue");
        label(after, "specifiedByValue", "specified by value");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(1, diff.count(ReleaseDiff.Change.ADDED), diff.getChanges().toString());
        ReleaseDiff.TermChange added = diff.of(ReleaseDiff.Change.ADDED).get(0);
        assertEquals(EntityType.OBJECT_PROPERTY, added.getType());
        assertEquals("specified by value", added.getAfter());
    }

    /**
     * And a removed one is caught like a removed class. Deleting that property from the release
     * said no term had been added, retired, relabelled, redefined or moved, and left the release
     * check nothing to refuse.
     */
    @Test
    void aRemovedPropertyIsCaughtLikeARemovedClass() {
        declareProperty(before, EntityType.OBJECT_PROPERTY, "specifiedByValue");
        declareProperty(before, EntityType.DATA_PROPERTY, "hasValue");
        declareProperty(before, EntityType.ANNOTATION_PROPERTY, "exampleOfUse");

        ReleaseDiff diff = ReleaseDiff.between(before, after);

        assertEquals(3, diff.removals().size(), diff.getChanges().toString());
        assertTrue(diff.summary().contains("3 removed"), diff.summary());
    }

    @Test
    void theNotesSayWhichKindOfTermAPropertyIs() {
        declareProperty(after, EntityType.OBJECT_PROPERTY, "specifiedByValue");
        label(after, "specifiedByValue", "specified by value");
        declare(after, "Polymer");
        label(after, "Polymer", "polymer");

        String notes = ReleaseDiff.between(before, after).asReleaseNotes("1.1.0", after);

        assertTrue(notes.contains("`specifiedByValue` specified by value (object property)"),
                notes);
        assertTrue(notes.contains("`Polymer` polymer\n"), "a class needs no word for its kind:\n"
                + notes);
    }

    /** OWL's own vocabulary is nobody's term: labelling a class does not add rdfs:label. */
    @Test
    void owlsOwnAnnotationPropertiesAreNotTerms() {
        declare(before, "Polymer");
        declare(after, "Polymer");
        label(after, "Polymer", "polymer");
        deprecate(after, "Polymer");

        for (ReleaseDiff.TermChange change : ReleaseDiff.between(before, after).getChanges()) {
            assertEquals(NS + "Polymer", change.getIri().toString(), change.toString());
        }
    }

    /** History lists properties its own way and takes only the classes from here. */
    @Test
    void aDiffRestrictedToClassesLeavesThePropertiesOut() {
        declare(after, "Polymer");
        declareProperty(after, EntityType.OBJECT_PROPERTY, "specifiedByValue");

        ReleaseDiff classes = ReleaseDiff.between(before, after).restrictedTo(EntityType.CLASS);

        assertEquals(1, classes.getChanges().size(), classes.getChanges().toString());
        assertEquals(EntityType.CLASS, classes.getChanges().get(0).getType());
    }

    // ---------- the label the board shows ----------

    /**
     * Release notes listed "Länge" and "Material" for terms the board showed as length and
     * material: the labels were sorted, and the German one sorted first.
     */
    @Test
    void theNotesUseTheEnglishLabelAsTheBoardDoes() {
        declare(after, "Length");
        for (String[] text : new String[][] {{"Länge", "de"}, {"length", "en"}}) {
            manager.addAxiom(after, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                    cls("Length").getIRI(), factory.getOWLLiteral(text[0], text[1])));
        }

        ReleaseDiff diff = ReleaseDiff.between(before, after);
        String notes = diff.asReleaseNotes("1.1.0", after);

        assertEquals("length", diff.of(ReleaseDiff.Change.ADDED).get(0).getAfter());
        assertTrue(notes.contains("`Length` length"), notes);
        assertFalse(notes.contains("Länge"), notes);
    }

    /**
     * A property used only in the ontology's header is not a published term: a review found that
     * changing dc:creator to dcterms:creator there refused the next release.
     */
    @Test
    void aHeaderAnnotationIsNotAPublishedTerm() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology before = manager.createOntology(IRI.create("http://example.org/o/1"));
        manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(before,
                df.getOWLAnnotation(df.getOWLAnnotationProperty(
                        IRI.create("http://purl.org/dc/elements/1.1/creator")),
                        df.getOWLLiteral("Someone"))));
        OWLOntology after = manager.createOntology(IRI.create("http://example.org/o/2"));

        assertTrue(ReleaseDiff.between(before, after).removals().isEmpty());
    }
}
