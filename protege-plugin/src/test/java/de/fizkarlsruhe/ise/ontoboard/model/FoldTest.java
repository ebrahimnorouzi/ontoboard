package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/** Folding a class on the board: what goes out of sight, and that the board remembers it. */
class FoldTest {

    private static final String NS = "http://example.org/o#";

    @Test
    void foldingAClassHidesItsSubclassesTransitivelyAndTheirIndividuals() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology(IRI.create("http://example.org/o"));
        OWLClass material = df.getOWLClass(IRI.create(NS + "Material"));
        OWLClass metal = df.getOWLClass(IRI.create(NS + "Metal"));
        OWLClass steel = df.getOWLClass(IRI.create(NS + "Steel"));
        OWLClass process = df.getOWLClass(IRI.create(NS + "Process"));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(metal, material));
        manager.addAxiom(o, df.getOWLSubClassOfAxiom(steel, metal));
        manager.addAxiom(o, df.getOWLDeclarationAxiom(process));
        manager.addAxiom(o, df.getOWLClassAssertionAxiom(steel,
                df.getOWLNamedIndividual(IRI.create(NS + "specimen7"))));

        assertEquals(new HashSet<String>(Arrays.asList(NS + "Material", NS + "Metal",
                NS + "Steel", NS + "specimen7")),
                OntologyProjection.everythingUnder(o, NS + "Material"));
        assertEquals(new HashSet<String>(Arrays.asList(NS + "Process")),
                OntologyProjection.everythingUnder(o, NS + "Process"), "a leaf holds nothing");
    }

    @Test
    void aFoldIsPartOfTheBoardAndItsHistory() {
        CanvasLayout layout = new CanvasLayout();
        layout.collapsed.add(NS + "Material");

        CanvasLayout copy = layout.copy();
        layout.collapsed.clear();

        assertEquals(Arrays.asList(NS + "Material"), copy.collapsed, "a copy shares nothing");
        layout.copyFrom(copy);
        assertEquals(Arrays.asList(NS + "Material"), layout.collapsed, "and undo puts it back");
    }

    @Test
    void aNewBoxBesideAnotherGoesBelowWhatIsAlreadyThere() {
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(NS + "A", new CanvasLayout.NodeLayout(300, 100));

        double[] spot = OntologyProjection.besideItsParentAt(layout, 300, 100);

        assertEquals(300, spot[0], 0.001);
        assertEquals(176, spot[1], 0.001);
    }
}
