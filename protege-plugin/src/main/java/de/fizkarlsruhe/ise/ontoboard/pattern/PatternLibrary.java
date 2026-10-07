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
        if (query == null || query.trim().isEmpty()) {
            return all();
        }
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        List<DesignPattern> hits = new ArrayList<DesignPattern>();
        for (DesignPattern pattern : all()) {
            String haystack = (pattern.getId() + " " + pattern.getName() + " "
                    + pattern.getDescription() + " " + pattern.getCategory() + " "
                    + pattern.getDomain()).toLowerCase(Locale.ROOT);
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

    // ---------- opening one ----------

    /** What a pattern actually contains, read from its file rather than from its metadata. */
    public static final class Contents {
        private final List<OWLEntity> classes;
        private final List<OWLEntity> properties;
        private final List<IRI> imports;
        private final IRI ontologyIri;

        Contents(List<OWLEntity> classes, List<OWLEntity> properties, List<IRI> imports,
                IRI ontologyIri) {
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
     * <p>Safe to hold for the session: these files are inside the jar and cannot change under
     * it. What is kept is the summary - lists of entities and import IRIs - not the ontologies,
     * so the 123 {@code OWLOntology} objects and their managers are still collected.
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
        Contents already = PARSED.get(pattern.getId());
        if (already != null) {
            return already;
        }
        Contents read = parse(pattern);
        PARSED.put(pattern.getId(), read);
        return read;
    }

    private static Contents parse(DesignPattern pattern) throws IOException {
        OWLOntology ontology = load(pattern);
        List<OWLEntity> classes = new ArrayList<OWLEntity>();
        List<OWLEntity> properties = new ArrayList<OWLEntity>();
        for (OWLEntity entity : sorted(ontology.getClassesInSignature(Imports.EXCLUDED))) {
            classes.add(entity);
        }
        for (OWLObjectProperty property
                : sorted(ontology.getObjectPropertiesInSignature(Imports.EXCLUDED))) {
            properties.add(property);
        }
        for (OWLDataProperty property
                : sorted(ontology.getDataPropertiesInSignature(Imports.EXCLUDED))) {
            properties.add(property);
        }
        List<IRI> imports = new ArrayList<IRI>();
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            imports.add(declaration.getIRI());
        }
        Collections.sort(imports);
        com.google.common.base.Optional<IRI> iri = ontology.getOntologyID().getOntologyIRI();
        return new Contents(classes, properties, imports, iri.isPresent() ? iri.get() : null);
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

    /** Loads the pattern, without chasing its imports over the network. */
    public static OWLOntology load(DesignPattern pattern) throws IOException {
        InputStream stream = PatternLibrary.class.getClassLoader()
                .getResourceAsStream(pattern.getResourcePath());
        if (stream == null) {
            throw new IOException("Not in this build: " + pattern.getResourcePath());
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            // SILENT, and the import list is reported separately instead. 101 of the 123 declare
            // imports; resolving them would make opening a pattern depend on thirteen websites,
            // and would pull DUL in behind the four that import it.
            manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.StreamDocumentSource(stream, baseFor(pattern)));
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
        File target = new File(directory, pattern.getId() + ".owl");
        InputStream stream = PatternLibrary.class.getClassLoader()
                .getResourceAsStream(pattern.getResourcePath());
        if (stream == null) {
            throw new IOException("Not in this build: " + pattern.getResourcePath());
        }
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
}
