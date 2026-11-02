package de.fizkarlsruhe.ise.ontoboard.robot;

import org.semanticweb.owlapi.model.IRI;

/**
 * A competency question turned into a SPARQL test, from a template and the terms it is about.
 *
 * <p>Asked for by a student and by a newcomer to ODK: the guide's step "Turn your questions into
 * tests" asked for a SPARQL query per question, and that is where people who do not write SPARQL
 * stopped. Most questions a first ontology asks are one of a few shapes - every pizza has a base,
 * nothing is both vegetarian and meaty, every class has a label - so each shape is a template here,
 * filled with the IRIs of the terms picked from lists.
 *
 * <p>Every query follows the build's rule for a test: it returns the terms that <em>break</em> the
 * rule, so an empty answer passes, and its first line is {@code # } and the question in words,
 * which is what the build reports when the test fails. Pure text; no OWL API, no Swing.
 */
public final class QuestionTests {

    private static final String PREFIXES =
            "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "PREFIX owl: <http://www.w3.org/2002/07/owl#>\n";

    private QuestionTests() {
    }

    /** The shapes offered, in the order a first ontology tends to need them. */
    public enum Template {
        /** Every kind of X says it has some P - read from the classes. */
        EVERY_SUBCLASS_HAS_SOME("Every kind of <class> has some <property>", true, true, false),
        /** Every individual of X has a value for P - read from the data. */
        EVERY_INDIVIDUAL_HAS("Every <class> in the data has a <property>", true, true, false),
        /** No individual is both an A and a B. */
        NOTHING_IS_BOTH("Nothing is both a <class> and a <second class>", true, false, true),
        /** Every kind of X, and every X in the data, has a label. */
        EVERY_ONE_HAS_A_LABEL("Every <class>, and every kind of it, has a label", true, false,
                false),
        /** No individual of X has more than N values of P. */
        NO_MORE_THAN("No <class> has more than N <property>", true, true, false);

        private final String words;
        private final boolean needsClass;
        private final boolean needsProperty;
        private final boolean needsSecondClass;

        Template(String words, boolean needsClass, boolean needsProperty,
                boolean needsSecondClass) {
            this.words = words;
            this.needsClass = needsClass;
            this.needsProperty = needsProperty;
            this.needsSecondClass = needsSecondClass;
        }

        public String getWords() {
            return words;
        }

        public boolean needsProperty() {
            return needsProperty;
        }

        public boolean needsSecondClass() {
            return needsSecondClass;
        }

        public boolean needsClass() {
            return needsClass;
        }

        @Override
        public String toString() {
            return words;
        }
    }

    /**
     * The test for a question.
     *
     * @param question the question in words, which becomes the first line
     * @param property null when the template needs none
     * @param second null when the template needs none
     * @param most the N of {@link Template#NO_MORE_THAN}; ignored otherwise
     */
    public static String query(Template template, String question, IRI cls, IRI property,
            IRI second, int most) {
        String head = "# " + oneLine(question) + "\n" + PREFIXES;
        switch (template) {
            case EVERY_SUBCLASS_HAS_SOME:
                // The class itself and every class under it; a restriction stated on a class
                // above counts, because the class inherits it.
                return head + "SELECT DISTINCT ?term ?label WHERE {\n"
                        + "  ?term rdfs:subClassOf* <" + cls + "> .\n"
                        + "  FILTER(isIRI(?term))\n"
                        + "  FILTER NOT EXISTS {\n"
                        + "    ?term rdfs:subClassOf* ?above .\n"
                        + "    ?above rdfs:subClassOf|owl:equivalentClass ?r .\n"
                        + "    ?r owl:onProperty <" + property + "> .\n"
                        + "  }\n"
                        + "  OPTIONAL { ?term rdfs:label ?label }\n"
                        + "}\n";
            case EVERY_INDIVIDUAL_HAS:
                return head + "SELECT DISTINCT ?term ?label WHERE {\n"
                        + "  ?term rdf:type/rdfs:subClassOf* <" + cls + "> .\n"
                        + "  FILTER NOT EXISTS { ?term <" + property + "> ?value }\n"
                        + "  OPTIONAL { ?term rdfs:label ?label }\n"
                        + "}\n";
            case NOTHING_IS_BOTH:
                return head + "SELECT DISTINCT ?term ?label WHERE {\n"
                        + "  ?term rdf:type/rdfs:subClassOf* <" + cls + "> .\n"
                        + "  ?term rdf:type/rdfs:subClassOf* <" + second + "> .\n"
                        + "  OPTIONAL { ?term rdfs:label ?label }\n"
                        + "}\n";
            case EVERY_ONE_HAS_A_LABEL:
                return head + "SELECT DISTINCT ?term WHERE {\n"
                        + "  { ?term rdfs:subClassOf* <" + cls + "> . FILTER(isIRI(?term)) }\n"
                        + "  UNION { ?term rdf:type/rdfs:subClassOf* <" + cls + "> }\n"
                        + "  FILTER NOT EXISTS { ?term rdfs:label ?label }\n"
                        + "}\n";
            case NO_MORE_THAN:
            default:
                return head + "SELECT ?term (COUNT(DISTINCT ?value) AS ?values) WHERE {\n"
                        + "  ?term rdf:type/rdfs:subClassOf* <" + cls + "> .\n"
                        + "  ?term <" + property + "> ?value .\n"
                        + "}\n"
                        + "GROUP BY ?term\n"
                        + "HAVING (COUNT(DISTINCT ?value) > " + Math.max(0, most) + ")\n";
        }
    }

    /** A file name for the test from the question: {@code every_pizza_has_a_base.rq}. */
    public static String fileNameFor(String question) {
        String name = oneLine(question).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (name.length() > 60) {
            name = name.substring(0, 60).replaceAll("_+$", "");
        }
        return (name.isEmpty() ? "question" : name) + ".rq";
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}
