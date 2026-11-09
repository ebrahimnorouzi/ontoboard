package de.fizkarlsruhe.ise.ontoboard.assistant.chat;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Finds the terms a model asked for (spec D7) - exactly, or not at all.
 *
 * <p>Exact and case-insensitive on any label in any language, a synonym, the IRI's local name, or
 * the whole IRI. Never fuzzy: a near match offered as "the term you asked for" would put the
 * wrong term in front of the model with OntoBoard's authority. A name two terms share is
 * reported as ambiguous with both, and neither is added.
 *
 * <p>Its own index rather than {@code robot/TermSearch}, which ranks partial matches for a
 * person typing - the opposite of what is wanted here.
 */
public final class NameResolver {

    /** A term's own names: what the column and what is sent call it. */
    private static final Set<String> LABELS = new HashSet<String>(Arrays.asList(
            "http://www.w3.org/2000/01/rdf-schema#label",
            "http://www.w3.org/2004/02/skos/core#prefLabel"));

    /**
     * Other names. A label beats a synonym (1.140.0): in NFDIcore "event" is one class's label
     * and a synonym of "process", and the class named "event" is meant.
     */
    private static final Set<String> SYNONYMS = new HashSet<String>(Arrays.asList(
            "http://www.w3.org/2004/02/skos/core#altLabel",
            "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasBroadSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasNarrowSynonym"));

    /** What one name came to. */
    public static final class Resolution {
        private final String name;
        private final IRI iri;
        private final String label;
        private final List<String> candidates;

        Resolution(String name, IRI iri, String label, List<String> candidates) {
            this.name = name;
            this.iri = iri;
            this.label = label;
            this.candidates = Collections.unmodifiableList(candidates);
        }

        /** As the model wrote it. */
        public String getName() {
            return name;
        }

        /** The term, when exactly one matched; otherwise null. */
        public IRI getIri() {
            return iri;
        }

        /** The term's label, when found; otherwise the name. */
        public String getLabel() {
            return label;
        }

        public boolean isFound() {
            return iri != null;
        }

        public boolean isAmbiguous() {
            return candidates.size() > 1;
        }

        /** The labels of every term that matched. */
        public List<String> getCandidates() {
            return candidates;
        }
    }

    private final OWLOntology ontology;
    /** IRIs, short names and labels. */
    private final Map<String, Set<IRI>> index = new HashMap<String, Set<IRI>>();
    /** Synonyms, consulted only for a name no term has as its own. */
    private final Map<String, Set<IRI>> other = new HashMap<String, Set<IRI>>();
    /** Classes, object and data properties and individuals: what an axiom can name. */
    private final Set<IRI> terms = new HashSet<IRI>();

