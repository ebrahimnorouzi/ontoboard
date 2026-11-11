package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNaryClassAxiom;
import org.semanticweb.owlapi.model.OWLNaryIndividualAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;
import org.semanticweb.owlapi.vocab.OWL2Datatype;

/**
 * One axiom in Manchester syntax, read as the assistant writes it (1.139.0): names are labels,
 * short names or IRIs of the ontology and its imports, quoted when they need to be; datatypes
 * are {@code xsd:}/{@code rdf:} names.
 *
 * <p>The OWL API's own parser does the reading; OntoBoard supplies the names. A name that
 * resolves to nothing is reported, and nothing is ever declared: a model that invents a class
 * gets a card that cannot be applied, not a new class in the ontology. A name two terms share is
 * reported with both.
 */
public final class AxiomParser {

    /** An axiom, or why there is none. */
    public static final class Result {
        private final OWLAxiom axiom;
        private final String problem;

        Result(OWLAxiom axiom, String problem) {
            this.axiom = axiom;
            this.problem = problem;
        }

        /** Null when it could not be read. */
        public OWLAxiom getAxiom() {
            return axiom;
        }

        /** Why it could not be read; null when it could. */
        public String getProblem() {
            return problem;
        }
    }

    private final OWLOntology ontology;
    private final OWLDataFactory factory;
    private NameResolver names;

    public AxiomParser(OWLOntology ontology) {
        this.ontology = ontology;
        this.factory = ontology.getOWLOntologyManager().getOWLDataFactory();
    }

    /**
     * The axiom as written; if it cannot be read so, with the quotes a model left out around the
     * ontology's names put back. Text valid as written is never changed (review 1.140.0 M1: a
     * label "Naples, Italy" turned a list of two individuals into one).
     */
    public Result parse(String text) {
        String written = text == null ? "" : text.trim();
        Result read = parseAsWritten(written);
        if (read.getAxiom() != null) {
            return read;
        }
        String quoted = quoteNames(written);
        if (quoted.equals(written)) {
            return read;
        }
        // When both fail, the quoted reading's problem: its unknown names are the ones really
        // missing, where the as-written one blamed a word of a label that exists (1.143.0,
        // review 1.141.0 I3).
        return parseAsWritten(quoted);
    }

    private Result parseAsWritten(String axiomText) {
        if (axiomText.isEmpty()) {
            return new Result(null, "It names no axiom.");
        }
        final Set<String> unknown = new LinkedHashSet<String>();
        final Set<String> ambiguous = new LinkedHashSet<String>();
        Checker checker = new Checker(unknown, ambiguous);
        try {
            // The OWL API 4.5 parser reads class axioms only (SubClassOf, EquivalentTo,
            // DisjointWith); the other shapes the assistant writes are read here, with the
            // parser doing their class expressions and data ranges.
            OWLAxiom axiom = new Shapes(checker).read(axiomText);
            if (axiom == null) {
                axiom = parser(checker, axiomText).parseAxiom();
            }
            if (namesOneTermTwice(axiom)) {
                throw new Twice();
            }
            return new Result(axiom, null);
        } catch (Twice twice) {
            return new Result(null, TWICE);
        } catch (RuntimeException failed) {
            if (!ambiguous.isEmpty()) {
                return new Result(null, String.join("; ", ambiguous) + ".");
            }
            Set<String> missing = new LinkedHashSet<String>(unknown);
            missing.removeAll(KNOWN_WORDS);
            if (!missing.isEmpty()) {
                return new Result(null, (missing.size() == 1 ? "This name is" : "These names are")
                        + " not in this ontology: " + String.join(", ", missing)
                        + ". OntoBoard never declares a new term from a suggestion.");
            }
            String said = failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage().split("\\r?\\n")[0];
            if (said.length() > 200) {
                said = said.substring(0, 200) + "…";
            }
            return new Result(null, "It is not an axiom in Manchester syntax that OntoBoard can"
                    + " read: " + said);
        }
    }

