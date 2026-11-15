package de.fizkarlsruhe.ise.ontoboard.assistant;

import java.io.InputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The assistant tests' ontology: a few pizzas and toppings, one universal and one existential
 * restriction, a disjointness, a domain and range, two individuals, a German label and a
 * definition. Small enough to read in one go, which is the point - every assertion about the
 * context can be checked by eye against {@code src/test/resources/assistant/mini-pizza.ofn}.
 */
public final class MiniPizza {

    public static final String NS = "http://example.org/mini-pizza#";

    private MiniPizza() {
    }

    /** A fresh copy in a fresh manager, so a test that changes it changes nothing else. */
    public static OWLOntology load() {
        try (InputStream in = MiniPizza.class.getResourceAsStream("/assistant/mini-pizza.ofn")) {
            if (in == null) {
                throw new IllegalStateException("mini-pizza.ofn is not on the test classpath");
            }
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(in);
        } catch (Exception cannotLoad) {
            throw new IllegalStateException("could not load mini-pizza.ofn", cannotLoad);
        }
    }

    public static IRI iri(String localName) {
        return IRI.create(NS + localName);
    }
}
