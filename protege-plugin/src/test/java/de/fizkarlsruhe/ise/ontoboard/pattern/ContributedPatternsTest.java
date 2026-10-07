package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pattern of your own, without rebuilding the plugin.
 *
 * <p>These run against files written here rather than against the shipped library, because the
 * claim being guarded is about a folder nobody has seen yet: that dropping a file in is the whole
 * procedure, that what the file says about itself is used, and that a file which will not parse is
 * named rather than silently absent.
 */
class ContributedPatternsTest {

    /** A small pattern that documents itself the way an ODP does. */
    private static final String TURTLE = String.join("\n",
            "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
            "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .",
            "@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .",
            "@prefix dcterms: <http://purl.org/dc/terms/> .",
            "@prefix cp: <http://www.ontologydesignpatterns.org/schemas/"
                    + "cpannotationschema.owl#> .",
            "@prefix : <http://example.org/sample#> .",
            "",
            "<http://example.org/sample> a owl:Ontology ;",
            "  dcterms:title \"Sample Measurement\" ;",
            "  dcterms:publisher \"Example Institute\" ;",
            "  cp:hasIntent \"To represent a measured value and its unit.\" ;",
            "  cp:coversRequirements \"What unit was this measured in?\" .",
            "",
            ":Measurement a owl:Class .",
            ":Unit a owl:Class .",
            ":hasUnit a owl:ObjectProperty ;",
            "  rdfs:domain :Measurement ;",
            "  rdfs:range :Unit .",
            "");

    /** Writes a file, making any folders it needs. */
    private static File write(File root, String relative, String content) throws IOException {
        File file = new File(root, relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Set<String> nothingTaken() {
        return new LinkedHashSet<String>();
    }

    // ---------- the normal case ----------

    /** A file in a folder, and that is all it takes. */
    @Test
    void aFileInAFolderIsAPattern(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(1, scan.getPatterns().size());
        assertEquals(0, scan.getProblems().size(), scan.getProblems().toString());
        DesignPattern pattern = scan.getPatterns().get(0);
        assertTrue(pattern.isContributed());
        assertNotNull(pattern.getFile());
        assertEquals("mine", pattern.getCollection(), "the folder is the collection");
    }

    /**
     * What the file says about itself is what is shown.
     *
     * <p>Measured across the 159 shipped patterns, 112 carry {@code coversRequirements} and 66
     * {@code hasIntent} - so reading the file is how a contributed pattern gets described without
     * anybody typing anything into a dialog.
     */
    @Test
    void theFileDescribesItself(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);

        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        assertEquals("Sample Measurement", pattern.getName());
        assertEquals("Example Institute", pattern.getPublisher());
        assertEquals("To represent a measured value and its unit.", pattern.getDescription());
        assertEquals("What unit was this measured in?", pattern.getCompetencyQuestions());
    }

    /** Its terms are read, so the recommender can rank it beside a shipped one. */
    @Test
    void itsTermsAreEvidence(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);

        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        assertEquals(3, pattern.getTermIris().size(), pattern.getTermIris().toString());
        assertTrue(pattern.getTermIris().contains("http://example.org/sample#Measurement"));
        assertTrue(pattern.getTermIris().contains("http://example.org/sample#hasUnit"));
    }

    /** And the browser can open it: the file is read where a resource would be. */
    @Test
    void itOpensLikeAShippedPattern(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);
        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        PatternLibrary.Contents contents = PatternLibrary.contentsOf(pattern);

