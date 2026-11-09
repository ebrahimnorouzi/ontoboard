package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The design patterns shipped inside the plugin, and what is in each one.
 *
 * <p>Asked for: "I want to have a pattern repository to import ODPs to the ontology, and have
 * these patterns, separated by the source, and uses ODK to import terms from the pattern." The
 * 123 patterns were already in the repository and reachable from nothing.
 *
 * <p><b>Importing a pattern is importing terms.</b> There is no second extraction path here: a
 * chosen pattern hands its signature to the same ROBOT extract that {@code ROBOT > Import
 * terms...} uses, which writes the term list, the module, the import and the catalog entry the
 * way ODK expects them. That matters more than it sounds, because 101 of the 123 patterns
 * declare {@code owl:imports} - 76 of them to the ODP annotation schema, others to DUL or to
 * sibling patterns. Copying a pattern file into a project would drag an upper ontology in behind
 * it; extracting a module takes the terms asked for and the axioms that give them meaning.
 */
public final class PatternLibrary {

    /** Where the patterns sit in the bundle. Trailing slash: it is concatenated, not joined. */
    public static final String DIRECTORY = "patterns/";

    private static volatile List<DesignPattern> cached;

    private PatternLibrary() {
    }

    /**
     * Every pattern in the library, in id order, or an empty list if the index is missing.
     *
     * <p>Empty rather than an exception: a library that fails to load should grey out a menu
     * item, not break the session that opened it.
     */
    public static List<DesignPattern> all() {
        List<DesignPattern> patterns = cached;
        if (patterns == null) {
            try {
                patterns = PatternIndex.readShipped();
            } catch (IOException unreadable) {
                patterns = Collections.emptyList();
            }
            cached = patterns;
        }
        return patterns;
    }

    /** Forgets the loaded index and everything parsed from it. For tests. */
    static void forget() {
        cached = null;
        PARSED.clear();
    }

