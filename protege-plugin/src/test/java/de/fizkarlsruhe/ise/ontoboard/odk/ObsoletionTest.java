package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Retiring a term without breaking everything that used it.
 *
 * <p>Deleting a published term is the one edit a consumer cannot recover from: their import still
 * resolves, their axioms still parse, and the term they referenced is simply gone, with no way to
 * tell it from one that never existed. Obsoleting keeps the IRI resolvable and says what happened
 * to it.
 *
 * <p>The half people get wrong is the logical axioms. Marking a term deprecated and leaving it as
 * somebody's superclass means the ontology goes on entailing things through a term nobody should
 * use - which is worse than not retiring it at all, because now it looks handled.
 */
class ObsoletionTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyWithAHierarchy() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();

        // Declared, as a real ontology declares its terms. Without declarations a class exists in
        // the signature only through the axioms that mention it, so obsoleting one term would take
        // its neighbours out too - see aDeclaredNeighbourSurvivesObsoletion, which is the case
        // that matters and the reason this fixture is not the lazier one.
        for (String name : new String[] {"Polymer", "Material", "Nylon"}) {
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls(name)));
        }
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Polymer"), cls("Material")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Nylon"), cls("Polymer")));
        label("Polymer", "polymer");
        label("Nylon", "nylon");
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(ReleaseDiff.DEFINITION),
                cls("Polymer").getIRI(), factory.getOWLLiteral("A large molecule.")));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private void label(String name, String text) {
        manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), cls(name).getIRI(), factory.getOWLLiteral(text)));
    }

    private void obsoletePolymer(IRI replacement, boolean exact, String reason) {
        manager.applyChanges(Obsoletion.obsolete(ontology, cls("Polymer").getIRI(),
                replacement, exact, reason));
    }

    private String annotation(String name, IRI property) {
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(cls(name).getIRI())) {
            if (property.equals(axiom.getProperty().getIRI())) {
                return axiom.getValue() instanceof OWLLiteral
                        ? ((OWLLiteral) axiom.getValue()).getLiteral()
                        : axiom.getValue().toString();
            }
        }
        return null;
    }

    // ---------- the term survives, which is the whole point ----------

    @Test
    void anObsoletedTermIsStillInTheOntology() {
        obsoletePolymer(null, false, null);

        assertTrue(ontology.containsClassInSignature(cls("Polymer").getIRI()),
                "obsoleting must not remove the term - that is what deleting does");
        assertTrue(Obsoletion.isObsolete(ontology, cls("Polymer").getIRI()));
    }

    /** A definition on an obsolete term is how somebody works out what it used to mean. */
    @Test
    void theAnnotationsSurvive() {
        obsoletePolymer(null, false, null);

        assertEquals("A large molecule.", annotation("Polymer", ReleaseDiff.DEFINITION));
    }

    @Test
    void theLabelIsPrefixedSoItIsObviousEverywhere() {
        obsoletePolymer(null, false, null);

        assertEquals("obsolete polymer", annotation("Polymer",
                org.semanticweb.owlapi.vocab.OWLRDFVocabulary.RDFS_LABEL.getIRI()));
    }

    /** Two labels means every tool that shows one picks arbitrarily. */
    @Test
    void thePrefixReplacesTheLabelRatherThanAddingASecond() {
        obsoletePolymer(null, false, null);

        int labels = 0;
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAnnotationAssertionAxioms(cls("Polymer").getIRI())) {
            if (axiom.getProperty().isLabel()) {
                labels++;
            }
        }
        assertEquals(1, labels);
    }

    // ---------- the half people get wrong ----------

    /**
     * A deprecated term that is still somebody's superclass means the ontology goes on entailing
     * things through a term nobody should use - and it looks handled, which is worse.
     */
    @Test
    void everyLogicalAxiomAboutItGoes() {
        obsoletePolymer(null, false, null);

        assertTrue(ontology.getSubClassAxiomsForSubClass(cls("Polymer")).isEmpty(),
                "it still has a parent");
        assertTrue(ontology.getSubClassAxiomsForSuperClass(cls("Polymer")).isEmpty(),
                "something is still a kind of it");
    }

    /** A term inside somebody else's restriction is entangled just as much. */
    @Test
    void anAxiomThatOnlyMentionsItAlsoGoes() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Bottle"),
                factory.getOWLObjectSomeValuesFrom(
                        factory.getOWLObjectProperty(IRI.create(NS + "madeOf")),
                        cls("Polymer"))));
        int before = ontology.getLogicalAxiomCount();

        obsoletePolymer(null, false, null);

        assertTrue(ontology.getLogicalAxiomCount() < before);
        for (org.semanticweb.owlapi.model.OWLLogicalAxiom axiom : ontology.getLogicalAxioms()) {
            assertFalse(axiom.getSignature().contains(cls("Polymer")),
                    "still logically entangled: " + axiom);
        }
    }

    // ---------- saying where the term went ----------

    /**
     * The difference matters to anybody migrating: an exact replacement can be swapped in
     * mechanically, a suggestion needs a person to look.
     */
    @Test
    void anExactReplacementUsesTermReplacedBy() {
        obsoletePolymer(IRI.create(NS + "Macromolecule"), true, null);

        assertEquals(NS + "Macromolecule",
                annotation("Polymer", Obsoletion.TERM_REPLACED_BY));
        assertNull(annotation("Polymer", Obsoletion.CONSIDER));
    }

    @Test
    void aSuggestionUsesConsider() {
        obsoletePolymer(IRI.create(NS + "Macromolecule"), false, null);

        assertEquals(NS + "Macromolecule", annotation("Polymer", Obsoletion.CONSIDER));
        assertNull(annotation("Polymer", Obsoletion.TERM_REPLACED_BY));
    }

    @Test
    void aReasonIsRecordedWhenGiven() {
        obsoletePolymer(null, false, "Duplicated CHEBI:60027.");

        assertEquals("Duplicated CHEBI:60027.",
                annotation("Polymer", Obsoletion.OBSOLESCENCE_REASON));
    }

    @Test
    void noReplacementAndNoReasonLeavesNeitherAnnotation() {
        obsoletePolymer(null, false, "   ");

        assertNull(annotation("Polymer", Obsoletion.TERM_REPLACED_BY));
        assertNull(annotation("Polymer", Obsoletion.CONSIDER));
        assertNull(annotation("Polymer", Obsoletion.OBSOLESCENCE_REASON));
    }

    // ---------- what the editor is told first ----------

    /**
     * Obsoleting is safe for consumers and not free inside the ontology: anything that was a
     * subclass loses a parent, and the editor should know which before pressing the button.
     */
    @Test
    void theTermsThatWillLoseAConnectionAreNamed() {
        java.util.Set<IRI> referencing =
                Obsoletion.termsReferencing(ontology, cls("Polymer").getIRI());

        assertTrue(referencing.contains(cls("Nylon").getIRI()), referencing.toString());
        assertTrue(referencing.contains(cls("Material").getIRI()), referencing.toString());
        assertFalse(referencing.contains(cls("Polymer").getIRI()),
                "a term does not reference itself");
    }

    @Test
    void aTermNothingReferencesHasNoReferences() throws Exception {
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(cls("Loner")));

        assertTrue(Obsoletion.termsReferencing(ontology, cls("Loner").getIRI()).isEmpty());
    }

    // ---------- refusals ----------

    /** Being referenced is the ordinary case and exactly what obsoleting handles well. */
    @Test
    void beingReferencedIsNotAReasonToRefuse() {
        assertNull(Obsoletion.whyNot(ontology, cls("Polymer").getIRI()));
    }

    @Test
    void obsoletingTwiceDoesNothingTheSecondTime() {
        obsoletePolymer(null, false, null);

        List<OWLOntologyChange> again = Obsoletion.obsolete(ontology,
                cls("Polymer").getIRI(), null, false, null);

        assertTrue(again.isEmpty(), again.toString());
        assertTrue(Obsoletion.whyNot(ontology, cls("Polymer").getIRI())
                .contains("already obsolete"));
    }

    @Test
    void aTermThatIsNotHereIsRefused() {
        assertTrue(Obsoletion.whyNot(ontology, IRI.create(NS + "Absent"))
                .contains("not in this ontology"));
        assertTrue(Obsoletion.whyNot(ontology, null).contains("Select a term"));
    }

    /**
     * Obsoleting disconnects the term, and must not take its neighbours with it. A term that is
     * declared survives losing every axiom that mentioned it; one that was never declared existed
     * in the signature only through those axioms, and vanishes - which is the same silent loss
     * this feature exists to prevent, arriving from the other direction.
     */
    @Test
    void aDeclaredNeighbourSurvivesObsoletion() {
        obsoletePolymer(null, false, null);

        assertTrue(ontology.containsClassInSignature(cls("Nylon").getIRI()),
                "the subclass was dropped along with its parent");
        assertTrue(ontology.containsClassInSignature(cls("Material").getIRI()),
                "the parent was dropped along with its subclass");
    }

    // ---------- it is what the diff wanted ----------

    /**
     * The release comparison refuses to ship a release that drops a published term and tells the
     * editor to obsolete it instead. That advice has to actually work: an obsoleted term must read
     * as obsoleted to the diff, not as removed.
     */
    @Test
    void anObsoletedTermReadsAsObsoletedToTheReleaseComparison() throws Exception {
        OWLOntology before = OWLManager.createOWLOntologyManager()
                .createOntology(ontology.getAxioms(), IRI.create("http://example.org/before"));

        obsoletePolymer(IRI.create(NS + "Macromolecule"), true, null);

        ReleaseDiff diff = ReleaseDiff.between(before, ontology);

        assertEquals(1, diff.count(ReleaseDiff.Change.OBSOLETED));
        assertTrue(diff.removals().isEmpty(),
                "the advice the release gives must not itself trip the release check");
    }
}
