package de.fizkarlsruhe.ise.ontoboard.assistant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * What is sent says what type a value has. The Manchester renderer writes a decimal as 9.50 and
 * a float as 9.50f, so "has value 9.50" beside "Range: float" hid the very clash the reasoner
 * found - a model given the pizza example's justification explained the wrong thing (1.136.0).
 */
class AssistantRenderingTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLDataFactory data = pizza.getOWLOntologyManager().getOWLDataFactory();

    @Test
    void aDecimalIsWrittenWithItsType() {
        OWLDataProperty price = data.getOWLDataProperty(MiniPizza.iri("hasPrice"));

        String text = AssistantRendering.render(pizza, data.getOWLDataPropertyAssertionAxiom(
                price, data.getOWLNamedIndividual(MiniPizza.iri("Naples")),
                data.getOWLLiteral("9.50", OWL2Datatype.XSD_DECIMAL)));

        assertTrue(text.contains("\"9.50\"^^xsd:decimal"), text);
        assertTrue(text.startsWith("Naples hasPrice"), text);
    }

    @Test
    void aFloatAndALanguageTagAreExplicitToo() {
        assertEquals("\"9.5\"^^xsd:float", AssistantRendering.render(pizza,
                data.getOWLLiteral("9.5", OWL2Datatype.XSD_FLOAT)));
        assertEquals("\"Pizza\"@de", AssistantRendering.render(pizza,
                data.getOWLLiteral("Pizza", "de")));
        assertEquals("\"plain\"", AssistantRendering.render(pizza, data.getOWLLiteral("plain")));
    }

    /**
     * Named by label, and quoted when the label is not a Manchester name (1.139.0): the model
     * copies axioms back as proposals, and an unquoted "cheese topping SubClassOf …" does not
     * parse.
     */
    @Test
    void entitiesAreNamedByLabelQuotedWhenNeeded() {
        assertEquals("'vegetarian pizza'", AssistantRendering.render(pizza,
                data.getOWLClass(MiniPizza.iri("VegetarianPizza"))));
        assertEquals("'cheese topping' SubClassOf VegetarianTopping",
                AssistantRendering.render(pizza, data.getOWLSubClassOfAxiom(
                        data.getOWLClass(MiniPizza.iri("CheeseTopping")),
                        data.getOWLClass(MiniPizza.iri("VegetarianTopping")))));
    }

    @Test
    void aPlainNameAndADatatypeAreNotQuoted() {
        OWLDataProperty price = data.getOWLDataProperty(MiniPizza.iri("hasPrice"));

        assertEquals("hasPrice Range: xsd:float", AssistantRendering.render(pizza,
                data.getOWLDataPropertyRangeAxiom(price,
                        data.getOWLDatatype(OWL2Datatype.XSD_FLOAT.getIRI()))));
    }

    /**
     * MWO (1.140.0): two properties are both labelled "is subject of". Written by label they are
     * one name for two terms; each is written by its own short name instead.
     */
    @Test
    void aLabelTwoTermsShareGivesWayToTheirShortNames() {
        pizza.getOWLOntologyManager().addAxiom(pizza, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), MiniPizza.iri("Country"), data.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), MiniPizza.iri("City"), data.getOWLLiteral("place")));

        assertEquals("City SubClassOf Country", AssistantRendering.render(pizza,
                data.getOWLSubClassOfAxiom(data.getOWLClass(MiniPizza.iri("City")),
                        data.getOWLClass(MiniPizza.iri("Country")))));
    }

    /** A label that is a Manchester keyword would be read as one: it is quoted too. */
    @Test
    void aLabelThatIsAKeywordIsQuoted() {
        pizza.getOWLOntologyManager().addAxiom(pizza, data.getOWLAnnotationAssertionAxiom(
                data.getRDFSLabel(), MiniPizza.iri("Country"), data.getOWLLiteral("only")));

        assertEquals("'only'", AssistantRendering.render(pizza,
                data.getOWLClass(MiniPizza.iri("Country"))));
    }
}
