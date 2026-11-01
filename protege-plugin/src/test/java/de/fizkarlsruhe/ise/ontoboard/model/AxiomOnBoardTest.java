package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
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
}
