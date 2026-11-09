package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** What the model is told about the ontology (spec D3): visible, minimal, deterministic. */
class ContextBuilderTest {

    private final OWLOntology pizza = MiniPizza.load();

    @Test
    void aTermBlockHasLabelsDefinitionAxiomsAndNeighbours() {
        String block = ContextBuilder.term(pizza, MiniPizza.iri("VegetarianPizza"));

        assertTrue(block.startsWith("Class: vegetarian pizza"), block);
        assertTrue(block.contains("IRI: " + MiniPizza.NS + "VegetarianPizza"), block);
        assertTrue(block.contains("vegetarische Pizza (de)"), block);
        assertTrue(block.contains("definition: A pizza with no meat."), block);
        assertTrue(block.contains("synonym: veggie pizza"), block);
        assertTrue(block.contains("SubClassOf: hasTopping only VegetarianTopping"), block);
        assertTrue(block.contains("Parents: Pizza"), block);
        assertTrue(block.contains("Siblings: NamedPizza"), block);
    }

    /**
     * Review #7: the context sends what D3 and docs/assistant.md say - labels, definitions,
     * comments and synonyms - and not editor notes or other annotations such as a contributor's
     * address. The column still lists every line.
     */
    @Test
    void onlyLabelsDefinitionsCommentsAndSynonymsAreSent() {
        OWLDataFactory factory = pizza.getOWLOntologyManager().getOWLDataFactory();
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(
                        IRI.create("http://purl.obolibrary.org/obo/IAO_0000116")),
                MiniPizza.iri("VegetarianPizza"), factory.getOWLLiteral("ask Anna first")));
        pizza.getOWLOntologyManager().addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getOWLAnnotationProperty(IRI.create("http://purl.org/dc/terms/contributor")),
                MiniPizza.iri("VegetarianPizza"), factory.getOWLLiteral("anna@example.org")));

        String block = ContextBuilder.term(pizza, MiniPizza.iri("VegetarianPizza"));

        assertFalse(block.contains("ask Anna first"), block);
        assertFalse(block.contains("anna@example.org"), block);
        assertTrue(block.contains("definition: A pizza with no meat."), block);
        assertTrue(TermLines.of(pizza, MiniPizza.iri("VegetarianPizza")).toString()
                .contains("ask Anna first"), "the column still lists it");
    }

    @Test
    void mentionedTermsComeWithKindAndDefinition() {
        String block = ContextBuilder.term(pizza, MiniPizza.iri("VegetarianPizza"));

        assertTrue(block.contains("VegetarianTopping (class): A topping that contains no meat"
                + " or fish."), block);
        assertTrue(block.contains("hasTopping (object property)"), block);
    }

    @Test
    void aPropertyBlockHasDomainRangeAndCharacteristics() {
        String hasTopping = ContextBuilder.term(pizza, MiniPizza.iri("hasTopping"));
        String locatedIn = ContextBuilder.term(pizza, MiniPizza.iri("locatedIn"));

        assertTrue(hasTopping.startsWith("Object property: hasTopping"), hasTopping);
        assertTrue(hasTopping.contains("Domain: Pizza"), hasTopping);
        assertTrue(hasTopping.contains("Range: PizzaTopping"), hasTopping);
        assertTrue(locatedIn.contains("Characteristic: Transitive"), locatedIn);
    }

    @Test
    void childrenAreCappedAndTheRestCounted() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology wide = manager.createOntology(IRI.create("http://example.org/wide"));
        OWLClass parent = factory.getOWLClass(IRI.create("http://example.org/wide#Parent"));
        for (int i = 1; i <= 25; i++) {
            OWLClass child = factory.getOWLClass(
                    IRI.create(String.format("http://example.org/wide#C%02d", i)));
            manager.addAxiom(wide, factory.getOWLSubClassOfAxiom(child, parent));
        }

        String block = ContextBuilder.term(wide, parent.getIRI());

        assertTrue(block.contains("C20"), block);
        assertFalse(block.contains("C21"), block);
        assertTrue(block.contains("and 5 more"), block);
    }

    /** Review focus 2: a term deleted between selecting it and sending. */
    @Test
    void aTermNoLongerInTheOntologyIsSaidPlainly() {
        IRI gone = MiniPizza.iri("Calzone");

        assertEquals(gone + " is no longer in this ontology.",
                ContextBuilder.term(pizza, gone).trim());
    }

    @Test
    void theHierarchyIsAnIndentedLabelTree() {
        String tree = ContextBuilder.hierarchy(pizza);

        assertTrue(tree.contains("Pizza\n  NamedPizza\n    Margherita\n  vegetarian pizza\n"),
                tree);
        assertTrue(tree.contains("PizzaTopping\n  MeatTopping\n  VegetarianTopping\n"
                + "    cheese topping\n"), tree);
    }

    @Test
    void aClassWithTwoParentsIsShownInFullOnce() throws Exception {
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(pizza, factory.getOWLSubClassOfAxiom(
                factory.getOWLClass(MiniPizza.iri("Margherita")),
                factory.getOWLClass(MiniPizza.iri("VegetarianPizza"))));

        String tree = ContextBuilder.hierarchy(pizza);

        assertTrue(tree.contains("Margherita (also under NamedPizza)"), tree);
    }

    @Test
    void theHeaderNamesTheOntology() {
        String header = ContextBuilder.header(pizza);

        assertTrue(header.contains("http://example.org/mini-pizza"), header);
        assertTrue(header.contains("Mini pizza"), header);
    }

    @Test
    void theWholeOntologyIsOneLinePerAxiom() {
        String whole = ContextBuilder.wholeOntology(pizza);

        assertTrue(whole.contains("Margherita SubClassOf NamedPizza\n"), whole);
        assertTrue(whole.contains("A pizza with no meat."), whole);
        assertTrue(whole.contains("hasTopping Domain Pizza"), whole);
    }

    @Test
    void chipsAreNamedByTheirTitle() {
        assertEquals("vegetarian pizza", ContextBuilder.titleOf(pizza,
                ContextItem.term(MiniPizza.iri("VegetarianPizza"), true)));
        assertEquals("class hierarchy", ContextBuilder.titleOf(pizza, ContextItem.hierarchy()));
        assertEquals("whole ontology",
                ContextBuilder.titleOf(pizza, ContextItem.wholeOntology()));
        assertEquals("Why x", ContextBuilder.titleOf(pizza, ContextItem.problem("Why x", "t")));
    }

    /** Review focus 3: non-ASCII labels reach the model as they are written. */
    @Test
    void nonAsciiLabelsSurvive() throws Exception {
        OWLOntologyManager manager = pizza.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        manager.addAxiom(pizza, factory.getOWLAnnotationAssertionAxiom(
                factory.getRDFSLabel(), MiniPizza.iri("City"),
                factory.getOWLLiteral("Größe → 大", "en")));

        assertTrue(ContextBuilder.term(pizza, MiniPizza.iri("City"))
                .contains("Größe → 大"));
    }

    @Test
    void tokensAreAQuarterOfTheCharacters() {
        assertEquals(2, Tokens.estimate("abcdefgh"));
        assertEquals(1, Tokens.estimate("abc"));
        assertEquals(0, Tokens.estimate(""));
        assertEquals(0, Tokens.estimate(null));
    }

    @Test
    void contextItemsAreEqualByWhatTheyShowNotByWhetherTheyFollow() {
        assertEquals(ContextItem.term(MiniPizza.iri("Pizza"), true),
                ContextItem.term(MiniPizza.iri("Pizza"), false));
        assertFalse(ContextItem.problem("a", "x").equals(ContextItem.problem("b", "x")));
    }
}
