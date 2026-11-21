package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.axiom.EntityFactory;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;

/**
 * nfdicore and MWO developed further by OntoBoard alone, on fresh clones - the round of 1.155.0's
 * Phase 7, done in Protégé with both projects, without Protégé and without a container:
 * opened as Protégé opens them, the imports it leaves out put in and every import resolved; an ID
 * range for a new editor, and a class and an object property from it; a term added to an
 * import's list; {@code make test} by OntoBoard's engine, the module built again with the term and
 * the project opened again whole; the working copy as released against the last release, the
 * project's own terms as they were but for the two new ones; the project's own
 * {@code release.sh}, line by line, by OntoBoard's engine; the release committed and tagged.
 *
 * <p>The network, and minutes: run with {@code -Dontoboard.realProjects=true}, skipped without
 * the network. nfdicore's mirror of DCAT imports by addresses that move from http to https, which
 * the OWL API of Protégé 5.5.0 does not follow for an import: it runs with
 * {@code -Dowlapi.version=4.5.29}, Protégé 5.6.9's (as {@link RealProjectsLiveTest}).
 */
class RealProjectsDevelopmentLiveTest {

    private static final String EDITOR = "ontoboard-live";

    /**
     * The projects at the commits of their last releases, the release that is next, a class of
     * their own to put the new one under, the import whose list gains a term, and the term - one
     * the module does not hold at that commit.
     */
    static Stream<Arguments> projects() {
        return Stream.of(
                Arguments.of("nfdicore", "https://github.com/ISE-FIZKarlsruhe/nfdicore.git",
                        "631e18a27f4a43f7f664264708466ce0c86215aa", "3.0.6",
                        "https://nfdi.fiz-karlsruhe.de/ontology/NFDI_0000102", "iao",
                        "http://purl.obolibrary.org/obo/IAO_0000032", true),
                Arguments.of("mwo", "https://github.com/ISE-FIZKarlsruhe/mwo.git",
                        "0dcb29f5708b2d45106fc3fc19b5224b684af711", "3.0.3",
                        "http://purls.helmholtz-metadaten.de/mwo/MWO_0001049", "iao",
                        "http://purl.obolibrary.org/obo/IAO_0000310", false));
    }

