package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Nothing becomes a card that could not be applied as the person reads it: each failure is
 * INVALID with a reason a person can act on.
 */
class ProposalValidatorTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();

    private static Proposal one(String block) {
        List<Proposal> found = ProposalBlock.parse("```ontoboard-proposal\n" + block + "\n```");
        assertEquals(1, found.size());
        return found.get(0);
    }

    private Proposal validate(String block) {
        return ProposalValidator.validate(pizza, one(block));
    }

    private static void invalid(Proposal proposal, String because) {
        assertEquals(Proposal.Status.INVALID, proposal.getStatus(), proposal.getProblem());
        assertTrue(proposal.getProblem().contains(because), proposal.getProblem());
    }

    /**
     * The 1.148.0 bench reviewer's declined note: a card with an action OntoBoard does not know
     * said it knows set, add and remove-axiom, leaving out add-axiom and replace-axiom (1.139.0),
     * so a person, or a model asked again, was not told the action it needed.
     */
    @Test
    void anUnknownActionNamesEveryActionOntoBoardKnows() {
        Proposal proposal = validate("action: delete-axiom\naxiom: cheese topping SubClassOf"
                + " VegetarianTopping");

        invalid(proposal, "\"delete-axiom\"");
        String listed = proposal.getProblem().substring(proposal.getProblem().indexOf("it knows"));
        for (Proposal.Action action : Proposal.Action.values()) {
            // A word of its own: "add" is in "add-axiom" too.
            assertTrue(java.util.regex.Pattern.compile("(?<![\\w-])"
                    + java.util.regex.Pattern.quote(action.getWord()) + "(?![\\w-])")
                    .matcher(listed).find(), action.getWord() + " in: " + listed);
        }
    }

    /**
     * 1.140.0, from the live run: qwen2.5:7b copied "'has value' Range: xsd:float" as "has value
     * Range: xsd:float" three rounds running, and the right repair was refused each time.
     */
    @Test
    void anAxiomCopiedWithoutItsQuotesStillMatches() {
        Proposal proposal = validate("action: remove-axiom\naxiom: cheese topping SubClassOf"
                + " VegetarianTopping");

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals(pizza.getOWLOntologyManager().getOWLDataFactory().getOWLSubClassOfAxiom(
                pizza.getOWLOntologyManager().getOWLDataFactory().getOWLClass(
                        MiniPizza.iri("CheeseTopping")),
                pizza.getOWLOntologyManager().getOWLDataFactory().getOWLClass(
                        MiniPizza.iri("VegetarianTopping"))), proposal.getMatchedAxiom());
    }

    /**
     * Review 1.140.0 M8: an add-axiom's "with:" was dropped without a word - in 6 of 13 live
     * answers it held the second direction of a definition.
     */
    @Test
    void anAddAxiomWithAWithIsRefusedWithTheReason() {
        invalid(validate("action: add-axiom\naxiom: Margherita SubClassOf hasTopping only"
                + " VegetarianTopping\nwith: VegetarianTopping SubClassOf Pizza"), "with:");
    }

    @Test
    void anUnknownSubjectIsInvalid() {
        invalid(validate("action: set\nsubject: calzone\nproperty: definition\nvalue: x"),
                "not in this ontology");
    }

    @Test
    void anAmbiguousSubjectIsInvalid() {
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("Country"), factory.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("City"), factory.getOWLLiteral("place")));

        invalid(validate("action: set\nsubject: place\nproperty: definition\nvalue: x"),
                "ambiguous");
    }

    @Test
    void anEmptyValueIsInvalid() {
        invalid(validate("action: set\nsubject: vegetarian pizza\nproperty: definition\nvalue:"),
                "no value");
    }

    @Test
    void aLabelOverTwoLinesIsInvalid() {
        invalid(validate("action: set\nsubject: vegetarian pizza\nproperty: label\n"
                + "language: en\nvalue: veggie\npizza"), "one line");
    }

    @Test
    void anUnknownPropertyIsInvalid() {
        invalid(validate("action: add\nsubject: vegetarian pizza\nproperty: colour\nvalue: red"),
                "colour");
    }

    @Test
    void anUnknownActionIsInvalid() {
        invalid(validate("action: rename\nsubject: vegetarian pizza\nproperty: label\nvalue: x"),
                "rename");
    }

    @Test
    void anAxiomThatMatchesNothingIsInvalid() {
        invalid(validate("action: remove-axiom\naxiom: Pizza SubClassOf Calzone"),
                "matches no axiom");
    }

    @Test
    void aValueTheTermAlreadyHasIsInvalid() {
        invalid(validate("action: set\nsubject: vegetarian pizza\nproperty: definition\n"
                + "value: A pizza with no meat."), "already");
    }

    @Test
    void aGoodDefinitionIsOfferedWithWhatItReplaces() {
        Proposal proposal = validate("action: set\nsubject: vegetarian pizza\n"
                + "property: definition\nvalue: A pizza whose toppings are all vegetarian.");

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals(MiniPizza.iri("VegetarianPizza"), proposal.getSubjectIri());
        assertEquals(IRI.create("http://purl.obolibrary.org/obo/IAO_0000115"),
                proposal.getPropertyIri());
        assertEquals(Arrays.asList("A pizza with no meat."), proposal.getReplaced());
    }

    /** "In its language": a label set without one replaces the English, not every label. */
    @Test
    void aLabelWithoutALanguageTakesEnglishWhenTheTermHasSeveral() {
        Proposal proposal = validate("action: set\nsubject: vegetarian pizza\nproperty: label\n"
                + "value: veggie pizza");

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals("en", proposal.getLanguage());
        assertEquals(Arrays.asList("vegetarian pizza"), proposal.getReplaced());
    }

    @Test
    void anAddedAxiomIsReadAgainstTheOntology() {
        Proposal proposal = validate("action: add-axiom\n"
                + "axiom: 'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'");

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianPizza")),
                factory.getOWLObjectSomeValuesFrom(
                        factory.getOWLObjectProperty(MiniPizza.iri("hasTopping")),
                        factory.getOWLClass(MiniPizza.iri("CheeseTopping")))),
                proposal.getNewAxiom());
    }

    @Test
    void anAddedAxiomWithAnInventedNameIsInvalid() {
        invalid(validate("action: add-axiom\naxiom: Pizza SubClassOf hasTopping some Calzone"),
                "not in this ontology");
    }

    @Test
    void anAxiomTheOntologyAlreadySaysIsInvalid() {
        invalid(validate("action: add-axiom\naxiom: Margherita SubClassOf NamedPizza"),
                "already");
    }

    @Test
    void aReplacementNeedsTheOldAxiomAndANewOne() {
        String old = AssistantRendering.render(pizza, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("CheeseTopping")),
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping"))));

        Proposal good = validate("action: replace-axiom\naxiom: " + old
                + "\nwith: 'cheese topping' SubClassOf PizzaTopping");
        Proposal missing = validate("action: replace-axiom\naxiom: " + old);
        Proposal same = validate("action: replace-axiom\naxiom: " + old + "\nwith: " + old);

        assertEquals(Proposal.Status.OFFERED, good.getStatus(), good.getProblem());
        assertEquals(factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("CheeseTopping")),
                factory.getOWLClass(MiniPizza.iri("PizzaTopping"))), good.getNewAxiom());
        assertTrue(good.getMatchedAxiom() != null);
        invalid(missing, "no new axiom");
        invalid(same, "nothing");
    }

    /** An edit file that imports the mini pizza, as an ODK -edit file imports BFO. */
    static OWLOntology importing(OWLOntology imported) {
        try {
            org.semanticweb.owlapi.model.OWLOntologyManager manager =
                    imported.getOWLOntologyManager();
            OWLOntology edit = manager.createOntology(IRI.create("http://example.org/edit"));
            manager.applyChange(new org.semanticweb.owlapi.model.AddImport(edit,
                    manager.getOWLDataFactory().getOWLImportsDeclaration(
                            imported.getOntologyID().getOntologyIRI().get())));
            return edit;
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /**
     * Review I2 (1.137.0): "Set the definition" of a term whose definition is in an import added
     * a second one beside it, with nothing struck through.
     */
    @Test
    void aValueOnlyInAnImportIsInvalid() {
        OWLOntology edit = importing(pizza);

        Proposal proposal = ProposalValidator.validate(edit, one("action: set\n"
                + "subject: vegetarian pizza\nproperty: definition\nvalue: A better one."));

        invalid(proposal, "imported ontology");
    }

    /**
     * Review R6 (1.138.0): an imported untagged definition refused a German one "change it
     * there", though nothing of the import would change: a translation is added here.
     */
    @Test
    void aTranslationOfAnImportedValueIsAddedHere() {
        OWLOntology edit = importing(pizza);

        Proposal proposal = ProposalValidator.validate(edit, one("action: set\n"
                + "subject: VegetarianTopping\nproperty: definition\nlanguage: de\n"
                + "value: Ein Belag ohne Fleisch oder Fisch."));

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
    }

    @Test
    void aRemoveAxiomIsMatchedAsTheContextRendersIt() {
        OWLAxiom disjoint = factory.getOWLDisjointClassesAxiom(
                factory.getOWLClass(MiniPizza.iri("VegetarianTopping")),
                factory.getOWLClass(MiniPizza.iri("MeatTopping")));
        String shown = AssistantRendering.render(pizza, disjoint);

        Proposal proposal = validate("action: remove-axiom\naxiom:  "
                + shown.replace(" ", "   ") + "\nreason: x");

        assertEquals(Proposal.Status.OFFERED, proposal.getStatus(), proposal.getProblem());
        assertEquals(disjoint, proposal.getMatchedAxiom());
    }
}