    public NameResolver(OWLOntology ontology) {
        this.ontology = ontology;
        for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
            if (entity.isBuiltIn()) {
                continue;
            }
            IRI iri = entity.getIRI();
            if (entity.isOWLClass() || entity.isOWLObjectProperty()
                    || entity.isOWLDataProperty() || entity.isOWLNamedIndividual()) {
                terms.add(iri);
            }
            put(index, iri.toString(), iri);
            put(index, DisplayLabels.shortNameOf(iri), iri);
        }
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (OWLAnnotationAssertionAxiom axiom
                    : part.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION)) {
                String property = axiom.getProperty().getIRI().toString();
                if ((LABELS.contains(property) || SYNONYMS.contains(property))
                        && axiom.getSubject() instanceof IRI
                        && axiom.getValue().asLiteral().isPresent()) {
                    put(LABELS.contains(property) ? index : other,
                            axiom.getValue().asLiteral().get().getLiteral(),
                            (IRI) axiom.getSubject());
                }
            }
        }
    }

    private static void put(Map<String, Set<IRI>> into, String name, IRI iri) {
        String key = normal(name);
        if (key.isEmpty()) {
            return;
        }
        Set<IRI> found = into.get(key);
        if (found == null) {
            found = new LinkedHashSet<IRI>();
            into.put(key, found);
        }
        found.add(iri);
    }

    /** The terms whose own name it is; else those whose synonym it is. */
    private Set<IRI> lookup(String name) {
        String key = normal(name);
        Set<IRI> found = index.get(key);
        if (found != null && !found.isEmpty()) {
            return found;
        }
        return other.get(key);
    }

    private static String normal(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<String> spaced;

    /**
     * The terms' own names - labels, not synonyms - that hold a space, in lower case, longest
     * first: the names an axiom must quote, so a parser can put back quotes a model left out.
     */
    public List<String> spacedNames() {
        if (spaced == null) {
            List<String> names = new ArrayList<String>();
            for (Map.Entry<String, Set<IRI>> entry : index.entrySet()) {
                if (entry.getKey().indexOf(' ') >= 0 && !entry.getValue().isEmpty()) {
                    names.add(entry.getKey());
                }
            }
            Collections.sort(names, (a, b) -> a.length() != b.length()
                    ? b.length() - a.length() : a.compareTo(b));
            spaced = Collections.unmodifiableList(names);
        }
        return spaced;
    }

    /**
     * The terms whose own names - labels and short names, not synonyms - the text holds as whole
     * words, case ignored, in the order they first appear (1.141.0, for "Write as OWL…"). The
     * longest name is found first, so a name inside a longer one does not count; a name two terms
     * share is left out, and so is one of a single letter. Only what an axiom can name counts: an
     * annotation property, or a labelled IRI that is no term, neither becomes one nor makes a
     * term's name shared (1.143.0, review 1.141.0 I4 - in MWO, dcterms:license hid the class).
     */
    public List<IRI> namedIn(String text) {
        List<IRI> named = new ArrayList<IRI>();
        if (text == null || text.trim().isEmpty()) {
            return named;
        }
        String lower = normal(text);
        List<String> names = new ArrayList<String>();
        for (String key : index.keySet()) {
            if (key.length() >= 2 && !key.contains("://") && lower.contains(key)) {
                names.add(key);
            }
        }
        Collections.sort(names, (a, b) -> a.length() != b.length() ? b.length() - a.length()
                : a.compareTo(b));
        boolean[] covered = new boolean[text.length()];
        java.util.TreeMap<Integer, IRI> found = new java.util.TreeMap<Integer, IRI>();
        for (String name : names) {
            StringBuilder pattern = new StringBuilder("(?<![\\p{L}\\p{N}_])");
            String[] words = name.split(" ");
            for (int w = 0; w < words.length; w++) {
                pattern.append(w == 0 ? "" : "\\s+").append(java.util.regex.Pattern.quote(
                        words[w]));
            }
            pattern.append("(?![\\p{L}\\p{N}_])");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile(pattern.toString(),
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE).matcher(text);
            Set<IRI> nameable = new LinkedHashSet<IRI>(index.get(name));
            nameable.retainAll(terms);
            while (match.find()) {
                boolean free = true;
                for (int i = match.start(); i < match.end() && free; i++) {
                    free = !covered[i];
                }
                if (!free) {
                    continue;
                }
                for (int i = match.start(); i < match.end(); i++) {
                    covered[i] = true;
                }
                if (nameable.size() == 1) {
                    found.put(match.start(), nameable.iterator().next());
                }
            }
        }
        for (IRI iri : found.values()) {
            if (!named.contains(iri)) {
                named.add(iri);
            }
        }
        return named;
    }

    /** Every term the name is exactly a name of; empty for none. */
    public Set<IRI> candidates(String name) {
        Set<IRI> found = lookup(name);
        return found == null ? Collections.<IRI>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<IRI>(found));
    }

    public Resolution resolve(String name) {
        Set<IRI> found = lookup(name);
        if (found == null || found.isEmpty()) {
            return new Resolution(name, null, name, Collections.<String>emptyList());
        }
        List<String> labels = new ArrayList<String>();
        for (IRI iri : found) {
            labels.add(labelOf(iri));
        }
        Collections.sort(labels);
        if (found.size() > 1) {
            return new Resolution(name, null, name, labels);
        }
        IRI iri = found.iterator().next();
        return new Resolution(name, iri, labelOf(iri), labels);
    }

    private String labelOf(IRI iri) {
        Set<OWLEntity> entities = ontology.getEntitiesInSignature(iri, Imports.INCLUDED);
        return entities.isEmpty() ? DisplayLabels.shortNameOf(iri)
                : DisplayLabels.forEntity(ontology, entities.iterator().next());
    }
}
