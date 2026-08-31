package de.fizkarlsruhe.ise.ontoboard.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EdgeAxioms;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Which OWL 2 profile an ontology is in, and which axiom takes it out.
 *
 * <p>ROBOT's measure already answers the first half as a boolean. Attribution is the half a person
 * can act on: "this ontology is not EL" is a mood, "this axiom, on this term, is why" is a finding.
 *
 * <p>It matters more here than it would elsewhere because the plugin contradicts itself without it.
 * The generated Makefile classifies with ELK, the release action classifies with ELK, ELK ignores
 * axioms outside EL silently - and the relation dialog offers readings that leave EL. The last test
 * here holds that contradiction in place so it cannot be forgotten again.
 */
class ProfileCheckTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anEmptyOntology() throws Exception {
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

    private void declare(OWLClass... classes) {
        for (OWLClass owlClass : classes) {
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(owlClass));
        }
    }

    // ---------- what is in EL and what is not ----------

    @Test
    void aPlainHierarchyIsEl() {
        declare(cls("Dog"), cls("Mammal"));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));

        assertTrue(ProfileCheck.isIn(ontology, ProfileCheck.Target.EL));
        assertEquals(ProfileCheck.Target.EL, ProfileCheck.tightestProfile(ontology));
        assertTrue(ProfileCheck.violations(ontology, ProfileCheck.Target.EL).isEmpty());
    }

    /** Existential restriction is the one construct EL exists for. */
    @Test
    void anExistentialRestrictionIsEl() {
        declare(cls("Parent"), cls("Person"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("hasChild")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Parent"),
                factory.getOWLObjectSomeValuesFrom(property("hasChild"), cls("Person"))));

        assertTrue(ProfileCheck.isIn(ontology, ProfileCheck.Target.EL));
    }

    @Test
    void aCardinalityRestrictionIsNotEl() {
        declare(cls("Person"), cls("Head"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("hasHead")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectMaxCardinality(1, property("hasHead"), cls("Head"))));

        assertFalse(ProfileCheck.isIn(ontology, ProfileCheck.Target.EL));
        assertTrue(ProfileCheck.isIn(ontology, ProfileCheck.Target.DL));
    }

    // ---------- the attribution, which is the point ----------

    /** "Not EL" is a mood. "This axiom is why" is something somebody can act on. */
    @Test
    void theOffendingAxiomIsNamed() {
        declare(cls("Dog"), cls("Mammal"), cls("Person"), cls("Head"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("hasHead")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));
        OWLAxiom offending = factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectMaxCardinality(1, property("hasHead"), cls("Head")));
        manager.addAxiom(ontology, offending);

        List<ProfileCheck.Violation> violations =
                ProfileCheck.violations(ontology, ProfileCheck.Target.EL);

        assertFalse(violations.isEmpty());
        boolean named = false;
        for (ProfileCheck.Violation violation : violations) {
            named |= offending.equals(violation.getAxiom());
        }
        assertTrue(named, "the axiom that leaves EL was not named: " + violations);
    }

    /** A term is something to open; an axiom string is something to search for. */
    @Test
    void theTermsTheAxiomIsAboutAreNamed() {
        declare(cls("Person"), cls("Head"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("hasHead")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Person"),
                factory.getOWLObjectMaxCardinality(1, property("hasHead"), cls("Head"))));

        ProfileCheck.Violation violation =
                ProfileCheck.violations(ontology, ProfileCheck.Target.EL).get(0);

        assertTrue(violation.getTerms().contains(cls("Person").getIRI()),
                violation.getTerms().toString());
    }

    /** One axiom with two problems must not appear twice in a list somebody works through. */
    @Test
    void anAxiomIsReportedOnce() {
        declare(cls("A"), cls("B"), cls("C"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("r")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("A"),
                factory.getOWLObjectIntersectionOf(
                        factory.getOWLObjectMaxCardinality(1, property("r"), cls("B")),
                        factory.getOWLObjectMaxCardinality(2, property("r"), cls("C")))));

        List<ProfileCheck.Violation> violations =
                ProfileCheck.violations(ontology, ProfileCheck.Target.EL);

        java.util.Set<OWLAxiom> axioms = new java.util.HashSet<OWLAxiom>();
        for (ProfileCheck.Violation violation : violations) {
            assertTrue(axioms.add(violation.getAxiom()),
                    "the same axiom was reported twice: " + violation.getAxiom());
        }
    }

    // ---------- one axiom on its own ----------

    /**
     * Computed rather than looked up. A table of "these constructs are EL" is a second copy of the
     * OWL 2 specification, written from memory, going stale in silence.
     */
    @Test
    void oneAxiomCanBeAskedAboutOnItsOwn() {
        assertEquals(ProfileCheck.Target.EL, ProfileCheck.tightestProfileFor(
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal"))));
        // RL, not DL - OWL 2 RL permits max-cardinality 0 or 1 in the superclass position. The
        // first version of this test asserted DL from memory and was wrong, which is the whole
        // argument for computing the answer instead of keeping a table of it.
        assertEquals(ProfileCheck.Target.RL, ProfileCheck.tightestProfileFor(
                factory.getOWLSubClassOfAxiom(cls("Person"),
                        factory.getOWLObjectMaxCardinality(1, property("hasHead"),
                                cls("Head")))));
    }

    /**
     * Without declaring the axiom's entities every axiom would come back "outside DL", and the
     * answer would be about the probe rather than about the axiom.
     */
    @Test
    void anAxiomIsNotBlamedForItsOwnUndeclaredEntities() {
        assertNotNull(ProfileCheck.tightestProfileFor(
                factory.getOWLSubClassOfAxiom(cls("Never"), cls("Declared"))));
    }

    @Test
    void nothingToCheckIsNotAnAnswer() {
        assertNull(ProfileCheck.tightestProfileFor(null));
        assertFalse(ProfileCheck.isIn(null, ProfileCheck.Target.EL));
        assertTrue(ProfileCheck.violations(null, ProfileCheck.Target.EL).isEmpty());
        assertTrue(ProfileCheck.violations(ontology, null).isEmpty());
    }

    // ---------- the warning at the gesture ----------

    @Test
    void anElAxiomWarnsAboutNothingWhenElIsTheTarget() {
        assertNull(ProfileCheck.warningFor(
                factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")),
                ProfileCheck.Target.EL));
    }

    /**
     * The sentence has to say what actually happens, which is not "this is invalid" - it is
     * perfectly valid OWL. It is that ELK will not see it.
     */
    @Test
    void leavingElSaysTheReasonerWillNotSeeIt() {
        String warning = ProfileCheck.warningFor(
                factory.getOWLSubClassOfAxiom(cls("Person"),
                        factory.getOWLObjectMaxCardinality(1, property("hasHead"), cls("Head"))),
                ProfileCheck.Target.EL);

        assertNotNull(warning);
        assertTrue(warning.contains("ELK"), warning);
        assertTrue(warning.contains("silently"), warning);
        assertFalse(warning.toLowerCase().contains("invalid"),
                "it is valid OWL - saying otherwise would be wrong: " + warning);
    }

    @Test
    void aDlAxiomIsFineWhenDlIsTheTarget() {
        assertNull(ProfileCheck.warningFor(
                factory.getOWLSubClassOfAxiom(cls("Person"),
                        factory.getOWLObjectMaxCardinality(1, property("hasHead"), cls("Head"))),
                ProfileCheck.Target.DL));
    }

    // ---------- the contradiction this exists to close ----------

    /**
     * The plugin's own relation dialog offers six readings of an arrow. The scaffold's Makefile
     * and the release action both classify with ELK. At least one of those readings leaves EL, so
     * the tool offers a gesture that makes part of the ontology invisible to its own release
     * reasoner - which is the entire reason this class exists.
     *
     * <p>Computed from the candidates rather than asserted from a list, so a seventh reading
     * cannot be added without this answering for it.
     */
    @Test
    void atLeastOneRelationDialogReadingLeavesElAndTheToolCanSayWhich() {
        List<String> outsideEl = new java.util.ArrayList<String>();
        for (EdgeAxioms.Candidate candidate : EdgeAxioms.Candidate.values()) {
            OWLAxiom axiom = EdgeAxioms.build(factory, candidate, cls("A"),
                    property("r"), cls("B"));
            ProfileCheck.Target tightest = ProfileCheck.tightestProfileFor(axiom);
            if (tightest != ProfileCheck.Target.EL) {
                outsideEl.add(candidate.name() + " -> " + tightest);
                assertNotNull(ProfileCheck.warningFor(axiom, ProfileCheck.Target.EL),
                        candidate + " leaves EL and produces no warning");
            }
        }
        assertFalse(outsideEl.isEmpty(),
                "if no reading leaves EL any more, this test and its warning are obsolete - "
                        + "check before deleting them");
    }

    /** Every profile has to explain itself, since choosing one is the user's decision. */
    @Test
    void everyProfileExplainsWhenItMatters() {
        for (ProfileCheck.Target target : ProfileCheck.Target.values()) {
            assertFalse(target.getLabel().trim().isEmpty(), target.name());
            assertTrue(target.getHelp().length() > 80,
                    target + " needs an explanation a user can choose by: " + target.getHelp());
        }
        assertTrue(ProfileCheck.Target.EL.getHelp().contains("ELK"),
                "EL's help must name the reasoner that makes it matter");
    }
}
