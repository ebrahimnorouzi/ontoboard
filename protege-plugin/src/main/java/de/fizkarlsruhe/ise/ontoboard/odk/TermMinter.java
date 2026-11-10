package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;

/**
 * Decides what IRI a new term gets, and makes the axioms that introduce it.
 *
 * <p>Two projects want two different things and both are correct, so the policy is discovered
 * rather than configured:
 *
 * <ul>
 *   <li>An <b>ODK or OBO project</b> - one with an {@code -idranges.owl} beside its ontology -
 *       mints opaque numeric identifiers like {@code MWO_0001000} from the editor's own allocated
 *       block, and carries the name the user typed as an {@code rdfs:label}. That is what makes
 *       two people editing one ontology safe, and it is the convention every OBO ontology follows.
 *   <li>Any <b>other project</b> keeps the existing behaviour: an IRI built from the name, under
 *       the ontology's own namespace. Forcing numeric identifiers on a project that never asked
 *       for them would be a worse imposition than the collision risk it avoids, since a lone
 *       editor has no one to collide with.
 * </ul>
 *
 * <p><b>The label is not optional in numeric mode.</b> {@code MWO_0001000} is unreadable, so
 * failing to attach {@code rdfs:label} would turn every new term into an opaque number in every
 * view - the canvas, the class hierarchy, and every downstream consumer. {@link #declare} always
 * emits it, which is why creation goes through this class rather than through
 * {@link EntityFactory#declare} directly.
 */
public final class TermMinter {

    /** How this project names new terms. */
    public enum Style {
        /** Numeric identifiers from an allocated range, with the typed name as rdfs:label. */
        NUMERIC,
        /** An IRI built from the typed name, under the ontology's namespace. */
        FROM_NAME
    }

    private final Style style;
    private final IdRanges ranges;
    private final String editor;
    private final File rangesFile;

    private TermMinter(Style style, IdRanges ranges, String editor, File rangesFile) {
        this.style = style;
        this.ranges = ranges;
        this.editor = editor;
        this.rangesFile = rangesFile;
    }

    /**
     * Works out the policy for the ontology in {@code ontologyFile}.
     *
     * <p>Looks for a sibling {@code *-idranges.owl}, which is where ODK puts it. An unreadable or
     * malformed one falls back to naming from the typed name rather than failing: being unable to
     * create a class at all because a metadata file is broken would be a disproportionate
     * response, and the fallback is the behaviour every non-ODK project already has.
     *
     * @param ontologyFile the ontology's own file, or null when it has never been saved
     * @param editor who is minting - matched against the ranges' allocations
     */
    public static TermMinter forOntologyFile(File ontologyFile, String editor) {
        File found = findRangesFile(ontologyFile);
        if (found == null) {
            return new TermMinter(Style.FROM_NAME, null, editor, null);
        }
        try {
            String text = new String(Files.readAllBytes(found.toPath()), StandardCharsets.UTF_8);
            return new TermMinter(Style.NUMERIC, IdRanges.parse(text), editor, found);
        } catch (IOException | RuntimeException unusable) {
            // Every way the file can be unusable, not the two that were thought of. IdRanges.parse
            // also throws IllegalArgumentException for a block whose bounds run backwards and
            // NumberFormatException for a non-numeric iddigits or bound, and neither was caught -
            // so one typo in a metadata file stopped "New class here..." with an exception from a
            // code path that had nothing to do with creating a class. Falling back to naming from
            // the typed name is what this method's javadoc already promises.
            return new TermMinter(Style.FROM_NAME, null, editor, null);
        }
    }

    /**
     * Reads the project's releases for their identifiers on a thread of its own, so that the
     * first New subclass... finds them read. {@link #releasedIdentifiers} remembers each file
     * until it changes, but the first time it read every release on the Swing thread: with a long
     * history of merged releases, the first new class of a session waited for all of them - a
     * code review's finding. Nothing happens for an ontology without ranges.
     *
     * @return the thread, already started, or null when there is nothing to read
     */
    public static Thread readReleasesInBackground(File ontologyFile) {
        final File found = findRangesFile(ontologyFile);
        if (found == null) {
            return null;
        }
        Thread reader = new Thread(() -> {
            try {
                String text = new String(Files.readAllBytes(found.toPath()),
                        StandardCharsets.UTF_8);
                releasedIdentifiers(found, IdRanges.parse(text));
            } catch (IOException | RuntimeException unusable) {
                // Then the first new term reads them, and says what is wrong, as before.
            }
        }, "OntoBoard: identifiers in the releases");
        reader.setDaemon(true);
        reader.setPriority(Thread.MIN_PRIORITY);
        reader.start();
        return reader;
    }

    /** How many release files have been read for identifiers and remembered. For tests. */
    static int filesRemembered() {
        return READ.size();
    }