    /** The pattern with that id, or null. */
    public static DesignPattern find(String id) {
        for (DesignPattern pattern : all()) {
            if (pattern.getId().equals(id)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * How the library's list names the pattern a duplicate is marked against: "same as" when the
     * two have exactly the same terms, "another version of" when they only declare the same
     * ontology IRI. Empty for a pattern that is no duplicate.
     *
     * <p>Found by a tester: <i>Collection Entity Pattern</i> was listed as "same as collection",
     * although it has a class, Entity, that <i>Collection Pattern</i> lacks - and an expert who
     * took the note at its word would import the one that cannot hold what the task needs.
     */
    public static String twinNote(DesignPattern pattern) {
        if (!pattern.isDuplicate()) {
            return "";
        }
        DesignPattern twin = find(pattern.getSameAs());
        boolean same = twin != null && new java.util.HashSet<String>(twin.getTermIris())
                .equals(new java.util.HashSet<String>(pattern.getTermIris()));
        return (same ? "same as " : "another version of ") + pattern.getSameAs();
    }

    /**
     * The patterns grouped by publisher, publishers in name order, each group in id order.
     *
     * <p>The grouping the request asked for. Grouping by {@code collection} would produce one
     * bucket holding everything, because the whole library came from one harvest of the ODP
     * portal; the portal is a catalogue of submissions from thirteen different publishers, and
     * that is the division that tells a user something.
     */
    public static Map<String, List<DesignPattern>> byPublisher() {
        return grouped(true);
    }

    /** The same, by category: structural, behavioural, conceptual and so on. */
    public static Map<String, List<DesignPattern>> byCategory() {
        return grouped(false);
    }

    private static Map<String, List<DesignPattern>> grouped(boolean byPublisher) {
        Map<String, List<DesignPattern>> groups = new LinkedHashMap<String, List<DesignPattern>>();
        for (String key : new TreeSet<String>(keysOf(byPublisher))) {
            groups.put(key, new ArrayList<DesignPattern>());
        }
        for (DesignPattern pattern : all()) {
            groups.get(byPublisher ? pattern.getPublisher() : pattern.getCategory()).add(pattern);
        }
        return Collections.unmodifiableMap(groups);
    }

    private static List<String> keysOf(boolean byPublisher) {
        List<String> keys = new ArrayList<String>();
        for (DesignPattern pattern : all()) {
            keys.add(byPublisher ? pattern.getPublisher() : pattern.getCategory());
        }
        return keys;
    }

    /**
     * Patterns whose name, id or description contains every word of the query.
     *
     * <p>Every word rather than any: with 123 entries, "time part" should narrow rather than
     * widen.
     */
    public static List<DesignPattern> matching(String query) {
        return matching(all(), query);
    }

    /**
     * The same patterns, those whose name says what was searched for first: the name itself, then
     * its words, then a word's stem - {@code measured} in {@code Measurement} - then the rest, in
     * the order they came. Stable, so an ordering chosen in the browser still orders each group.
     *
     * <p>Found by three testers and two scenario builders: the list kept the library's order and
     * the first row was selected, so a search for {@code place} offered a pattern about material
     * entities with <i>Place Pattern</i> tenth, and {@code measured} or {@code dataset} put another
     * pattern in front of the one the guide named - one press of Import terms... from the wrong
     * import.
     */
    public static List<DesignPattern> nameMatchesFirst(List<DesignPattern> patterns,
            String query) {
        List<DesignPattern> ordered = new ArrayList<DesignPattern>(patterns);
        if (query == null || query.trim().isEmpty()) {
            return ordered;
        }
        final String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        Collections.sort(ordered, (one, other) -> Integer.compare(
                nameRank(one.getName(), words), nameRank(other.getName(), words)));
        return ordered;
    }

    /** 0 the name is the query, 1 every word is a word of the name, 2 every stem is in it, 3 not. */
    static int nameRank(String name, String[] words) {
        String plain = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceFirst("^pattern \\d+:\\s*", "").replaceFirst("\\s+(odp\\s+)?pattern$", "")
                .trim();
        if (plain.equals(String.join(" ", words))) {
            return 0;
        }
        List<String> nameWords = java.util.Arrays.asList(plain.split("[^\\p{L}\\p{N}]+"));
        boolean allWords = true;
        boolean allStems = true;
        for (String word : words) {
            allWords &= nameWords.contains(word);
            allStems &= plain.contains(stem(word));
        }
        return allWords ? 1 : allStems ? 2 : 3;
    }

    /** A word without the endings English puts on it, when enough of it is left to mean much. */
    static String stem(String word) {
        for (String ending : new String[] {"ments", "ment", "ings", "ing", "ed", "es", "s"}) {
            if (word.endsWith(ending) && word.length() - ending.length() >= 4) {
                return word.substring(0, word.length() - ending.length());
            }
        }
        return word;
    }

    /**
     * The same filter over a list given to it, rather than over the shipped library.
     *
     * <p>What the browser uses, because the list it shows is the shipped patterns plus whatever
     * the user contributed, and searching has to reach both. {@link #all()} deliberately still
     * means only what is in the jar: it is cached for the session and a folder on disk is not.
     */
    public static List<DesignPattern> matching(List<DesignPattern> patterns, String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<DesignPattern>(patterns));
        }
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<DesignPattern> hits = new ArrayList<DesignPattern>();
        for (DesignPattern pattern : patterns) {
            // THE COMPETENCY QUESTIONS ARE SEARCHED TOO, because they are the one field written
            // in the words somebody arrives with. A curator does not search for "behavioral" or
            // for a pattern's id; they search for "who works for which organization", which is
            // what actingfor's coversRequirements says almost verbatim. 123 of the 163 patterns
            // carry questions, and leaving them out meant the library could not be searched by
            // the question it exists to answer.
            String haystack = (pattern.getId() + " " + pattern.getName() + " "
                    + pattern.getDescription() + " " + pattern.getCategory() + " "
                    + pattern.getDomain() + " " + pattern.getCompetencyQuestions())
                    .toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                hits.add(pattern);
            }
        }
        return Collections.unmodifiableList(hits);
    }

