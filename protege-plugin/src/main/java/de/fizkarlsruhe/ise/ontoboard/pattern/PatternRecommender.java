package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Which bundled patterns are worth looking at for the ontology that is open.
 *
 * <p>123 patterns is too many to browse when you want one. The question somebody actually has is
 * "is there a pattern for what I am modelling", and the ontology in front of them is the best
 * available evidence of what that is.
 *
 * <p><b>Two signals, measured before being chosen.</b> A pattern is relevant when the ontology
 * already speaks its vocabulary - either exactly, by using the same IRIs, or loosely, by having
 * a term that goes by the same name. Measured on a real BFO-based project against the whole
 * library: <em>zero</em> patterns share an IRI, because the ODP collection and the OBO world
 * have no vocabulary in common at all, while 48 share two or more names. So both signals are
 * needed - the exact one for a pattern harvested from an ontology the project actually imports,
 * the loose one for everything else.
 */
public final class PatternRecommender {

    /**
     * Namespaces every ontology contains, so sharing one of their terms means nothing.
     *
     * <p>Not a detail. Without this the recommender looked like it worked: 43 of 123 patterns
     * "shared an IRI" with a real project, and every single one of those matches was
     * {@code owl:Thing}, which 42 patterns declare. A signal that fires on everything ranks
     * nothing.
     */
    static final String[] BUILTIN_NAMESPACES = {
        "http://www.w3.org/2002/07/owl#",
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "http://www.w3.org/2000/01/rdf-schema#",
        "http://www.w3.org/2001/XMLSchema#",
        "http://www.w3.org/2004/02/skos/core#",
    };

    /** At least this many shared terms before a pattern is worth offering. */
    static final int ENOUGH_SHARED = 2;

    /**
     * A word match counts for this much of an IRI match.
     *
     * <p>Sharing an IRI means the ontology uses that very term. Sharing a name means something
     * in it is called the same thing, which is good evidence and not the same thing: half the
     * library matches on "object" or "entity".
     */
    static final double WORD_WEIGHT = 0.5;

    /** What an ontology's vocabulary looks like for matching. */
    public static final class Vocabulary {
        private final Set<String> iris;
        private final Set<String> words;
        /**
         * What the ontology calls each term it has.
         *
         * <p>So the evidence reads. An OBO identifier is a number, and an explanation that
         * says "BFO_0000004, BFO_0000015, BFO_0000023" tells a modeller nothing they can
         * check; "independent continuant, process, role" is the same fact in their own words.
         */
        private final java.util.Map<String, String> labels;

        Vocabulary(Set<String> iris, Set<String> words, java.util.Map<String, String> labels) {
            this.iris = Collections.unmodifiableSet(iris);
            this.words = Collections.unmodifiableSet(words);
            this.labels = Collections.unmodifiableMap(labels);
        }

        /** The ontology's own name for that IRI, or null. */
        String labelFor(String iri) {
            return labels.get(iri);
        }

        public int size() {
            return iris.size();
        }

        public boolean isEmpty() {
            return iris.isEmpty() && words.isEmpty();
        }
    }

    /** One suggestion, and the evidence for it. */
    public static final class Recommendation {
        private final DesignPattern pattern;
        private final double score;
        private final List<String> sharedIris;
        private final List<String> sharedWords;
        private final int terms;

        Recommendation(DesignPattern pattern, double score, List<String> sharedIris,
                List<String> sharedWords, int terms) {
            this.pattern = pattern;
            this.score = score;
            this.sharedIris = Collections.unmodifiableList(sharedIris);
            this.sharedWords = Collections.unmodifiableList(sharedWords);
            this.terms = terms;
        }

        public DesignPattern getPattern() {
            return pattern;
        }

        /** Between 0 and 1: how much of the pattern's vocabulary the ontology already has. */
        public double getScore() {
            return score;
        }

        /** Terms the ontology uses by the very same IRI. */
        public List<String> getSharedIris() {
            return sharedIris;
        }