    /**
     * The {@code *-idranges.owl} beside {@code ontologyFile}, or null.
     *
     * <p>Only a sibling counts: an idranges file elsewhere in the repository belongs to a
     * different ontology, and minting from another ontology's ranges would hand out identifiers in
     * someone else's space.
     */
    public static File findRangesFile(File ontologyFile) {
        if (ontologyFile == null) {
            return null;
        }
        File directory = ontologyFile.getParentFile();
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] candidates = directory.listFiles();
        if (candidates == null) {
            return null;
        }
        for (File candidate : candidates) {
            if (candidate.isFile()
                    && candidate.getName().toLowerCase().endsWith("-idranges.owl")) {
                return candidate;
            }
        }
        return null;
    }

    public Style getStyle() {
        return style;
    }

    public boolean isNumeric() {
        return style == Style.NUMERIC;
    }

    /** The ranges in force, or null when naming from the typed name. */
    public IdRanges getRanges() {
        return ranges;
    }

    /**
     * One line describing what will happen, for a dialog to show before the user commits.
     *
     * <p>Worth showing because the two styles produce very different IRIs, and a user who expects
     * {@code #Person} and gets {@code MWO_0001000} will think something has gone wrong.
     */
    public String describe() {
        if (!isNumeric()) {
            return "New terms are named from what you type - 'tensile test' becomes TensileTest,"
                    + " labelled 'tensile test' - in the namespace this ontology's terms use.";
        }
        IdRanges.Range mine = ranges.rangeFor(editor);
        if (mine == null) {
            return "This project allocates numeric identifiers, but none are allocated to '"
                    + editor + "'. Add a range to " + rangesFile.getName() + " before creating "
                    + "terms.";
        }
        return "New terms get numeric identifiers from your range (" + mine.getLower() + " to "
                + mine.getUpper() + "), with what you type as the label.";
    }

    /**
     * The IRI for a new term whose label is {@code label}.
     *
     * @throws IllegalArgumentException when a from-name IRI cannot be built from the label
     * @throws IdRanges.NoRangeException when numeric minting is not possible for this editor,
     *     which is deliberately not caught here - creating a term with a colliding identifier is
     *     worse than not creating one
     */
    public IRI mintFor(OWLOntology ontology, String label) {
        if (!isNumeric()) {
            return IRI.create(namespaceForNewTerms(ontology) + localNameFor(label));
        }
        Set<String> taken = new HashSet<String>();
        for (OWLEntity entity : ontology.getSignature()) {
            taken.add(entity.getIRI().toString());
        }
        taken.addAll(releasedIdentifiers(rangesFile, ranges));
        return IRI.create(ranges.mint(editor, taken));
    }

    /**
     * Every identifier with these ranges' prefix that a release of this project has carried.
     *
     * <p>Counted as taken, because "taken" meant what the open ontology mentions, and that
     * forgets every term deleted after it was released. A tester released {@code steel plate}
     * as HARDTEST_0001001, deleted it and made {@code aluminium plate}, which was given
     * HARDTEST_0001001; the next release said "Relabelled: aluminium plate, was steel plate".
     * Whoever had used that identifier for a steel plate now meant aluminium. An identifier that
     * has been released keeps its meaning.
     *
     * <p>Read as text, not parsed: a merged release can run to tens of megabytes, and New
     * subclass... waits for this. The identifier's own part - {@code HARDTEST_0001001} - is
     * looked for wherever it stands, because a release may write the namespace as a prefix. A
     * match that is not really an identifier costs one unused number; a miss would hand an
     * identifier out twice. Each file is read again only when it changes.
     *
     * @param rangesFile the {@code -idranges.owl} beside the edit file; the releases are under
     *     the project root that file's directory belongs to
     */
    public static Set<String> releasedIdentifiers(File rangesFile, IdRanges ranges) {
        Set<String> released = new HashSet<String>();
        File directory = rangesFile == null ? null : rangesFile.getAbsoluteFile().getParentFile();
        if (directory == null || ranges == null || ranges.getIdPrefix().isEmpty()) {
            return released;
        }
        File[] versions = new File(projectRootAbove(directory), "releases").listFiles();
        if (versions == null) {
            return released;
        }
        String prefix = ranges.getIdPrefix();
        int cut = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('#')) + 1;
        String local = prefix.substring(cut);
        // HARDTEST_ wherever it stands, unless the prefix ends in / or #: then only the whole
        // namespace is distinctive enough to look for.
        Pattern identifier = Pattern.compile(local.isEmpty()
                ? Pattern.quote(prefix) + "([0-9]+)"
                : "(?<![A-Za-z0-9])" + Pattern.quote(local) + "([0-9]+)");
        for (File version : versions) {
            File[] files = version.isDirectory() ? version.listFiles() : null;
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".owl")) {
                    for (String digits : numbersIn(file, identifier)) {
                        released.add(prefix + digits);
                    }
                }
            }
        }
        return released;
    }

    /**
     * The project root releases are written under, for an edit file in {@code directory}: above
     * {@code src/ontology}, or the directory itself - the rule {@code ReleaseAction.projectRootOf}
     * applies when it writes one.
     */
    private static File projectRootAbove(File directory) {
        File src = directory.getParentFile();
        if ("ontology".equalsIgnoreCase(directory.getName()) && src != null
                && "src".equalsIgnoreCase(src.getName()) && src.getParentFile() != null) {
            return src.getParentFile();
        }
        return directory;
    }

    /** What a release file was found to hold, by path, size, date and what was looked for. */
    private static final Map<String, Set<String>> READ = Collections.synchronizedMap(
            new LinkedHashMap<String, Set<String>>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                    // Room for every release a project is likely to have: at 64 a review found a
                    // history of 70 releases evicted each file before its next use, so every New
                    // subclass... read them all again.
                    return size() > 4096;
                }
            });

    private static Set<String> numbersIn(File file, Pattern identifier) {
        String key = file.getAbsolutePath() + '|' + file.length() + '|' + file.lastModified()
                + '|' + identifier.pattern();
        Set<String> found = READ.get(key);
        if (found != null) {
            return found;
        }
        found = new HashSet<String>();
        // A byte that is not UTF-8 is replaced rather than thrown on: one stray character in a
        // comment must not make a whole release's identifiers look free.
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), decoder))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                Matcher match = identifier.matcher(line);
                while (match.find()) {
                    found.add(match.group(1));
                }
            }
        } catch (IOException unreadable) {
            // Not remembered, so the next new term tries again.
            return found;
        }
        READ.put(key, Collections.unmodifiableSet(found));
        return found;
    }

    /**
     * What a typed name becomes in an IRI: as typed when it already is a name, and otherwise
     * its words joined - "tensile test" is TensileTest. It used to refuse anything with a space,
     * so the term a materials scientist meant could not be made without being renamed by hand,
     * and then had no label.
     */
    public static String localNameFor(String typed) {
        String trimmed = typed == null ? "" : typed.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Give the new term a name.");
        }
        if (trimmed.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
            return trimmed;
        }
        StringBuilder name = new StringBuilder();
        for (String word : trimmed.split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        if (name.length() == 0) {
            throw new IllegalArgumentException("'" + trimmed + "' has no letters or digits to "
                    + "name a term after.");
        }
        if (Character.isDigit(name.charAt(0))) {
            name.insert(0, '_');
        }
        return name.toString();
    }

    /**
     * The namespace this ontology's own named terms are in, so a new one sits beside them; the
     * ontology's IRI and {@code #} when it has none - and when its terms are numbered
     * ({@code PREFIX_0001001}), because a name there breaks the convention everyone else follows.
     */
    public static String namespaceForNewTerms(OWLOntology ontology) {
        java.util.Map<String, Integer> named = new java.util.HashMap<String, Integer>();
        int numbered = 0;
        for (OWLEntity entity : ontology.getSignature(
                org.semanticweb.owlapi.model.parameters.Imports.EXCLUDED)) {
            if (entity.isBuiltIn() || entity.isOWLDatatype()) {
                continue;
            }
            String iri = entity.getIRI().toString();
            int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1;
            if (cut <= 0 || cut >= iri.length()) {
                continue;
            }
            if (iri.substring(cut).matches("[A-Za-z]+_[0-9]+")) {
                numbered++;
                continue;
            }
            String namespace = iri.substring(0, cut);
            Integer seen = named.get(namespace);
            named.put(namespace, seen == null ? 1 : seen + 1);
        }
        String best = null;
        for (java.util.Map.Entry<String, Integer> namespace : named.entrySet()) {
            if (best == null || namespace.getValue() > named.get(best)) {
                best = namespace.getKey();
            }
        }
        return best != null && named.get(best) > numbered ? best
                : EntityFactory.namespaceOf(ontology);
    }

    /**
     * Everything needed to introduce the term: its declaration and, in numeric mode, its label.
     *
     * <p>The label is added only in numeric mode. In from-name mode the IRI already carries the
     * name, and adding a redundant {@code rdfs:label} to every term would be an unrequested change
     * to how the ontology is written.
     */
    public List<OWLOntologyChange> declare(OWLOntology ontology, IRI iri, EntityFactory.Kind kind,
            String label) {
        List<OWLOntologyChange> changes =
                new ArrayList<OWLOntologyChange>(EntityFactory.declare(ontology, iri, kind));
        // A name typed with spaces is the label: the IRI carries only "TensileTest".
        boolean renamed = label != null && !label.trim().isEmpty()
                && !iri.toString().endsWith(label.trim());
        if ((isNumeric() || renamed) && label != null && !label.trim().isEmpty()) {
            OWLDataFactory factory = ontology.getOWLOntologyManager().getOWLDataFactory();
            changes.add(new AddAxiom(ontology, factory.getOWLAnnotationAssertionAxiom(
                    factory.getRDFSLabel(), iri, factory.getOWLLiteral(label.trim(), "en"))));
        }
        return changes;
    }
}
