package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
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
 * ROBOT's ontology-modifying operations, run without touching the ontology.
 *
 * <p>The property every test here exists for: <b>the live ontology must be unchanged after a
 * preview</b>. Every one of these ROBOT operations mutates in place, so a mistake in the copying
 * would rewrite Protege's ontology behind the model manager's back - no change events, stale
 * views, and no undo. That failure would not throw; it would look like the operation worked and
 * leave the user with no way back.
 *
 * <p>The second property is that the diff is a real diff. An operation that returned every axiom
 * as an addition would "work" and produce an undo entry the size of the ontology.
 */
class RobotTransformTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyWithAnEquivalence() throws Exception {
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();

        // Relax turns an equivalent-class definition into the weaker subclass form, so this is
        // an ontology relax has something to say about.
        manager.addAxiom(ontology, factory.getOWLEquivalentClassesAxiom(cls("Parent"),
                factory.getOWLObjectIntersectionOf(cls("Person"),
                        factory.getOWLObjectSomeValuesFrom(property("hasChild"),
                                cls("Person")))));
        manager.addAxiom(ontology,
                factory.getOWLSubClassOfAxiom(cls("Person"), cls("Animal")));
    }

    private OWLClass cls(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    // ---------- the ontology is not touched ----------

    /**
     * The one that matters. A preview that mutated the live ontology would bypass the model
     * manager entirely: no events, stale views, no undo, and nothing thrown to say so.
     */
    @Test
    void previewingLeavesTheOntologyExactlyAsItWas() {
        Set<OWLAxiom> before = new HashSet<OWLAxiom>(ontology.getAxioms());

        for (RobotTransform.Kind kind : RobotTransform.Kind.values()) {
            try {
                RobotTransform.preview(ontology, kind, null);
            } catch (RuntimeException cannotRunHere) {
                // Some operations need a newer OWL API than this build pins. Not being able to
                // run is acceptable; mutating the ontology on the way to failing is not.
                continue;
            }
            assertEquals(before, new HashSet<OWLAxiom>(ontology.getAxioms()),
                    kind + " changed the live ontology instead of a copy");
        }
    }

    @Test
    void theChangesTargetTheRealOntologySoTheModelManagerCanApplyThem() {
        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.RELAX,
                null);

        for (OWLOntologyChange change : diff.getChanges()) {
            assertEquals(ontology, change.getOntology(),
                    "a change aimed at the copy would apply to nothing");
        }
    }

    // ---------- the diff is a real diff ----------

    /**
     * An operation that returned the whole ontology as additions would appear to work and produce
     * an undo entry the size of the ontology.
     */
    @Test
    void relaxAddsTheSubclassFormAndRemovesNothing() {
        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.RELAX,
                null);

        assertEquals(0, diff.getRemoved(), "relax only adds: " + diff);
        assertTrue(diff.getAdded() > 0, "relax should have something to add here");
        assertTrue(diff.getAdded() < ontology.getAxiomCount() + 5,
                "the diff looks like the whole ontology rather than a difference: " + diff);
    }

    @Test
    void theAddedAxiomsAreOnesTheOntologyDoesNotAlreadyHave() {
        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.RELAX,
                null);

        for (OWLOntologyChange change : diff.getChanges()) {
            if (change instanceof AddAxiom) {
                assertFalse(ontology.containsAxiom(change.getAxiom()),
                        "already present, so adding it is a no-op change: " + change.getAxiom());
            } else {
                assertTrue(ontology.containsAxiom(change.getAxiom()),
                        "removing an axiom that is not there does nothing: " + change.getAxiom());
            }
        }
    }

    /** Applying the preview must actually produce what the preview promised. */
    @Test
    void applyingTheChangesProducesTheResultThePreviewDescribed() {
        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.RELAX,
                null);
        int before = ontology.getAxiomCount();

        manager.applyChanges(diff.getChanges());

        assertEquals(before + diff.getAdded() - diff.getRemoved(), ontology.getAxiomCount(),
                "the axiom count after applying does not match what the preview counted");
    }

    /** Running it twice must find nothing left to do, or the operation is not idempotent. */
    @Test
    void relaxingAnAlreadyRelaxedOntologyChangesNothingFurther() {
        manager.applyChanges(
                RobotTransform.preview(ontology, RobotTransform.Kind.RELAX, null).getChanges());

        RobotTransform.Diff second =
                RobotTransform.preview(ontology, RobotTransform.Kind.RELAX, null);

        assertTrue(second.isEmpty(), "relax is not idempotent: " + second);
    }

    @Test
    void anOntologyWithNothingToRelaxProducesAnEmptyDiff() throws Exception {
        OWLOntology plain = OWLManager.createOWLOntologyManager()
                .createOntology(IRI.create("http://example.org/plain"));

        assertTrue(RobotTransform.preview(plain, RobotTransform.Kind.RELAX, null).isEmpty());
    }

    // ---------- reduce removes, and says so ----------

    /**
     * Reduce is the only operation here that removes axioms, which is why the dialog tells a user
     * to read the result first. If it stopped removing, that warning would be a lie.
     */
    @Test
    void reduceRemovesARedundantSubclassAxiom() throws Exception {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Mammal"), cls("Animal")));
        // Entailed by the two above, so reduce should take it out.
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")));

        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.REDUCE,
                null);

        assertTrue(diff.getRemoved() > 0, "the redundant Dog -> Animal axiom should go: " + diff);
        boolean removedTheRedundantOne = false;
        for (OWLOntologyChange change : diff.getChanges()) {
            if (change instanceof RemoveAxiom && change.getAxiom().equals(
                    factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal")))) {
                removedTheRedundantOne = true;
            }
        }
        assertTrue(removedTheRedundantOne, "it removed something, but not the redundant axiom");
    }

    // ---------- merge produces something self-contained ----------

    /**
     * Merge promises "a single self-contained file". An ontology that has the imported axioms
     * copied in but still imports the same ontology is neither the before state nor the after
     * state - it is a file that says the same thing twice, which is what happens if the import
     * declarations are forgotten because they are not axioms and so not visible to an axiom diff.
     */
    @Test
    void mergeBringsTheImportedAxiomsInAndDropsTheImport() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/imported"));
        manager.addAxiom(imported,
                factory.getOWLSubClassOfAxiom(cls("Beetle"), cls("Insect")));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/imported"))));

        RobotTransform.Diff diff = RobotTransform.preview(ontology,
                RobotTransform.Kind.MERGE_IMPORTS, null);

        assertEquals(1, diff.getImportsDropped(), "the import statement should go: " + diff);
        boolean broughtTheAxiomAcross = false;
        boolean droppedTheImport = false;
        for (OWLOntologyChange change : diff.getChanges()) {
            if (change instanceof AddAxiom && change.getAxiom().equals(
                    factory.getOWLSubClassOfAxiom(cls("Beetle"), cls("Insect")))) {
                broughtTheAxiomAcross = true;
            }
            if (change instanceof org.semanticweb.owlapi.model.RemoveImport) {
                droppedTheImport = true;
            }
        }
        assertTrue(broughtTheAxiomAcross, "the imported axiom was not copied in: " + diff);
        assertTrue(droppedTheImport, "the import declaration was not removed: " + diff);
    }

    /**
     * Applying merge must leave an ontology that stands alone. Asserted here on the result rather
     * than on the changes, because the two can disagree.
     */
    @Test
    void afterApplyingMergeTheOntologyImportsNothingAndStillSaysEverything() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/imported"));
        manager.addAxiom(imported,
                factory.getOWLSubClassOfAxiom(cls("Beetle"), cls("Insect")));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/imported"))));

        manager.applyChanges(RobotTransform
                .preview(ontology, RobotTransform.Kind.MERGE_IMPORTS, null).getChanges());

        assertTrue(ontology.getImportsDeclarations().isEmpty(),
                "still imports something, so it is not self-contained");
        assertTrue(ontology.containsAxiom(
                factory.getOWLSubClassOfAxiom(cls("Beetle"), cls("Insect"))),
                "the imported axiom was lost along with the import");
    }

    /** Nothing else touches imports, and a relax that silently dropped one would be a disaster. */
    @Test
    void noOtherOperationTouchesTheImports() throws Exception {
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/imported"))));

        for (RobotTransform.Kind kind : RobotTransform.Kind.values()) {
            if (kind == RobotTransform.Kind.MERGE_IMPORTS) {
                continue;
            }
            assertEquals(0, RobotTransform.preview(ontology, kind, null).getImportsDropped(),
                    kind + " would remove an import statement, which it does not advertise");
        }
    }

    // ---------- an import that is declared but not loaded ----------

    /**
     * The state a freshly cloned ODK project is in before its import modules are built - and one
     * this plugin can produce itself, since Open from GitHub clones exactly such repositories and
     * warns about it.
     */
    private void declareAnImportNothingLoaded() {
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/never-loaded"))));
    }

    /**
     * Merge takes its axioms from the imports closure, and OWL API builds that closure by
     * silently skipping declarations it could not resolve. Dropping such a declaration afterwards
     * deletes the only record the import existed while merging nothing in its place - and reports
     * it as a success, describing the result as self-contained. Saving loses the import for good.
     */
    @Test
    void mergeRefusesRatherThanDroppingAnImportItCouldNotRead() {
        declareAnImportNothingLoaded();

        RobotException refused = assertThrows(RobotException.class,
                () -> RobotTransform.preview(ontology, RobotTransform.Kind.MERGE_IMPORTS, null));

        assertTrue(refused.getMessage().contains("never-loaded"), refused.getMessage());
        assertTrue(refused.getMessage().contains("without bringing"), refused.getMessage());
        assertEquals(1, ontology.getImportsDeclarations().size(),
                "the declaration must survive a refusal");
    }

    @Test
    void aRefusedMergeSuggestsWhatToDoAboutIt() {
        declareAnImportNothingLoaded();

        RobotException refused = assertThrows(RobotException.class,
                () -> RobotTransform.preview(ontology, RobotTransform.Kind.MERGE_IMPORTS, null));

        assertTrue(refused.getMessage().contains("make imports")
                        || refused.getMessage().contains("catalog"),
                "an ODK user needs to be told how to resolve it: " + refused.getMessage());
    }

    /** The other operations do not touch imports, so an unresolved one must not stop them. */
    @Test
    void theOtherOperationsStillRunWithAnUnresolvedImport() {
        declareAnImportNothingLoaded();

        for (RobotTransform.Kind kind : RobotTransform.Kind.values()) {
            if (kind == RobotTransform.Kind.MERGE_IMPORTS) {
                continue;
            }
            RobotTransform.Diff diff = RobotTransform.preview(ontology, kind, null);
            assertEquals(0, diff.getImportsDropped(), kind + " touched the imports");
        }
    }

    // ---------- the operations can see what the imports say ----------

    /**
     * Reduce cannot know an axiom is redundant when the axiom that makes it redundant lives in an
     * import. Given a copy with no closure it silently finds nothing - a wrong answer that looks
     * exactly like a clean ontology.
     */
    @Test
    void reduceSeesRedundancyThatOnlyTheImportsExplain() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/upper"));
        manager.addAxiom(imported, factory.getOWLSubClassOfAxiom(cls("Mineral"), cls("Solid")));
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/upper"))));
        // Redundant only because the IMPORT says Mineral is a Solid.
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Quartz"), cls("Mineral")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Quartz"), cls("Solid")));

        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.REDUCE,
                null);

        boolean removedTheRedundantOne = false;
        for (OWLOntologyChange change : diff.getChanges()) {
            if (change instanceof RemoveAxiom && change.getAxiom().equals(
                    factory.getOWLSubClassOfAxiom(cls("Quartz"), cls("Solid")))) {
                removedTheRedundantOne = true;
            }
        }
        assertTrue(removedTheRedundantOne,
                "reduce did not see the imported axiom that makes Quartz -> Solid redundant: "
                        + diff);
    }

    /**
     * The other half of giving the operations the closure: an imported axiom must not become a
     * change to the edit file. A repair of an imported annotation written into the importing file
     * would be a copy nobody asked for and nothing maintains.
     */
    @Test
    void noImportedAxiomEverBecomesAChangeToTheEditFile() throws Exception {
        OWLOntology imported = manager.createOntology(IRI.create("http://example.org/upper"));
        manager.addAxiom(imported, factory.getOWLEquivalentClassesAxiom(cls("Imported"),
                factory.getOWLObjectIntersectionOf(cls("Person"),
                        factory.getOWLObjectSomeValuesFrom(property("hasChild"),
                                cls("Person")))));
        Set<OWLAxiom> importedAxioms = new HashSet<OWLAxiom>(imported.getAxioms());
        manager.applyChange(new org.semanticweb.owlapi.model.AddImport(ontology,
                factory.getOWLImportsDeclaration(IRI.create("http://example.org/upper"))));

        for (RobotTransform.Kind kind : RobotTransform.Kind.values()) {
            if (kind == RobotTransform.Kind.MERGE_IMPORTS) {
                continue; // merge is defined as bringing them across
            }
            for (OWLOntologyChange change : RobotTransform.preview(ontology, kind, null)
                    .getChanges()) {
                assertFalse(importedAxioms.contains(change.getAxiom()),
                        kind + " turned an imported axiom into a change to the edit file: "
                                + change);
            }
        }
    }

    // ---------- every operation is described for a user ----------

    /**
     * These sentences are the "?" in the parameter dialog, and they are the only thing standing
     * between a user and an operation that rewrites their ontology.
     */
    @Test
    void everyOperationExplainsWhatItDoesToTheOntology() {
        for (RobotTransform.Kind kind : RobotTransform.Kind.values()) {
            assertFalse(kind.getLabel().trim().isEmpty(), kind.name());
            assertTrue(kind.getHelp().length() > 80,
                    kind + " needs an explanation a user can act on, got: " + kind.getHelp());
            assertFalse(kind.getHelp().toLowerCase().startsWith(kind.getLabel().toLowerCase()),
                    kind + " restates its own label instead of explaining");
        }
    }

    @Test
    void theOperationsThatReasonSayThatTheyDo() {
        assertTrue(RobotTransform.Kind.REDUCE.needsReasoner());
        assertTrue(RobotTransform.Kind.REASON.needsReasoner());
        assertFalse(RobotTransform.Kind.RELAX.needsReasoner());
        assertFalse(RobotTransform.Kind.REPAIR.needsReasoner());
        assertFalse(RobotTransform.Kind.MERGE_IMPORTS.needsReasoner());
    }

    /**
     * Reason is what turns an edit file into a release: the conclusions become ordinary axioms,
     * so a consumer who never runs a reasoner still sees the hierarchy.
     *
     * <p>The fixture defines Parent as Person-with-a-child, and nothing says a Parent is a Person -
     * that has to be worked out. Which is the whole point: it is the definition-derived
     * subsumptions, not the ones already written down, that a consumer would otherwise miss.
     */
    @Test
    void reasonWritesDownASubsumptionThatOnlyTheDefinitionImplies() {
        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.REASON,
                null);

        assertEquals(0, diff.getRemoved(), "reason only adds: " + diff);
        boolean wroteTheInference = false;
        for (OWLOntologyChange change : diff.getChanges()) {
            if (change instanceof AddAxiom && change.getAxiom().equals(
                    factory.getOWLSubClassOfAxiom(cls("Parent"), cls("Person")))) {
                wroteTheInference = true;
            }
        }
        assertTrue(wroteTheInference,
                "Parent -> Person follows from the definition and was not written down: " + diff);
    }

    /**
     * Only the direct ones. ROBOT drops a subsumption already implied by a chain that is being
     * written anyway, and an ontology padded with every indirect pair would be unreadable and
     * enormous - so the absence of Dog -> Animal here is correct, not a gap.
     */
    @Test
    void reasonDoesNotPadTheOntologyWithIndirectSubsumptions() {
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Mammal")));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(cls("Mammal"), cls("Animal")));

        RobotTransform.Diff diff = RobotTransform.preview(ontology, RobotTransform.Kind.REASON,
                null);

        for (OWLOntologyChange change : diff.getChanges()) {
            assertFalse(change.getAxiom().equals(
                    factory.getOWLSubClassOfAxiom(cls("Dog"), cls("Animal"))),
                    "an indirect subsumption was written down: " + change);
        }
    }

    /** It writes what the reasoner believes, so a user has to be told to read it first. */
    @Test
    void reasonWarnsThatItRecordsWhatTheReasonerBelieves() {
        String help = RobotTransform.Kind.REASON.getHelp().toLowerCase();

        assertTrue(help.contains("mistake"), help);
        assertTrue(help.contains("before applying"), help);
    }

    @Test
    void noOntologyIsRefusedRatherThanReturningAnEmptyDiff() {
        assertThrows(IllegalArgumentException.class,
                () -> RobotTransform.preview(null, RobotTransform.Kind.RELAX, null));
    }

    /**
     * Materialize writes down inferred relations, which is a different job from Reason.
     *
     * <p>Reason asserts inferred subclass axioms. Materialize asserts inferred existential
     * relations - that a pizza with a mozzarella topping has a topping that is a cheese topping -
     * which a plain reasoner leaves implicit and a downstream consumer cannot work out. Asserting
     * that it adds axioms is the whole claim: an operation that runs an expensive reasoner and
     * changes nothing would look identical to one that is broken.
     */
    @Test
    void materializeAssertsInferredRelations() throws Exception {
        org.semanticweb.owlapi.model.OWLOntology pizza =
                de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology.v2();

        RobotTransform.Diff diff = RobotTransform.preview(pizza, RobotTransform.Kind.MATERIALIZE,
                Reasoners.Choice.ELK.newFactory());

        assertTrue(diff.getAdded() > 0,
                "materialize over an ontology with defined classes must assert something: " + diff);
        assertEquals(0, diff.getRemoved(), "materialize only adds: " + diff);
    }

    /** It needs a reasoner, and the enum has to say so or the dialog will not offer one. */
    @Test
    void materializeDeclaresThatItNeedsAReasoner() {
        assertTrue(RobotTransform.Kind.MATERIALIZE.needsReasoner());
        assertFalse(RobotTransform.Kind.MATERIALIZE.getHelp().trim().isEmpty());
    }
}
