package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.search.EntitySearcher;

/**
 * Chooses the text shown on a canvas node.
 *
 * <p>An IRI fragment is a poor label: real ontologies use opaque identifiers such as
 * {@code MWO_0000042}, which tells a reader nothing. Where an entity carries
 * {@code rdfs:label} that is used instead.
 *
 * <p>Selection is deterministic on purpose. Several labels are common (one per language),
 * and OWL API returns them in an unordered collection, so picking "the first" would make
 * the diagram's text change between runs. English wins, then an unlanguaged literal, then
 * the lexicographically smallest - never whatever the hash order happened to yield.
 */
public final class DisplayLabels {

    private static final String PREFERRED_LANGUAGE = "en";

    private DisplayLabels() {
    }

    /**
     * The label for {@code entity}, or its short name when it carries no usable
     * {@code rdfs:label}.
     */
    public static String forEntity(OWLOntology ontology, OWLEntity entity) {
        Collection<OWLAnnotation> annotations = EntitySearcher.getAnnotations(
                entity, ontology, ontology.getOWLOntologyManager().getOWLDataFactory()
                        .getRDFSLabel());

        List<String> preferred = new ArrayList<String>();
        List<String> unlanguaged = new ArrayList<String>();
        List<String> other = new ArrayList<String>();

        for (OWLAnnotation annotation : annotations) {
            if (!annotation.getValue().asLiteral().isPresent()) {
                continue;
            }
            OWLLiteral literal = annotation.getValue().asLiteral().get();
            String text = literal.getLiteral();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (literal.hasLang(PREFERRED_LANGUAGE)) {
                preferred.add(text);
            } else if (!literal.hasLang()) {
                unlanguaged.add(text);
            } else {
                other.add(text);
            }
        }

        String chosen = smallest(preferred);
        if (chosen == null) {
            chosen = smallest(unlanguaged);
        }
        if (chosen == null) {
            chosen = smallest(other);
        }
        return chosen != null ? chosen : shortNameOf(entity.getIRI());
    }

    private static String smallest(List<String> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates);
        return candidates.get(0);
    }

    /**
     * The last meaningful segment of an IRI. Kept public so callers with an IRI but no
     * entity - datatypes, literals - can label consistently.
     */
    public static String shortNameOf(IRI value) {
        String fragment = value.getFragment();
        if (fragment != null && !fragment.isEmpty()) {
            return fragment;
        }
        String text = value.toString();
        // IRI.getFragment() returns "" (not null) for a bare trailing '/' or '#', so both
        // fall through to here. Strip a single trailing delimiter before taking the last
        // path segment, otherwise "foo/" yields the whole IRI and "foo#" leaks the '#'.
        if (text.endsWith("/") || text.endsWith("#")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return value.toString();
        }
        int slash = text.lastIndexOf('/');
        return slash >= 0 && slash < text.length() - 1 ? text.substring(slash + 1) : text;
    }
}
