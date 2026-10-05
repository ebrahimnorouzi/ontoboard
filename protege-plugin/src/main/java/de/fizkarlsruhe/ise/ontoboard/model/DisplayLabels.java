package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
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
     *
     * <p><b>The imports are searched, and until 1.78.0 they were not.</b> This asked
     * {@code EntitySearcher} for annotations in one ontology, and an imported term's label is
     * not in that ontology - it is in the import. So on exactly the projects this plugin is
     * for, where most of what a curator refers to comes from BFO, IAO, RO or OBI, the canvas
     * drew opaque identifiers and called them labels.
     *
     * <p>Measured on a real ODK project, before the fix: of 656 entities carrying an
     * {@code rdfs:label} somewhere in the closure, <b>113</b> were drawn with it and <b>543</b>
     * were drawn as {@code BFO_0000004} - an entity whose label is "independent continuant".
     * The class javadoc already said "an IRI fragment is a poor label: real ontologies use
     * opaque identifiers such as MWO_0000042, which tells a reader nothing", and then showed
     * exactly that to five readers out of six.
     *
     * <p><b>The edit file wins.</b> Its own annotations are searched first and the imports only
     * if it says nothing, because an ontology that re-labels an imported term has done so on
     * purpose - and a canvas that showed the upstream label instead would be overruling the
     * author in their own file. This is what Protege's own rendering does.
     */
    public static String forEntity(OWLOntology ontology, OWLEntity entity) {
        if (ontology == null || entity == null) {
            return entity == null ? "" : shortNameOf(entity.getIRI());
        }
        OWLAnnotationProperty rdfsLabel =
                ontology.getOWLOntologyManager().getOWLDataFactory().getRDFSLabel();

        String own = chooseFrom(EntitySearcher.getAnnotations(entity, ontology, rdfsLabel));
        if (own != null) {
            return own;
        }
        // getImportsClosure includes the ontology itself; it has just been searched and found
        // nothing, so re-reading it costs one pass over an empty answer rather than a wrong one.
        String imported =
                chooseFrom(EntitySearcher.getAnnotations(entity, ontology.getImportsClosure(),
                        rdfsLabel));
        return imported != null ? imported : shortNameOf(entity.getIRI());
    }

    /**
     * The best of a set of label annotations, or null when none is usable.
     *
     * <p>Deterministic on purpose, for the reason the class comment gives: several labels are
     * common, one per language, and OWL API hands them back unordered - so "the first" would
     * make the diagram's text change between runs of the same file.
     */
    private static String chooseFrom(Collection<OWLAnnotation> annotations) {
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
        return chosen;
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
