package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/** An axiom's line in Protégé's description view finds the arrows that draw it on the board. */
class AxiomOnBoardTest {

    private static final String NS = "http://example.org/pizza#";

    @Test
    void eachAxiomFindsItsOwnArrowsAndNoOthers() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/pizza"));
        OWLClass pizza = df.getOWLClass(IRI.create(NS + "Pizza"));
        OWLClass topping = df.getOWLClass(IRI.create(NS + "Topping"));
        OWLClass food = df.getOWLClass(IRI.create(NS + "Food"));
        OWLObjectProperty hasTopping = df.getOWLObjectProperty(IRI.create(NS + "hasTopping"));
        OWLAxiom some = df.getOWLSubClassOfAxiom(pizza,
                df.getOWLObjectSomeValuesFrom(hasTopping, topping));
        OWLAxiom only = df.getOWLSubClassOfAxiom(pizza,
                df.getOWLObjectAllValuesFrom(hasTopping, topping));
        OWLAxiom isA = df.getOWLSubClassOfAxiom(pizza, food);
        manager.addAxioms(o, new java.util.HashSet<OWLAxiom>(Arrays.asList(some, only, isA,
                df.getOWLSubClassOfAxiom(topping, food))));
        List<AxiomOnBoard.Arrow> arrows = new ArrayList<AxiomOnBoard.Arrow>();
        for (CanvasEdge edge : OntologyProjection.project(o,
                OntologyProjection.termIdentifiers(o, Imports.INCLUDED), true).getEdges()) {
            arrows.add(new AxiomOnBoard.Arrow(edge.getId() + " " + edge.getLabel(),
                    edge.getSourceId(), edge.getTargetId(), edge.getLabel()));
        }

        List<String> forSome = AxiomOnBoard.arrowsFor(some, o, arrows);
        List<String> forIsA = AxiomOnBoard.arrowsFor(isA, o, arrows);