        /** Terms something in the ontology shares a name with. */
        public List<String> getSharedWords() {
            return sharedWords;
        }

        public int getSharedCount() {
            return sharedIris.size() + sharedWords.size();
        }

        /**
         * Why this was suggested, in words.
         *
         * <p>The most important thing here. A ranked list with no reason is a magic box, and a
         * modeller deciding whether to adopt somebody else's pattern needs the evidence rather
         * than a number - "3 of its 4 terms are already in your ontology: Agent, Role, hasRole"
         * is checkable, and 0.75 is not.
         */
        public String explain() {
            StringBuilder why = new StringBuilder();
            why.append(getSharedCount()).append(" of its ").append(terms)
               .append(terms == 1 ? " term is" : " terms are").append(" already in your ontology");
            // Capped across both lists, not just the words. Pointed at the project these
            // patterns were harvested from, every term matches - one explanation listed
            // thirty-six OBO identifiers, which is a wall rather than a reason.
            List<String> shown = new ArrayList<String>();
            for (String name : sharedIris) {
                if (shown.size() >= MOST_SHOWN) {
                    break;
                }
                shown.add(name);
            }
            for (String word : sharedWords) {
                if (shown.size() >= MOST_SHOWN) {
                    break;
                }
                shown.add(word);
            }
            if (!shown.isEmpty()) {
                why.append(": ").append(String.join(", ", shown));
                if (getSharedCount() > shown.size()) {
                    why.append(" and ").append(getSharedCount() - shown.size()).append(" more");
                }
            }
            if (!sharedIris.isEmpty()) {
                why.append(". ").append(sharedIris.size())
                   .append(sharedIris.size() == 1 ? " is the same IRI" : " are the same IRIs")
                   .append(", not just the same name.");
            }
            return why.toString();
        }

        @Override
        public String toString() {
            return pattern.getId() + " " + String.format(Locale.ROOT, "%.2f", score);
        }
    }

    /** Beyond this the explanation stops being a sentence. */
    static final int MOST_SHOWN = 5;

    private PatternRecommender() {
    }

    /**
     * The ontology's vocabulary, over its imports closure.
     *
     * <p>The closure rather than the file: a project's own edit file holds very little, and what
     * it imports is most of what it speaks. Reading only the edit file would make every project
     * look like it had nothing in common with anything.
     */
    public static Vocabulary vocabularyOf(OWLOntology ontology) {
        Set<String> iris = new HashSet<String>();
        Set<String> words = new HashSet<String>();
        java.util.Map<String, String> labels = new java.util.HashMap<String, String>();
        if (ontology == null) {
            return new Vocabulary(iris, words, labels);
        }
        for (OWLEntity entity : ontology.getSignature(Imports.INCLUDED)) {
            if (isBuiltin(entity.getIRI())) {
                continue;
            }
            iris.add(entity.getIRI().toString());
            words.add(normalise(localNameOf(entity.getIRI())));
        }
        // Labels as well as local names. An OBO identifier is a number - BFO_0000015 - so the
        // only readable name it has is its label, and a pattern that calls the same thing
        // "Process" would otherwise match nothing.
        for (OWLOntology one : ontology.getImportsClosure()) {
            for (OWLAnnotationAssertionAxiom axiom
                    : one.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                if (axiom.getProperty().isLabel() && axiom.getValue() instanceof OWLLiteral) {
                    String label = ((OWLLiteral) axiom.getValue()).getLiteral();
                    words.add(normalise(label));
                    if (axiom.getSubject() instanceof IRI) {
                        labels.put(axiom.getSubject().toString(), label);
                    }
                }
            }
        }
        words.remove("");
        return new Vocabulary(iris, words, labels);
    }

