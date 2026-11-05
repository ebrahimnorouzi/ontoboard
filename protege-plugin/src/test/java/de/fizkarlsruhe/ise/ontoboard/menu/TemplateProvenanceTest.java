package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.prov.Provenance;
import de.fizkarlsruhe.ise.ontoboard.prov.ProvenanceSettings;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Who <i>Template...</i> records as having added a term, when provenance is recorded.
 *
 * <p>Found preparing the expert test on pizza-bfo's knowledge graph. The graph imports the
 * ontology's 1.0.0 release, and a sheet row "order 4, a hawaiian" names the class
 * {@code hawaiian}, which the release declares and no committed row had used. The run decided
 * what was new by looking in the graph's own signature only, so with <i>Record who edits
 * terms...</i> set to Always it stamped {@code hawaiian} - somebody else's class, from the import -
 * with the participant as its contributor and today as the day it was created. The rule the rest
 * of the plugin keeps ({@code EditWatcher}) is that a term the ontology does not own is not ours
 * to stamp.
 */
class TemplateProvenanceTest {

    private static final String BASE = "https://example.org/pizza/";
    private static final ProvenanceSettings ALWAYS =
            new ProvenanceSettings("Test Editor", ProvenanceSettings.Mode.ALWAYS);

    /** The terms a list of changes stamps with dcterms:contributor. */
    private static Set<IRI> stampedIn(List<OWLOntologyChange> changes) {
        Set<IRI> stamped = new LinkedHashSet<IRI>();
        for (OWLOntologyChange change : changes) {
            if (!change.isAddAxiom()) {
                continue;
            }
            OWLAxiom axiom = change.getAxiom();
            if (axiom instanceof OWLAnnotationAssertionAxiom) {
                OWLAnnotationAssertionAxiom assertion = (OWLAnnotationAssertionAxiom) axiom;
                if (assertion.getProperty().getIRI().equals(Provenance.CONTRIBUTOR)
                        && assertion.getSubject() instanceof IRI) {
                    stamped.add((IRI) assertion.getSubject());
                }
            }
        }
        return stamped;
    }

    /** A graph that imports a release declaring the classes and the property its rows name. */
    private static final class Graph {
        final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        final OWLDataFactory factory = manager.getOWLDataFactory();
        final OWLClass hawaiian = factory.getOWLClass(IRI.create(BASE + "hawaiian"));
        final OWLObjectProperty hasTopping =
                factory.getOWLObjectProperty(IRI.create(BASE + "has_topping"));
        final OWLOntology graph;

        Graph() throws Exception {
            IRI releaseIri = IRI.create(BASE + "releases/1.0.0/pizza.owl");
            OWLOntology release = manager.createOntology(releaseIri);
            manager.addAxiom(release, factory.getOWLDeclarationAxiom(hawaiian));
            manager.addAxiom(release, factory.getOWLDeclarationAxiom(hasTopping));
            graph = manager.createOntology(IRI.create(BASE + "kg/pizza-kg.ttl"));
            manager.applyChange(new AddImport(graph,
                    factory.getOWLImportsDeclaration(releaseIri)));
        }

        /** What a ROBOT template row "order 4, a hawaiian" builds: a typed, labelled individual. */
        OWLOntology rowFor(OWLNamedIndividual individual, String label) throws Exception {
            OWLOntology generated = manager.createOntology();
            manager.addAxiom(generated, factory.getOWLDeclarationAxiom(individual));
            manager.addAxiom(generated, factory.getOWLClassAssertionAxiom(hawaiian, individual));
            manager.addAxiom(generated, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), individual.getIRI(),
                    factory.getOWLLiteral(label, "en")));
            return generated;
        }
    }

    @Test
    void aClassTheImportBringsIsNotStampedAsNew() throws Exception {
        Graph kg = new Graph();
        OWLNamedIndividual order4 =
                kg.factory.getOWLNamedIndividual(IRI.create(BASE + "kg/PB_0000704"));

        Set<IRI> stamped = stampedIn(TemplateAction.changesFor(kg.graph,
                Collections.singletonList(kg.rowFor(order4, "order 4, a hawaiian")), ALWAYS));

        assertTrue(stamped.contains(order4.getIRI()), "the row's own individual is new: "
                + stamped);
        assertFalse(stamped.contains(kg.hawaiian.getIRI()),
                "hawaiian comes from the imported release, so nobody here added it: " + stamped);
    }

    @Test
    void aBuiltInVocabularyTermIsNeverStamped() throws Exception {
        Graph kg = new Graph();
        OWLNamedIndividual order4 =
                kg.factory.getOWLNamedIndividual(IRI.create(BASE + "kg/PB_0000704"));

        // The graph has no labels yet, so rdfs:label first appears with this run - it is still
        // the RDF Schema's, not the participant's.
        Set<IRI> stamped = stampedIn(TemplateAction.changesFor(kg.graph,
                Collections.singletonList(kg.rowFor(order4, "order 4, a hawaiian")), ALWAYS));

        assertFalse(stamped.contains(kg.factory.getRDFSLabel().getIRI()), "stamped: " + stamped);
        assertEquals(Collections.singleton(order4.getIRI()), stamped);
    }

    @Test
    void nothingIsStampedWhenNobodyIsNamed() throws Exception {
        Graph kg = new Graph();
        OWLNamedIndividual order4 =
                kg.factory.getOWLNamedIndividual(IRI.create(BASE + "kg/PB_0000704"));

        Set<IRI> stamped = stampedIn(TemplateAction.changesFor(kg.graph,
                Collections.singletonList(kg.rowFor(order4, "order 4, a hawaiian")),
                new ProvenanceSettings("", ProvenanceSettings.Mode.ALWAYS)));

        assertTrue(stamped.isEmpty(), "stamped: " + stamped);
    }
}
