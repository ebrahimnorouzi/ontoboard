package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * A class with two superclasses is drawn under the deeper one, so that the other stands above it.
 *
 * <p>Found by a tester: a pattern says <i>value specification</i> is under both <i>continuant</i>
 * and <i>information content entity</i>. Drawn under continuant, the first by IRI, it stood above
 * information content entity, and the line to that superclass pointed down.
 */
class DeepestParentTest {

    private static final String NS = "http://example.org/o#";

    @Test
    void theDeeperSuperclassIsTheOneDrawnAbove() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLClass continuant = df.getOWLClass(IRI.create(NS + "A_continuant"));
        OWLClass dependent = df.getOWLClass(IRI.create(NS + "B_dependent"));
        OWLClass content = df.getOWLClass(IRI.create(NS + "C_content"));
        OWLClass value = df.getOWLClass(IRI.create(NS + "D_value"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dependent, continuant));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(content, dependent));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value, continuant));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(value, content));

        Map<String, String> parents = OntologyProjection.superclassesAmong(ontology,
                Arrays.asList(NS + "A_continuant", NS + "B_dependent", NS + "C_content",
                        NS + "D_value"));

        assertEquals(NS + "C_content", parents.get(NS + "D_value"));
        assertEquals(NS + "B_dependent", parents.get(NS + "C_content"));
        assertEquals(NS + "A_continuant", parents.get(NS + "B_dependent"));
    }

    @Test
    void anEquivalenceDoesNotLoop() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/o"));
        OWLClass one = df.getOWLClass(IRI.create(NS + "One"));
        OWLClass two = df.getOWLClass(IRI.create(NS + "Two"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(one, two));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(two, one));

        Map<String, String> parents = OntologyProjection.superclassesAmong(ontology,
                Arrays.asList(NS + "One", NS + "Two"));

        assertEquals(2, parents.size());
    }
}
