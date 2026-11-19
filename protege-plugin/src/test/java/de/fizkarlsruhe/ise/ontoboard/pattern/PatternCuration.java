package de.fizkarlsruhe.ise.ontoboard.pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * The curation of a library pattern (1.155.0, D2): what OntoBoard changes so that the pattern
 * passes ODK's quality report with its default profile, names every term with a label, and is
 * consistent and coherent - each change listed in the pattern itself: under {@code curated} in
 * its metadata.json, and in an rdfs:comment on its ontology that starts "Curated by OntoBoard",
 * which a project that imports it can read.
 *
 * <p>The rules, in order:
 *
 * <ol>
 *   <li>a label listed in {@code patterns/curation-fixes.tsv} is corrected;
 *   <li>a label's leading and trailing whitespace is removed, and a language written into its text
 *       ("Persona {it}") becomes its tag;
 *   <li>one rdfs:label per term - ROBOT's multiple_labels counts any two, tags or not: the English
 *       one stays (of two, the one the term's home ontology gives it), each other language's moves
 *       to skos:prefLabel, a second English one to skos:altLabel, and a copy is dropped;
 *   <li>the header: dcterms:title and dcterms:description from the library's metadata,
 *       dcterms:license from the pattern's own cc:license or {@code patterns/licences.tsv};
 *   <li>the axioms listed in curation-fixes.tsv are removed and added;
 *   <li>every term the pattern uses and does not declare is declared, as its imports declare it,
 *       so the file reads the same without them;
 *   <li>every term without a label gets one: the label another library pattern gives it, else
 *       the one its home ontology gives it ({@code known-labels.tsv}), else its name in words;
 *   <li>refused: a term the OWL API could not read, and two terms with one label.
 * </ol>
 *
 * <p>pattern.owl is then written as RDF/XML - what an import copies - and pattern.ttl as Turtle,
 * from the same ontology. A pattern with nothing to change is not written at all, so curating
 * twice changes nothing.
 *
 * <p>{@link #main}: {@code PatternCuration <patterns dir> <stubs dir> <fetched imports dir>
 * [pattern id...]}, run from protege-plugin/ (it reads src/test/resources/pattern/known-labels.tsv).
 */
final class PatternCuration {

    static final String CURATED = "Curated by OntoBoard";
    static final String VERSION = "1.155.0";

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String DC = "http://purl.org/dc/elements/1.1/";
    private static final String CC_LICENSE = "http://creativecommons.org/ns#license";
    private static final String ERROR = "http://org.semanticweb.owlapi/error#";
    private static final IRI RDFS_LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI();
    private static final List<String> BUILT_IN = Arrays.asList(
            "http://www.w3.org/2002/07/owl#", "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#", "http://www.w3.org/2001/XMLSchema#");
    /** "Persona {it}", "Community {@en}", "is characterized by {@en-us}". */
    private static final Pattern MARKER =
            Pattern.compile("^(.*?)\\s*\\{@?([A-Za-z]{2,3}(?:-[A-Za-z0-9]+)*)\\}\\s*$", Pattern.DOTALL);
    /** The kinds of change, by the words a line starts with, in the order a summary gives them. */
    private static final List<String> KINDS = Arrays.asList("label corrected", "label trimmed",
            "label rewritten", "label moved to skos:prefLabel", "label moved to skos:altLabel",
            "label removed", "label added", "term declared", "header", "axiom removed",
            "axiom added", "file");
    /** The kinds a summary lists line by line: they change what the pattern says. */
    private static final List<String> SAID_IN_FULL = Arrays.asList("label corrected", "header",
            "axiom removed", "axiom added", "file");

    /** A label someone gives a term: its text, its language tag, and who gives it. */
    static final class Given {
        final String text;
        final String lang;
        final String from;

        Given(String text, String lang, String from) {
            this.text = text;
            this.lang = lang;
            this.from = from;
        }
    }

    /** A change that needs a person's judgement: a row of patterns/curation-fixes.tsv. */
    static final class Fix {
        final String pattern;
        final String iri;
        final String property;
        final String old;
        final String neu;
        final String why;

        Fix(String pattern, String iri, String property, String old, String neu, String why) {
            this.pattern = pattern;
            this.iri = iri;
            this.property = property;
            this.old = old;
            this.neu = neu;
            this.why = why;
        }

        static List<Fix> read(File tsv) throws IOException {
            List<Fix> fixes = new ArrayList<Fix>();
            for (String line : Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8)) {
                String[] cells = line.split("\t", -1);
                if (line.startsWith("#") || line.trim().isEmpty() || "pattern".equals(cells[0])) {
                    continue;
                }
                if (cells.length != 6) {
                    throw new IllegalStateException(tsv + ": not six columns: " + line);
                }
                fixes.add(new Fix(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]));
            }
            return fixes;
        }
    }

    /** The licence of each pattern source, by the prefix of its URL: patterns/licences.tsv. */
    static final class Licences {
        private final List<String[]> rows;

        /** Each row: the source prefix, the licence IRI ("" when none was found), the evidence. */
        Licences(List<String[]> rows) {
            this.rows = rows;
        }

        static Licences none() {
            return new Licences(new ArrayList<String[]>());
        }

        static Licences read(File tsv) throws IOException {
            List<String[]> rows = new ArrayList<String[]>();
            for (String line : Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8)) {
                String[] cells = line.split("\t", -1);
                if (line.trim().isEmpty() || "source_prefix".equals(cells[0]) || cells.length < 3) {
                    continue;
                }
                rows.add(new String[] {cells[0], cells[1], cells[2]});
            }
            return new Licences(rows);
        }

        /** The row whose prefix is the longest that one of {@code urls} starts with, or null. */
        String[] of(String... urls) {
            String[] best = null;
            for (String[] row : rows) {
                for (String url : urls) {
                    if (url != null && url.startsWith(row[0])
                            && (best == null || row[0].length() > best[0].length())) {
                        best = row;
                    }
                }
            }
            return best;
        }
    }

    /** What curation changed in one pattern, a line per change. */
    static final class Changes {
        private final List<String> lines = new ArrayList<String>();

        void add(String line) {
            lines.add(line);
        }

        List<String> lines() {
            return Collections.unmodifiableList(lines);
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    /** One rdfs:label of a term as written, and as read: trimmed, its marker made its tag. */
    private static final class Written {
        final OWLAnnotationAssertionAxiom axiom;     // null for the ontology's own label
        final OWLAnnotation annotation;              // the ontology's own label
        final OWLLiteral literal;
        final String text;
        final String lang;

        Written(OWLAnnotationAssertionAxiom axiom, OWLAnnotation annotation, OWLLiteral literal) {
            this.axiom = axiom;
            this.annotation = annotation;
            this.literal = literal;
            String read = literal.getLiteral().trim();
            String tag = literal.getLang();
            Matcher marker = MARKER.matcher(read);
            if (tag.isEmpty() && marker.matches()) {
                read = marker.group(1).trim();
                tag = marker.group(2).toLowerCase(Locale.ROOT);
            }
            this.text = read;
            this.lang = tag;
        }

        boolean english() {
            return lang.isEmpty() || "en".equalsIgnoreCase(lang)
                    || lang.toLowerCase(Locale.ROOT).startsWith("en-");
        }

        /** "en" before "en-*" before none. */
        int englishRank() {
            return "en".equalsIgnoreCase(lang) ? 0 : lang.isEmpty() ? 2 : 1;
        }

        boolean readAsWritten() {
            return text.equals(literal.getLiteral()) && lang.equals(literal.getLang());
        }

        String asWritten() {
            return quoted(literal.getLiteral(), literal.getLang());
        }

        String asRead() {
            return quoted(text, lang);
        }
    }

    private final Map<String, Given> library;
    private final Map<String, Given> known;
    private final Licences licences;
    private final List<Fix> fixes;
    private final File stubs;
    private final Function<String, File> importCopy;

    /**
     * @param library the label another library pattern gives a term, by IRI
     * @param known the label a term's home ontology gives it, by IRI (known-labels.tsv)
     * @param importCopy a local copy of an imported ontology, by its IRI, or null for a stub
     */
    PatternCuration(Map<String, Given> library, Map<String, Given> known, Licences licences,
            List<Fix> fixes, File stubs, Function<String, File> importCopy) {
        this.library = library;
        this.known = known;
        this.licences = licences;
        this.fixes = fixes;
        this.stubs = stubs;
        this.importCopy = importCopy;
    }

    /** Curates the pattern in {@code patternDir}; see the class comment. */
    Changes curate(File patternDir) throws Exception {
        String id = patternDir.getName();
        File owlFile = new File(patternDir, "pattern.owl");
        File ttlFile = new File(patternDir, "pattern.ttl");
        byte[] owlBytes = Files.readAllBytes(owlFile.toPath());
        OWLOntologyManager manager = manager();
        OWLOntology ontology = load(manager, owlBytes, owlFile);
        OWLDocumentFormat format = manager.getOntologyFormat(ontology);
        Set<OWLAxiom> asItWas = withoutAnnotations(ontology.getAxioms());
        Changes changes = new Changes();

        for (Fix fix : fixesFor(id, "rdfs:label")) {
            correct(ontology, fix, changes);
        }
        relabel(ontology, changes);
        header(ontology, patternDir, changes);
        for (Fix fix : fixesFor(id, "axiom")) {
            applyAxiom(ontology, fix, changes);
        }
        declare(ontology, changes);
        label(ontology, changes);
        refuseErrors(id, ontology);
        refuseDuplicateLabels(id, ontology);
        ttlAlone(ttlFile, asItWas, ontology, changes);
        if (!(format instanceof RDFXMLDocumentFormat)) {
            changes.add("file: pattern.owl written as RDF/XML - it held "
                    + (format == null ? "something else" : format.getKey()));
        }
        if (changes.isEmpty()) {
            return changes;
        }
        List<String> all = record(patternDir, changes);
        comment(ontology, all);
        write(manager, ontology, format, owlFile, ttlFile);
        return changes;
    }

    // --- 1. listed labels ----------------------------------------------------------------------

    private List<Fix> fixesFor(String id, String property) {
        List<Fix> mine = new ArrayList<Fix>();
        for (Fix fix : fixes) {
            if (fix.pattern.equals(id) && fix.property.equals(property)) {
                mine.add(fix);
            }
        }
        return mine;
    }

    private static void correct(OWLOntology ontology, Fix fix, Changes changes) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        List<OWLAnnotationAssertionAxiom> matching = new ArrayList<OWLAnnotationAssertionAxiom>();
        for (OWLAnnotationAssertionAxiom assertion
                : ontology.getAnnotationAssertionAxioms(IRI.create(fix.iri))) {
            if (assertion.getProperty().getIRI().equals(RDFS_LABEL)
                    && assertion.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) assertion.getValue()).getLiteral().equals(fix.old)) {
                matching.add(assertion);
            }
        }
        if (matching.isEmpty()) {
            throw new IllegalStateException("curation-fixes.tsv: <" + fix.iri + "> has no rdfs:label \""
                    + fix.old + "\" in pattern " + fix.pattern);
        }
        for (OWLAnnotationAssertionAxiom assertion : matching) {
            OWLLiteral old = (OWLLiteral) assertion.getValue();
            ontology.getOWLOntologyManager().removeAxiom(ontology, assertion);
            ontology.getOWLOntologyManager().addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    assertion.getProperty(), assertion.getSubject(), literal(factory, fix.neu,
                            old.getLang()), assertion.getAnnotations()));
        }
        changes.add("label corrected: <" + fix.iri + "> \"" + fix.old + "\" is now \"" + fix.neu
                + "\" - " + fix.why);
    }

    // --- 2 and 3. one rdfs:label per term ------------------------------------------------------

    private void relabel(OWLOntology ontology, Changes changes) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        Map<String, List<Written>> bySubject = new TreeMap<String, List<Written>>();
        for (OWLAnnotationAssertionAxiom assertion
                : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().getIRI().equals(RDFS_LABEL) && assertion.getSubject() instanceof IRI
                    && assertion.getValue() instanceof OWLLiteral) {
                put(bySubject, assertion.getSubject().toString(),
                        new Written(assertion, null, (OWLLiteral) assertion.getValue()));
            }
        }
        String self = ontologyIri(ontology);
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (self != null && annotation.getProperty().getIRI().equals(RDFS_LABEL)
                    && annotation.getValue() instanceof OWLLiteral) {
                put(bySubject, self, new Written(null, annotation, (OWLLiteral) annotation.getValue()));
            }
        }
        for (Map.Entry<String, List<Written>> subject : bySubject.entrySet()) {
            String iri = subject.getKey();
            List<Written> labels = subject.getValue();
            sort(labels);
            Written keeper = labels.get(keeperOf(iri, iri.equals(self), labels, known));
            Map<String, String> prefByLang = new HashMap<String, String>();
            Set<String> alternatives = new HashSet<String>();
            for (Written label : labels) {
                if (label == keeper) {
                    if (!label.readAsWritten()) {
                        boolean onlyTrimmed = label.lang.equals(label.literal.getLang());
                        replace(ontology, label, iri, RDFS_LABEL, literal(factory, label.text, label.lang));
                        changes.add(onlyTrimmed
                                ? "label trimmed: <" + iri + "> " + label.asWritten()
                                : "label rewritten: <" + iri + "> " + label.asWritten() + " is now "
                                        + label.asRead());
                    }
                    continue;
                }
                String as = label.asWritten().equals(label.asRead()) ? "" : " as " + label.asRead();
                if (label.english() && label.text.equals(keeper.text)
                        || alternatives.contains(label.lang + "\t" + label.text)
                        || label.text.equals(prefByLang.get(label.lang.toLowerCase(Locale.ROOT)))) {
                    remove(ontology, label);
                    changes.add("label removed (a copy of one it keeps): <" + iri + "> " + label.asWritten());
                } else if (!label.english() && !prefByLang.containsKey(label.lang.toLowerCase(Locale.ROOT))) {
                    prefByLang.put(label.lang.toLowerCase(Locale.ROOT), label.text);
                    replace(ontology, label, iri, IRI.create(SKOS + "prefLabel"),
                            literal(factory, label.text, label.lang));
                    changes.add("label moved to skos:prefLabel: <" + iri + "> " + label.asWritten() + as);
                } else {
                    alternatives.add(label.lang + "\t" + label.text);
                    replace(ontology, label, iri, IRI.create(SKOS + "altLabel"),
                            literal(factory, label.text, label.lang));
                    changes.add("label moved to skos:altLabel: <" + iri + "> " + label.asWritten() + as);
                }
            }
        }
    }

    private static void put(Map<String, List<Written>> bySubject, String iri, Written label) {
        if (!bySubject.containsKey(iri)) {
            bySubject.put(iri, new ArrayList<Written>());
        }
        bySubject.get(iri).add(label);
    }

    /** By language, then text: so "first" means the same on every run. */
    private static void sort(List<Written> labels) {
        Collections.sort(labels, new Comparator<Written>() {
            @Override
            public int compare(Written a, Written b) {
                int byLang = a.lang.compareTo(b.lang);
                return byLang != 0 ? byLang : (a.text + a.literal.getLiteral())
                        .compareTo(b.text + b.literal.getLiteral());
            }
        });
    }

    /**
     * The label that stays a term's rdfs:label: an English one - of two English texts, the one
     * that names the ontology (for its own header), else the one the term's home ontology gives
     * it, else one tagged @en - and of those with that text, the best tagged; else the first.
     */
    private static int keeperOf(String iri, boolean isOntology, List<Written> labels,
            Map<String, Given> known) {
        List<Integer> english = new ArrayList<Integer>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).english()) {
                english.add(i);
            }
        }
        if (english.isEmpty()) {
            return 0;
        }
        String text = null;
        if (isOntology) {
            String name = squeezed(iri.replaceAll("\\.owl$", "")
                    .substring(Math.max(iri.replaceAll("\\.owl$", "").lastIndexOf('/'),
                            iri.replaceAll("\\.owl$", "").lastIndexOf('#')) + 1));
            for (int i : english) {
                if (squeezed(labels.get(i).text).equals(name)) {
                    text = labels.get(i).text;
                    break;
                }
            }
        }
        Given home = known.get(iri);
        if (text == null && home != null) {
            for (int i : english) {
                if (labels.get(i).text.equalsIgnoreCase(home.text)) {
                    text = labels.get(i).text;
                    break;
                }
            }
        }
        if (text == null) {
            int best = english.get(0);
            for (int i : english) {
                if (labels.get(i).englishRank() < labels.get(best).englishRank()) {
                    best = i;
                }
            }
            text = labels.get(best).text;
        }
        int keeper = -1;
        for (int i : english) {
            if (labels.get(i).text.equals(text)
                    && (keeper < 0 || labels.get(i).englishRank() < labels.get(keeper).englishRank())) {
                keeper = i;
            }
        }
        return keeper;
    }

    private static String squeezed(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void replace(OWLOntology ontology, Written label, String iri, IRI property,
            OWLLiteral value) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLAnnotationProperty annotationProperty = factory.getOWLAnnotationProperty(property);
        remove(ontology, label);
        if (label.axiom != null) {
            manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(annotationProperty,
                    IRI.create(iri), value, label.axiom.getAnnotations()));
        } else {
            manager.applyChange(new AddOntologyAnnotation(ontology,
                    factory.getOWLAnnotation(annotationProperty, value)));
        }
    }

    private static void remove(OWLOntology ontology, Written label) {
        if (label.axiom != null) {
            ontology.getOWLOntologyManager().removeAxiom(ontology, label.axiom);
        } else {
            ontology.getOWLOntologyManager().applyChange(
                    new RemoveOntologyAnnotation(ontology, label.annotation));
        }
    }

    // --- 4. the header -------------------------------------------------------------------------

    private void header(OWLOntology ontology, File patternDir, Changes changes) throws IOException {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        JsonNode metadata = new ObjectMapper().readTree(new File(patternDir, "metadata.json"));
        String name = metadata.path("name").asText("").trim();
        String description = metadata.path("description").asText("").trim();
        if (!has(ontology, DC + "title", DCTERMS + "title") && !name.isEmpty()) {
            add(ontology, DCTERMS + "title", factory.getOWLLiteral(name));
            changes.add("header: dcterms:title \"" + name + "\" - the library's name for the pattern");
        }
        if (!has(ontology, DC + "description", DCTERMS + "description") && !description.isEmpty()) {
            add(ontology, DCTERMS + "description", factory.getOWLLiteral(description));
            changes.add("header: dcterms:description - the library's description of the pattern");
        }
        if (!has(ontology, DCTERMS + "license")) {
            OWLAnnotationValue own = valueOf(ontology, CC_LICENSE);
            String[] row = licences.of(metadata.path("source").asText(null),
                    metadata.path("pattern_iri").asText(null));
            if (own != null) {
                add(ontology, DCTERMS + "license", own);
                changes.add("header: dcterms:license " + shown(own) + " - the pattern's own cc:license");
            } else if (row != null && !row[1].isEmpty()) {
                add(ontology, DCTERMS + "license", IRI.create(row[1]));
                changes.add("header: dcterms:license <" + row[1] + "> - " + row[2]);
            }
        }
    }

    private static boolean has(OWLOntology ontology, String... properties) {
        for (String property : properties) {
            if (valueOf(ontology, property) != null) {
                return true;
            }
        }
        return false;
    }

    private static OWLAnnotationValue valueOf(OWLOntology ontology, String property) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (annotation.getProperty().getIRI().toString().equals(property)) {
                return annotation.getValue();
            }
        }
        return null;
    }

    private static void add(OWLOntology ontology, String property, OWLAnnotationValue value) {
        OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
        ontology.getOWLOntologyManager().applyChange(new AddOntologyAnnotation(ontology,
                factory.getOWLAnnotation(factory.getOWLAnnotationProperty(IRI.create(property)), value)));
    }

    private static String shown(OWLAnnotationValue value) {
        return value instanceof IRI ? "<" + value + ">" : value instanceof OWLLiteral
                ? "\"" + ((OWLLiteral) value).getLiteral() + "\"" : String.valueOf(value);
    }

    // --- 5. listed axioms ----------------------------------------------------------------------

    private static void applyAxiom(OWLOntology ontology, Fix fix, Changes changes) throws Exception {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        if (!fix.old.isEmpty()) {
            OWLAxiom gone = axiom(fix.old);
            Set<OWLAxiom> present = ontology.getAxiomsIgnoreAnnotations(gone, Imports.EXCLUDED);
            if (present.isEmpty()) {
                throw new IllegalStateException("curation-fixes.tsv: pattern " + fix.pattern
                        + " has no axiom " + fix.old);
            }
            for (OWLAxiom axiom : present) {
                manager.removeAxiom(ontology, axiom);
            }
            changes.add("axiom removed: " + fix.old + " - " + fix.why);
        }
        if (!fix.neu.isEmpty()) {
            manager.addAxiom(ontology, axiom(fix.neu));
            changes.add("axiom added: " + fix.neu + " - " + fix.why);
        }
    }

    /** One axiom written in OWL functional syntax, with full IRIs. */
    static OWLAxiom axiom(String functional) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology parsed = manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                "Ontology(<urn:ontoboard:curation-fix>\n" + functional + "\n)",
                IRI.create("urn:ontoboard:curation-fix-document"), new FunctionalSyntaxDocumentFormat(),
                null));
        List<OWLAxiom> axioms = new ArrayList<OWLAxiom>();
        for (OWLAxiom axiom : parsed.getAxioms()) {
            if (!axiom.isOfType(AxiomType.DECLARATION)) {
                axioms.add(axiom);
            }
        }
        if (axioms.size() != 1) {
            throw new IllegalStateException("not one axiom: " + functional + " -> " + axioms);
        }
        return axioms.get(0);
    }

    // --- 6. declarations -----------------------------------------------------------------------

    private static void declare(OWLOntology ontology, Changes changes) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (OWLEntity entity : new TreeSet<OWLEntity>(ontology.getSignature())) {
            if (isBuiltIn(entity) || ontology.isDeclared(entity)) {
                continue;
            }
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(entity));
            changes.add("term declared: <" + entity.getIRI() + "> as " + kindOf(entity));
        }
    }

    private static String kindOf(OWLEntity entity) {
        switch (entity.getEntityType().getName()) {
            case "Class":
                return "a class";
            case "ObjectProperty":
                return "an object property";
            case "DataProperty":
                return "a data property";
            case "AnnotationProperty":
                return "an annotation property";
            case "NamedIndividual":
                return "an individual";
            default:
                return "a datatype";
        }
    }

    private static boolean isBuiltIn(OWLEntity entity) {
        if (entity.isBuiltIn()) {
            return true;
        }
        String iri = entity.getIRI().toString();
        for (String prefix : BUILT_IN) {
            if (iri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // --- 7. labels for the unlabelled ----------------------------------------------------------

    private void label(OWLOntology ontology, Changes changes) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        Set<String> labelled = new HashSet<String>();
        for (OWLAnnotationAssertionAxiom assertion : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().getIRI().equals(RDFS_LABEL) && assertion.getSubject() instanceof IRI) {
                labelled.add(assertion.getSubject().toString());
            }
        }
        Set<String> done = new HashSet<String>();
        for (OWLEntity entity : new TreeSet<OWLEntity>(ontology.getSignature())) {
            String iri = entity.getIRI().toString();
            if (isBuiltIn(entity) || labelled.contains(iri) || !done.add(iri)) {
                continue;
            }
            Given given = library.get(iri);
            String from;
            if (given != null) {
                from = "from the library's " + given.from;
            } else if (known.containsKey(iri)) {
                given = known.get(iri);
                from = "from " + given.from;
            } else {
                given = new Given(NamesAsLabels.labelFrom(entity.getIRI()), "en", "its name");
                from = "from its name";
            }
            if (given.text.trim().isEmpty()) {
                throw new IllegalStateException("<" + iri + "> has no label and no name to make one from");
            }
            manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                    entity.getIRI(), literal(factory, given.text, given.lang)));
            changes.add("label added: <" + iri + "> " + quoted(given.text, given.lang) + " - " + from);
        }
    }

    // --- 8. refusals ---------------------------------------------------------------------------

    private static void refuseErrors(String id, OWLOntology ontology) {
        Set<String> unread = new TreeSet<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            if (entity.getIRI().toString().startsWith(ERROR)) {
                unread.add(entity.getIRI().toString());
            }
        }
        if (!unread.isEmpty()) {
            throw new IllegalStateException("pattern " + id + ": the OWL API could not read part of it "
                    + "(it made " + unread + ") - an import that declares what it uses is missing");
        }
    }

    private static void refuseDuplicateLabels(String id, OWLOntology ontology) {
        Map<String, Set<String>> byLabel = new TreeMap<String, Set<String>>();
        for (OWLAnnotationAssertionAxiom assertion : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().getIRI().equals(RDFS_LABEL) && assertion.getSubject() instanceof IRI
                    && assertion.getValue() instanceof OWLLiteral) {
                OWLLiteral value = (OWLLiteral) assertion.getValue();
                String key = quoted(value.getLiteral(), value.getLang());
                if (!byLabel.containsKey(key)) {
                    byLabel.put(key, new TreeSet<String>());
                }
                byLabel.get(key).add(assertion.getSubject().toString());
            }
        }
        StringBuilder twice = new StringBuilder();
        for (Map.Entry<String, Set<String>> label : byLabel.entrySet()) {
            if (label.getValue().size() > 1) {
                twice.append(twice.length() == 0 ? "" : "; ").append(label.getKey()).append(": ")
                        .append(label.getValue());
            }
        }
        if (twice.length() > 0) {
            throw new IllegalStateException("pattern " + id + ": one rdfs:label on two terms (ROBOT's "
                    + "duplicate_label) - list a fix in curation-fixes.tsv: " + twice);
        }
    }

    // --- pattern.ttl, the record, the files ----------------------------------------------------

    /** Lists what only pattern.ttl said, and what curation has not put into pattern.owl since. */
    private void ttlAlone(File ttlFile, Set<OWLAxiom> owlAsItWas, OWLOntology curated, Changes changes) {
        if (!ttlFile.isFile()) {
            changes.add("file: pattern.ttl written - there was none");
            return;
        }
        Set<OWLAxiom> ttl;
        try {
            ttl = withoutAnnotations(load(manager(), Files.readAllBytes(ttlFile.toPath()), ttlFile).getAxioms());
        } catch (Exception unreadable) {                // NOPMD - said, then replaced
            changes.add("file: pattern.ttl could not be read (" + unreadable.getClass().getSimpleName()
                    + "), and is written from pattern.owl");
            return;
        }
        Set<OWLAxiom> now = withoutAnnotations(curated.getAxioms());
        Set<String> dropped = new TreeSet<String>();
        for (OWLAxiom axiom : ttl) {
            if (!owlAsItWas.contains(axiom) && !now.contains(axiom)) {
                dropped.add(axiom.toString());
            }
        }
        for (String axiom : dropped) {
            changes.add("file: only pattern.ttl said " + axiom + " - not kept: pattern.owl is what an "
                    + "import copies");
        }
    }

    /** metadata.json: the lines added under "curated"; returns them all, old and new. */
    private static List<String> record(File patternDir, Changes changes) throws IOException {
        File file = new File(patternDir, "metadata.json");
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean crlf = text.contains("\r\n");
        text = text.replace("\r\n", "\n");
        ObjectMapper json = new ObjectMapper();
        List<String> all = new ArrayList<String>();
        for (JsonNode line : json.readTree(text).path("curated")) {
            all.add(line.asText());
        }
        all.addAll(changes.lines());
        int at = text.lastIndexOf(",\n  \"curated\": [");
        String head = at >= 0 ? text.substring(0, at)
                : text.substring(0, text.lastIndexOf('}')).replaceAll("\\s+$", "");
        StringBuilder out = new StringBuilder(head).append(",\n  \"curated\": [");
        for (int i = 0; i < all.size(); i++) {
            out.append(i == 0 ? "\n" : ",\n").append("    ").append(json.writeValueAsString(all.get(i)));
        }
        out.append("\n  ]\n}\n");
        String written = crlf ? out.toString().replace("\n", "\r\n") : out.toString();
        Files.write(file.toPath(), written.getBytes(StandardCharsets.UTF_8));
        return all;
    }

    /** The ontology's rdfs:comment that says it was curated, and how. */
    private static void comment(OWLOntology ontology, List<String> all) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (OWLAnnotation annotation : new ArrayList<OWLAnnotation>(ontology.getAnnotations())) {
            if (annotation.getProperty().isComment() && annotation.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) annotation.getValue()).getLiteral().startsWith(CURATED)) {
                manager.applyChange(new RemoveOntologyAnnotation(ontology, annotation));
            }
        }
        manager.applyChange(new AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                factory.getRDFSComment(), factory.getOWLLiteral(summary(all), "en"))));
    }

    /** What the comment says: how many of each kind, and in full what changes meaning. */
    static String summary(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        List<String> inFull = new ArrayList<String>();
        for (String kind : KINDS) {
            counts.put(kind, 0);
        }
        for (String line : lines) {
            for (String kind : KINDS) {
                if (line.startsWith(kind)) {
                    counts.put(kind, counts.get(kind) + 1);
                    if (SAID_IN_FULL.contains(kind)) {
                        inFull.add(line);
                    }
                    break;
                }
            }
        }
        StringBuilder text = new StringBuilder(CURATED).append(' ').append(VERSION)
                .append(", so that the pattern passes ODK's quality report with its default profile,"
                        + " names every term with a label, and is consistent and coherent. Changes:");
        for (Map.Entry<String, Integer> kind : counts.entrySet()) {
            if (kind.getValue() > 0 && !SAID_IN_FULL.contains(kind.getKey())) {
                text.append(' ').append(kind.getKey()).append(" x").append(kind.getValue()).append(';');
            }
        }
        for (String line : inFull) {
            text.append("\n- ").append(line);
        }
        return text.append("\nEach change is listed under \"curated\" in the pattern's metadata.json in"
                + " OntoBoard's pattern library.").toString();
    }

    private static void write(OWLOntologyManager manager, OWLOntology ontology, OWLDocumentFormat format,
            File owlFile, File ttlFile) throws Exception {
        RDFXMLDocumentFormat rdfxml = new RDFXMLDocumentFormat();
        TurtleDocumentFormat turtle = new TurtleDocumentFormat();
        if (format instanceof PrefixDocumentFormat) {
            rdfxml.copyPrefixesFrom((PrefixDocumentFormat) format);
            turtle.copyPrefixesFrom((PrefixDocumentFormat) format);
        }
        for (String[] prefix : new String[][] {{"skos:", SKOS}, {"dcterms:", DCTERMS}}) {
            if (!rdfxml.getPrefixName2PrefixMap().containsValue(prefix[1])) {
                rdfxml.setPrefix(prefix[0], prefix[1]);
                turtle.setPrefix(prefix[0], prefix[1]);
            }
        }
        writeLike(owlFile, render(manager, ontology, rdfxml));
        writeLike(ttlFile, render(manager, ontology, turtle));
    }

    private static String render(OWLOntologyManager manager, OWLOntology ontology,
            OWLDocumentFormat format) throws Exception {
        StringDocumentTarget target = new StringDocumentTarget();
        manager.saveOntology(ontology, format, target);
        return target.toString().replace("\r\n", "\n");
    }

    /** Written with the line endings the file had (this working copy's), LF for a new one. */
    private static void writeLike(File file, String text) throws IOException {
        boolean crlf = file.isFile() && new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8).contains("\r\n");
        Files.write(file.toPath(), (crlf ? text.replace("\n", "\r\n") : text)
                .getBytes(StandardCharsets.UTF_8));
    }

    // --- loading -------------------------------------------------------------------------------

    private OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(new OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI ontologyIRI) {
                String iri = ontologyIRI.toString();
                if (iri.startsWith("file:") || iri.startsWith("urn:ontoboard")) {
                    return null;
                }
                File copy = importCopy.apply(iri);
                if (copy != null && copy.isFile()) {
                    return IRI.create(copy);
                }
                try {
                    return IRI.create(stubFor(iri));
                } catch (IOException cannotWrite) {
                    return null;
                }
            }
        });
        return manager;
    }

    private File stubFor(String iri) throws IOException {
        File stub = new File(stubs, Integer.toHexString(iri.hashCode()) + ".ttl");
        if (!stub.exists()) {
            Files.write(stub.toPath(), ("<" + iri + "> a <http://www.w3.org/2002/07/owl#Ontology> .\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return stub;
    }

    /** From the file's bytes, line endings as LF - a Turtle literal over two lines keeps no CR. */
    private static OWLOntology load(OWLOntologyManager manager, byte[] bytes, File file) throws Exception {
        return manager.loadOntologyFromOntologyDocument(new StringDocumentSource(
                new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                IRI.create(file.getAbsoluteFile().toURI()), null, null));
    }

    private static Set<OWLAxiom> withoutAnnotations(Set<OWLAxiom> axioms) {
        Set<OWLAxiom> bare = new HashSet<OWLAxiom>();
        for (OWLAxiom axiom : axioms) {
            bare.add(axiom.getAxiomWithoutAnnotations());
        }
        return bare;
    }

    private static String ontologyIri(OWLOntology ontology) {
        return ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : null;
    }

    private static OWLLiteral literal(OWLDataFactory factory, String text, String lang) {
        return lang == null || lang.isEmpty() ? factory.getOWLLiteral(text) : factory.getOWLLiteral(text, lang);
    }

    private static String quoted(String text, String lang) {
        return "\"" + text + "\"" + (lang == null || lang.isEmpty() ? "" : "@" + lang);
    }

    // --- the library ---------------------------------------------------------------------------

    /** known-labels.tsv: iri, label, lang, source. */
    static Map<String, Given> knownLabels(File tsv) throws IOException {
        Map<String, Given> known = new HashMap<String, Given>();
        for (String line : Files.readAllLines(tsv.toPath(), StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (line.startsWith("#") || cells.length != 4 || "iri".equals(cells[0])) {
                continue;
            }
            known.put(cells[0], new Given(cells[1], cells[2], cells[3]));
        }
        return known;
    }

    /**
     * The label the library's patterns give each term, as curation keeps it: per pattern the
     * rdfs:label that stays; of several patterns, the label most of them give, then the first.
     */
    static Map<String, Given> libraryLabels(File patternsDir, Map<String, Given> known, File stubs)
            throws Exception {
        Map<String, Map<String, Set<String>>> votes = new TreeMap<String, Map<String, Set<String>>>();
        PatternCuration reader = new PatternCuration(Collections.<String, Given>emptyMap(), known,
                Licences.none(), Collections.<Fix>emptyList(), stubs, iri -> null);
        File[] directories = patternsDir.listFiles();
        Arrays.sort(directories);
        for (File directory : directories) {
            File owl = new File(directory, "pattern.owl");
            if (!owl.isFile()) {
                continue;
            }
            OWLOntology ontology = load(reader.manager(), Files.readAllBytes(owl.toPath()), owl);
            Map<String, List<Written>> bySubject = new TreeMap<String, List<Written>>();
            for (OWLAnnotationAssertionAxiom assertion : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
                if (assertion.getProperty().getIRI().equals(RDFS_LABEL) && assertion.getSubject() instanceof IRI
                        && assertion.getValue() instanceof OWLLiteral) {
                    put(bySubject, assertion.getSubject().toString(),
                            new Written(assertion, null, (OWLLiteral) assertion.getValue()));
                }
            }
            for (Map.Entry<String, List<Written>> subject : bySubject.entrySet()) {
                List<Written> labels = subject.getValue();
                sort(labels);
                Written keeper = labels.get(keeperOf(subject.getKey(), false, labels, known));
                if (!keeper.english()) {
                    continue;
                }
                String key = keeper.lang + "\t" + keeper.text;
                if (!votes.containsKey(subject.getKey())) {
                    votes.put(subject.getKey(), new TreeMap<String, Set<String>>());
                }
                Map<String, Set<String>> forTerm = votes.get(subject.getKey());
                if (!forTerm.containsKey(key)) {
                    forTerm.put(key, new TreeSet<String>());
                }
                forTerm.get(key).add(directory.getName());
            }
        }
        Map<String, Given> labels = new HashMap<String, Given>();
        for (Map.Entry<String, Map<String, Set<String>>> term : votes.entrySet()) {
            String best = null;
            for (Map.Entry<String, Set<String>> label : term.getValue().entrySet()) {
                if (best == null || label.getValue().size() > term.getValue().get(best).size()) {
                    best = label.getKey();
                }
            }
            String[] langAndText = best.split("\t", 2);
            labels.put(term.getKey(), new Given(langAndText[1], langAndText[0],
                    "pattern " + term.getValue().get(best).iterator().next()));
        }
        return labels;
    }

    /** The whole library, or the patterns named: see the class comment. */
    public static void main(String[] args) throws Exception {
        File patterns = new File(args[0]);
        File stubs = new File(args[1]);
        final File fetched = new File(args[2]);
        if (!stubs.isDirectory() && !stubs.mkdirs()) {
            throw new IOException("could not make " + stubs);
        }
        List<Fix> fixes = Fix.read(new File(patterns, "curation-fixes.tsv"));
        Licences licences = Licences.read(new File(patterns, "licences.tsv"));
        Map<String, Given> known = knownLabels(new File("src/test/resources/pattern/known-labels.tsv"));
        Map<String, Given> library = libraryLabels(patterns, known, stubs);
        final Map<String, File> libraryCopies = new HashMap<String, File>();
        for (File directory : patterns.listFiles()) {
            File metadata = new File(directory, "metadata.json");
            if (metadata.isFile()) {
                String iri = new ObjectMapper().readTree(metadata).path("pattern_iri").asText("");
                if (!iri.isEmpty()) {
                    libraryCopies.put(iri, new File(directory, "pattern.owl"));
                }
            }
        }
        for (Fix fix : fixes) {
            if (!new File(patterns, fix.pattern + "/pattern.owl").isFile()) {
                throw new IllegalStateException("curation-fixes.tsv names no library pattern: " + fix.pattern);
            }
        }
        PatternCuration curation = new PatternCuration(library, known, licences, fixes, stubs, iri -> {
            File copy = new File(fetched, PatternAudit.fileNameFor(iri));
            return copy.isFile() ? copy : libraryCopies.get(iri);
        });
        List<File> directories = new ArrayList<File>();
        if (args.length > 3) {
            for (int i = 3; i < args.length; i++) {
                directories.add(new File(patterns, args[i]));
            }
        } else {
            for (File directory : patterns.listFiles()) {
                if (new File(directory, "pattern.owl").isFile()) {
                    directories.add(directory);
                }
            }
            Collections.sort(directories);
        }
        Map<String, String> refused = new TreeMap<String, String>();
        int changed = 0;
        for (File directory : directories) {
            try {
                Changes changes = curation.curate(directory);
                changed += changes.isEmpty() ? 0 : 1;
                System.out.println(directory.getName() + "\t" + changes.lines().size() + " changes");
            } catch (Exception failed) {            // NOPMD - every pattern is tried; refusals listed
                refused.put(directory.getName(), String.valueOf(failed.getMessage()));
                System.out.println(directory.getName() + "\tREFUSED: " + failed.getMessage());
            }
        }
        System.out.println(changed + " of " + directories.size() + " patterns changed; " + refused.size()
                + " refused");
        for (Map.Entry<String, String> refusal : refused.entrySet()) {
            System.out.println("REFUSED " + refusal.getKey() + ": " + refusal.getValue());
        }
        if (!refused.isEmpty()) {
            System.exit(1);
        }
    }
}