    /**
     * An IRI in angle brackets has no space inside; "[< 5]" opens a facet, not an IRI (review
     * 1.140.0 M1: nothing after a facet was quoted).
     */
    private static boolean isIri(String text, int at) {
        for (int i = at + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '>') {
                return i > at + 1;
            }
            if (Character.isWhitespace(c)) {
                return false;
            }
        }
        return false;
    }

    /** Words that join expressions: a label holding one is never quoted for a model. */
    private static final java.util.regex.Pattern JOINS = java.util.regex.Pattern.compile(
            "(^| )(and|or|that|not)( |$)");

    /**
     * The text with the ontology's own names that hold a space put in single quotes where a
     * model left the quotes out (1.140.0, measured: qwen2.5:7b wrote "has value Range:
     * xsd:float"). Only outside quotes, IRIs and strings, longest name first, and never a name
     * holding "and", "or", "that" or "not", which unquoted can already mean something else.
     * The card shows the axiom as it was read.
     */
    private String quoteNames(String text) {
        if (text.indexOf(' ') < 0) {
            return text;
        }
        boolean[] taken = new boolean[text.length()];
        char open = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (open != 0) {
                taken[i] = true;
                if (c == '\\' && open != '>' && i + 1 < text.length()) {
                    taken[++i] = true;
                } else if (c == open) {
                    open = 0;
                }
            } else if (c == '\'' || c == '"' || (c == '<' && isIri(text, i))) {
                taken[i] = true;
                open = c == '<' ? '>' : c;
            }
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        List<int[]> spans = new java.util.ArrayList<int[]>();
        for (String name : names().spacedNames()) {
            // A comma separates the items of a list: a label holding one is never quoted.
            if (!lower.contains(name) || JOINS.matcher(name).find() || name.indexOf(',') >= 0) {
                continue;
            }
            StringBuilder pattern = new StringBuilder("(?<![\\p{L}\\p{N}_])");
            String[] words = name.split(" ");
            for (int w = 0; w < words.length; w++) {
                pattern.append(w == 0 ? "" : "\\s+").append(java.util.regex.Pattern.quote(
                        words[w]));
            }
            pattern.append("(?![\\p{L}\\p{N}_])");
            java.util.regex.Matcher found = java.util.regex.Pattern.compile(pattern.toString(),
                    java.util.regex.Pattern.CASE_INSENSITIVE
                            | java.util.regex.Pattern.UNICODE_CASE).matcher(text);
            while (found.find()) {
                boolean free = true;
                for (int i = found.start(); i < found.end() && free; i++) {
                    free = !taken[i];
                }
                if (free) {
                    for (int i = found.start(); i < found.end(); i++) {
                        taken[i] = true;
                    }
                    spans.add(new int[] {found.start(), found.end()});
                }
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        java.util.Collections.sort(spans, (a, b) -> a[0] - b[0]);
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int[] span : spans) {
            out.append(text, at, span[0]).append('\'')
                    .append(text.substring(span[0], span[1]).replace("'", "\\'")).append('\'');
            at = span[1];
        }
        return out.append(text.substring(at)).toString();
    }

    private static final String TWICE ="It names one term twice, perhaps once by a synonym, so"
            + " it would not say what it reads as. Name each term once, as the context names it.";

    /** One term written twice in a list of terms (review 1.139.0 I1b). */
    private static final class Twice extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * An equivalence or disjointness of one class, or one individual the same as itself. The OWL
     * API rewrites "X DisjointWith X" into "X DisjointWith owl:Thing" and annotates it; a parsed
     * axiom has no annotations otherwise.
     */
    private static boolean namesOneTermTwice(OWLAxiom axiom) {
        if (axiom.isAnnotated()) {
            return true;
        }
        if (axiom instanceof OWLNaryClassAxiom) {
            return ((OWLNaryClassAxiom) axiom).getClassExpressions().size() < 2;
        }
        if (axiom instanceof OWLNaryIndividualAxiom) {
            return ((OWLNaryIndividualAxiom) axiom).getIndividuals().size() < 2;
        }
        return false;
    }

    /**
     * Words the parser offers the checker on its way through a keyword-led clause; they are not
     * names a model invented.
     */
    private static final Set<String> KNOWN_WORDS = new LinkedHashSet<String>(
            java.util.Arrays.asList("SubClassOf", "EquivalentTo", "DisjointWith", "Type", "Types",
                    "Domain", "Range", "Domain:", "Range:", "Facts", "SameAs", "DifferentFrom",
                    "InverseOf", "SubPropertyOf", "Characteristics", "some", "only", "and", "or",
                    "not", "value", "min", "max", "exactly", "that", "inverse", "Self"));

    private ManchesterOWLSyntaxParser parser(Checker checker, String text) {
        ManchesterOWLSyntaxParser parser = OWLManager.createManchesterParser();
        parser.setDefaultOntology(ontology);
        parser.setOWLEntityChecker(checker);
        parser.setStringToParse(text);
        return parser;
    }

    /** The axiom shapes the OWL API's axiom parser does not read, as AssistantRendering writes them. */
    private final class Shapes {
        private final Checker checker;

        Shapes(Checker checker) {
            this.checker = checker;
        }

        /** The axiom, or null when the text is not one of these shapes. */
        OWLAxiom read(String text) {
            java.util.regex.Matcher heading = java.util.regex.Pattern.compile(
                    "^(Functional|InverseFunctional|Transitive|Symmetric|Asymmetric|Reflexive"
                            + "|Irreflexive|DisjointClasses|EquivalentClasses|SameIndividual"
                            + "|DifferentIndividuals):\\s*(.+)$", java.util.regex.Pattern.DOTALL)
                    .matcher(text);
            if (heading.matches()) {
                return headed(heading.group(1), heading.group(2).trim());
            }
            List<String> words = tokens(text, 2);
            if (words.size() < 3) {
                return null;
            }
            String subject = words.get(0);
            String keyword = words.get(1).endsWith(":")
                    ? words.get(1).substring(0, words.get(1).length() - 1) : words.get(1);
            String rest = words.get(2);
            switch (keyword) {
                case "Type":
                case "Types":
                    return factory.getOWLClassAssertionAxiom(expression(rest),
                            individual(subject));
                case "SameAs":
                    return factory.getOWLSameIndividualAxiom(individual(subject),
                            individual(rest));
                case "DifferentFrom":
                    return factory.getOWLDifferentIndividualsAxiom(individual(subject),
                            individual(rest));
                case "Domain":
                    return objectProperty(subject) != null
                            ? factory.getOWLObjectPropertyDomainAxiom(objectProperty(subject),
                                    expression(rest))
                            : factory.getOWLDataPropertyDomainAxiom(dataProperty(subject),
                                    expression(rest));
                case "Range":
                    return objectProperty(subject) != null
                            ? factory.getOWLObjectPropertyRangeAxiom(objectProperty(subject),
                                    expression(rest))
                            : factory.getOWLDataPropertyRangeAxiom(dataProperty(subject),
                                    dataRange(subject, rest));
                case "SubPropertyOf":
                    return objectProperty(subject) != null
                            ? factory.getOWLSubObjectPropertyOfAxiom(objectProperty(subject),
                                    need(objectProperty(rest), rest))
                            : factory.getOWLSubDataPropertyOfAxiom(dataProperty(subject),
                                    need(dataProperty(rest), rest));
                case "InverseOf":
                    return factory.getOWLInverseObjectPropertiesAxiom(
                            need(objectProperty(subject), subject),
                            need(objectProperty(rest), rest));
                default:
                    return assertion(subject, words.get(1), rest);
            }
        }

        /** {@code x p y} or {@code x p "9.50"^^xsd:decimal}; null if p is no property. */
        private OWLAxiom assertion(String subject, String property, String value) {
            OWLObjectProperty object = checker.getOWLObjectProperty(property);
            if (object != null) {
                return factory.getOWLObjectPropertyAssertionAxiom(object, individual(subject),
                        individual(value));
            }
            OWLDataProperty data = checker.getOWLDataProperty(property);
            if (data != null) {
                return factory.getOWLDataPropertyAssertionAxiom(data, individual(subject),
                        literal(value));
            }
            return null;
        }

        private OWLAxiom headed(String heading, String list) {
            List<String> items = items(list);
            switch (heading) {
                case "DisjointClasses":
                case "EquivalentClasses": {
                    Set<org.semanticweb.owlapi.model.OWLClassExpression> classes =
                            new LinkedHashSet<org.semanticweb.owlapi.model.OWLClassExpression>();
                    for (String item : items) {
                        classes.add(expression(item));
                    }
                    if (classes.size() < items.size()) {
                        throw new Twice();
                    }
                    return "DisjointClasses".equals(heading)
                            ? factory.getOWLDisjointClassesAxiom(classes)
                            : factory.getOWLEquivalentClassesAxiom(classes);
                }
                case "SameIndividual":
                case "DifferentIndividuals": {
                    Set<OWLNamedIndividual> individuals = new LinkedHashSet<OWLNamedIndividual>();
                    for (String item : items) {
                        individuals.add(individual(item));
                    }
                    if (individuals.size() < items.size()) {
                        throw new Twice();
                    }
                    return "SameIndividual".equals(heading)
                            ? factory.getOWLSameIndividualAxiom(individuals)
                            : factory.getOWLDifferentIndividualsAxiom(individuals);
                }
                default:
                    return characteristic(heading, list);
            }
        }

        private OWLAxiom characteristic(String heading, String name) {
            OWLObjectProperty object = checker.getOWLObjectProperty(name);
            if ("Functional".equals(heading)) {
                return object != null ? factory.getOWLFunctionalObjectPropertyAxiom(object)
                        : factory.getOWLFunctionalDataPropertyAxiom(need(
                                checker.getOWLDataProperty(name), name));
            }
            object = need(object, name);
            switch (heading) {
                case "InverseFunctional":
                    return factory.getOWLInverseFunctionalObjectPropertyAxiom(object);
                case "Transitive":
                    return factory.getOWLTransitiveObjectPropertyAxiom(object);
                case "Symmetric":
                    return factory.getOWLSymmetricObjectPropertyAxiom(object);
                case "Asymmetric":
                    return factory.getOWLAsymmetricObjectPropertyAxiom(object);
                case "Reflexive":
                    return factory.getOWLReflexiveObjectPropertyAxiom(object);
                default:
                    return factory.getOWLIrreflexiveObjectPropertyAxiom(object);
            }
        }

        private org.semanticweb.owlapi.model.OWLClassExpression expression(String text) {
            return parser(checker, text).parseClassExpression();
        }

        /**
         * The range read as the filler of {@code p some (range)}: the OWL API's data-range reader
         * stops where the range ends and ignores the rest (review 1.139.0 I1a), while its
         * class-expression reader insists on the end of the text.
         */
        private org.semanticweb.owlapi.model.OWLDataRange dataRange(String property,
                String text) {
            org.semanticweb.owlapi.model.OWLClassExpression read = parser(checker,
                    property + " some (" + text + ")").parseClassExpression();
            if (!(read instanceof org.semanticweb.owlapi.model.OWLDataSomeValuesFrom)) {
                throw new IllegalArgumentException("'" + unquote(property) + "' is not a data"
                        + " property with this range");
            }
            return ((org.semanticweb.owlapi.model.OWLDataSomeValuesFrom) read).getFiller();
        }

        private OWLNamedIndividual individual(String name) {
            return need(checker.getOWLIndividual(name), name);
        }

        private OWLObjectProperty objectProperty(String name) {
            return checker.getOWLObjectProperty(name);
        }

        private OWLDataProperty dataProperty(String name) {
            return need(checker.getOWLDataProperty(name), name);
        }

        private <T> T need(T found, String name) {
            if (found == null) {
                throw new IllegalArgumentException("'" + unquote(name) + "' is not a term of the"
                        + " kind this axiom needs");
            }
            return found;
        }

        /** {@code "9.50"^^xsd:decimal}, {@code "text"@en}, {@code "plain"}, 9.5, 3, true. */
        private org.semanticweb.owlapi.model.OWLLiteral literal(String text) {
            java.util.regex.Matcher typed = java.util.regex.Pattern.compile(
                    "^\"((?:[^\"\\\\]|\\\\.)*)\"(?:\\^\\^(\\S+)|@([A-Za-z0-9-]+))?$",
                    java.util.regex.Pattern.DOTALL).matcher(text.trim());
            if (typed.matches()) {
                String lexical = typed.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                if (typed.group(2) != null) {
                    return factory.getOWLLiteral(lexical, need(checker.getOWLDatatype(
                            typed.group(2)), typed.group(2)));
                }
                if (typed.group(3) != null) {
                    return factory.getOWLLiteral(lexical, typed.group(3));
                }
                return factory.getOWLLiteral(lexical);
            }
            String plain = text.trim();
            if (plain.matches("-?\\d+")) {
                return factory.getOWLLiteral(plain, OWL2Datatype.XSD_INTEGER);
            }
            if (plain.matches("-?\\d*\\.\\d+")) {
                return factory.getOWLLiteral(plain, OWL2Datatype.XSD_DECIMAL);
            }
            if (plain.matches("-?\\d*\\.?\\d+([eE]-?\\d+)?[fF]")) {
                return factory.getOWLLiteral(plain.substring(0, plain.length() - 1),
                        OWL2Datatype.XSD_FLOAT);
            }
            if ("true".equals(plain) || "false".equals(plain)) {
                return factory.getOWLLiteral(plain, OWL2Datatype.XSD_BOOLEAN);
            }
            throw new IllegalArgumentException("'" + plain + "' is not a value OntoBoard can"
                    + " read");
        }
    }

    /**
     * The first {@code count} words - a quoted name, a literal with its type, or an IRI in
     * angle brackets is one word - and then the rest as one string.
     */
    static List<String> tokens(String text, int count) {
        List<String> words = new java.util.ArrayList<String>();
        int at = 0;
        int length = text.length();
        while (words.size() < count) {
            while (at < length && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            if (at >= length) {
                return words;
            }
            int start = at;
            char first = text.charAt(at);
            if (first == '\'' || first == '"') {
                at++;
                while (at < length && (text.charAt(at) != first || text.charAt(at - 1) == '\\')) {
                    at++;
                }
                at++;
            } else if (first == '<') {
                while (at < length && text.charAt(at) != '>') {
                    at++;
                }
                at++;
            }
            while (at < length && !Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            words.add(text.substring(start, Math.min(at, length)));
        }
        String rest = text.substring(Math.min(at, length)).trim();
        if (!rest.isEmpty()) {
            words.add(rest);
        }
        return words;
    }

    /** A comma-separated list, commas inside quotes or brackets kept. */
    static List<String> items(String list) {
        List<String> items = new java.util.ArrayList<String>();
        int depth = 0;
        char quote = 0;
        StringBuilder item = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (quote != 0) {
                if (c == quote && list.charAt(i - 1) != '\\') {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                items.add(item.toString().trim());
                item.setLength(0);
                continue;
            }
            item.append(c);
        }
        if (item.toString().trim().length() > 0) {
            items.add(item.toString().trim());
        }
        return items;
    }

    private NameResolver names() {
        if (names == null) {
            names = new NameResolver(ontology);
        }
        return names;
    }

    /** OntoBoard's names, for the parser. */
    private final class Checker implements OWLEntityChecker {
        private final Set<String> unknown;
        private final Set<String> ambiguous;

        Checker(Set<String> unknown, Set<String> ambiguous) {
            this.unknown = unknown;
            this.ambiguous = ambiguous;
        }

        /** The term the name stands for, if it is of the wanted kind; null otherwise. */
        private IRI resolve(String token, EntityKind kind) {
            String name = unquote(token);
            if (name.isEmpty()) {
                return null;
            }
            if (name.startsWith("<") && name.endsWith(">")) {
                IRI iri = IRI.create(name.substring(1, name.length() - 1));
                return kind.isIn(ontology, iri) ? iri : null;
            }
            Set<IRI> candidates = names().candidates(name);
            if (candidates.isEmpty()) {
                // A bracket or a comma the parser offers as a name is a syntax error, not an
                // unknown term (review 1.139.0 M5).
                if (name.matches(".*[\\p{L}\\p{N}].*")) {
                    unknown.add(token);
                }
                return null;
            }
            // A label two terms share names the one of the kind the axiom needs, when only
            // one of them is of that kind (pizza-bfo: a class and a property "identifier").
            Set<IRI> ofKind = new LinkedHashSet<IRI>();
            for (IRI candidate : candidates) {
                if (kind.isIn(ontology, candidate)) {
                    ofKind.add(candidate);
                }
            }
            if (ofKind.size() > 1) {
                ambiguous.add("'" + name + "' is ambiguous: it names "
                        + String.join(", ", names().resolve(name).getCandidates()));
                return null;
            }
            return ofKind.isEmpty() ? null : ofKind.iterator().next();
        }

        /** "Thing" when a term of the ontology is called that too (review 1.139.0 I1c). */
        private boolean alsoNamed(String plain) {
            if (names().candidates(plain).isEmpty()) {
                return false;
            }
            ambiguous.add("'" + plain + "' is ambiguous: it names owl:" + plain + " and "
                    + String.join(", ", names().resolve(plain).getCandidates())
                    + "; write owl:" + plain + " for the first");
            return true;
        }

        @Override
        public OWLClass getOWLClass(String name) {
            String plain = unquote(name);
            if ("Thing".equals(plain) || "owl:Thing".equals(plain)) {
                return "Thing".equals(plain) && alsoNamed(plain) ? null : factory.getOWLThing();
            }
            if ("Nothing".equals(plain) || "owl:Nothing".equals(plain)) {
                return "Nothing".equals(plain) && alsoNamed(plain) ? null
                        : factory.getOWLNothing();
            }
            IRI iri = resolve(name, EntityKind.CLASS);
            return iri == null ? null : factory.getOWLClass(iri);
        }

        @Override
        public OWLObjectProperty getOWLObjectProperty(String name) {
            String plain = unquote(name);
            if ("topObjectProperty".equals(plain) || "owl:topObjectProperty".equals(plain)) {
                return factory.getOWLTopObjectProperty();
            }
            if ("bottomObjectProperty".equals(plain)
                    || "owl:bottomObjectProperty".equals(plain)) {
                return factory.getOWLBottomObjectProperty();
            }
            IRI iri = resolve(name, EntityKind.OBJECT_PROPERTY);
            return iri == null ? null : factory.getOWLObjectProperty(iri);
        }

        @Override
        public OWLDataProperty getOWLDataProperty(String name) {
            String plain = unquote(name);
            if ("topDataProperty".equals(plain) || "owl:topDataProperty".equals(plain)) {
                return factory.getOWLTopDataProperty();
            }
            if ("bottomDataProperty".equals(plain) || "owl:bottomDataProperty".equals(plain)) {
                return factory.getOWLBottomDataProperty();
            }
            IRI iri = resolve(name, EntityKind.DATA_PROPERTY);
            return iri == null ? null : factory.getOWLDataProperty(iri);
        }

        @Override
        public OWLNamedIndividual getOWLIndividual(String name) {
            IRI iri = resolve(name, EntityKind.INDIVIDUAL);
            return iri == null ? null : factory.getOWLNamedIndividual(iri);
        }

        @Override
        public OWLDatatype getOWLDatatype(String name) {
            String plain = unquote(name);
            for (OWL2Datatype datatype : OWL2Datatype.values()) {
                if (datatype.getPrefixedName().equals(plain)
                        || datatype.getShortForm().equals(plain)) {
                    return factory.getOWLDatatype(datatype.getIRI());
                }
            }
            IRI iri = resolve(name, EntityKind.DATATYPE);
            return iri == null ? null : factory.getOWLDatatype(iri);
        }

        @Override
        public OWLAnnotationProperty getOWLAnnotationProperty(String name) {
            IRI iri = resolve(name, EntityKind.ANNOTATION_PROPERTY);
            return iri == null ? null : factory.getOWLAnnotationProperty(iri);
        }
    }

    /** What the parser asks for, and whether the ontology has one by that IRI. */
    private enum EntityKind {
        CLASS, OBJECT_PROPERTY, DATA_PROPERTY, INDIVIDUAL, DATATYPE, ANNOTATION_PROPERTY;

        boolean isIn(OWLOntology ontology, IRI iri) {
            for (OWLEntity entity : ontology.getEntitiesInSignature(iri, Imports.INCLUDED)) {
                if ((this == CLASS && entity.isOWLClass())
                        || (this == OBJECT_PROPERTY && entity.isOWLObjectProperty())
                        || (this == DATA_PROPERTY && entity.isOWLDataProperty())
                        || (this == INDIVIDUAL && entity.isOWLNamedIndividual())
                        || (this == DATATYPE && entity.isOWLDatatype())
                        || (this == ANNOTATION_PROPERTY && entity.isOWLAnnotationProperty())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 'cheese topping' as cheese topping; \' as '. */
    static String unquote(String token) {
        String text = token == null ? "" : token.trim();
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            text = text.substring(1, text.length() - 1).replace("\\'", "'");
        }
        return text;
    }
}
