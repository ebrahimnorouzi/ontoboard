package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.yaml.snakeyaml.Yaml;

/**
 * The format of {@code patterns/index.tsv}, and the code that produces it from the directories.
 *
 * <p>Reader and writer live together on purpose: the shipped index is generated from the pattern
 * directories, and a test regenerates it and compares, so a pattern added without reindexing
 * fails the build rather than going missing from the library in silence.
 *
 * <p><b>Why an index at all.</b> A jar cannot be listed. {@code ClassLoader.getResource} on a
 * directory gives a {@code bundle:} URL under Felix that cannot be enumerated - the same trap
 * {@link de.fizkarlsruhe.ise.ontoboard.robot.ReportQueries} documents for ROBOT's own report
 * queries. {@code getResourceAsStream} on a known path works perfectly, so the library needs a
 * known path listing the rest.
 */
public final class PatternIndex {

    /** The index, beside the pattern directories. */
    public static final String RESOURCE = PatternLibrary.DIRECTORY + "index.tsv";

    /** Ten tab-separated columns. */
    static final int COLUMNS = 10;

    private PatternIndex() {
    }

    // ---------- reading ----------

    /** Parses an index, skipping comments and blank lines. Never returns null. */
    public static List<DesignPattern> read(Reader source) throws IOException {
        List<DesignPattern> patterns = new ArrayList<DesignPattern>();
        BufferedReader lines = new BufferedReader(source);
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            // -1, so a trailing empty description is a column rather than a missing one.
            String[] cells = line.split("\t", -1);
            if (cells.length < COLUMNS) {
                continue;
            }
            patterns.add(new DesignPattern(cells[0], cells[1], cells[2], cells[3], cells[4],
                    cells[5], cells[6], cells[7], cells[8], cells[9]));
        }
        return Collections.unmodifiableList(patterns);
    }

    // ---------- writing ----------

    /**
     * Builds the index from the pattern directories, loading each OWL file.
     *
     * <p>Slow - 123 ontologies - and not called at runtime. The library reads the generated
     * file; this exists so that generating it and checking it are the same code.
     */
    public static String buildFrom(File patternsDirectory) throws IOException {
        File[] found = patternsDirectory.listFiles();
        List<File> directories = new ArrayList<File>();
        if (found != null) {
            for (File candidate : found) {
                if (candidate.isDirectory() && new File(candidate, "metadata.json").isFile()
                        && new File(candidate, "pattern.owl").isFile()) {
                    directories.add(candidate);
                }
            }
        }
        Collections.sort(directories, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });

        Map<String, Map<String, Object>> metadata = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, String> signatures = new LinkedHashMap<String, String>();
        Map<String, String> terms = new LinkedHashMap<String, String>();
        Map<String, String> declaredIris = new LinkedHashMap<String, String>();
        for (File directory : directories) {
            String id = directory.getName();
            metadata.put(id, metadataIn(new File(directory, "metadata.json")));
            OWLOntology ontology = load(new File(directory, "pattern.owl"));
            signatures.put(id, signatureOf(ontology));
            terms.put(id, termsOf(ontology));
            declaredIris.put(id, ontologyIriOf(ontology));
        }

        Map<String, String> sameAs = duplicatesIn(metadata, signatures);

        StringBuilder out = new StringBuilder();
        for (String comment : HEADER) {
            out.append(comment).append('\n');
        }
        for (Map.Entry<String, Map<String, Object>> entry : metadata.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> one = entry.getValue();
            String declared = text(one.get("pattern_iri"));
            // A machine-local file: IRI says nothing about who published it. Three of these
            // carry an absolute path from the machine the harvest ran on, naming a directory
            // that is no longer in the repository.
            String forPublisher = declared.startsWith("file:") ? declaredIris.get(id) : declared;
            out.append(id).append('\t')
               .append(oneLine(text(one.get("name")), id)).append('\t')
               .append(oneLine(text(one.get("collection")), COLLECTION_ODP)).append('\t')
               .append(publisherFor(one, forPublisher)).append('\t')
               .append(oneLine(text(one.get("category")), "uncategorised")).append('\t')
               .append(oneLine(text(one.get("domain")), "general")).append('\t')
               .append(sameAs.containsKey(id) ? sameAs.get(id) : "").append('\t')
               .append(descriptionOf(one)).append('\t')
               .append(oneLine(text(one.get("competency_questions")), "")).append('\t')
               .append(terms.containsKey(id) ? terms.get(id) : "").append('\n');
        }
        return out.toString();
    }

    /** The only collection there is so far: everything was harvested from the ODP portal. */
    public static final String COLLECTION_ODP = "odp";

    private static final String[] HEADER = {
        "# OntoBoard pattern index. One row per pattern, tab separated.",
        "#",
        "# Generated by PatternIndex.buildFrom from the pattern directories beside it.",
        "# PatternIndexTest regenerates it and fails if this file no longer matches, so a",
        "# pattern added without reindexing breaks the build instead of going missing from the",
        "# library in silence.",
        "#",
        "# There are no counts here on purpose. The harvested metadata states a class_count for",
        "# every pattern and it is wrong for 41 of the 123, and a property_count wrong for 17.",
        "# The only number worth showing is the one read from the OWL file when it is opened.",
        "#",
        "# id\tname\tcollection\tpublisher\tcategory\tdomain\tsameAs\tdescription"
                + "\tcompetencyQuestions\tterms",
    };

    // ---------- the pieces, each testable on its own ----------

    /**
     * Which patterns are another one under a second name.
     *
     * <p>Two signals, because neither finds all of them. An identical signature catches the five
     * that are the same file - {@code collection} and {@code collectionentity}. The same declared
     * IRI catches four more whose files differ slightly while claiming to be the same ontology -
     * {@code agentrole} and {@code agent-role}. A {@code file:} IRI is never a signal: three
     * patterns carry one pointing at the harvesting machine, and they would otherwise look
     * related to each other for no better reason than that.
     *
     * @return duplicate id to the id it duplicates; the alphabetically first of a group is kept
     */
    static Map<String, String> duplicatesIn(Map<String, Map<String, Object>> metadata,
            Map<String, String> signatures) {
        Map<String, String> sameAs = new LinkedHashMap<String, String>();
        Map<String, List<String>> bySignature = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, String> entry : signatures.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                add(bySignature, entry.getValue(), entry.getKey());
            }
        }
        collapse(bySignature, sameAs);

        Map<String, List<String>> byIri = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, Map<String, Object>> entry : metadata.entrySet()) {
            String iri = text(entry.getValue().get("pattern_iri"));
            if (!iri.isEmpty() && !iri.startsWith("file:")) {
                add(byIri, iri, entry.getKey());
            }
        }
        collapse(byIri, sameAs);
        return sameAs;
    }

    private static void add(Map<String, List<String>> groups, String key, String id) {
        List<String> members = groups.get(key);
        if (members == null) {
            members = new ArrayList<String>();
            groups.put(key, members);
        }
        members.add(id);
    }

    private static void collapse(Map<String, List<String>> groups, Map<String, String> sameAs) {
        for (List<String> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<String> sorted = new ArrayList<String>(group);
            Collections.sort(sorted);
            String canonical = sorted.get(0);
            for (String id : sorted) {
                if (!id.equals(canonical) && !sameAs.containsKey(id)) {
                    sameAs.put(id, canonical);
                }
            }
        }
    }

    /**
     * The description, with the wiki headings the harvest swept up taken off the front.
     *
     * <p>Seventeen of the 123 begin with {@code Diagram (this article has no graphical
     * representation)} or {@code Diagram | Intent: |} - section headings from the ODP wiki page,
     * not prose about the pattern. For eight of them the heading is the whole field, so after
     * cleaning there is genuinely nothing; those get the competency questions if there are any,
     * and otherwise nothing at all. A heading shown where a description belongs tells the reader
     * the pattern was documented when it was not.
     */
    static String descriptionOf(Map<String, Object> metadata) {
        String text = oneLine(text(metadata.get("description")), "");
        text = text.replaceAll("(?i)^\\s*Diagram\\s*"
                + "(\\(this article has no graphical representation\\))?\\s*\\|?\\s*", "");
        text = text.replaceAll("(?i)^\\s*Intent:\\s*\\|?\\s*", "");
        text = text.replaceAll("^[\\s|]+", "").trim();
        if (!text.isEmpty()) {
            return text;
        }
        String scenarios = oneLine(text(metadata.get("scenarios")), "");
        return scenarios.isEmpty()
                ? oneLine(text(metadata.get("competency_questions")), "") : scenarios;
    }

    /**
     * Who published the pattern: what the metadata says, or the host of its own IRI.
     *
     * <p>Both, because the two kinds of entry answer the question differently. An ODP pattern
     * declares the IRI it was published under, so the host of that IRI is the publisher. A
     * pattern harvested from a documentation page has a module IRI this project minted, which
     * says nothing about anybody - so the harvest records the publisher directly, and that is
     * believed in preference to deriving nonsense from our own IRI.
     */
    static String publisherFor(Map<String, Object> metadata, String ownIri) {
        String declared = oneLine(text(metadata.get("publisher")), "");
        return declared.isEmpty() ? publisherOf(ownIri) : declared;
    }

    /** The publisher, as the host of the IRI a pattern declares for itself. */
    static String publisherOf(String iri) {
        if (iri == null || iri.trim().isEmpty()) {
            return UNKNOWN_PUBLISHER;
        }
        try {
            String host = new URI(iri.trim()).getHost();
            if (host == null || host.isEmpty()) {
                return UNKNOWN_PUBLISHER;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception notAUri) {
            return UNKNOWN_PUBLISHER;
        }
    }

    /** Shown for a pattern whose own IRI names no host - a {@code file:} path, or none. */
    public static final String UNKNOWN_PUBLISHER = "unknown";

    /** A cell: no tabs, no newlines, no runs of spaces, never empty unless the fallback is. */
    static String oneLine(String value, String fallback) {
        String one = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        while (one.contains("  ")) {
            one = one.replace("  ", " ");
        }
        one = one.trim();
        return one.isEmpty() ? fallback : one;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataIn(File json) throws IOException {
        // JSON is YAML, and snakeyaml is already in the bundle. Its load() returns Object in the
        // copy protege-editor-owl brings, so the cast is what makes this compile against either.
        Object parsed = new Yaml().load(
                new String(Files.readAllBytes(json.toPath()), StandardCharsets.UTF_8));
        return parsed instanceof Map ? (Map<String, Object>) parsed
                : new LinkedHashMap<String, Object>();
    }

    /**
     * Loads a pattern from a file, imports left unresolved.
     *
     * <p>Package-visible because {@link ContributedPatterns} reads a user's own files the
     * same way. A second loader with a different missing-import setting would make a
     * contributed pattern behave unlike a shipped one for no reason anybody chose.
     */
    static OWLOntology load(File owl) throws IOException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        // Not over the network. 101 of the 123 declare owl:imports - 76 of them to the ODP
        // annotation schema alone - and chasing those would make indexing depend on thirteen
        // websites still being up.
        manager.setOntologyLoaderConfiguration(manager.getOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
        // Through a stream this code closes, under a document IRI that is not the file's own.
        // Both halves are needed, and the second is the surprising one.
        //
        // manager.loadOntologyFromOntologyDocument(File) leaves a handle on the file. On Windows
        // that means the file cannot be deleted or renamed, and in some editors cannot be saved
        // over, once the library has looked at it - which was invisible while every pattern was a
        // read-only resource in the jar and became a defect the moment a pattern was a file
        // somebody owns. Found by ContributedPatternsTest: nineteen tests passed every assertion
        // and then failed because JUnit could not remove its own temporary directory.
        //
        // Closing our own stream is not enough. Measured: a StreamDocumentSource is single-use -
        // after the first parser reads it, it reports "InputStream not available" - so the OWL API
        // falls back to opening the document IRI itself, and on the path where every parser fails
        // it never closes that. A StringDocumentSource leaks identically, so the source is not the
        // lever; the IRI is. With a synthetic file: IRI the file is never opened by anything but
        // this method, and it stays deletable.
        //
        // Still a file: IRI, because that is what makes an unresolvable owl:imports fail the way
        // MissingImportHandlingStrategy.SILENT expects - see PatternLibrary.baseFor, which found
        // this out on two airline patterns. Nothing in the generated index depends on the real
        // path: terms exclude file: IRIs by design, a declared ontology IRI does not come from the
        // document, and CI already regenerates the index under a different absolute path and gets
        // the same bytes.
        InputStream stream = Files.newInputStream(owl.toPath());
        try {
            return manager.loadOntologyFromOntologyDocument(
                    new org.semanticweb.owlapi.io.StreamDocumentSource(stream,
                            documentIriFor(owl)));
        } catch (Exception broken) {
            throw new IOException("cannot read " + owl.getName() + ": " + broken.getMessage(),
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
     * A {@code file:} IRI standing in for the pattern's own path.
     *
     * <p>Under a fixed directory that does not exist, so it is the same whichever machine and
     * whichever checkout the file is read from. The filename is kept, because it is what a parse
     * error quotes back at the user and {@code /ontoboard/pattern/x} is no help.
     */
    static IRI documentIriFor(File owl) {
        return IRI.create("file:/ontoboard/pattern/" + owl.getName());
    }

    /**
     * Every IRI the pattern declares, sorted, space separated.
     *
     * <p>One string serving two purposes. As the {@code terms} column it is what the
     * recommender matches against, which is the whole reason it exists: parsing all 123
     * patterns to answer one question takes 24 seconds, and reading a column takes none. As a
     * fingerprint it is what {@link #duplicatesIn} compares, and two patterns with the same
     * sorted signature produce the same string whichever way it is written.
     *
     * <p>Space separated because an IRI cannot contain a space, so nothing needs escaping and
     * the column stays readable.
     */
    private static String signatureOf(OWLOntology ontology) {
        Set<String> iris = new TreeSet<String>();
        for (OWLEntity entity : ontology.getSignature(Imports.EXCLUDED)) {
            iris.add(entity.getIRI().toString());
        }
        StringBuilder joined = new StringBuilder();
        for (String iri : iris) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(iri);
        }
        return joined.toString();
    }

    /**
     * The classes and properties a pattern models, for the {@code terms} column.
     *
     * <p>Narrower than the signature on purpose, and both are needed. The signature is a
     * fingerprint, so it has to be everything. This is evidence, so it has to be only what
     * carries any: an annotation property is not modelling. Measured, the difference matters
     * twice over. The column went from 451 KB to a fraction of it, and the recommender stopped
     * offering {@code IAO_0000115} - "definition" - as a reason, which every OBO ontology has
     * and which is as meaningless as matching {@code owl:Thing}.
     *
     * <p>Builtins are left out here as well, so the stored list is the one the recommender
     * actually scores against rather than a list it has to filter on every call.
     *
     * <p>Package-visible because {@link ContributedPatterns} derives a user's pattern's
     * terms by this same rule. Evidence has to mean the same thing for both kinds, or a
     * contributed pattern would rank against a different measure than the one beside it.
     */
    static String termsOf(OWLOntology ontology) {
        Set<String> iris = new TreeSet<String>();
        for (OWLEntity entity : ontology.getSignature(Imports.EXCLUDED)) {
            if (!(entity.isOWLClass() || entity.isOWLObjectProperty()
                    || entity.isOWLDataProperty())) {
                continue;
            }
            if (entity.isBuiltIn() || PatternRecommender.isBuiltin(entity.getIRI())) {
                continue;
            }
            if (isLocalOrBroken(entity.getIRI().toString())) {
                continue;
            }
            iris.add(entity.getIRI().toString());
        }
        StringBuilder joined = new StringBuilder();
        for (String iri : iris) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(iri);
        }
        return joined.toString();
    }

    /**
     * Whether an IRI is one of this machine's paths, or a parse failure wearing an IRI.
     *
     * <p>Both occur, in the same pattern, and both had to be caught. {@code airline/pattern.owl}
     * declares no usable ontology IRI, so its {@code hasAircraft} resolves against wherever the
     * file happens to be - the index came out holding
     * {@code file:/C:/Users/.../protege-plugin/../patterns/airline/pattern.owl#hasAircraft}. That
     * is unreproducible by construction: the same checkout on another machine, or the same
     * machine loading by a different relative path, generates a different index, and the test
     * that rebuilds and compares would have failed on CI rather than here.
     *
     * <p>The same file is malformed - its DOCTYPE is missing a space - so the OWL API invents
     * {@code http://org.semanticweb.owlapi/error#Error1} through {@code Error6} and puts them in
     * the signature. Neither kind can ever match a term in somebody's ontology, so neither is
     * evidence and neither belongs in a column that exists to supply evidence.
     */
    static boolean isLocalOrBroken(String iri) {
        return iri.startsWith("file:/") || iri.startsWith("jar:")
                || iri.startsWith("http://org.semanticweb.owlapi/error#");
    }

    private static String ontologyIriOf(OWLOntology ontology) {
        com.google.common.base.Optional<IRI> iri = ontology.getOntologyID().getOntologyIRI();
        return iri.isPresent() ? iri.get().toString() : "";
    }

    /** Reads the index shipped in the jar. */
    static List<DesignPattern> readShipped() throws IOException {
        InputStream stream = PatternIndex.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            return Collections.unmodifiableList(new ArrayList<DesignPattern>(
                    Arrays.<DesignPattern>asList()));
        }
        try {
            return read(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } finally {
            stream.close();
        }
    }
}