    @ParameterizedTest
    @MethodSource("projects")
    void developedFurtherAndReleasedByOntoBoardAlone(String name, String url, String commit,
            String next, String parent, String importName, String term,
            boolean importsThatMove, @TempDir File temp) throws Exception {
        assumeTrue(Boolean.getBoolean("ontoboard.realProjects"),
                "-Dontoboard.realProjects=true runs it: it needs the network, and minutes");
        assumeTrue(!importsThatMove || RunComparisons.onOdksOwlApi(), name + " imports by "
                + "addresses that move from http to https; run with -Dowlapi.version=4.5.29");
        assumeTrue(ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version"),
                "git is not installed");
        File clone = new File(temp, name);
        ProcessRunner.Outcome cloned = ProcessRunner.real().run(temp, Arrays.asList("git",
                "clone", "--quiet", url, clone.getPath()), 10, null);
        assumeTrue(cloned.isSuccess(), "no network: " + cloned.lastMeaningfulLine());
        git(clone, "checkout", "--quiet", "-B", "main", commit);
        git(clone, "config", "user.name", "OntoBoard live test");
        git(clone, "config", "user.email", "live-test@example.org");
        git(clone, "config", "commit.gpgsign", "false");
        git(clone, "config", "tag.gpgsign", "false");
        File edit = new File(clone, "src/ontology/" + name + "-edit.owl");
        File catalog = new File(edit.getParentFile(), "catalog-v001.xml");

        // ------------------------------------------------------------ opened as Protégé opens it
        OWLOntology open = openedAsProtegeOpensIt(edit, catalog);
        assertFalse(ImportLinks.link(open, catalog).isEmpty(),
                name + "'s modules call themselves otherwise; OntoBoard puts them in");
        assertEverythingImported(open, catalog);

        // ------------------------------------------------------------ a class and a property
        File rangesFile = new File(edit.getParentFile(), name + "-idranges.owl");
        String original = read(rangesFile);
        IdRanges ranges = IdRanges.parse(original).withRangeFor(EDITOR, 100);
        assertEquals(Collections.<String>emptyList(),
                IdRanges.whatWouldBeLost(original, ranges.toManchester()));
        write(rangesFile, ranges.toManchester());
        TermMinter minter = TermMinter.forOntologyFile(edit, EDITOR);
        assertTrue(minter.isNumeric(), minter.describe());
        OWLOntologyManager manager = open.getOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        IRI cls = minter.mintFor(open, "live test class");
        assertTrue(cls.toString().startsWith(ranges.getIdPrefix()), cls.toString());
        manager.applyChanges(minter.declare(open, cls, EntityFactory.Kind.CLASS,
                "live test class"));
        manager.addAxiom(open, factory.getOWLSubClassOfAxiom(factory.getOWLClass(cls),
                factory.getOWLClass(IRI.create(parent))));
        IRI property = minter.mintFor(open, "live test relation");
        assertNotEquals(cls, property, "the next identifier of the range");
        manager.applyChanges(minter.declare(open, property, EntityFactory.Kind.OBJECT_PROPERTY,
                "live test relation"));
        OWLDocumentFormat format = manager.getOntologyFormat(open);
        assertNotNull(format, "the edit file keeps its format");
        manager.saveOntology(open, format, IRI.create(edit.toURI()));

        // ------------------------------------------------------------ a term for an import
        File terms = new File(edit.getParentFile(), "imports/" + importName + "_terms.txt");
        write(terms, read(terms).replaceAll("\\s*$", "") + "\n" + term + "\n");

        // ------------------------------------------------------------ make test, by the engine
        long started = System.currentTimeMillis();
        ProcessRunner.Outcome built = OdkRelease.byOntoBoardItself(edit, null)
                .run(Arrays.asList("make", "test"), false);
        assertTrue(built.isSuccess(), "make test: " + tail(built.getOutput()));
        File module = new File(edit.getParentFile(), "imports/" + importName + "_import.owl");
        String local = term.substring(term.lastIndexOf('/') + 1);
        assertTrue(read(module).contains(local), "the module was built again with " + local);
        OWLOntology again = openedAsProtegeOpensIt(edit, catalog);
        ImportLinks.link(again, catalog);
        assertEverythingImported(again, catalog);
        System.out.println(name + ": make test in " + seconds(started));

        // ------------------------------------------------------------ against the last release
        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(clone,
                Release.ontologyIdFrom(edit));
        assertFalse(releases.isEmpty(), name + "'s releases are its tags");
        ReleaseDiff since = ReleaseDiff.between(releases.get(0).load(),
                ReleaseHistory.asReleased(again));
        List<String> ownChanges = new ArrayList<String>();
        String own = ranges.getIdPrefix().substring(0, Math.max(
                ranges.getIdPrefix().lastIndexOf('/'), ranges.getIdPrefix().lastIndexOf('#')) + 1);
        for (ReleaseDiff.TermChange change : since.getChanges()) {
            boolean ours = change.getIri().toString().startsWith(own);
            boolean added = change.getIri().equals(cls) || change.getIri().equals(property);
            // A move can come from the imports: a parent the reasoner finds through them.
            if (ours && !added && change.getChange() != ReleaseDiff.Change.MOVED) {
                ownChanges.add(change.toString());
            }
        }
        assertEquals(Collections.<String>emptyList(), ownChanges,
                "the project's own terms as they were: " + since.summary());
        assertTrue(since.getChanges().toString().contains("Added: " + cls),
                since.getChanges().toString());
        assertTrue(since.getChanges().toString().contains("Added: " + property),
                since.getChanges().toString());

        // ------------------------------------------------------------ its own release.sh
        // As the Release form does: the script's VERSION= is the release's, its PRIOR_VERSION=
        // the release it follows - the one the script names now.
        ReleaseRun.Script script = ReleaseRun.scriptIn(edit.getParentFile());
        assertNotNull(script, name + " is released by its own release.sh");
        String lastVersion = script.version();
        script.write(script.withLines(next, script.priorVersion() == null ? null : lastVersion,
                null));
        started = System.currentTimeMillis();
        OdkRelease.Outcome released = OdkRelease.run(new OdkRelease.Request(edit, next, null,
                true, false, false, true), OdkRelease.byOntoBoardItself(edit, null),
                ProcessRunner.real(), staging -> true);
        String said = released.getFailure() + "\n" + String.join("\n",
                released.getTranscript());
        assertTrue(released.isOk(), said);
        assertTrue(said.contains("The script ran to its end."), said);
        assertTrue(released.isCommitted(), said);
        assertEquals("v" + next, released.getTag());
        assertEquals(Collections.singletonList("release(" + next + ")"),
                git(clone, "log", "-1", "--format=%s"));
        assertTrue(read(new File(clone, name + ".owl")).contains(
                cls.toString().substring(cls.toString().lastIndexOf('/') + 1)),
                "the release holds the new class");
        System.out.println(name + ": release.sh in " + seconds(started));
    }

    /** Read as Protégé 5.6.9 reads it: by a manager of its own, then moved into another. */
    private static OWLOntology openedAsProtegeOpensIt(File edit, File catalog) throws Exception {
        final String xml = read(catalog);
        final File folder = catalog.getParentFile();
        OWLOntologyManager aside = OWLManager.createOWLOntologyManager();
        aside.getIRIMappers().add((OWLOntologyIRIMapper) wanted -> {
            String mapped = Catalog.entryFor(xml, wanted.toString());
            File file = mapped == null ? null : new File(folder, mapped);
            return file != null && file.isFile() ? IRI.create(file.toURI()) : null;
        });
        OWLOntology read = aside.loadOntologyFromOntologyDocument(
                new org.semanticweb.owlapi.io.FileDocumentSource(edit),
                new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                        MissingImportHandlingStrategy.SILENT));
        IRI name = read.getOntologyID().getOntologyIRI().get();
        OWLOntologyManager workspace = OWLManager.createOWLOntologyManager();
        for (OWLOntology one : new ArrayList<OWLOntology>(aside.getOntologies())) {
            workspace.copyOntology(one, OntologyCopy.MOVE);
        }
        return workspace.getOntology(name);
    }

    /** Every import of the ontology, and of what it imports, has arrived. */
    private static void assertEverythingImported(OWLOntology open, File catalog) {
        for (OWLOntology part : open.getImportsClosure()) {
            assertEquals(Collections.<ImportHealth.Missing>emptyList(), ImportHealth.missingFrom(
                    part, open.getOWLOntologyManager(), catalog), String.valueOf(
                            part.getOntologyID()));
        }
    }

    private static List<String> git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Collections.singletonList("git"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 5, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
        return outcome.getOutput();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String tail(List<String> lines) {
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }

    private static String seconds(long started) {
        return (System.currentTimeMillis() - started) / 1000 + " s";
    }
}
