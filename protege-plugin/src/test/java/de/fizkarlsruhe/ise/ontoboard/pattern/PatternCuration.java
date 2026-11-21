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

    /** How the comment begins - the import carries it to a project's module by this (ImportProvenance). */
    static final String CURATED = de.fizkarlsruhe.ise.ontoboard.robot.ImportProvenance.CURATED;
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
    /** Labels with their source in brackets that every pattern uses for a term, by IRI. */
    private final Map<String, String> qualified;
    /** Finding, not curating: nothing is written. */
    private final boolean dryRun;
    /** The labels this run put their source in brackets, by IRI. */
    private final Map<String, String> qualifiedHere = new TreeMap<String, String>();

    /**
     * @param library the label another library pattern gives a term, by IRI
     * @param known the label a term's home ontology gives it, by IRI (known-labels.tsv)
     * @param importCopy a local copy of an imported ontology, by its IRI, or null for a stub
     */
    PatternCuration(Map<String, Given> library, Map<String, Given> known, Licences licences,
            List<Fix> fixes, File stubs, Function<String, File> importCopy) {
        this(library, known, licences, fixes, stubs, importCopy, Collections.<String, String>emptyMap(),
                false);
    }

    private PatternCuration(Map<String, Given> library, Map<String, Given> known, Licences licences,
            List<Fix> fixes, File stubs, Function<String, File> importCopy, Map<String, String> qualified,
            boolean dryRun) {
        this.library = library;
        this.known = known;
        this.licences = licences;
        this.fixes = fixes;
        this.stubs = stubs;
        this.importCopy = importCopy;
        this.qualified = qualified;
        this.dryRun = dryRun;
    }

    /**
     * The terms that get their source in brackets in some pattern - where another term of it
     * would carry the same label - and that no library pattern's author labels: such a term is
     * labelled the same way in every pattern, so two patterns imported together give it one label.
     * Found by curating each pattern without writing anything.
     */
    Map<String, String> qualifiedIn(List<File> patternDirs) {
        PatternCuration finding = new PatternCuration(library, known, licences, fixes, stubs, importCopy,
                Collections.<String, String>emptyMap(), true);
        Map<String, String> found = new TreeMap<String, String>();
        for (File directory : patternDirs) {
            try {
                finding.curate(directory);
            } catch (Exception refused) {               // NOPMD - the curating run reports it
                continue;
            }
        }
        for (Map.Entry<String, String> term : finding.qualifiedHere.entrySet()) {
            if (!library.containsKey(term.getKey())) {
                found.put(term.getKey(), term.getValue());
            }
        }
        return found;
    }

    /** This curation, labelling each of {@code qualifiedLabels}' terms as it says. */
    PatternCuration withQualified(Map<String, String> qualifiedLabels) {
        return new PatternCuration(library, known, licences, fixes, stubs, importCopy, qualifiedLabels, false);
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
        declare(ontology, declaredIn(ttlFile), changes);
        String name = new ObjectMapper().readTree(new File(patternDir, "metadata.json")).path("name")
                .asText(id).trim();
        label(ontology, name, changes);
        refuseErrors(id, ontology);
        refuseDuplicateLabels(id, ontology);
        ttlAlone(ttlFile, asItWas, ontology, changes);
        if (!(format instanceof RDFXMLDocumentFormat)) {
            changes.add("file: pattern.owl written as RDF/XML - it held "
                    + (format == null ? "something else" : format.getKey()));
        }
        if (changes.isEmpty() || dryRun) {
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
            for (OWLAnnotationAssertionAxiom assertion
                    : ontology.getAnnotationAssertionAxioms(IRI.create(fix.iri))) {
                if (assertion.getProperty().getIRI().equals(RDFS_LABEL)
                        && assertion.getValue() instanceof OWLLiteral
                        && ((OWLLiteral) assertion.getValue()).getLiteral().equals(fix.neu)) {
                    return;                             // made by an earlier curation
                }
            }
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
            // ROBOT reads an empty label as none: it goes, and the term gets one below.
            for (java.util.Iterator<Written> each = labels.iterator(); each.hasNext();) {
                Written label = each.next();
                if (label.text.isEmpty()) {
                    remove(ontology, label);
                    changes.add("label removed (empty): <" + iri + "> " + label.asWritten());
                    each.remove();
                }
            }
            if (labels.isEmpty()) {
                continue;
            }
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
        OWLAxiom added = fix.neu.isEmpty() ? null : axiom(fix.neu);
        boolean addedAlready = added != null
                && !ontology.getAxiomsIgnoreAnnotations(added, Imports.EXCLUDED).isEmpty();
        if (!fix.old.isEmpty()) {
            OWLAxiom gone = axiom(fix.old);
            Set<OWLAxiom> present = ontology.getAxiomsIgnoreAnnotations(gone, Imports.EXCLUDED);
            if (present.isEmpty()) {
                if (added == null || addedAlready) {
                    return;                             // made by an earlier curation
                }
                throw new IllegalStateException("curation-fixes.tsv: pattern " + fix.pattern
                        + " has no axiom " + fix.old);
            }
            for (OWLAxiom axiom : present) {
                manager.removeAxiom(ontology, axiom);
            }
            changes.add("axiom removed: " + fix.old + " - " + fix.why);
        }
        if (added != null && !addedAlready) {
            manager.addAxiom(ontology, added);
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

    /**
     * Declares every term the pattern names and does not declare. A declaration pattern.ttl had
     * and pattern.owl lacked is the pattern's own, said as that; any other is of a term the
     * pattern borrows ("term declared"), which tools/sync-pattern-owl.py leaves out of its size.
     */
    private static void declare(OWLOntology ontology, Set<OWLEntity> ttlDeclares, Changes changes) {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        for (OWLEntity entity : new TreeSet<OWLEntity>(ownSignature(ontology))) {
            if (isBuiltIn(entity) || ontology.isDeclared(entity)) {
                continue;
            }
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(entity));
            changes.add(ttlDeclares.contains(entity)
                    ? "file: pattern.owl declares <" + entity.getIRI() + "> as " + kindOf(entity)
                            + ", as pattern.ttl did"
                    : "term declared: <" + entity.getIRI() + "> as " + kindOf(entity));
        }
    }

    /** The terms pattern.ttl declares; none when it has none or cannot be read. */
    private Set<OWLEntity> declaredIn(File ttlFile) {
        Set<OWLEntity> declared = new HashSet<OWLEntity>();
        if (!ttlFile.isFile()) {
            return declared;
        }
        try {
            OWLOntology ttl = load(manager(), Files.readAllBytes(ttlFile.toPath()), ttlFile);
            for (OWLAxiom axiom : ttl.getAxioms(AxiomType.DECLARATION)) {
                declared.add(((org.semanticweb.owlapi.model.OWLDeclarationAxiom) axiom).getEntity());
            }
        } catch (Exception unreadable) {                // NOPMD - ttlAlone says so
            return declared;
        }
        return declared;
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

    private void label(OWLOntology ontology, String patternName, Changes changes) throws Exception {
        OWLOntologyManager manager = ontology.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        // The labels the pattern has, by label - the header's too: an ontology IRI that names a
        // term keeps its rdfs:label there.
        Map<String, Set<String>> byLabel = new TreeMap<String, Set<String>>();
        Set<String> labelled = new HashSet<String>();
        for (OWLAnnotationAssertionAxiom assertion : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (assertion.getProperty().getIRI().equals(RDFS_LABEL) && assertion.getSubject() instanceof IRI) {
                labelled.add(assertion.getSubject().toString());
                if (assertion.getValue() instanceof OWLLiteral) {
                    OWLLiteral value = (OWLLiteral) assertion.getValue();
                    with(byLabel, quoted(value.getLiteral(), value.getLang()), assertion.getSubject().toString());
                }
            }
        }
        String self = ontologyIri(ontology);
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (self != null && annotation.getProperty().getIRI().equals(RDFS_LABEL)
                    && annotation.getValue() instanceof OWLLiteral) {
                OWLLiteral value = (OWLLiteral) annotation.getValue();
                labelled.add(self);
                with(byLabel, quoted(value.getLiteral(), value.getLang()), self);
            }
        }
        // Which terms get one: every term of the pattern without a label, and what ODK's report
        // asks a label for besides - ROBOT's missing_label counts every IRI the pattern says
        // something about, an OWL term ("owl:Thing") or one only commented, never declared.
        Map<String, OWLEntity> entities = new TreeMap<String, OWLEntity>();
        for (OWLEntity entity : new TreeSet<OWLEntity>(ownSignature(ontology))) {
            String iri = entity.getIRI().toString();
            if (!isBuiltIn(entity) && !labelled.contains(iri) && !entities.containsKey(iri)) {
                entities.put(iri, entity);
            }
        }
        Set<String> toLabel = new TreeSet<String>(entities.keySet());
        Set<String> asked = new TreeSet<String>();
        for (String iri : reportAsksALabelFor(ontology)) {
            if (!labelled.contains(iri) && toLabel.add(iri)) {
                asked.add(iri);
            }
        }
        // The labels they get: another pattern's, the home ontology's, or the name in words.
        Map<String, Given> planned = new TreeMap<String, Given>();
        Map<String, String> from = new HashMap<String, String>();
        for (String iri : toLabel) {
            Given given = library.get(iri);
            if (iri.equals(self)) {
                given = new Given(patternName, "en", "the library's name for the pattern");
                from.put(iri, "the library's name for the pattern: the ontology's IRI names a term too");
            } else if (given != null) {
                from.put(iri, "from the library's " + given.from);
            } else if (known.containsKey(iri)) {
                given = known.get(iri);
                from.put(iri, "from " + given.from);
            } else {
                given = new Given(NamesAsLabels.labelFrom(IRI.create(iri)), "en", "its name");
                from.put(iri, "from its name");
            }
            if (asked.contains(iri)) {
                from.put(iri, from.get(iri) + " (ODK's report asks for one: the pattern says something about it)");
            }
            if (given.text.trim().isEmpty()) {
                throw new IllegalStateException("<" + iri + "> has no label and no name to make one from");
            }
            if (qualified.containsKey(iri)) {
                from.put(iri, from.get(iri) + ", with its source in brackets: in the library it shares \""
                        + given.text + "\" with another term");
                given = new Given(qualified.get(iri), given.lang, given.from);
            }
            planned.put(iri, given);
            with(byLabel, quoted(given.text, given.lang), iri);
        }
        // Two terms, one label: the author's label stays, then an OBO term's, then the label the
        // library gives a term; the others get their source in brackets.
        for (Set<String> sharing : byLabel.values()) {
            Set<String> mine = new TreeSet<String>(sharing);
            mine.retainAll(planned.keySet());
            if (sharing.size() < 2 || mine.isEmpty()) {
                continue;
            }
            String keep = mine.size() == sharing.size() ? keeperOf(mine, entities, selfNamespace(ontology)) : null;
            for (String iri : mine) {
                if (iri.equals(keep)) {
                    continue;
                }
                Given given = planned.get(iri);
                String text = given.text + " (" + qualifierOf(iri, sharing, entities) + ")";
                from.put(iri, from.get(iri) + ", with its source in brackets: another term of the pattern is"
                        + " called \"" + given.text + "\"");
                planned.put(iri, new Given(text, given.lang, given.from));
                qualifiedHere.put(iri, text);
            }
        }
        for (Map.Entry<String, Given> term : planned.entrySet()) {
            Given given = term.getValue();
            if (term.getKey().equals(self)) {
                manager.applyChange(new AddOntologyAnnotation(ontology, factory.getOWLAnnotation(
                        factory.getRDFSLabel(), literal(factory, given.text, given.lang))));
            } else {
                manager.addAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(factory.getRDFSLabel(),
                        IRI.create(term.getKey()), literal(factory, given.text, given.lang)));
            }
            changes.add("label added: <" + term.getKey() + "> " + quoted(given.text, given.lang) + " - "
                    + from.get(term.getKey()));
        }
    }

    /**
     * The IRIs ODK's report asks a label for (ROBOT's missing_label, with ODK's default profile),
     * the ontology's own aside - asked of the pattern alone: OntoBoard's report reads the imports
     * too, and what only an import describes (smarthome's GeoSPARQL: its people, its datatypes) is
     * that import's to label, while a term an import labels and the pattern only describes
     * (set's hasMember) still needs one in the pattern.
     */
    private static Set<String> reportAsksALabelFor(OWLOntology ontology) throws Exception {
        OWLOntologyManager apart = OWLManager.createOWLOntologyManager();
        OWLOntology alone = apart.createOntology(ontology.getAxioms(),
                ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get()
                        : IRI.create("urn:ontoboard:pattern-alone"));
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            apart.applyChange(new AddOntologyAnnotation(alone, annotation));
        }
        Map<String, String> options = org.obolibrary.robot.ReportOperation.getDefaultOptions();
        options.put(de.fizkarlsruhe.ise.ontoboard.robot.QualityReport.OPTION_PROFILE,
                new File(PatternAudit.ODK_PROFILE).getAbsolutePath());
        Set<String> asked = new TreeSet<String>();
        for (de.fizkarlsruhe.ise.ontoboard.robot.QualityFinding finding
                : de.fizkarlsruhe.ise.ontoboard.robot.QualityReport.run(alone, options)) {
            if ("missing_label".equals(finding.getRule()) && finding.getSubject() != null
                    && finding.getSubject().matches("^[a-z]+:.*")
                    && !finding.getSubject().equals(ontologyIri(ontology))) {
                asked.add(finding.getSubject());
            }
        }
        return asked;
    }

    private static void with(Map<String, Set<String>> byLabel, String label, String iri) {
        if (!byLabel.containsKey(label)) {
            byLabel.put(label, new TreeSet<String>());
        }
        byLabel.get(label).add(iri);
    }

    /**
     * Of terms that would all get one label from curation, the one that keeps it: an OBO term (a
     * project that imports its ontology then sees one label), else the one term the library's
     * authors label, else the one term of the pattern's own namespace, else - of one source - its
     * one class; null when none stands out.
     */
    private String keeperOf(Set<String> terms, Map<String, OWLEntity> entities, String own) {
        List<String> obo = new ArrayList<String>();
        List<String> authored = new ArrayList<String>();
        List<String> ours = new ArrayList<String>();
        List<String> classes = new ArrayList<String>();
        Set<String> sources = new HashSet<String>();
        for (String iri : terms) {
            if (iri.matches("^http://purl\\.obolibrary\\.org/obo/[A-Za-z]+_\\d+$")) {
                obo.add(iri);
            }
            if (library.containsKey(iri)) {
                authored.add(iri);
            }
            if (own != null && iri.startsWith(own)) {
                ours.add(iri);
            }
            if (entities.get(iri) != null && entities.get(iri).isOWLClass()) {
                classes.add(iri);
            }
            sources.add(sourceOf(iri));
        }
        if (!obo.isEmpty()) {
            return obo.size() == 1 ? obo.get(0) : null;
        }
        if (!authored.isEmpty()) {
            return authored.size() == 1 ? authored.get(0) : null;
        }
        if (ours.size() == 1) {
            return ours.get(0);
        }
        return sources.size() == 1 && classes.size() == 1 ? classes.get(0) : null;
    }

    /** What goes in brackets: the term's source - or its kind, when another of the same source shares the label. */
    private static String qualifierOf(String iri, Set<String> sharing, Map<String, OWLEntity> entities) {
        String source = sourceOf(iri);
        for (String other : sharing) {
            if (!other.equals(iri) && sourceOf(other).equals(source)) {
                OWLEntity entity = entities.get(iri);
                return entity == null ? "term" : entity.isOWLClass() ? "class" : entity.isOWLNamedIndividual()
                        ? "individual" : entity.isOWLDatatype() ? "datatype" : "property";
            }
        }
        return source;
    }

    /** Short names of the vocabularies the library uses most. */
    private static final String[][] SOURCES = {
        {"http://www.w3.org/2004/02/skos/core#", "SKOS"},
        {"http://purl.org/dc/elements/1.1/", "Dublin Core"},
        {"http://purl.org/dc/terms/", "DC terms"},
        {"http://schema.org/", "schema.org"},
        {"https://schema.org/", "schema.org"},
        {"http://www.w3.org/2006/time#", "OWL-Time"},
        {"http://xmlns.com/foaf/0.1/", "FOAF"},
        {"http://www.w3.org/ns/prov#", "PROV"},
        {"http://www.ontologydesignpatterns.org/ont/dul/DUL.owl#", "DUL"},
    };

    /**
     * A term's source, as a reader knows it: an OBO term's ID space (IAO), a well-known
     * vocabulary's name (SKOS, DC terms), else its namespace's last part (classification,
     * trajectory), else its host (prez.dev).
     */
    static String sourceOf(String iri) {
        Matcher obo = Pattern.compile("^http://purl\\.obolibrary\\.org/obo/([A-Za-z]+)_\\d+$").matcher(iri);
        if (obo.matches()) {
            return obo.group(1);
        }
        for (String[] source : SOURCES) {
            if (iri.startsWith(source[0])) {
                return source[1];
            }
        }
        String namespace = iri.substring(0, Math.max(0, Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'))))
                .replaceAll("[#/]+$", "");
        String last = namespace.substring(namespace.lastIndexOf('/') + 1).replaceAll("\\.(owl|ttl|rdf|xml)$", "");
        if (last.isEmpty() || last.matches("[0-9.]+")) {
            String host = namespace.replaceAll("^[a-z]+://", "");
            return host.contains("/") ? host.substring(0, host.indexOf('/')) : host;
        }
        return last;
    }

    // --- 8. refusals ---------------------------------------------------------------------------

    private static void refuseErrors(String id, OWLOntology ontology) {
        Set<String> unread = new TreeSet<String>();
        for (OWLEntity entity : ownSignature(ontology)) {
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
        String self = ontologyIri(ontology);
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (self != null && annotation.getProperty().getIRI().equals(RDFS_LABEL)
                    && annotation.getValue() instanceof OWLLiteral) {
                OWLLiteral value = (OWLLiteral) annotation.getValue();
                String key = quoted(value.getLiteral(), value.getLang());
                if (!byLabel.containsKey(key)) {
                    byLabel.put(key, new TreeSet<String>());
                }
                byLabel.get(key).add(self);
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
            // A blank node is a new one on every reading, so an axiom about one never matches.
            if (!axiom.getAnonymousIndividuals().isEmpty()) {
                continue;
            }
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
        // The source's prefixes one by one, never its default: re-binding a copied ":" leaves the
        // OWL API 4.5.9 Turtle writer abbreviating the old default namespace's terms as ":name".
        if (format instanceof PrefixDocumentFormat) {
            for (Map.Entry<String, String> prefix
                    : ((PrefixDocumentFormat) format).getPrefixName2PrefixMap().entrySet()) {
                if (!":".equals(prefix.getKey()) && !prefix.getKey().isEmpty()) {
                    rdfxml.setPrefix(prefix.getKey(), prefix.getValue());
                    turtle.setPrefix(prefix.getKey(), prefix.getValue());
                }
            }
        }
        for (String[] prefix : new String[][] {{"skos:", SKOS}, {"dcterms:", DCTERMS}}) {
            if (!rdfxml.getPrefixName2PrefixMap().containsValue(prefix[1])) {
                rdfxml.setPrefix(prefix[0], prefix[1]);
                turtle.setPrefix(prefix[0], prefix[1]);
            }
        }
        // Never the source's default prefix: a stale xmlns default (digitalvideo's names an
        // "untitled-ontology-17") makes the OWL API's Turtle writer abbreviate the pattern's own
        // terms into that namespace, so pattern.ttl named other terms than pattern.owl.
        String own = ownNamespace(ontology);
        if (own != null) {
            rdfxml.setDefaultPrefix(own);
            turtle.setDefaultPrefix(own);
        }
        writeLike(owlFile, render(manager, ontology, rdfxml));
        writeLike(ttlFile, render(manager, ontology, turtle));
    }

    /**
     * The pattern's own namespace: of its terms' namespaces, one that extends the ontology's IRI,
     * else the one most of them share - a well-known vocabulary or OBO never; the ontology's IRI
     * with "#" when it has no term of its own.
     */
    private static String ownNamespace(OWLOntology ontology) {
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (OWLEntity entity : ownSignature(ontology)) {
            String iri = entity.getIRI().toString();
            if (isBuiltIn(entity) || iri.startsWith("http://purl.obolibrary.org/obo/") || isVocabulary(iri)) {
                continue;
            }
            int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
            if (cut > 0) {
                counts.merge(iri.substring(0, cut + 1), 1, Integer::sum);
            }
        }
        String self = ontologyIri(ontology);
        String best = null;
        for (boolean extendsSelf : new boolean[] {true, false}) {
            for (Map.Entry<String, Integer> namespace : counts.entrySet()) {
                if (extendsSelf && (self == null || !namespace.getKey().startsWith(self))) {
                    continue;
                }
                if (best == null || namespace.getValue() > counts.get(best)) {
                    best = namespace.getKey();
                }
            }
            if (best != null) {
                return best;
            }
        }
        return self == null ? null : self + "#";
    }

    /** The namespace of the pattern's own terms when it extends the ontology's IRI, else null. */
    private static String selfNamespace(OWLOntology ontology) {
        String self = ontologyIri(ontology);
        String own = ownNamespace(ontology);
        return self != null && own != null && own.startsWith(self) ? own : null;
    }

    private static boolean isVocabulary(String iri) {
        for (String[] source : SOURCES) {
            if (iri.startsWith(source[0])) {
                return true;
            }
        }
        return false;
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

    /**
     * The terms the pattern's own file names: its axioms' and its header's. Not the OWL API's
     * getSignature(), which - once an import is loaded - can count every annotation property the
     * import declares (actingfor read with the ODP annotation schema had its extractedFrom, which
     * actingfor never uses), so a second curation declared and labelled terms the first had not.
     */
    private static Set<OWLEntity> ownSignature(OWLOntology ontology) {
        Set<OWLEntity> named = new TreeSet<OWLEntity>();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            named.addAll(axiom.getSignature());
        }
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            named.addAll(annotation.getSignature());
        }
        return named;
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
    static Map<String, Given> libraryLabels(File patternsDir, Map<String, Given> known, List<Fix> fixes,
            File stubs) throws Exception {
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
                String text = keeper.text;
                for (Fix fix : fixes) {
                    if (fix.pattern.equals(directory.getName()) && "rdfs:label".equals(fix.property)
                            && fix.iri.equals(subject.getKey()) && fix.old.equals(keeper.literal.getLiteral())) {
                        text = fix.neu;
                    }
                }
                String key = keeper.lang + "\t" + text;
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
            final Map<String, Set<String>> given = term.getValue();
            List<String> candidates = new ArrayList<String>(given.keySet());
            // Most patterns first; then tagged @en; then in words rather than a name ("has part",
            // not "hasPart"); then alphabetically.
            Collections.sort(candidates, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int byCount = given.get(b).size() - given.get(a).size();
                    if (byCount != 0) {
                        return byCount;
                    }
                    int byTag = tagRank(a.split("\t", 2)[0]) - tagRank(b.split("\t", 2)[0]);
                    if (byTag != 0) {
                        return byTag;
                    }
                    int byWords = (inWords(a.split("\t", 2)[1]) ? 0 : 1) - (inWords(b.split("\t", 2)[1]) ? 0 : 1);
                    return byWords != 0 ? byWords : a.compareTo(b);
                }
            });
            String best = candidates.get(0);
            String[] langAndText = best.split("\t", 2);
            labels.put(term.getKey(), new Given(langAndText[1], langAndText[0],
                    "pattern " + term.getValue().get(best).iterator().next()));
        }
        return labels;
    }

    private static int tagRank(String lang) {
        return "en".equalsIgnoreCase(lang) ? 0 : lang.isEmpty() ? 2 : 1;
    }

    /** "has part", "Agent" - not "hasPart". */
    private static boolean inWords(String text) {
        return text.contains(" ") || text.length() < 2 || !text.substring(1).matches(".*[A-Z].*");
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
        Map<String, Given> library = libraryLabels(patterns, known, fixes, stubs);
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
        List<File> everyPattern = new ArrayList<File>();
        for (File directory : patterns.listFiles()) {
            if (new File(directory, "pattern.owl").isFile()) {
                everyPattern.add(directory);
            }
        }
        Collections.sort(everyPattern);
        List<File> directories = new ArrayList<File>();
        if (args.length > 3) {
            for (int i = 3; i < args.length; i++) {
                directories.add(new File(patterns, args[i]));
            }
        } else {
            directories.addAll(everyPattern);
        }
        Map<String, String> qualifiedLabels = curation.qualifiedIn(everyPattern);
        System.out.println(qualifiedLabels.size() + " terms get their source in brackets in every pattern: "
                + qualifiedLabels);
        curation = curation.withQualified(qualifiedLabels);
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