        assertEquals(2, contents.getClasses().size());
        assertEquals(1, contents.getProperties().size());
        assertEquals(3, contents.getTerms().size());
    }

    /**
     * A pattern copied out for ROBOT keeps its syntax.
     *
     * <p>Every shipped pattern is RDF/XML so {@code .owl} was always right. This library ships a
     * {@code .ttl} beside each one, so somebody who copies a pattern out to edit and contribute
     * is holding Turtle - and writing Turtle to a file named {@code .owl} hands ROBOT a name that
     * lies about the contents.
     */
    @Test
    void copyingItOutKeepsItsSyntax(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);
        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        File copied = PatternLibrary.copyTo(pattern, new File(root, "out"));

        assertTrue(copied.getName().endsWith(".ttl"), copied.getName());
        assertEquals(Files.size(new File(root, "mine/sample.ttl").toPath()),
                Files.size(copied.toPath()));
    }

    /**
     * Reading a pattern leaves the file closed, so the user can still edit it.
     *
     * <p>A defect this found. {@code manager.loadOntologyFromOntologyDocument(File)} leaves the
     * handle open, which on Windows means the file cannot be deleted, renamed or in some editors
     * saved over - and the whole point of a contributed pattern is that it is yours to edit. It
     * did not matter while every pattern was a read-only resource in the jar, and became a defect
     * the moment one was a file somebody owns.
     *
     * <p>Every test in this class guards it too, because JUnit deletes its temporary directory
     * afterwards and could not: nineteen of them failed on the handle while every assertion in
     * them passed. This one says so on purpose rather than as a side effect.
     */
    @Test
    void readingItDoesNotHoldTheFileOpen(@TempDir File root) throws Exception {
        File file = write(root, "mine/sample.ttl", TURTLE);
        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);
        PatternLibrary.contentsOf(pattern);

        assertTrue(file.delete(), "the file is still open: " + file);
        assertTrue(ContributedPatterns.scan(root, nothingTaken()).getPatterns().isEmpty(),
                "and a pattern taken out of the folder leaves the library");
    }

    // ---------- collections ----------

    /** A fifth collection is a folder, which is the whole point. */
    @Test
    void aFolderIsACollection(@TempDir File root) throws Exception {
        write(root, "ourgroup/a.ttl", TURTLE);
        write(root, "someone-else/b.ttl", TURTLE);
        write(root, "loose.ttl", TURTLE);

        List<String> collections = new ArrayList<String>();
        for (DesignPattern pattern : ContributedPatterns.scan(root, nothingTaken())
                .getPatterns()) {
            collections.add(pattern.getCollection());
        }

        assertTrue(collections.contains("ourgroup"));
        assertTrue(collections.contains("someone-else"));
        assertTrue(collections.contains(ContributedPatterns.DEFAULT_COLLECTION),
                "a file loose in the root still belongs somewhere: " + collections);
    }

    /** However deep the folders go, the collection is the first one. */
    @Test
    void theCollectionIsTheFirstFolder(@TempDir File root) throws Exception {
        write(root, "mwo/process/deep/one.ttl", TURTLE);
        // The shipped layout, so a pattern copied out of the library and dropped back in works.
        write(root, "mwo/componency/pattern.ttl", TURTLE);

        for (DesignPattern pattern : ContributedPatterns.scan(root, nothingTaken())
                .getPatterns()) {
            assertEquals("mwo", pattern.getCollection(), pattern.getId());
        }
    }

    /** They sort into their own group with the shipped ones, which is why collection matters. */
    @Test
    void theySortBesideTheShippedCollections(@TempDir File root) throws Exception {
        write(root, "ourgroup/sample.ttl", TURTLE);
        List<DesignPattern> everything = new ArrayList<DesignPattern>(PatternLibrary.all());
        everything.addAll(ContributedPatterns.scan(root, nothingTaken()).getPatterns());

        List<DesignPattern> sorted = PatternOrder.sorted(everything, PatternOrder.BY_COLLECTION);

        String collection = "";
        List<String> blocks = new ArrayList<String>();
        for (DesignPattern pattern : sorted) {
            if (!pattern.getCollection().equals(collection)) {
                assertFalse(blocks.contains(pattern.getCollection()),
                        pattern.getCollection() + " appears in two blocks");
                blocks.add(pattern.getCollection());
                collection = pattern.getCollection();
            }
        }
        assertEquals(5, blocks.size(), "four shipped collections and the user's: " + blocks);
        assertTrue(blocks.contains("ourgroup"));
    }

    /**
     * Your own pattern is ranked and explained like any other.
     *
     * <p>The claim that makes this worth having. "Suggest and recommend, rank patterns" was asked
     * for about the shipped library; a pattern of your own that could be listed but never
     * suggested would be a second-class entry, and your own patterns are the ones most likely to
     * match what you are modelling.
     */
    @Test
    void yourOwnPatternIsRecommendedToo(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);
        List<DesignPattern> candidates = ContributedPatterns.scan(root, nothingTaken())
                .getPatterns();
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        org.semanticweb.owlapi.model.OWLOntology ontology = manager.createOntology(
                org.semanticweb.owlapi.model.IRI.create("http://example.org/mine"));
        org.semanticweb.owlapi.model.OWLDataFactory factory = manager.getOWLDataFactory();
        for (String iri : new String[] {"http://example.org/sample#Measurement",
            "http://example.org/sample#Unit"}) {
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(factory.getOWLClass(
                    org.semanticweb.owlapi.model.IRI.create(iri))));
        }

        List<PatternRecommender.Recommendation> ranked = PatternRecommender.forOntology(
                PatternRecommender.vocabularyOf(ontology), candidates, 5);

        assertEquals(1, ranked.size());
        assertEquals("mine-sample", ranked.get(0).getPattern().getId());
        assertTrue(ranked.get(0).explain().contains("Measurement"),
                "with the same evidence a shipped pattern gets: " + ranked.get(0).explain());
    }

    // ---------- nothing is lost in silence ----------

    /**
     * A file that will not parse is named, with the reason.
     *
     * <p>The term-list reader's precedent: a line that is not a term is reported as malformed
     * rather than dropped. A library quietly holding fewer patterns than the folder does cannot
     * be trusted about the ones it does hold.
     */
    @Test
    void anUnreadableFileIsReported(@TempDir File root) throws Exception {
        write(root, "mine/good.ttl", TURTLE);
        write(root, "mine/broken.ttl", "this is not turtle at all <<<");

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(1, scan.getPatterns().size());
        assertEquals(1, scan.getProblems().size());
        assertTrue(scan.getProblems().get(0).contains("broken.ttl"),
                scan.getProblems().get(0));
        assertEquals(2, scan.getCandidates(), "both files looked like patterns");
    }

    /** One bad file costs that file and nothing else. */
    @Test
    void theOthersStillLoad(@TempDir File root) throws Exception {
        write(root, "a.ttl", "nonsense <<<");
        write(root, "b.ttl", TURTLE);
        write(root, "c.ttl", TURTLE);

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(2, scan.getPatterns().size());
        assertEquals(1, scan.getProblems().size());
    }

    /** A folder nobody has made is not a problem to report. */
    @Test
    void nothingContributedIsNotAProblem(@TempDir File root) {
        ContributedPatterns.Scan scan = ContributedPatterns.scan(new File(root, "never-made"),
                nothingTaken());

        assertTrue(scan.isEmpty());
        assertEquals(0, scan.getProblems().size(),
                "a warning on every open teaches the user to dismiss warnings");
        assertTrue(ContributedPatterns.scan((File) null, nothingTaken()).isEmpty());
    }

    /** Files other than patterns are left alone. */
    @Test
    void onlyPatternFilesAreRead(@TempDir File root) throws Exception {
        write(root, "notes.md", "# my notes");
        write(root, "README.txt", "patterns live here");
        write(root, "sample.ttl", TURTLE);

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(1, scan.getPatterns().size());
        assertEquals(1, scan.getCandidates());
        assertEquals(0, scan.getProblems().size());
    }

    /**
     * A folder kept in git is not walked into.
     *
     * <p>Pointing this at {@code src/patterns} of an ODK repository is the case that makes the
     * patterns travel with the project, and a {@code .git} directory holds thousands of files
     * that would both find nothing and consume the cap.
     */
    @Test
    void hiddenFoldersAreSkipped(@TempDir File root) throws Exception {
        write(root, ".git/objects/pack/something.owl", TURTLE);
        write(root, ".hidden.ttl", TURTLE);
        write(root, "sample.ttl", TURTLE);

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(1, scan.getPatterns().size());
        assertEquals(1, scan.getCandidates());
    }

    /**
     * Too many files is reported, not silently truncated.
     *
     * <p>Each one has to be parsed, so a folder pointed at a whole ontology repository would hang
     * the dialog for minutes. The cap is a cost decision and it has to be visible, or a library
     * showing 200 of 2000 patterns looks complete.
     */
    @Test
    void aCapIsSaidOutLoud(@TempDir File root) throws Exception {
        for (int each = 0; each < ContributedPatterns.MOST_READ + 3; each++) {
            // Not parseable, which keeps this test fast: the cap is counted before anything is
            // read, so what matters is that the count is right and the overflow is named.
            write(root, "f" + each + ".owl", "x");
        }

        ContributedPatterns.Scan scan = ContributedPatterns.scan(root, nothingTaken());

        assertEquals(ContributedPatterns.MOST_READ + 3, scan.getCandidates());
        assertTrue(scan.getProblems().get(0).contains("3 further files were not read"),
                scan.getProblems().get(0));
    }

    // ---------- ids ----------

    /**
     * A contributed pattern cannot take a shipped pattern's id.
     *
     * <p>Three things key on the id: {@code find} returns the first match, {@code contentsOf}
     * caches by it, and the import copies the pattern out as {@code <id>.owl}. A file called
     * {@code componency.ttl} shadowing the shipped {@code componency} would be three bugs.
     */
    @Test
    void aContributedIdNeverShadowsAShippedOne(@TempDir File root) throws Exception {
        write(root, "componency.ttl", TURTLE);
        Set<String> shipped = new LinkedHashSet<String>();
        for (DesignPattern pattern : PatternLibrary.all()) {
            shipped.add(pattern.getId());
        }

        DesignPattern contributed = ContributedPatterns.scan(root, shipped).getPatterns().get(0);

        assertFalse(shipped.contains(contributed.getId()), contributed.getId());
        assertEquals("componency-2", contributed.getId());
        assertFalse(PatternLibrary.find("componency").isContributed(),
                "the shipped one is still the one that id names");
    }

    /** Two files that slug to the same id get different ones. */
    @Test
    void twoFilesNeverShareAnId(@TempDir File root) throws Exception {
        write(root, "a/My Pattern.ttl", TURTLE);
        write(root, "a/my-pattern.ttl", TURTLE);

        List<DesignPattern> found = ContributedPatterns.scan(root, nothingTaken()).getPatterns();

        assertEquals(2, found.size());
        assertFalse(found.get(0).getId().equals(found.get(1).getId()),
                found.get(0).getId() + " twice");
    }

    /** The id is built from the path, so it is stable across sessions. */
    @Test
    void theIdComesFromThePath(@TempDir File root) {
        assertEquals("mwo-sample", ContributedPatterns.idFor(root,
                new File(root, "mwo/sample.ttl")));
        assertEquals("sample", ContributedPatterns.idFor(root, new File(root, "sample.owl")));
        assertEquals("a-b-c", ContributedPatterns.idFor(root, new File(root, "a/B  C.owl")));
    }

    // ---------- falling back ----------

    /** A file that says nothing about itself is still usable. */
    @Test
    void aSilentFileFallsBackToItsName(@TempDir File root) throws Exception {
        write(root, "my-own-idea.ttl", String.join("\n",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
                "@prefix : <http://example.org/quiet#> .",
                "<http://example.org/quiet> a owl:Ontology .",
                ":Thing a owl:Class .",
                ""));

        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        assertEquals("my-own-idea", pattern.getName(), "the filename is the user's own word");
        assertEquals("example.org", pattern.getPublisher(), "the host of its own IRI");
        assertEquals("", pattern.getDescription());
    }

    /** A pattern with no IRI at all has no publisher, rather than a made-up one. */
    @Test
    void noIriMeansNoPublisher(@TempDir File root) throws Exception {
        write(root, "anon.ttl", String.join("\n",
                "@prefix owl: <http://www.w3.org/2002/07/owl#> .",
                "@prefix : <http://example.org/anon#> .",
                "[] a owl:Ontology .",
                ":Thing a owl:Class .",
                ""));

        DesignPattern pattern = ContributedPatterns.scan(root, nothingTaken()).getPatterns().get(0);

        assertEquals(PatternIndex.UNKNOWN_PUBLISHER, pattern.getPublisher());
    }

    // ---------- adding one ----------

    /** Adding is copying, and the copy is what the library reads. */
    @Test
    void addingCopiesItIn(@TempDir File root) throws Exception {
        File elsewhere = new File(root, "desktop");
        assertTrue(elsewhere.mkdirs());
        File source = write(elsewhere, "exported.ttl", TURTLE);
        File patterns = new File(root, "library");

        File added = ContributedPatterns.add(patterns, "ourgroup", source);

        assertTrue(added.isFile());
        assertEquals("ourgroup", added.getParentFile().getName());
        assertEquals(1, ContributedPatterns.scan(patterns, nothingTaken()).getPatterns().size());
    }

    /** Nothing already there is overwritten, because it is somebody's work. */
    @Test
    void anExistingFileIsNotOverwritten(@TempDir File root) throws Exception {
        File source = write(new File(root, "desktop"), "sample.ttl", TURTLE);
        File patterns = new File(root, "library");
        ContributedPatterns.add(patterns, "mine", source);

        IOException refused = assertThrows(IOException.class,
                () -> ContributedPatterns.add(patterns, "mine", source));

        assertTrue(refused.getMessage().contains("already there"), refused.getMessage());
    }

    /** A file the library could not find again is refused rather than copied. */
    @Test
    void aFileWithTheWrongExtensionIsRefused(@TempDir File root) throws Exception {
        File source = write(new File(root, "desktop"), "pattern.txt", TURTLE);

        IOException refused = assertThrows(IOException.class,
                () -> ContributedPatterns.add(new File(root, "library"), "mine", source));

        assertTrue(refused.getMessage().contains(".ttl"), refused.getMessage());
    }

    /** Whatever the user types becomes a usable folder name. */
    @Test
    void theCollectionNameIsMadeSafe() {
        assertEquals("our group", ContributedPatterns.safeFolder("our group"));
        assertEquals("a-b", ContributedPatterns.safeFolder("a/b"));
        assertEquals("up", ContributedPatterns.safeFolder("../up"));
        assertEquals(ContributedPatterns.DEFAULT_COLLECTION, ContributedPatterns.safeFolder("///"));
        assertEquals(ContributedPatterns.DEFAULT_COLLECTION, ContributedPatterns.safeFolder("  "));
    }

    // ---------- where they live ----------

    /**
     * The folder is remembered, and the default is stored as a default.
     *
     * <p>Not as its path: a user whose home directory moves, or whose preferences follow them to
     * another machine, would otherwise be pointed at a folder that is not there.
     */
    @Test
    void theFolderIsRemembered(@TempDir File root) {
        FakePreferences preferences = new FakePreferences();

        assertEquals(ContributedPatterns.defaultRoot(),
                ContributedPatterns.rootIn(preferences));

        ContributedPatterns.setRootIn(preferences, new File(root, "elsewhere"));
        assertEquals(new File(root, "elsewhere").getAbsolutePath(),
                ContributedPatterns.rootIn(preferences).getAbsolutePath());

        ContributedPatterns.setRootIn(preferences, ContributedPatterns.defaultRoot());
        assertEquals("", preferences.getString(ContributedPatterns.KEY_ROOT, "unset"),
                "the default is stored as a default, not as this machine's path");
    }

    /** Not inside Protege's own directory, where clearing its state would take them. */
    @Test
    void theDefaultIsNotInsideProtegesOwnDirectory() {
        String path = ContributedPatterns.defaultRoot().getAbsolutePath().replace('\\', '/');

        assertFalse(path.contains("/.Protege"), path);
        assertTrue(path.endsWith("/.ontoboard/patterns"), path);
    }

    /** The shipped library is still only what ships, so nothing depends on this machine. */
    @Test
    void theShippedLibraryIsUnaffected(@TempDir File root) throws Exception {
        write(root, "mine/sample.ttl", TURTLE);

        ContributedPatterns.scan(root, nothingTaken());

        assertEquals(159, PatternLibrary.all().size(),
                "all() means the jar; a folder on this machine must not change it");
        assertNull(PatternLibrary.find("mine-sample"));
    }

    /** In-memory Preferences; Protege's is an interface, so no running Protege is needed. */
    private static final class FakePreferences
            implements org.protege.editor.core.prefs.Preferences {
        private final java.util.Map<String, Object> values =
                new java.util.LinkedHashMap<String, Object>();

        @Override
        public void clear() {
            values.clear();
        }

        @Override
        public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }

        @Override
        public void putString(String key, String value) {
            values.put(key, value);
        }

        @Override
        public List<String> getStringList(String key, List<String> fallback) {
            return fallback;
        }

        @Override
        public void putStringList(String key, List<String> value) {
            values.put(key, new ArrayList<String>(value));
        }

        @Override
        public int getInt(String key, int fallback) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : fallback;
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, value);
        }

        @Override
        public float getFloat(String key, float fallback) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : fallback;
        }

        @Override
        public void putFloat(String key, float value) {
            values.put(key, value);
        }

        @Override
        public long getLong(String key, long fallback) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : fallback;
        }

        @Override
        public void putLong(String key, long value) {
            values.put(key, value);
        }

        @Override
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, value);
        }

        @Override
        public byte[] getByteArray(String key, byte[] fallback) {
            return fallback;
        }

        @Override
        public void putByteArray(String key, byte[] value) {
            values.put(key, value);
        }

        @Override
        public List<byte[]> getByteArrayList(String key, List<byte[]> fallback) {
            return fallback;
        }

        @Override
        public double getDouble(String key, double fallback) {
            Object value = values.get(key);
            return value instanceof Double ? (Double) value : fallback;
        }

        @Override
        public void putDouble(String key, double value) {
            values.put(key, value);
        }

        @Override
        public void putByteArrayList(String key, List<byte[]> value) {
            values.put(key, value);
        }
    }
}
