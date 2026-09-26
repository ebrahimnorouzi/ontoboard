package de.fizkarlsruhe.ise.ontoboard.axiom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * What an edge drawn on the canvas is allowed to become.
 *
 * <p>Every rule here exists to prevent one thing: a line on the board that no axiom stands behind. The
 * canvas is a projection of the ontology, so a drawn-but-unasserted edge would be the single line on
 * screen that means nothing while looking exactly like the ones that mean something - and it would then
 * vanish at the next refresh, which any edit anywhere in Prot&eacute;g&eacute; triggers.
 *
 * <p>These were four {@code if} statements inside a 2,300-line view, where none of them could be
 * tested. The gesture that reaches them cannot be driven from a test either, which is the argument for
 * having the rules somewhere a test can reach.
 */
class DrawnEdgeTest {

    private static final String NS = "http://example.org/o#";

    private OWLOntology ontology;
    private OWLDataFactory factory;

    @BeforeEach
    void anOntologyWithTwoClassesAnIndividualAndAProperty() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(IRI.create("http://example.org/o"));
        factory = manager.getOWLDataFactory();
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Pizza"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLClass(IRI.create(NS + "Topping"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLNamedIndividual(IRI.create(NS + "margherita"))));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + "hasTopping"))));
    }

    private static Set<String> onBoard(String... iris) {
        return new HashSet<String>(Arrays.asList(iris));
    }

    // ---------- can these ends carry anything at all ----------

    @Test
    void twoTermsOnTheBoardCanCarryAnAxiom() {
        assertEquals(DrawnEdge.Verdict.OFFER, DrawnEdge.verdictFor(NS + "Pizza", NS + "Topping",
                onBoard(NS + "Pizza", NS + "Topping")));
    }

    @Test
    void aTermCannotBeRelatedToItself() {
        assertEquals(DrawnEdge.Verdict.SAME_TERM,
                DrawnEdge.verdictFor(NS + "Pizza", NS + "Pizza", onBoard(NS + "Pizza")));
    }

    /**
     * A sticky note is not a term.
     *
     * <p>Notes and frames are stored in the layout sidecar, not the ontology, so an arrow to one could
     * never be backed by an axiom. It is also the easiest wrong drag to make, because on screen a note
     * looks like just another box.
     */
    @Test
    void anEdgeToAStickyNoteOrAFrameIsRefused() {
        String note = SchemaGraph.NOTE_ID_PREFIX + "3f2a1b9c";
        String frame = SchemaGraph.FRAME_ID_PREFIX + "7c1d";

        assertEquals(DrawnEdge.Verdict.ANNOTATION_END,
                DrawnEdge.verdictFor(NS + "Pizza", note, onBoard(NS + "Pizza", note)));
        assertEquals(DrawnEdge.Verdict.ANNOTATION_END,
                DrawnEdge.verdictFor(frame, NS + "Pizza", onBoard(frame, NS + "Pizza")));
    }

    @Test
    void anEndThatIsNotOnTheBoardIsRefused() {
        assertEquals(DrawnEdge.Verdict.NOT_ON_BOARD,
                DrawnEdge.verdictFor(NS + "Pizza", NS + "Topping", onBoard(NS + "Pizza")));
        assertEquals(DrawnEdge.Verdict.NOT_ON_BOARD,
                DrawnEdge.verdictFor(NS + "Pizza", NS + "Topping", Collections.<String>emptySet()));
        assertEquals(DrawnEdge.Verdict.NOT_ON_BOARD,
                DrawnEdge.verdictFor(NS + "Pizza", NS + "Topping", null));
    }

    @Test
    void anEdgeWithAMissingEndIsRefusedRatherThanThrown() {
        assertEquals(DrawnEdge.Verdict.NOT_ON_BOARD,
                DrawnEdge.verdictFor(null, NS + "Topping", onBoard(NS + "Topping")));
        assertEquals(DrawnEdge.Verdict.NOT_ON_BOARD,
                DrawnEdge.verdictFor(NS + "Pizza", null, onBoard(NS + "Pizza")));
    }

    /** A note dragged onto itself has two problems; the simpler one is the one to report. */
    @Test
    void theSameTermCheckComesFirst() {
        String note = SchemaGraph.NOTE_ID_PREFIX + "abcd";

        assertEquals(DrawnEdge.Verdict.SAME_TERM, DrawnEdge.verdictFor(note, note, onBoard(note)));
    }

    /** Every refusal a user can provoke by dragging says something. */
    @Test
    void theRefusalsWorthExplainingHaveWords() {
        assertNotNull(DrawnEdge.Verdict.SAME_TERM.getMessage());
        assertNotNull(DrawnEdge.Verdict.ANNOTATION_END.getMessage());
        assertTrue(DrawnEdge.Verdict.ANNOTATION_END.getMessage().contains("ontology"),
                "the message should say why, not just no");
    }

    // ---------- and what can they mean ----------

    @Test
    void twoClassesCanBeEitherAParentOrARestriction() {
        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology,
                factory.getOWLClass(IRI.create(NS + "Pizza")),
                factory.getOWLClass(IRI.create(NS + "Topping")));

        assertEquals(Arrays.asList(DrawnEdge.Option.HIERARCHY, DrawnEdge.Option.OBJECT_PROPERTY),
                options, "hierarchy first: it is commoner and asks no further questions");
    }

    /**
     * An individual to a class is a type, and nothing else.
     *
     * <p>Offering a restriction here would be offering a way to get an error, which is what the node
     * menu did before the two paths were separated.
     */
    @Test
    void anIndividualToAClassIsATypeAndNotARestriction() {
        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology,
                factory.getOWLNamedIndividual(IRI.create(NS + "margherita")),
                factory.getOWLClass(IRI.create(NS + "Pizza")));

        assertEquals(Collections.singletonList(DrawnEdge.Option.HIERARCHY), options);
    }

    @Test
    void twoPropertiesOfTheSameKindAreASubPropertyLinkOnly() {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(
                factory.getOWLObjectProperty(IRI.create(NS + "hasIngredient"))));

        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology,
                factory.getOWLObjectProperty(IRI.create(NS + "hasTopping")),
                factory.getOWLObjectProperty(IRI.create(NS + "hasIngredient")));

        assertEquals(Collections.singletonList(DrawnEdge.Option.HIERARCHY), options);
    }

    /** An individual to a property is nothing at all, and the caller has to say so. */
    @Test
    void somePairsCanCarryNothing() {
        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology,
                factory.getOWLNamedIndividual(IRI.create(NS + "margherita")),
                factory.getOWLObjectProperty(IRI.create(NS + "hasTopping")));

        assertTrue(options.isEmpty());
        // And the caller has words for it, which is why an empty list is an acceptable answer.
        assertNotNull(HierarchyAxioms.whyNot(
                factory.getOWLNamedIndividual(IRI.create(NS + "margherita")),
                factory.getOWLObjectProperty(IRI.create(NS + "hasTopping"))));
    }

    /**
     * A class on the board that is no longer declared offers no restriction.
     *
     * <p>Not hypothetical in a shared session: a collaborator retracts the declaration while the term
     * is still drawn on somebody else's board. The check is against the ontology rather than the
     * entity's Java type, which is what makes that case come out right.
     */
    @Test
    void aClassNoLongerInTheOntologyOffersNoRestriction() {
        List<DrawnEdge.Option> options = DrawnEdge.optionsFor(ontology,
                factory.getOWLClass(IRI.create(NS + "Pizza")),
                factory.getOWLClass(IRI.create(NS + "NeverDeclared")));

        assertFalse(options.contains(DrawnEdge.Option.OBJECT_PROPERTY), options.toString());
    }

    @Test
    void nothingIsOfferedForMissingArguments() {
        assertTrue(DrawnEdge.optionsFor(null, factory.getOWLClass(IRI.create(NS + "Pizza")),
                factory.getOWLClass(IRI.create(NS + "Topping"))).isEmpty());
        assertTrue(DrawnEdge.optionsFor(ontology, null,
                factory.getOWLClass(IRI.create(NS + "Topping"))).isEmpty());
        assertTrue(DrawnEdge.optionsFor(ontology,
                factory.getOWLClass(IRI.create(NS + "Pizza")), null).isEmpty());
    }
}