    /**
     * The patterns {@link #matching} leaves out whose terms are called what was typed: every word
     * of the query in the labels of the pattern's own classes and properties.
     *
     * <p>Separate, so the browser can list these after what matched by name, description and
     * questions: a pattern that merely contains a {@code hardness} class is a weaker answer to
     * "hardness" than one about it. But an answer. Searching for the thing you want to model is
     * how anybody new to a library searches it, and the patterns are mostly described in their own
     * words - "Nothing matches." for {@code hardness}, with a pattern holding {@code hardness} and
     * {@code indentation hardness} right there, was a dead end found in a rehearsal of the study.
     */
    public static List<DesignPattern> matchingByTerms(List<DesignPattern> patterns,
            String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<DesignPattern> already = matching(patterns, query);
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<DesignPattern> hits = new ArrayList<DesignPattern>();
        for (DesignPattern pattern : patterns) {
            if (already.contains(pattern) || pattern.getTermLabels().isEmpty()) {
                continue;
            }
            String haystack = String.join(" | ", pattern.getTermLabels()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : words) {
                if (!haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                hits.add(pattern);
            }
        }
        return Collections.unmodifiableList(hits);
    }

    /**
     * The labels of the pattern's terms that hold a word of the query, at most three: what the
     * browser says a pattern was found by when nothing else about it matched.
     */
    public static List<String> termsMatching(DesignPattern pattern, String query) {
        List<String> found = new ArrayList<String>();
        if (pattern == null || query == null || query.trim().isEmpty()) {
            return found;
        }
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        for (String label : pattern.getTermLabels()) {
            String lower = label.toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (lower.contains(word)) {
                    found.add(label);
                    break;
                }
            }
            if (found.size() == 3) {
                break;
            }
        }
        return found;
    }

    // ---------- opening one ----------

    /** What a pattern actually contains, read from its file rather than from its metadata. */
    public static final class Contents {
        private final List<OWLEntity> classes;
        private final List<OWLEntity> properties;
        private final List<IRI> imports;
        private final IRI ontologyIri;
        private final Map<IRI, String> labels;

        Contents(List<OWLEntity> classes, List<OWLEntity> properties, List<IRI> imports,
                IRI ontologyIri, Map<IRI, String> labels) {
            this.labels = Collections.unmodifiableMap(labels);
            this.classes = Collections.unmodifiableList(classes);
            this.properties = Collections.unmodifiableList(properties);
            this.imports = Collections.unmodifiableList(imports);
            this.ontologyIri = ontologyIri;
        }

        public List<OWLEntity> getClasses() {
            return classes;
        }

        public List<OWLEntity> getProperties() {
            return properties;
        }

        /**
         * What a term is called in the pattern, or null if nothing says. A pattern's identifiers
         * are mostly numbers, BFO_0000004 and the like, and a list of those tells nobody what they
         * are about to import.
         */
        public String labelOf(IRI term) {
            return labels.get(term);
        }

        /** Whether a term is a class rather than a property. */
        public boolean isClass(IRI term) {
            for (OWLEntity entity : classes) {
                if (entity.getIRI().equals(term)) {
                    return true;
                }
            }
            return false;
        }

        /** Everything the pattern declares, classes then properties. */
        public List<IRI> getTerms() {
            List<IRI> terms = new ArrayList<IRI>();
            for (OWLEntity entity : classes) {
                terms.add(entity.getIRI());
            }
            for (OWLEntity entity : properties) {
                terms.add(entity.getIRI());
            }
            return Collections.unmodifiableList(terms);
        }

        /** What the pattern imports, which an extracted module will not bring with it. */
        public List<IRI> getImports() {
            return imports;
        }

        /** The IRI the pattern declares, or null when it declares none. */
        public IRI getOntologyIri() {
            return ontologyIri;
        }

        public String describe() {
            return getClasses().size() + " classes, " + getProperties().size() + " properties";
        }
    }

    /**
     * What has already been parsed, because parsing the library is expensive.
     *
     * <p>Measured: one pass over all 123 patterns takes 24 seconds, which is what the
     * recommender needs to answer a single question. Unusable in a dialog, and it would have
     * shipped that way - nothing about the code looks slow, and the cost only appears when
     * something asks about every pattern at once rather than the one a user clicked.
     *
     * <p>Safe to hold for the session, by two different arguments. A shipped pattern is inside
     * the jar and cannot change under it. A contributed one is a file the user may well be
     * editing, so {@link #cacheKey} folds its size and timestamp into the key and an edit is
     * read again. What is kept either way is the summary - lists of entities and import IRIs -
     * not the ontologies, so the {@code OWLOntology} objects and their managers are still
     * collected.
     */
    private static final java.util.Map<String, Contents> PARSED =
            new java.util.concurrent.ConcurrentHashMap<String, Contents>();

    /**
     * Reads the pattern's OWL file out of the bundle and reports what is in it.
     *
     * <p>By a known resource path, never by listing a directory: under Felix a classpath
     * directory resolves to a {@code bundle:} URL that cannot be enumerated, which is why the
     * index exists at all.
     *
     * @throws IOException if the pattern is not in the bundle or will not parse
     */
    public static Contents contentsOf(DesignPattern pattern) throws IOException {
        String key = cacheKey(pattern);
        Contents already = PARSED.get(key);
        if (already != null) {
            return already;
        }
        Contents read = parse(pattern);
        PARSED.put(key, read);
        return read;
    }

