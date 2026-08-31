package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * That every reasoner the menu offers is actually there and actually reasons.
 *
 * <p>The failure this guards against is a menu that lists five reasoners of which one throws
 * {@code NoClassDefFoundError} when chosen, because the jar it lives in was not embedded or its
 * class name was mistyped. Nothing at compile time catches a reasoner missing from the assembled
 * bundle, and the person who finds out is the user who picked it.
 */
class ReasonersTest {

    /**
     * Each factory is constructed for real, so a reasoner missing from the build fails here
     * rather than in the menu.
     */
    @Test
    void everyOfferedReasonerCanActuallyBeConstructed() {
        for (Reasoners.Choice choice : Reasoners.Choice.values()) {
            OWLReasonerFactory factory = choice.newFactory();
            assertNotNull(factory, choice + " produced no factory");
        }
    }

    /**
     * Constructing a factory is not enough - a factory that cannot create a reasoner over a
     * trivial ontology is no use, and some of these are OWL EL only.
     */
    @Test
    void everyReasonerClassifiesASimpleHierarchy() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/r"));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://example.org/r#Dog")),
                factory.getOWLClass(IRI.create("http://example.org/r#Mammal"))));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(IRI.create("http://example.org/r#Mammal")),
                factory.getOWLClass(IRI.create("http://example.org/r#Animal"))));

        for (Reasoners.Choice choice : Reasoners.Choice.values()) {
            OWLReasoner reasoner = choice.newFactory().createReasoner(ontology);
            try {
                assertTrue(reasoner.getSuperClasses(
                                factory.getOWLClass(IRI.create("http://example.org/r#Dog")), false)
                        .containsEntity(
                                factory.getOWLClass(IRI.create("http://example.org/r#Animal"))),
                        choice + " did not infer Dog is an Animal");
            } finally {
                reasoner.dispose();
            }
        }
    }

    /** A shared factory instance would leak reasoner state between two unrelated operations. */
    @Test
    void eachCallProducesItsOwnFactory() {
        assertNotSame(Reasoners.Choice.STRUCTURAL.newFactory(),
                Reasoners.Choice.STRUCTURAL.newFactory());
    }

    // ---------- the names are ROBOT's ----------

    /**
     * A result recording "Reasoner: ELK" is only comparable with a Makefile's {@code --reasoner
     * ELK} if the spelling is the same one.
     */
    @Test
    void theLabelsAreTheOnesRobotUses() {
        assertEquals("[ELK, HermiT, JFact, Whelk, Structural]", Reasoners.labels().toString());
    }

    @Test
    void aNameCopiedOutOfAMakefileResolvesWhateverItsCase() {
        assertEquals(Reasoners.Choice.ELK, Reasoners.byLabel("elk"));
        assertEquals(Reasoners.Choice.HERMIT, Reasoners.byLabel("HERMIT"));
        assertEquals(Reasoners.Choice.JFACT, Reasoners.byLabel(" JFact "));
    }

    /** "No such reasoner" without the list of real ones leaves a user guessing. */
    @Test
    void anUnknownNameIsRejectedWithTheListOfRealOnes() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Reasoners.byLabel("Pellet"));

        assertTrue(refused.getMessage().contains("Pellet"), refused.getMessage());
        assertTrue(refused.getMessage().contains("ELK"),
                "the message should list what is available: " + refused.getMessage());
    }

    @Test
    void theDefaultIsTheOneOdkBuildsUse() {
        assertEquals(Reasoners.Choice.ELK, Reasoners.DEFAULT);
    }

    // ---------- the help makes it a decision rather than a guess ----------

    /**
     * The whole reason for offering five reasoners is that they differ. Help that does not say how
     * turns the dropdown into a coin toss.
     */
    @Test
    void everyReasonerExplainsWhatDistinguishesItFromTheOthers() {
        Set<String> seen = new HashSet<String>();
        for (Reasoners.Choice choice : Reasoners.Choice.values()) {
            String help = choice.getHelp();
            assertTrue(help.length() > 120,
                    choice + " needs more than a phrase to justify choosing it: " + help);
            assertTrue(seen.add(help), choice + " reuses another reasoner's description");
            assertFalse(help.toLowerCase().startsWith(choice.getLabel().toLowerCase()),
                    choice + " restates its own name instead of explaining");
        }
    }

    /** ELK's profile limit is the single most consequential fact about the default. */
    @Test
    void elkSaysThatItIgnoresAxiomsOutsideItsProfile() {
        String help = Reasoners.Choice.ELK.getHelp().toLowerCase();

        assertTrue(help.contains("el"), help);
        assertTrue(help.contains("ignore") || help.contains("skip"),
                "a user choosing the default must be told it silently drops axioms: " + help);
    }

    /** Structural is not reasoning; presenting it as one would produce wrong conclusions. */
    @Test
    void structuralSaysItIsNotReallyReasoning() {
        String help = Reasoners.Choice.STRUCTURAL.getHelp().toLowerCase();

        assertTrue(help.contains("asserted") || help.contains("told"), help);
    }

    @Test
    void theCombinedHelpNamesEveryReasoner() {
        String help = Reasoners.help();

        for (Reasoners.Choice choice : Reasoners.Choice.values()) {
            assertTrue(help.contains(choice.getLabel()), choice + " missing from " + help);
        }
    }
}
