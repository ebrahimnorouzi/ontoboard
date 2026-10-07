package de.fizkarlsruhe.ise.ontoboard.pattern;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.protege.editor.core.prefs.Preferences;
import org.protege.editor.core.prefs.PreferencesManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Patterns the user put in a folder, read without rebuilding the plugin.
 *
 * <p>The last thing the library could not do. Four collections and 159 patterns shipped inside
 * the jar, and contributing a 160th - or a fifth collection of your own group's patterns - meant
 * editing the repository, regenerating {@code index.tsv} and building a 66 MB bundle. For a tool
 * whose point is that an ontology engineer can get on with modelling, that is the wrong answer.
 *
 * <p><b>No index, and that is not an inconsistency.</b> The shipped library needs
 * {@link PatternIndex} because a jar cannot be enumerated - under Felix a classpath directory
 * resolves to a {@code bundle:} URL that answers nothing. A directory on disk <em>can</em> be
 * listed, so a contributed pattern needs no index, no metadata file and no registration: it is a
 * file in a folder, and dropping one in is the whole procedure.
 *
 * <p><b>What it says about itself is believed.</b> Measured across the 159 shipped patterns,
 * 112 carry {@code coversRequirements}, 66 {@code hasIntent}, 65 {@code rdfs:comment} and 38
 * {@code rdfs:label} on the ontology itself - so the name, the description and the competency
 * questions are read out of the file rather than asked for in a dialog. A pattern that documents
 * itself needs nothing typed in. One that documents nothing falls back to its filename, which is
 * at least the user's own word for it.
 *
 * <p><b>Nothing is skipped in silence.</b> A file that will not parse is reported with its path
 * and the parser's reason, and so is the count of files left unread when a folder holds more than
 * {@link #MOST_READ}. The precedent is the term-list reader: a line that is not a term is
 * reported as malformed rather than quietly dropped, because a library that is missing something
 * and says nothing is worse than one that refuses to load.
 */
public final class ContributedPatterns {

    private ContributedPatterns() {
    }

    /**
     * The collection a pattern lands in when it sits directly in the root.
     *
     * <p>A collection is the name of the first folder under the root, so making a fifth
     * collection is making a folder. A file loose in the root still needs a name for the
     * "By collection" ordering to put it somewhere, and "yours" is what it is.
     */
    public static final String DEFAULT_COLLECTION = "yours";

    /**
     * What counts as a pattern file.
     *
     * <p>Every syntax the OWL API sniffs without being told, which is what
     * {@code loadOntologyFromOntologyDocument} does with a file. Turtle is in the list because
     * this library ships a {@code .ttl} beside every {@code .owl}, so somebody who copies one out
     * to edit it will be holding Turtle.
     */
    static final List<String> EXTENSIONS = Collections.unmodifiableList(Arrays.asList(
            ".owl", ".ttl", ".rdf", ".owx", ".omn", ".ofn"));

    /**
     * How many files are read, with the rest reported rather than dropped.
     *
     * <p>Each one is parsed, and parsing is the expensive part - measured at roughly 0.2 s for a
     * shipped pattern, which is why the index carries the terms column at all. A folder someone
     * points at by mistake, say a checkout of an ontology repository, can hold thousands of OWL
     * files; reading them would hang the dialog for minutes. The cap is high enough that a real
     * collection of patterns never meets it and low enough that a wrong folder costs seconds.
     */
    static final int MOST_READ = 200;

    /** How deep the walk goes, so a symlinked loop cannot spin. */
    static final int DEEPEST = 8;

    static final String PREFERENCE_SET = "de.fizkarlsruhe.ise.ontoboard";
    static final String KEY_ROOT = "patterns.root";

    // ---------- the result ----------

    /** What a folder turned out to hold. */
    public static final class Scan {
        private final List<DesignPattern> patterns;
        private final List<String> problems;
        private final int candidates;

        Scan(List<DesignPattern> patterns, List<String> problems, int candidates) {
            this.patterns = Collections.unmodifiableList(patterns);
            this.problems = Collections.unmodifiableList(problems);
            this.candidates = candidates;
        }

        /** The patterns that loaded, in path order. */
        public List<DesignPattern> getPatterns() {
            return patterns;
        }

        /**
         * One line per file that could not be read, and per file left unread by the cap.
         *
         * <p>Shown rather than logged. The user put these files there on purpose, and a library
         * that quietly holds fewer patterns than the folder does is a library that cannot be
         * trusted about the ones it does hold.
         */
        public List<String> getProblems() {
            return problems;
        }

        /** How many files looked like patterns, read or not. */
        public int getCandidates() {
            return candidates;
        }

        public boolean isEmpty() {
            return patterns.isEmpty() && problems.isEmpty();
        }
    }

    // ---------- where they live ----------

    /**
     * The folder contributed patterns live in unless the user moved it.
     *
     * <p>Beside nothing of Protege's. Protege keeps its own state in {@code ~/.Protege} and a
     * plugin dropping a user's source files among a host application's caches invites them to be
     * deleted by somebody clearing that directory. {@code OpenFromGitHubAction} already puts
     * clones in {@code ~/ontology-projects} for the same reason.
     */
    public static File defaultRoot() {
        return new File(System.getProperty("user.home", "."), ".ontoboard/patterns");
    }

    /**
     * Where to look, which the user can move - to a folder inside their ODK project, for
     * instance, so the patterns travel with the repository and the whole team sees them.
     *
     * <p>Falls back to {@link #defaultRoot} on anything at all, including there being no Protege
     * to ask. Never throws: a preference that cannot be read must not stop the library opening.
     */
    public static File root() {
        try {
            return rootIn(preferences());
        } catch (Throwable noPreferences) {
            return defaultRoot();
        }
    }

    /** Remembers a new folder between sessions. */
    public static void setRoot(File root) {
        try {
            setRootIn(preferences(), root);
        } catch (Throwable noPreferences) {
            // Then it applies to this session only, which is better than refusing to change it.
        }
    }

    static File rootIn(Preferences preferences) {
        String saved = preferences.getString(KEY_ROOT, "");
        return saved == null || saved.trim().isEmpty() ? defaultRoot() : new File(saved.trim());
    }

    static void setRootIn(Preferences preferences, File root) {
        // The default is stored as empty rather than as its path, so a user who moves their home
        // directory - or runs the same preferences on another machine - keeps working.
        preferences.putString(KEY_ROOT, root == null || root.equals(defaultRoot())
                ? "" : root.getAbsolutePath());
    }

    private static Preferences preferences() {
        return PreferencesManager.getInstance().getApplicationPreferences(PREFERENCE_SET);
    }

    // ---------- reading them ----------

    /**
     * Every pattern under {@code root}, and every reason one is missing.
     *
     * <p>An absent folder is not a problem to report: having contributed nothing is the normal
     * state, and a warning about it on every open would train the user to dismiss warnings.
     *
     * @param known ids already taken, so a contributed pattern cannot collide with a shipped one
     */
    public static Scan scan(File root, Set<String> known) {
        List<DesignPattern> patterns = new ArrayList<DesignPattern>();
        List<String> problems = new ArrayList<String>();
        if (root == null || !root.isDirectory()) {
            return new Scan(patterns, problems, 0);
        }
        List<File> files = new ArrayList<File>();
        collect(root, 0, files);
        Collections.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getAbsolutePath().compareToIgnoreCase(right.getAbsolutePath());
            }
        });
        int candidates = files.size();
        if (candidates > MOST_READ) {
            problems.add((candidates - MOST_READ) + " further files were not read. "
                    + root.getAbsolutePath() + " holds " + candidates + " pattern files, and "
                    + MOST_READ + " is as many as this reads - each one has to be parsed. Point "
                    + "the folder at the patterns themselves rather than at a whole repository.");
            files = files.subList(0, MOST_READ);
        }
        Set<String> taken = new LinkedHashSet<String>(known == null
                ? Collections.<String>emptySet() : known);
        for (File file : files) {
            String id = unique(idFor(root, file), taken);
            try {
                patterns.add(read(root, file, id));
                taken.add(id);
            } catch (IOException unreadable) {
                problems.add(relative(root, file) + " - " + unreadable.getMessage());
            } catch (RuntimeException broken) {
                // A parser can throw almost anything on a file that is not what its extension
                // claims. One bad file must cost that file and nothing else.
                problems.add(relative(root, file) + " - " + describe(broken));
            }
        }
        return new Scan(patterns, problems, candidates);
    }

    /** The same, with the shipped ids already taken. */
    public static Scan scan(File root) {
        Set<String> shipped = new LinkedHashSet<String>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            shipped.add(pattern.getId());
        }
        return scan(root, shipped);
    }

    private static void collect(File directory, int depth, List<File> into) {
        if (depth > DEEPEST) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            // Hidden entries are not contributions. A pattern folder kept in git has a .git
            // directory full of object files, and walking into it would both find nothing and
            // count towards the cap.
            if (child.getName().startsWith(".")) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, depth + 1, into);
            } else if (looksLikeAPattern(child.getName())) {
                into.add(child);
            }
        }
    }

    static boolean looksLikeAPattern(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one file and describes it the way the index describes a shipped pattern.
     *
     * @throws IOException if it will not parse, with the parser's own reason
     */
    private static DesignPattern read(File root, File file, String id) throws IOException {
        OWLOntology ontology = PatternIndex.load(file);
        String declaredIri = ontology.getOntologyID().getOntologyIRI().isPresent()
                ? ontology.getOntologyID().getOntologyIRI().get().toString() : "";
        String name = PatternIndex.oneLine(firstOf(ontology, TITLES), nameFromFile(file));
        String publisher = PatternIndex.oneLine(firstOf(ontology, PUBLISHERS),
                PatternIndex.publisherOf(declaredIri));
        return new DesignPattern(id, name, collectionOf(root, file), publisher,
                "uncategorised", "general", "",
                PatternIndex.oneLine(firstOf(ontology, DESCRIPTIONS), ""),
                PatternIndex.oneLine(firstOf(ontology, QUESTIONS), ""),
                PatternIndex.termsOf(ontology), file);
    }

    /**
     * The ontology-level annotations that name a pattern, in the order they are believed.
     *
     * <p>Measured on the 159 shipped patterns: 38 carry {@code rdfs:label} and 6
     * {@code dc:title}. Dublin Core comes first anyway, because a label is often the same word
     * as the filename while a title was written to be read.
     */
    static final List<String> TITLES = Collections.unmodifiableList(Arrays.asList(
            "http://purl.org/dc/terms/title",
            "http://purl.org/dc/elements/1.1/title",
            "http://www.w3.org/2000/01/rdf-schema#label"));

    /**
     * Where a description comes from.
     *
     * <p>{@code hasIntent} is the ODP portal's own annotation and the most common of these by
     * far - 66 of the 159 - so a pattern copied out of this library and edited keeps its
     * description. {@code rdfs:comment} is next with 65.
     */
    static final List<String> DESCRIPTIONS = Collections.unmodifiableList(Arrays.asList(
            "http://www.ontologydesignpatterns.org/schemas/cpannotationschema.owl#hasIntent",
            "http://purl.org/dc/terms/description",
            "http://purl.org/dc/elements/1.1/description",
            "http://www.w3.org/2000/01/rdf-schema#comment"));

    /**
     * The competency questions, which are how an ODP is actually chosen.
     *
     * <p>112 of the 159 shipped patterns carry {@code coversRequirements} - more than carry any
     * other annotation except {@code owl:versionInfo}. "What role does this agent play?" decides
     * whether Agent Role is the pattern wanted; "behavioural, general" does not.
     */
    static final List<String> QUESTIONS = Collections.unmodifiableList(Arrays.asList(
            "http://www.ontologydesignpatterns.org/schemas/"
                    + "cpannotationschema.owl#coversRequirements",
            "http://purl.org/dc/terms/requires"));

    /**
     * Who published it.
     *
     * <p>Not {@code dc:creator}, which 34 shipped patterns carry and which names a person.
     * "Who wrote this" and "whose modelling am I adopting" are different questions, and the
     * grouping answers the second.
     */
    static final List<String> PUBLISHERS = Collections.unmodifiableList(Arrays.asList(
            "http://purl.org/dc/terms/publisher",
            "http://purl.org/dc/elements/1.1/publisher"));

    /** The first of those annotations the ontology carries, as text, or empty. */
    private static String firstOf(OWLOntology ontology, List<String> properties) {
        for (String property : properties) {
            List<String> found = new ArrayList<String>();
            for (OWLAnnotation annotation : ontology.getAnnotations()) {
                if (annotation.getProperty().getIRI().equals(IRI.create(property))
                        && annotation.getValue() instanceof OWLLiteral) {
                    found.add(((OWLLiteral) annotation.getValue()).getLiteral());
                }
            }
            if (!found.isEmpty()) {
                // Sorted, so a pattern with several competency questions reads the same way
                // every time rather than in whatever order the parser handed them over.
                Collections.sort(found);
                StringBuilder joined = new StringBuilder();
                for (String one : found) {
                    if (joined.length() > 0) {
                        joined.append(" | ");
                    }
                    joined.append(one.trim());
                }
                return joined.toString();
            }
        }
        return "";
    }

    /**
     * The collection: the first folder under the root.
     *
     * <p>One rule, so it stays predictable however deep the folders go.
     * {@code <root>/mwo/process/foo.owl} is in "mwo", and so is
     * {@code <root>/mwo/foo/pattern.owl} - which is the shipped layout, so a pattern copied out
     * of this library and dropped back in lands where its folder says.
     */
    static String collectionOf(File root, File file) {
        String relative = relative(root, file);
        int firstSlash = relative.indexOf('/');
        return firstSlash <= 0 ? DEFAULT_COLLECTION : relative.substring(0, firstSlash);
    }

    /** The id: the path under the root, as a slug, so two files never share one. */
    static String idFor(File root, File file) {
        String relative = relative(root, file);
        int dot = relative.lastIndexOf('.');
        String withoutExtension = dot > 0 ? relative.substring(0, dot) : relative;
        StringBuilder slug = new StringBuilder();
        for (char each : withoutExtension.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean usable = (each >= 'a' && each <= 'z') || (each >= '0' && each <= '9');
            slug.append(usable ? each : '-');
        }
        String squeezed = slug.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return squeezed.isEmpty() ? "pattern" : squeezed;
    }

    /**
     * That id, or the next free one.
     *
     * <p>Checked against the shipped ids as well. {@code PatternLibrary.find} returns the first
     * match, {@code contentsOf} caches by id, and the import path copies a pattern out as
     * {@code <id>.owl} - so a contributed file named {@code componency.owl} silently shadowing
     * the shipped {@code componency} would be three bugs rather than one.
     */
    static String unique(String id, Set<String> taken) {
        if (!taken.contains(id)) {
            return id;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = id + "-" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return id + "-" + taken.size();
    }

    /** The filename, as a name to show when the file says nothing about itself. */
    static String nameFromFile(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String relative(File root, File file) {
        String rootPath = root.getAbsolutePath().replace('\\', '/');
        String filePath = file.getAbsolutePath().replace('\\', '/');
        if (filePath.toLowerCase(Locale.ROOT).startsWith(rootPath.toLowerCase(Locale.ROOT) + "/")) {
            return filePath.substring(rootPath.length() + 1);
        }
        return file.getName();
    }

    private static String describe(Throwable thrown) {
        String message = thrown.getMessage();
        return message == null || message.trim().isEmpty()
                ? thrown.getClass().getSimpleName() : message.trim();
    }

    // ---------- adding one ----------

    /**
     * Copies a file into the folder, which is all that contributing a pattern is.
     *
     * <p>Copied rather than linked. A pattern the library lists has to stay readable after the
     * file somebody exported to their desktop is gone, and a copy is also what makes the folder
     * worth pointing at a git repository.
     *
     * @param collection the folder to put it in, or null for {@link #DEFAULT_COLLECTION}
     * @return where it landed
     * @throws IOException if the folder cannot be made, the source cannot be read, or a file of
     *     that name is already there - never overwriting one, because the file already there is
     *     somebody's work
     */
    public static File add(File root, String collection, File source) throws IOException {
        if (root == null) {
            throw new IOException("No folder to add it to.");
        }
        if (source == null || !source.isFile()) {
            throw new IOException("Not a file: " + source);
        }
        if (!looksLikeAPattern(source.getName())) {
            throw new IOException(source.getName() + " is not one of " + EXTENSIONS
                    + ", so the library would not find it again.");
        }
        String folder = collection == null || collection.trim().isEmpty()
                ? DEFAULT_COLLECTION : safeFolder(collection);
        File directory = new File(root, folder);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create " + directory.getAbsolutePath());
        }
        File target = new File(directory, source.getName());
        if (target.exists()) {
            throw new IOException(target.getAbsolutePath() + " is already there. Rename the file, "
                    + "or remove the one already in the folder.");
        }
        copy(source, target);
        return target;
    }

    /** A folder name from whatever the user typed, with the separators taken out. */
    static String safeFolder(String collection) {
        StringBuilder safe = new StringBuilder();
        for (char each : collection.trim().toCharArray()) {
            boolean usable = Character.isLetterOrDigit(each) || each == '-' || each == '_'
                    || each == ' ';
            safe.append(usable ? each : '-');
        }
        String trimmed = safe.toString().trim().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return trimmed.isEmpty() ? DEFAULT_COLLECTION : trimmed;
    }

    private static void copy(File source, File target) throws IOException {
        InputStream in = Files.newInputStream(source.toPath());
        try {
            OutputStream out = Files.newOutputStream(target.toPath());
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