        assertEquals(1, forSome.size(), "the some arrow, not the only one: " + forSome);
        assertEquals(true, forSome.get(0).contains("\u2203"), forSome.toString());
        List<String> forOnly = AxiomOnBoard.arrowsFor(only, o, arrows);
        assertEquals(1, forOnly.size(), forOnly.toString());
        assertEquals(true, forOnly.get(0).contains("\u2200"), forOnly.toString());
        assertEquals(1, forIsA.size(), forIsA.toString());
        assertEquals(true, forIsA.get(0).contains(NS + "Food"), forIsA.toString());
    }

    private static final String OBI = "http://example.org/obi#";

    private static void label(OWLOntologyManager manager, OWLOntology ontology, OWLEntity entity,
            String text) {
        OWLDataFactory df = manager.getOWLDataFactory();
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(entity.getIRI(),
                df.getOWLAnnotation(df.getRDFSLabel(), df.getOWLLiteral(text, "en"))));
    }

    /**
     * A property's own lines - from its box to its domain, its range, its super-property, its
     * inverse, a property it is disjoint with, its chain - are found from the rows of its
     * description. They read "domain", "range", "rdfs:subPropertyOf" or nothing, never the
     * property's name. A restriction that names the property, and ends where its domain line does,
     * still finds only its own arrow.
     *
     * <p>Found in review (1.135.1): clicking "Domains: process" in the description of "has
     * specified input" said "That axiom is not drawn on the board as it is now." with the line on
     * the board.
     */
    @Test
    void aPropertysAxiomsFindTheLinesAtItsOwnBox() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/obi"));
        OWLClass process = df.getOWLClass(IRI.create(OBI + "process"));
        OWLClass entity = df.getOWLClass(IRI.create(OBI + "entity"));
        OWLObjectProperty input = df.getOWLObjectProperty(IRI.create(OBI + "hasInput"));
        OWLObjectProperty inputOf = df.getOWLObjectProperty(IRI.create(OBI + "isInputOf"));
        OWLObjectProperty participant = df.getOWLObjectProperty(IRI.create(OBI + "hasParticipant"));
        OWLObjectProperty involves = df.getOWLObjectProperty(IRI.create(OBI + "involves"));
        OWLObjectProperty output = df.getOWLObjectProperty(IRI.create(OBI + "hasOutput"));
        OWLObjectProperty part = df.getOWLObjectProperty(IRI.create(OBI + "hasPart"));
        OWLDataProperty value = df.getOWLDataProperty(IRI.create(OBI + "hasValue"));
        OWLDataProperty number = df.getOWLDataProperty(IRI.create(OBI + "hasNumber"));
        OWLDataProperty count = df.getOWLDataProperty(IRI.create(OBI + "hasCount"));
        OWLDataProperty name = df.getOWLDataProperty(IRI.create(OBI + "hasName"));
        // Labels unlike the identifiers, as OBI's are.
        label(manager, o, input, "has specified input");
        label(manager, o, inputOf, "is specified input of");
        label(manager, o, participant, "has participant");
        label(manager, o, involves, "involves participant");
        label(manager, o, output, "has specified output");
        label(manager, o, part, "has part");
        label(manager, o, value, "has value");
        label(manager, o, number, "has number");
        label(manager, o, count, "has count");
        label(manager, o, name, "has name");

        Map<OWLAxiom, List<String>> expected = new LinkedHashMap<OWLAxiom, List<String>>();
        expected.put(df.getOWLObjectPropertyDomainAxiom(input, process),
                Arrays.asList("domain|" + OBI + "hasInput|" + OBI + "process"));
        expected.put(df.getOWLObjectPropertyRangeAxiom(input, entity),
                Arrays.asList("range|" + OBI + "hasInput|" + OBI + "entity"));
        expected.put(df.getOWLSubObjectPropertyOfAxiom(input, participant),
                Arrays.asList("subprop|" + OBI + "hasInput|" + OBI + "hasParticipant"));
        expected.put(df.getOWLInverseObjectPropertiesAxiom(input, inputOf),
                Arrays.asList("inv|" + OBI + "hasInput|" + OBI + "isInputOf"));
        expected.put(df.getOWLDisjointObjectPropertiesAxiom(input, output),
                Arrays.asList("pdisj|" + OBI + "hasInput|" + OBI + "hasOutput"));
        // A domain and a range joining the same two boxes, told apart.
        expected.put(df.getOWLObjectPropertyDomainAxiom(output, process),
                Arrays.asList("domain|" + OBI + "hasOutput|" + OBI + "process"));
        expected.put(df.getOWLObjectPropertyRangeAxiom(output, process),
                Arrays.asList("range|" + OBI + "hasOutput|" + OBI + "process"));
        // An equivalence is drawn as a sub-property line each way, or as the inverse line.
        expected.put(df.getOWLEquivalentObjectPropertiesAxiom(participant, involves),
                Arrays.asList("subprop|" + OBI + "hasParticipant|" + OBI + "involves",
                        "subprop|" + OBI + "involves|" + OBI + "hasParticipant"));
        expected.put(df.getOWLEquivalentObjectPropertiesAxiom(inputOf,
                df.getOWLObjectInverseOf(input)),
                Arrays.asList("inv|" + OBI + "hasInput|" + OBI + "isInputOf"));
        expected.put(df.getOWLSubDataPropertyOfAxiom(number, value),
                Arrays.asList("subprop|" + OBI + "hasNumber|" + OBI + "hasValue"));
        expected.put(df.getOWLDataPropertyDomainAxiom(value, entity),
                Arrays.asList("domain|" + OBI + "hasValue|" + OBI + "entity"));
        expected.put(df.getOWLDataPropertyRangeAxiom(value,
                df.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#decimal"))),
                Arrays.asList("range|" + OBI
                        + "hasValue|http://www.w3.org/2001/XMLSchema#decimal"));
        expected.put(df.getOWLEquivalentDataPropertiesAxiom(count, number),
                Arrays.asList("subprop|" + OBI + "hasCount|" + OBI + "hasNumber",
                        "subprop|" + OBI + "hasNumber|" + OBI + "hasCount"));
        expected.put(df.getOWLDisjointDataPropertiesAxiom(name, value),
                Arrays.asList("pdisj|" + OBI + "hasName|" + OBI + "hasValue"));
        // It names has specified input and ends at process, as the domain line does.
        expected.put(df.getOWLSubClassOfAxiom(process,
                df.getOWLObjectSomeValuesFrom(input, entity)),
                Arrays.asList("rest|some|" + OBI + "process|" + OBI + "hasInput|" + OBI
                        + "entity"));
        OWLAxiom union = df.getOWLObjectPropertyRangeAxiom(part,
                df.getOWLObjectUnionOf(process, entity));
        OWLAxiom chain = df.getOWLSubPropertyChainOfAxiom(Arrays.asList(input, part), input);
        manager.addAxioms(o, new HashSet<OWLAxiom>(expected.keySet()));
        manager.addAxiom(o, union);
        manager.addAxiom(o, chain);

        for (boolean expressions : new boolean[] {false, true}) {
            Projection projection = OntologyProjection.project(o,
                    OntologyProjection.termIdentifiers(o, Imports.INCLUDED), expressions);
            List<AxiomOnBoard.Arrow> arrows = new ArrayList<AxiomOnBoard.Arrow>();
            for (CanvasEdge edge : projection.getEdges()) {
                // As the description view hands them over: the line's id, its ends, its label.
                arrows.add(new AxiomOnBoard.Arrow(edge.getId(), edge.getSourceId(),
                        edge.getTargetId(), edge.getLabel()));
            }
            String unionBox = null;
            String chainBox = null;
            for (CanvasNode node : projection.getNodes()) {
                if (node.getKind() == NodeKind.EXPRESSION && node.getLabel().equals("⊔")) {
                    unionBox = node.getId();
                } else if (node.getKind() == NodeKind.EXPRESSION
                        && node.getLabel().startsWith("chain: ")) {
                    chainBox = node.getId();
                }
            }
            Map<OWLAxiom, List<String>> wanted =
                    new LinkedHashMap<OWLAxiom, List<String>>(expected);
            // A union range: to the union's box with class expressions drawn, else to each class.
            wanted.put(union, expressions
                    ? Arrays.asList("range|" + OBI + "hasPart|" + unionBox)
                    : Arrays.asList("range-or|" + OBI + "hasPart|" + OBI + "entity",
                            "range-or|" + OBI + "hasPart|" + OBI + "process"));
            wanted.put(chain, Arrays.asList("chain|" + chainBox + "|" + OBI + "hasInput",
                    "chain|" + chainBox + "|0|" + OBI + "hasInput",
                    "chain|" + chainBox + "|1|" + OBI + "hasPart"));
            StringBuilder want = new StringBuilder();
            StringBuilder found = new StringBuilder();
            for (Map.Entry<OWLAxiom, List<String>> one : wanted.entrySet()) {
                List<String> ids = new ArrayList<String>(one.getValue());
                List<String> got = new ArrayList<String>(
                        AxiomOnBoard.arrowsFor(one.getKey(), o, arrows));
                Collections.sort(ids);
                Collections.sort(got);
                want.append(one.getKey()).append("\n    -> ").append(ids).append('\n');
                found.append(one.getKey()).append("\n    -> ").append(got).append('\n');
            }
            assertEquals(want.toString().replace(OBI, ""), found.toString().replace(OBI, ""),
                    "expressions " + expressions);
        }
    }
}
