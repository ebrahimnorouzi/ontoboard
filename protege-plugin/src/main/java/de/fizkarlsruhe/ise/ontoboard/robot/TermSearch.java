package de.fizkarlsruhe.ise.ontoboard.robot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * The terms of an ontology, searchable by what they are called.
 *
 * <p>Asked for: "what if the user doesn't know the terms? They need the source ontology, and from
 * the labels there choose - load the ontology from a URL, search through the labels, select the
 * terms they want, and they are added." Import terms asked for IRIs, which is to say it asked the
 * user to have found the terms somewhere else first.
 *
 * <p>A term is found by its label, by any of its synonyms, or by its IRI's local name - what people
 * type when they know the term is "the one with 'temperature' in it". Ranked so that what they
 * most likely mean comes first: the label exactly, then a label starting with it, then a label
 * containing it, then a synonym, then the IRI.
 */
public final class TermSearch {

    /** Annotation properties a synonym is commonly given with. */
    private static final Set<String> SYNONYMS = new LinkedHashSet<String>(java.util.Arrays.asList(
            "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasBroadSynonym",
            "http://www.geneontology.org/formats/oboInOwl#hasNarrowSynonym",
            "http://www.w3.org/2004/02/skos/core#prefLabel",
            "http://www.w3.org/2004/02/skos/core#altLabel"));

    /** Annotation properties a definition is commonly given with, the first found winning. */
    private static final List<String> DEFINITIONS = java.util.Arrays.asList(
            "http://purl.obolibrary.org/obo/IAO_0000115",
            "http://www.w3.org/2004/02/skos/core#definition",
            "http://www.w3.org/2000/01/rdf-schema#comment");

    /** One term: what it is called, what kind of thing it is, and what it means. */
    public static final class Term {
        private final String iri;
        private final String label;
        private final String kind;
        private final String definition;
        private final List<String> synonyms;
        private final boolean obsolete;
        private final boolean described;
        private String shortIri;

        Term(String iri, String label, String kind, String definition, List<String> synonyms,
                boolean obsolete, boolean described) {
            this.iri = iri;
            this.label = label;
            this.kind = kind;
            this.definition = definition;
            this.synonyms = synonyms;
            this.obsolete = obsolete;
            this.described = described;
            this.shortIri = localName(iri);
        }

        public String getIri() {
            return iri;
        }

        /** Its label, or its IRI's local name when it has none. */
        public String getLabel() {
            return label;
        }

        /** "Class", "Object property", "Data property" or "Individual". */
        public String getKind() {
            return kind;
        }

        /** Its definition, or "" when it has none. */
        public String getDefinition() {
            return definition;
        }

        public List<String> getSynonyms() {
            return synonyms;
        }

        /** Marked {@code owl:deprecated}: usually not what anybody means to import. */
        public boolean isObsolete() {
            return obsolete;
        }

        /**
         * Declared or annotated in the ontology, rather than only used in one of its axioms - as
         * FOAF uses schema.org's Person, which it says nothing else about.
         */
        public boolean isDescribed() {
            return described;
        }

        /**
         * The local name for a term in the ontology's own namespace, and otherwise enough of the
         * IRI to tell where it is from - {@code schema.org/Person} - so three terms called Person
         * are not three identical rows.
         */
        public String getShortIri() {
            return shortIri;
        }
    }

    private final List<Term> terms;

    private TermSearch(List<Term> terms) {
        this.terms = terms;
    }

    /** Every class, property and individual of {@code source}, its imports included. */
    public static TermSearch of(OWLOntology source) {
        List<Term> terms = new ArrayList<Term>();
        Set<IRI> seen = new java.util.HashSet<IRI>();
        Map<String, Integer> namespaces = new HashMap<String, Integer>();
        for (OWLEntity entity : source.getSignature(Imports.INCLUDED)) {
            String kind = kindOf(entity);
            if (kind == null || entity.isBuiltIn() || !seen.add(entity.getIRI())) {
                continue;
            }
            Term term = termFor(source, entity, kind);
            terms.add(term);
            if (term.described) {
                String namespace = namespaceOf(term.iri);
                Integer count = namespaces.get(namespace);
                namespaces.put(namespace, count == null ? 1 : count + 1);
            }
        }
        String home = null;
        for (Map.Entry<String, Integer> namespace : namespaces.entrySet()) {
            if (home == null || namespace.getValue() > namespaces.get(home)) {
                home = namespace.getKey();
            }
        }
        for (Term term : terms) {
            if (home != null && !home.equals(namespaceOf(term.iri))) {
                term.shortIri = withoutScheme(term.iri);
            }
        }
        // What the ontology describes before what it only mentions, each alphabetically: the
        // order an empty search lists them in, and the order within each kind of match.
        Collections.sort(terms, new Comparator<Term>() {
            @Override
            public int compare(Term one, Term other) {
                if (one.described != other.described) {
                    return one.described ? -1 : 1;
                }
                return one.label.compareToIgnoreCase(other.label);
            }
        });
        return new TermSearch(terms);
    }