    /**
     * What makes a parse reusable: the id, and for a contributed pattern its file's state.
     *
     * <p>A shipped pattern cannot change under the session. A contributed one is a file the user
     * is quite likely editing - that is the point of being able to contribute it - so its size
     * and timestamp are part of the key. Editing a pattern and looking at it again shows what it
     * now says, and nothing is re-parsed that did not change.
     */
    private static String cacheKey(DesignPattern pattern) {
        if (!pattern.isContributed()) {
            return pattern.getId();
        }
        File file = pattern.getFile();
        return pattern.getId() + "@" + file.length() + "@" + file.lastModified();
    }

    private static Contents parse(DesignPattern pattern) throws IOException {
        OWLOntology ontology = load(pattern);
        List<OWLEntity> classes = new ArrayList<OWLEntity>();
        List<OWLEntity> properties = new ArrayList<OWLEntity>();
        // NO BUILT-INS. owl:Thing is in the class signature of 47 of the 162 patterns, and
        // owl:Nothing in one, and neither is a term of any of them. Listed, owl:Thing was offered
        // for import and boxed inside the pattern's frame, where a Mac smoke of 1.129.0 showed it
        // as one of Componency's "2 terms" with two edge labels drawn over it, and as the
        // "1 already on the board" in Price's. PatternIndex.termsOf already skipped built-ins, so
        // the index and the dialog disagreed about what a pattern's terms are.
        for (OWLEntity entity : sorted(ontology.getClassesInSignature(Imports.EXCLUDED))) {
            if (!entity.isBuiltIn()) {
                classes.add(entity);
            }
        }
        for (OWLObjectProperty property
                : sorted(ontology.getObjectPropertiesInSignature(Imports.EXCLUDED))) {
            if (!property.isBuiltIn()) {
                properties.add(property);
            }
        }
        for (OWLDataProperty property
                : sorted(ontology.getDataPropertiesInSignature(Imports.EXCLUDED))) {
            if (!property.isBuiltIn()) {
                properties.add(property);
            }
        }
        List<IRI> imports = new ArrayList<IRI>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            imports.add(declaration.getIRI());
        }
        Collections.sort(imports);
        com.google.common.base.Optional<IRI> iri = ontology.getOntologyID().getOntologyIRI();
        Map<IRI, String> labels = new java.util.HashMap<IRI, String>();
        List<OWLEntity> named = new ArrayList<OWLEntity>(classes);
        named.addAll(properties);
        for (OWLEntity entity : named) {
            String label = de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels.forEntity(ontology,
                    entity);
            if (label != null && !label.trim().isEmpty()
                    && !label.equals(entity.getIRI().getShortForm())) {
                labels.put(entity.getIRI(), label.trim());
            }
        }
        return new Contents(classes, properties, imports, iri.isPresent() ? iri.get() : null,
                labels);
    }

    private static <T extends OWLEntity> List<T> sorted(java.util.Set<T> entities) {
        List<T> list = new ArrayList<T>(entities);
        Collections.sort(list, new java.util.Comparator<T>() {
            @Override
            public int compare(T left, T right) {
                return left.getIRI().toString().compareTo(right.getIRI().toString());
            }
        });
        return list;
    }

    /**
     * The pattern's bytes, from the jar or from the user's folder.
     *
     * <p>The one place that knows a pattern can come from either. Everything else - parsing,
     * copying out, the recommender, the browser - works the same for both kinds because of it.
     *
     * @throws IOException if it is not where the pattern says it is
     */
    static InputStream openStream(DesignPattern pattern) throws IOException {
        if (pattern.isContributed()) {
            if (!pattern.getFile().isFile()) {
                throw new IOException("No longer there: " + pattern.getFile().getAbsolutePath());
            }
            return Files.newInputStream(pattern.getFile().toPath());
        }
        InputStream stream = PatternLibrary.class.getClassLoader()
                .getResourceAsStream(pattern.getResourcePath());
        if (stream == null) {
            throw new IOException("Not in this build: " + pattern.getResourcePath());
        }
        return stream;
    }

    /** Loads the pattern, without chasing its imports over the network. */
    public static OWLOntology load(DesignPattern pattern) throws IOException {
        return read(pattern, baseFor(pattern), false);
    }

    /**
     * Reads the pattern as if from {@code documentIri}, its imports answered by nothing.
     *
     * <p>For saying, before anything is copied, what the import will make of the copy: a pattern
     * that declares no ontology IRI, or a relative one, is named after the file it is read from,
     * and the import reads it from its copy under {@code mirror/}, not from inside the jar - see
     * {@link #copyFor}. Its imports are not asked for at all, because the question is only what
     * the pattern calls itself, and it is asked while somebody waits for a dialog.
     */
    public static OWLOntology loadAs(DesignPattern pattern, IRI documentIri) throws IOException {
        return read(pattern, documentIri, true);
    }

    private static OWLOntology read(DesignPattern pattern, IRI documentIri,
            boolean importsNowhere) throws IOException {
        InputStream stream = openStream(pattern);
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            // SILENT, and the import list is reported separately instead. 101 of the 123 declare
            // imports; resolving them would make opening a pattern depend on thirteen websites,
            // and would pull DUL in behind the four that import it.
            manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
            if (importsNowhere) {
                final IRI nowhere = IRI.create("file:/" + DIRECTORY + "no-such-import.owl");
                manager.getIRIMappers().add(iri -> nowhere);
            }
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.StreamDocumentSource(stream, documentIri));
        } catch (Exception broken) {
            throw new IOException("Cannot read " + pattern.getId() + ": " + broken.getMessage(),
                    broken);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Already read, or already broken; neither changes what to report.
            }
        }
    }

    /**
     * A document IRI for a pattern read out of the bundle, under the {@code file} scheme.
     *
     * <p>A stream with no document IRI is not the same as a file, and two patterns show it. Both
     * airline patterns declare {@code owl:imports file:/schemas/cpannotationschema.owl}. Loaded
     * from a file that resolves to a {@code file:} IRI, the OWL API fails to fetch it and the
     * silent missing-import setting swallows the failure. Loaded from a bare stream there is no
     * base, the import becomes {@code urn:absolute:/schemas/cpannotationschema.owl}, no factory
     * handles that scheme, and the exception thrown is not a missing import at all - so it is
     * not silenced, and the pattern will not open. The path named here need not exist; it only
     * has to be a {@code file:} IRI, so that an unresolvable import fails the way the strategy
     * expects it to.
     */
    static IRI baseFor(DesignPattern pattern) {
        if (pattern.isContributed()) {
            // Not the file's own URI, which would be the obvious choice and would let a sibling
            // import resolve. Measured: when every parser fails, the OWL API opens the document
            // IRI and never closes it, so a user's own pattern that does not parse becomes a file
            // they cannot delete until Protege exits - see PatternIndex.load. A sibling import
            // resolving is worth less than that.
            return IRI.create("file:/" + DIRECTORY + "yours/" + pattern.getId()
                    + extensionOf(pattern));
        }
        return IRI.create("file:/" + DIRECTORY + pattern.getId() + "/pattern.owl");
    }

    /**
     * Writes the pattern's OWL file to disk, so ROBOT can be pointed at it by IRI.
     *
     * <p>The extraction path takes a source ontology named by an IRI. A resource inside an OSGi
     * bundle has no file IRI, so it is copied out first - to the directory given, which for an
     * ODK project should be one that is committed, because a module whose source cannot be found
     * again is a module nobody dares regenerate.
     */
    public static File copyTo(DesignPattern pattern, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory.getAbsolutePath());
        }
        File target = copyFor(pattern, directory);
        InputStream stream = openStream(pattern);
        try {
            OutputStream out = Files.newOutputStream(target.toPath());
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            stream.close();
        }
        return target;
    }

    /** The file {@link #copyTo} writes in {@code directory}, whether or not it exists yet. */
    public static File copyFor(DesignPattern pattern, File directory) {
        return new File(directory, pattern.getId() + extensionOf(pattern));
    }

    /**
     * The extension the copy keeps, which for a contributed pattern is its own.
     *
     * <p>Every shipped pattern is RDF/XML, so {@code .owl} was always right for them. A
     * contributed one may well be Turtle - this library ships a {@code .ttl} beside every
     * pattern, so a user who copies one out to edit is holding Turtle - and writing Turtle into
     * a file called {@code .owl} would hand ROBOT a file whose name lies about its syntax.
     */
    static String extensionOf(DesignPattern pattern) {
        if (!pattern.isContributed()) {
            return ".owl";
        }
        String name = pattern.getFile().getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : ".owl";
    }
}
