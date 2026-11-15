package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.assistant.MiniPizza;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.AssistantRendering;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * Logic a model proposes, read with the OWL API's Manchester parser over the ontology's own
 * names (1.139.0). A name that resolves to nothing is reported; nothing is ever declared.
 */
class AxiomParserTest {

    private final OWLOntology pizza = MiniPizza.load();
    private final OWLDataFactory f = pizza.getOWLOntologyManager().getOWLDataFactory();

    private OWLClass cls(String name) {
        return f.getOWLClass(MiniPizza.iri(name));
    }

    private OWLObjectProperty hasTopping() {
        return f.getOWLObjectProperty(MiniPizza.iri("hasTopping"));
    }

    private AxiomParser.Result parse(String text) {
        return new AxiomParser(pizza).parse(text);
    }

    @Test
    void aSubClassOfWithSomeAndQuotedLabelsParses() {
        AxiomParser.Result result = parse(
                "'vegetarian pizza' SubClassOf hasTopping some 'cheese topping'");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("VegetarianPizza"),
                f.getOWLObjectSomeValuesFrom(hasTopping(), cls("CheeseTopping"))),
                result.getAxiom());
    }

    @Test
    void anOnlyRestrictionByShortNamesParses() {
        AxiomParser.Result result = parse(
                "Margherita SubClassOf hasTopping only VegetarianTopping");

        assertEquals(f.getOWLSubClassOfAxiom(cls("Margherita"),
                f.getOWLObjectAllValuesFrom(hasTopping(), cls("VegetarianTopping"))),
                result.getAxiom());
    }

    /** The pizza example's repair: the price range becomes decimal. */
    @Test
    void aRangeWithAnXsdDatatypeParses() {
        OWLDataProperty price = f.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(price));

        AxiomParser.Result result = parse("hasPrice Range: xsd:decimal");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLDataPropertyRangeAxiom(price,
                f.getOWLDatatype(OWL2Datatype.XSD_DECIMAL.getIRI())), result.getAxiom());
    }

    /**
     * Review 1.139.0 I1a: the OWL API's data-range reader stops where the range ends, so the
     * float half of a two-range answer was dropped and Accept wrote decimal alone.
     */
    @Test
    void trailingTextAfterADataRangeIsRefused() {
        OWLDataProperty price = f.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(price));

        for (String text : new String[] {"hasPrice Range: xsd:decimal, xsd:float",
                "hasPrice Range: xsd:decimal (was float)"}) {
            AxiomParser.Result result = parse(text);
            assertNull(result.getAxiom(), text + " read as " + result.getAxiom());
            assertNotNull(result.getProblem(), text);
        }
    }

    @Test
    void trailingTextAfterAClassAxiomIsRefused() {
        AxiomParser.Result result = parse("Margherita SubClassOf NamedPizza Pizza");

        assertNull(result.getAxiom(), "read as " + result.getAxiom());
    }

    /**
     * Review 1.139.0 I1b: one term written twice - once by a synonym - made the OWL API write
     * "DisjointWith owl:Thing" with its own comments, or an equivalence of one class.
     */
    @Test
    void aListThatNamesOneTermTwiceIsRefused() {
        for (String text : new String[] {"DisjointClasses: City, City",
                "VegetarianPizza DisjointWith 'veggie pizza'", "EquivalentClasses: Pizza, Pizza",
                "SameIndividual: Naples, Naples"}) {
            AxiomParser.Result result = parse(text);
            assertNull(result.getAxiom(), text + " read as " + result.getAxiom());
            assertTrue(result.getProblem().contains("twice"), text + ": " + result.getProblem());
        }
    }

    /** Review 1.139.0 I1c: a class the ontology labels "Thing" is not owl:Thing. */
    @Test
    void aTermLabelledThingIsAmbiguous() {
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("Country"), f.getOWLLiteral("Thing")));

        AxiomParser.Result result = parse("Thing SubClassOf Pizza");

        assertNull(result.getAxiom(), "read as " + result.getAxiom());
        assertTrue(result.getProblem().contains("ambiguous"), result.getProblem());
    }

    /** 1.140.0, from the live run: a name with a space, written without its quotes. */
    @Test
    void aMultiWordNameWithoutQuotesIsRead() {
        AxiomParser.Result result = parse("vegetarian pizza SubClassOf hasTopping some cheese"
                + " topping");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("VegetarianPizza"),
                f.getOWLObjectSomeValuesFrom(hasTopping(), cls("CheeseTopping"))),
                result.getAxiom());
    }

    /**
     * Review 1.141.0 I3: unquoted labels and an invented name, the commonest failure measured.
     * Both readings fail; the as-written one blamed "vegetarian", a word of a label that exists,
     * and the card should name what is really missing.
     */
    @Test
    void whenBothReadingsFailTheQuotedReadingsProblemIsShown() {
        AxiomParser.Result result = parse("vegetarian pizza SubClassOf hasTopping some FooTopping");

        assertNull(result.getAxiom());
        assertTrue(result.getProblem().contains("FooTopping"), result.getProblem());
        assertFalse(result.getProblem().contains("vegetarian"), result.getProblem());
    }

    /** 1.144.0: every shape the benchmark says, as a model may write it, quoted or not. */
    @Test
    void everyShapeParsesQuotedAndUnquoted() throws java.io.IOException {
        OWLOntology fixture = de.fizkarlsruhe.ise.ontoboard.assistant.bench.AssistantBenchAccess
                .load("../benchmarks/assistant/ontologies/fixtures/restrictions.ofn");
        String[] written = {
            "alloy SubClassOf 'has constituent element' min 2 element",
            "alloy SubClassOf has constituent element min 2 element",
            "'pure metal' SubClassOf 'has constituent element' max 1 element",
            "pure metal SubClassOf has constituent element max 1 element",
            "brass SubClassOf 'has constituent element' exactly 2 element",
            "steel SubClassOf 'has main element' value iron",
            "steel SubClassOf has main element value iron",
            "'test specimen' SubClassOf 'has density' some xsd:decimal",
            "test specimen SubClassOf has density some xsd:decimal",
            "'reference steel' SubClassOf 'has density' value 7.85",
            "'binary alloy' EquivalentTo alloy and ('has constituent element' exactly 2 element)",
        };
        List<String> unread = new java.util.ArrayList<String>();
        for (String text : written) {
            AxiomParser.Result result = new AxiomParser(fixture).parse(text);
            if (result.getAxiom() == null) {
                unread.add(text + " -> " + result.getProblem());
            }
        }
        assertEquals(java.util.Collections.<String>emptyList(), unread);

        String value = new AxiomParser(fixture).parse(
                "'reference steel' SubClassOf 'has density' value 7.85").getAxiom().toString();
        assertTrue(value.contains("\"7.85\"^^xsd:decimal"), value);
    }

    /** The pizza repair as qwen2.5:7b wrote it: "with: has value Range: xsd:decimal". */
    @Test
    void anUnquotedMultiWordPropertyInARangeIsRead() {
        OWLDataProperty price = f.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(price));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), price.getIRI(), f.getOWLLiteral("has value", "en")));

        AxiomParser.Result result = parse("has value Range: xsd:decimal");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLDataPropertyRangeAxiom(price,
                f.getOWLDatatype(OWL2Datatype.XSD_DECIMAL.getIRI())), result.getAxiom());
    }

    /**
     * Review 1.140.0 M1: putting quotes back changed or refused text that was valid as written,
     * when a label happened to read like part of it.
     */
    @Test
    void textValidAsWrittenIsReadAsWritten() {
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("City"), f.getOWLLiteral("Naples, Italy")));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("Country"),
                f.getOWLLiteral("only VegetarianTopping")));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("Pizza"), f.getOWLLiteral("Type City")));
        org.semanticweb.owlapi.model.OWLNamedIndividual naples =
                f.getOWLNamedIndividual(MiniPizza.iri("Naples"));
        org.semanticweb.owlapi.model.OWLNamedIndividual italy =
                f.getOWLNamedIndividual(MiniPizza.iri("Italy"));

        assertEquals(f.getOWLDifferentIndividualsAxiom(naples, italy),
                parse("DifferentIndividuals: Naples, Italy").getAxiom());
        assertEquals(f.getOWLSubClassOfAxiom(cls("Margherita"), f.getOWLObjectAllValuesFrom(
                hasTopping(), cls("VegetarianTopping"))),
                parse("Margherita SubClassOf hasTopping only VegetarianTopping").getAxiom());
        assertEquals(f.getOWLClassAssertionAxiom(cls("City"), naples),
                parse("Naples Type City").getAxiom());
    }

    /** Review 1.140.0 M1: "[< 5]" opened an "IRI" to the end, and nothing after it was quoted. */
    @Test
    void aNameAfterAFacetIsStillQuoted() {
        OWLDataProperty price = f.getOWLDataProperty(MiniPizza.iri("hasPrice"));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLDeclarationAxiom(price));

        AxiomParser.Result result = parse("Margherita SubClassOf (hasPrice some xsd:integer[< 5])"
                + " and (hasTopping some cheese topping)");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("Margherita"), f.getOWLObjectIntersectionOf(
                f.getOWLDataSomeValuesFrom(price, f.getOWLDatatypeRestriction(
                        f.getIntegerOWLDatatype(),
                        org.semanticweb.owlapi.vocab.OWLFacet.MAX_EXCLUSIVE,
                        f.getOWLLiteral(5))),
                f.getOWLObjectSomeValuesFrom(hasTopping(), cls("CheeseTopping")))),
                result.getAxiom());
    }

    /** A name inside quotes or a string stays as it is. */
    @Test
    void quotedNamesAndStringsAreLeftAlone() {
        AxiomParser.Result quoted = parse("'vegetarian pizza' SubClassOf hasTopping some cheese"
                + " topping");
        AxiomParser.Result inString = parse("Naples Type City");

        assertNull(quoted.getProblem(), quoted.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("VegetarianPizza"),
                f.getOWLObjectSomeValuesFrom(hasTopping(), cls("CheeseTopping"))),
                quoted.getAxiom());
        assertNull(inString.getProblem(), inString.getProblem());
    }

    /** 1.141.0, from the live run: "'specification datum' And (...)", "Some". */
    @Test
    void capitalisedKeywordsAreRead() {
        AxiomParser.Result result = parse("Margherita SubClassOf NamedPizza And (hasTopping Some"
                + " 'cheese topping')");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("Margherita"), f.getOWLObjectIntersectionOf(
                cls("NamedPizza"), f.getOWLObjectSomeValuesFrom(hasTopping(),
                        cls("CheeseTopping")))), result.getAxiom());
    }

    /** 1.139.0 review M5: a misplaced bracket was reported as a name "not in this ontology". */
    @Test
    void aSyntaxErrorIsNotAnUnknownName() {
        AxiomParser.Result result = parse("(hasTopping some VegetarianTopping SubClassOf Pizza");

        assertNull(result.getAxiom());
        assertFalse(result.getProblem().contains("not in this ontology"), result.getProblem());
        assertTrue(result.getProblem().contains("Manchester syntax"), result.getProblem());
    }

    @Test
    void aClassAssertionParses() {
        AxiomParser.Result result = parse("Naples Type City");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLClassAssertionAxiom(cls("City"),
                f.getOWLNamedIndividual(MiniPizza.iri("Naples"))), result.getAxiom());
    }

    @Test
    void anUnknownNameIsReportedAndNothingIsDeclared() {
        AxiomParser.Result result = parse("'vegetarian pizza' SubClassOf hasTopping some calzone");

        assertNull(result.getAxiom());
        assertTrue(result.getProblem().contains("calzone"), result.getProblem());
        assertTrue(result.getProblem().contains("not in this ontology"), result.getProblem());
        assertFalse(pizza.containsClassInSignature(MiniPizza.iri("calzone")));
    }

    @Test
    void anAmbiguousNameIsReported() {
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("Country"), f.getOWLLiteral("place")));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("City"), f.getOWLLiteral("place")));

        AxiomParser.Result result = parse("Naples Type place");

        assertNull(result.getAxiom());
        assertTrue(result.getProblem().contains("ambiguous"), result.getProblem());
    }

    /** pizza.owl: "Price SubClassOf 'has currency' exactly 1 Thing" (round trip, 1.139.0). */
    @Test
    void thingAndNothingAreOwls() {
        AxiomParser.Result result = parse("Pizza SubClassOf Thing");

        assertEquals(f.getOWLSubClassOfAxiom(cls("Pizza"), f.getOWLThing()), result.getAxiom());
    }

    /**
     * pizza-bfo: a class and another term share the label "identifier". Where the axiom needs a
     * class, the one class is meant; that is not a guess.
     */
    @Test
    void aSharedLabelIsResolvedByTheKindTheAxiomNeeds() {
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("PizzaTopping"), f.getOWLLiteral("topping")));
        pizza.getOWLOntologyManager().addAxiom(pizza, f.getOWLAnnotationAssertionAxiom(
                f.getRDFSLabel(), MiniPizza.iri("hasTopping"), f.getOWLLiteral("topping")));

        AxiomParser.Result result = parse("Margherita SubClassOf hasTopping some topping");

        assertNull(result.getProblem(), result.getProblem());
        assertEquals(f.getOWLSubClassOfAxiom(cls("Margherita"),
                f.getOWLObjectSomeValuesFrom(hasTopping(), cls("PizzaTopping"))),
                result.getAxiom());
    }

    /** MWO (1.140.0): "'has acronym' SubPropertyOf: topDataProperty". */
    @Test
    void theTopPropertiesAreOwls() {
        AxiomParser.Result result = parse("hasTopping SubPropertyOf: topObjectProperty");

        assertEquals(f.getOWLSubObjectPropertyOfAxiom(hasTopping(), f.getOWLTopObjectProperty()),
                result.getAxiom());
    }

    @Test
    void proseIsReportedNotGuessed() {
        AxiomParser.Result result = parse("every vegetarian pizza has cheese");

        assertNull(result.getAxiom());
        assertFalse(result.getProblem().isEmpty());
    }

    /**
     * The contract that makes add and replace work: every axiom the assistant shows the model
     * parses back to itself, so an axiom the model copies is the axiom it saw.
     */
    @Test
    void everyLogicalAxiomTheAssistantShowsParsesBack() {
        List<String> failed = new ArrayList<String>();
        AxiomParser parser = new AxiomParser(pizza);
        for (OWLAxiom axiom : pizza.getLogicalAxioms()) {
            String shown = AssistantRendering.render(pizza, axiom);
            AxiomParser.Result back = parser.parse(shown);
            if (!axiom.equals(back.getAxiom())) {
                failed.add(shown + " -> " + (back.getAxiom() == null ? back.getProblem()
                        : back.getAxiom()));
            }
        }

        assertTrue(failed.isEmpty(), String.join("\n", failed));
    }
}