    /** How many terms there are to search. */
    public int size() {
        return terms.size();
    }

    /**
     * The terms {@code query} finds, best first, at most {@code limit} of them. An empty query
     * lists terms alphabetically. Obsolete terms only when asked for.
     */
    public List<Term> find(String query, int limit, boolean withObsolete) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<List<Term>> ranks = new ArrayList<List<Term>>();
        for (int rank = 0; rank < 5; rank++) {
            ranks.add(new ArrayList<Term>());
        }
        for (Term term : terms) {
            if (term.obsolete && !withObsolete) {
                continue;
            }
            int rank = rankOf(term, wanted);
            if (rank >= 0) {
                ranks.get(rank).add(term);
            }
        }
        List<Term> found = new ArrayList<Term>();
        for (List<Term> rank : ranks) {
            for (Term term : rank) {
                if (found.size() >= limit) {
                    return found;
                }
                found.add(term);
            }
        }
        return found;
    }

    /** How many terms {@code query} finds, without the limit. */
    public int count(String query, boolean withObsolete) {
        return find(query, Integer.MAX_VALUE, withObsolete).size();
    }

    /** 0 for the label exactly, 1 for a label starting with it, ... -1 for no match at all. */
    private static int rankOf(Term term, String wanted) {
        if (wanted.isEmpty()) {
            return 0;
        }
        String label = term.label.toLowerCase(Locale.ROOT);
        if (label.equals(wanted)) {
            return 0;
        }
        if (label.startsWith(wanted)) {
            return 1;
        }
        if (label.contains(wanted)) {
            return 2;
        }
        for (String synonym : term.synonyms) {
            if (synonym.toLowerCase(Locale.ROOT).contains(wanted)) {
                return 3;
            }
        }
        return term.iri.toLowerCase(Locale.ROOT).contains(wanted) ? 4 : -1;
    }

    private static String kindOf(OWLEntity entity) {
        if (entity.isOWLClass()) {
            return "Class";
        }
        if (entity.isOWLObjectProperty()) {
            return "Object property";
        }
        if (entity.isOWLDataProperty()) {
            return "Data property";
        }
        if (entity.isOWLNamedIndividual()) {
            return "Individual";
        }
        return null;
    }

    private static Term termFor(OWLOntology source, OWLEntity entity, String kind) {
        String label = null;
        String englishLabel = null;
        String definition = "";
        List<String> synonyms = new ArrayList<String>();
        boolean obsolete = false;
        boolean described = false;
        int definitionRank = Integer.MAX_VALUE;
        for (OWLOntology part : source.getImportsClosure()) {
            described |= !part.getDeclarationAxioms(entity).isEmpty();
            for (OWLAnnotationAssertionAxiom axiom
                    : part.getAnnotationAssertionAxioms(entity.getIRI())) {
                described = true;
                if (!(axiom.getValue() instanceof OWLLiteral)) {
                    continue;
                }
                OWLLiteral literal = (OWLLiteral) axiom.getValue();
                IRI property = axiom.getProperty().getIRI();
                if (OWLRDFVocabulary.RDFS_LABEL.getIRI().equals(property)) {
                    if (label == null) {
                        label = literal.getLiteral();
                    }
                    if (englishLabel == null && (literal.getLang().isEmpty()
                            || literal.getLang().toLowerCase(Locale.ROOT).startsWith("en"))) {
                        englishLabel = literal.getLiteral();
                    }
                } else if (SYNONYMS.contains(property.toString())) {
                    synonyms.add(literal.getLiteral());
                } else if (DEFINITIONS.contains(property.toString())
                        && DEFINITIONS.indexOf(property.toString()) < definitionRank) {
                    definition = literal.getLiteral();
                    definitionRank = DEFINITIONS.indexOf(property.toString());
                } else if (OWLRDFVocabulary.OWL_DEPRECATED.getIRI().equals(property)
                        && literal.parseBoolean()) {
                    obsolete = true;
                }
            }
        }
        String shown = englishLabel != null ? englishLabel
                : label != null ? label : localName(entity.getIRI().toString());
        return new Term(entity.getIRI().toString(), shown, kind, definition,
                Collections.unmodifiableList(synonyms), obsolete, described);
    }

    /** The IRI up to and including its last {@code #} or {@code /}. */
    static String namespaceOf(String iri) {
        return iri.substring(0, Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1);
    }

    /** {@code https://www.example.org/x#Y} as {@code example.org/x#Y}. */
    static String withoutScheme(String iri) {
        String shown = iri.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        return shown.startsWith("www.") ? shown.substring(4) : shown;
    }

    /** The part of an IRI after its last {@code #} or {@code /}. */
    public static String localName(String iri) {
        int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut < iri.length() - 1 ? iri.substring(cut + 1) : iri;
    }
}