    /**
     * The patterns worth offering, best first.
     *
     * <p>Scored by how much of the <em>pattern</em> the ontology covers, not by how many terms
     * matched. Measured on a real project, ranking by match count puts a 195-term pattern first
     * on the strength of twenty-five generic words, while coverage puts Agent Role, Participation
     * and Componency at the top - which is what a BFO project about processes and roles should
     * be offered.
     *
     * @param most how many to return; the rest are not worth scrolling
     */
    public static List<Recommendation> forOntology(Vocabulary vocabulary,
            List<DesignPattern> patterns, int most) {
        List<Recommendation> found = new ArrayList<Recommendation>();
        if (vocabulary == null || vocabulary.isEmpty() || patterns == null) {
            return Collections.unmodifiableList(found);
        }
        for (DesignPattern pattern : patterns) {
            if (pattern.isDuplicate()) {
                // Ten of the library are another entry under a second name, and they match
                // identically - so without this, Agent Role and Agentrole take two of the ten
                // places on offer and say the same thing twice.
                continue;
            }
            Recommendation one = score(vocabulary, pattern);
            if (one != null) {
                found.add(one);
            }
        }
        Collections.sort(found, new Comparator<Recommendation>() {
            @Override
            public int compare(Recommendation left, Recommendation right) {
                int byScore = Double.compare(right.score, left.score);
                if (byScore != 0) {
                    return byScore;
                }
                // Then by how much evidence there is, so two patterns at the same coverage are
                // ordered by the one with more behind it; then by id, so the list never
                // reshuffles between openings.
                int byEvidence = right.getSharedCount() - left.getSharedCount();
                return byEvidence != 0 ? byEvidence
                        : left.pattern.getId().compareTo(right.pattern.getId());
            }
        });
        return Collections.unmodifiableList(
                found.subList(0, Math.min(most < 0 ? 0 : most, found.size())));
    }

    /**
     * One pattern against one vocabulary, or null when there is too little in common.
     *
     * <p>Reads the pattern's terms from the index, not from its file. Measured: parsing all the
     * patterns to answer one question took 24 seconds, which is a dialog that appears to have
     * hung. The index is generated from the files by {@link PatternIndex#buildFrom} and a test
     * rebuilds it and compares, so the stored list cannot quietly disagree with them.
     */
    static Recommendation score(Vocabulary vocabulary, DesignPattern pattern) {
        List<String> terms = pattern.getTermIris();
        if (terms.isEmpty()) {
            return null;
        }
        List<String> sharedIris = new ArrayList<String>();
        List<String> sharedWords = new ArrayList<String>();
        // Names, not IRIs, so the evidence does not read "Object, Object" when a pattern
        // declares two terms from different namespaces that are both called Object.
        Set<String> named = new HashSet<String>();
        int counted = 0;
        for (String term : terms) {
            IRI iri = IRI.create(term);
            if (isBuiltin(iri)) {
                continue;
            }
            counted++;
            String name = localNameOf(iri);
            boolean exact = vocabulary.iris.contains(term);
            if (!exact && !vocabulary.words.contains(normalise(name))) {
                continue;
            }
            if (!named.add(normalise(name))) {
                continue;
            }
            String readable = vocabulary.labelFor(term);
            if (exact) {
                sharedIris.add(readable == null ? name : readable);
            } else {
                sharedWords.add(name);
            }
        }
        int shared = sharedIris.size() + sharedWords.size();
        if (counted == 0 || shared < ENOUGH_SHARED) {
            return null;
        }
        double score = (sharedIris.size() + WORD_WEIGHT * sharedWords.size()) / counted;
        return new Recommendation(pattern, score, sharedIris, sharedWords, counted);
    }

    static boolean isBuiltin(IRI iri) {
        String text = iri == null ? "" : iri.toString();
        for (String namespace : BUILTIN_NAMESPACES) {
            if (text.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    static String localNameOf(IRI iri) {
        String text = iri.toString();
        int cut = Math.max(text.lastIndexOf('/'), text.lastIndexOf('#'));
        return cut < 0 ? text : text.substring(cut + 1);
    }

    /** Case, punctuation and spacing removed, so "hasPart", "has_part" and "has part" match. */
    static String normalise(String text) {
        return text == null ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
