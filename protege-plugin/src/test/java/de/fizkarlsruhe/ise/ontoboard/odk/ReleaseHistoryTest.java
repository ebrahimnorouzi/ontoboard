package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

class ReleaseHistoryTest {

    private static final String NS = "http://example.org/menu#";
    private static final OWLDataFactory DF = OWLManager.getOWLDataFactory();

    /**
     * Every release, newest first, with what each holds and what changed since the one before.
     *
     * <p>Asked for: "go through the evolution of the ontology... the statistics of what was
     * changed: added, deleted, obsoleted".
     */
    @Test
    void eachReleaseSaysWhatChangedSinceTheOneBefore(@TempDir File project) throws Exception {
        release(project, "0.1.0", declare("Pizza"), label("Pizza", "pizza"));
        release(project, "0.2.0", declare("Pizza"), label("Pizza", "a pizza"), declare("Base"),
                DF.getOWLDeclarationAxiom(DF.getOWLObjectProperty(IRI.create(NS + "hasBase"))));
        release(project, "0.10.0", declare("Pizza"), label("Pizza", "a pizza"), declare("Base"),
                DF.getOWLDeclarationAxiom(DF.getOWLObjectProperty(IRI.create(NS + "hasBase"))),
                DF.getOWLAnnotationAssertionAxiom(DF.getOWLDeprecated(), IRI.create(NS + "Base"),
                        DF.getOWLLiteral(true)),
                DF.getOWLDeclarationAxiom(DF.getOWLNamedIndividual(IRI.create(NS + "margherita"))));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("0.10.0", "0.2.0", "0.1.0"), null, null, null);

        assertEquals("[0.10.0, 0.2.0, 0.1.0]", names(history), "newest first, by version");
        assertEquals("First release", history.get(2).describeChange());
        assertNull(history.get(2).getSincePrevious());
        assertEquals("+1 class, 1 relabelled, +1 property", history.get(1).describeChange());
        assertEquals("1 obsoleted, +1 individual", history.get(0).describeChange());
        assertEquals(2, history.get(0).getCounts().getClasses());
        assertEquals(1, history.get(0).getCounts().getObsolete());
        assertEquals(4, history.get(0).getCounts().getTerms());
    }

    /**
     * The working copy is compared as a release would be, its imports folded in.
     *
     * <p>A release is merged; the edit file is not. Compared as they are, every term an import
     * brings in would be reported as removed since the last release - the warning that matters
     * most, raised falsely.
     */
    @Test
    void theWorkingCopyIsComparedWithItsImportsIn(@TempDir File project) throws Exception {
        release(project, "1.0.0", declare("Pizza"), declare("Object"));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology module = manager.createOntology(IRI.create("http://example.org/module.owl"));
        manager.addAxiom(module, declare("Object"));
        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxiom(edit, declare("Pizza"));
        manager.addAxiom(edit, declare("Topping"));
        manager.applyChange(new AddImport(edit,
                DF.getOWLImportsDeclaration(IRI.create("http://example.org/module.owl"))));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("1.0.0"), edit, new File(project, "menu-edit.owl"), null);

        assertEquals("[Working copy, 1.0.0]", names(history));
        assertEquals("+1 class", history.get(0).describeChange(), "Object was not removed");
    }

    /**
     * A release is reasoned and the working copy is not, so the working copy is reasoned before
     * they are compared.
     *
     * <p>Seen on the pizza walkthrough: its working copy, identical to its 1.0.0 release, came
     * back "7 moved" - the parents ELK had added to the release, such as Diavola under
     * SpicyPizza, read as terms the user had moved.
     */
    @Test
    void whatTheReasonerAddedIsNotAChange(@TempDir File project) throws Exception {
        OWLAxiom spicyIsDefined = DF.getOWLEquivalentClassesAxiom(cls("Spicy"),
                DF.getOWLObjectIntersectionOf(cls("Pizza"), DF.getOWLObjectSomeValuesFrom(
                        DF.getOWLObjectProperty(IRI.create(NS + "hasTopping")), cls("Hot"))));
        OWLAxiom[] asserted = {declare("Pizza"), declare("Spicy"), declare("Hot"),
            declare("Diavola"), spicyIsDefined, DF.getOWLSubClassOfAxiom(cls("Diavola"),
                    cls("Pizza")),
            DF.getOWLSubClassOfAxiom(cls("Diavola"), DF.getOWLObjectSomeValuesFrom(
                    DF.getOWLObjectProperty(IRI.create(NS + "hasTopping")), cls("Hot")))};
        // Released as Release releases: the asserted axioms, reasoned with ELK through ROBOT.
        OWLOntologyManager releaser = OWLManager.createOWLOntologyManager();
        OWLOntology released = releaser.createOntology(IRI.create("http://example.org/menu.owl"));
        releaser.addAxioms(released, new java.util.HashSet<OWLAxiom>(Arrays.asList(asserted)));
        releaser.applyChanges(de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.preview(released,
                de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.Kind.REASON,
                de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.DEFAULT.newFactory()).getChanges());
        org.junit.jupiter.api.Assertions.assertTrue(released.containsAxiom(
                DF.getOWLSubClassOfAxiom(cls("Diavola"), cls("Spicy"))), "the reasoner placed it");
        release(project, "1.0.0", released.getAxioms().toArray(new OWLAxiom[0]));

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology edit = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxioms(edit, new java.util.HashSet<OWLAxiom>(Arrays.asList(asserted)));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("1.0.0"), edit, new File(project, "menu-edit.owl"), null);

        assertEquals("No change to any term", history.get(0).describeChange());
        assertEquals(asserted.length, edit.getAxiomCount(),
                "the working copy itself was not touched - it was reasoned as a copy");
    }

    /** Any two versions can be compared, not only neighbours, and in either order of picking. */
    @Test
    void anyTwoVersionsCanBeCompared(@TempDir File project) throws Exception {
        release(project, "0.1.0", declare("Pizza"));
        release(project, "0.2.0", declare("Pizza"), declare("Base"));
        release(project, "0.3.0", declare("Pizza"), declare("Base"), declare("Topping"));
        List<ReleaseHistory.Version> history = ReleaseHistory.of(project, "menu",
                Arrays.asList("0.3.0", "0.2.0", "0.1.0"), null, null, null);

        ReleaseHistory.Version since = ReleaseHistory.compare(history.get(2), history.get(0),
                null);

        assertEquals("+2 classes", since.describeChange());
    }

    // ---------- the releases of a project: its git tags and its releases/ folder ----------

    private static final String DEMO = "https://example.org/demo#";

    /** A machine without git: no git command starts. */
    private static final ProcessRunner.Runner NO_GIT = new ProcessRunner.Runner() {
        @Override
        public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
            throw new IOException("Cannot run program \"git\"");
        }
    };

    static boolean gitIsThere() {
        return ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version");
    }

    static void assumeGit() {
        assumeTrue(gitIsThere(), "git is not installed");
    }

    /** git in {@code root}, as nobody in particular, leaving the bytes of files alone. */
    static void git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>(Arrays.asList("git", "-c",
                "user.name=Test", "-c", "user.email=test@example.org", "-c",
                "core.autocrlf=false"));
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
    }

    /** The demo ontology with these classes, as RDF/XML. */
    static byte[] demo(String... classes) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("https://example.org/demo.owl"));
        for (String name : classes) {
            manager.addAxiom(ontology,
                    DF.getOWLDeclarationAxiom(DF.getOWLClass(IRI.create(DEMO + name))));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        return out.toByteArray();
    }

    /** ODK's way: demo.owl at the project's root, committed, and the commit tagged. */
    static void tagged(File root, String version, byte[] ontology) throws Exception {
        Files.write(new File(root, "demo.owl").toPath(), ontology);
        git(root, "add", "-f", "demo.owl");
        git(root, "commit", "-q", "-m", "release(" + version + ")");
        git(root, "tag", "-a", "v" + version, "-m", "demo " + version);
    }

    /** OntoBoard's older way: a copy kept in {@code releases/<folder>/}. */
    static File kept(File root, String folder, byte[] ontology) throws Exception {
        File file = new File(new File(new File(root, "releases"), folder), "demo.owl");
        assertTrue(file.getParentFile().mkdirs());
        Files.write(file.toPath(), ontology);
        return file;
    }

    private static Set<String> classesOf(OWLOntology ontology) {
        Set<String> names = new TreeSet<String>();
        for (OWLClass owlClass : ontology.getClassesInSignature()) {
            String iri = owlClass.getIRI().toString();
            if (iri.startsWith(DEMO)) {
                names.add(iri.substring(DEMO.length()));
            }
        }
        return names;
    }

    private static Set<String> set(String... names) {
        return new TreeSet<String>(Arrays.asList(names));
    }

    private static String versionsOf(List<ReleaseHistory.Entry> releases) {
        List<String> versions = new ArrayList<String>();
        for (ReleaseHistory.Entry release : releases) {
            versions.add(release.version());
        }
        return versions.toString();
    }

    private static String sourcesOf(List<ReleaseHistory.Entry> releases) {
        List<String> sources = new ArrayList<String>();
        for (ReleaseHistory.Entry release : releases) {
            sources.add(String.valueOf(release.source()));
        }
        return sources.toString();
    }

    /**
     * ODK releases with a commit and a tag {@code v<version>}, the files at the project's root,
     * and keeps no releases/ folder. The tag is the release, and what it holds is what was
     * committed - whatever the file at the root holds now.
     */
    @Test
    void aTagIsARelease(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        byte[] released = demo("Egg");
        tagged(root, "1.0.0", released);
        Files.write(new File(root, "demo.owl").toPath(), demo("Egg", "Pan"));

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo");

        assertEquals("[1.0.0]", versionsOf(releases));
        ReleaseHistory.Entry release = releases.get(0);
        assertEquals(ReleaseHistory.Source.TAG, release.source());
        assertEquals("v1.0.0", release.tag());
        assertNull(release.file(), "no file on disk is this release");
        assertFalse(new File(root, "releases").exists());
        assertArrayEquals(released, release.bytes(), "what was committed at the tag");
        assertEquals(set("Egg"), classesOf(release.load()),
                "not the working copy's demo.owl, which has Pan now");
    }

    /** A project that is no git repository, on a machine without git: its folders, no error. */
    @Test
    void aFolderIsStillARelease(@TempDir File root) throws Exception {
        byte[] released = demo("Egg");
        File file = kept(root, "1.0.0", released);

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo", NO_GIT);

        assertEquals("[1.0.0]", versionsOf(releases));
        ReleaseHistory.Entry release = releases.get(0);
        assertEquals(ReleaseHistory.Source.FOLDER, release.source());
        assertNull(release.tag());
        assertEquals(file.getAbsolutePath(), release.file().getAbsolutePath());
        assertArrayEquals(released, release.bytes());
        assertEquals(set("Egg"), classesOf(release.load()));
        if (gitIsThere()) {
            assertEquals("[1.0.0]", versionsOf(ReleaseHistory.releasesOf(root, "demo")),
                    "with git, in a folder that is no repository: the same, and no error");
        }
    }

    /**
     * A version with a tag and a folder is one release. The folder's file is read when it is
     * there - on disk, and OntoBoard's copy of what was tagged; a folder without it leaves the
     * tag's.
     */
    @Test
    void bothForOneVersionAreOneRelease(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        File copy = kept(root, "1.0.0", demo("Egg", "Copy"));
        tagged(root, "1.1.0", demo("Egg", "Pan"));
        assertTrue(new File(root, "releases/1.1.0").mkdirs());

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo");

        assertEquals("[1.1.0, 1.0.0]", versionsOf(releases), "each version once");
        assertEquals("[BOTH, BOTH]", sourcesOf(releases));
        assertEquals("v1.0.0", releases.get(1).tag());
        assertEquals(copy.getAbsolutePath(), releases.get(1).file().getAbsolutePath());
        assertEquals(set("Copy", "Egg"), classesOf(releases.get(1).load()), "the folder's file");
        assertNull(releases.get(0).file(), "an empty folder");
        assertEquals(set("Egg", "Pan"), classesOf(releases.get(0).load()), "so the tag's");
    }

    /**
     * A repository holds tags of other things too. A tag whose commit has no demo.owl at the
     * project's root is not a release of this ontology - not even one listed without a file.
     */
    @Test
    void aTagWithoutTheOntologyIsNotARelease(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        Files.write(new File(root, "tool.txt").toPath(),
                "a tool\n".getBytes(StandardCharsets.UTF_8));
        git(root, "add", "-f", "tool.txt");
        git(root, "commit", "-q", "-m", "a tool");
        git(root, "tag", "v3.0.0");
        tagged(root, "1.0.0", demo("Egg"));
        git(root, "rm", "-q", "demo.owl");
        File elsewhere = new File(root, "other/demo.owl");
        assertTrue(elsewhere.getParentFile().mkdirs());
        Files.write(elsewhere.toPath(), demo("Egg"));
        git(root, "add", "-f", "other/demo.owl");
        git(root, "commit", "-q", "-m", "demo.owl moved");
        git(root, "tag", "-a", "v2.0.0", "-m", "demo.owl not at the root");

        assertEquals("[v3.0.0, v2.0.0, v1.0.0]",
                GitTags.releases(root, ProcessRunner.real()).toString(),
                "three release tags by their names");
        assertEquals("[1.0.0]", versionsOf(ReleaseHistory.releasesOf(root, "demo")));
        assertEquals("[2.0.0]", versionsOf(ReleaseHistory.releasesOf(
                new File(root, "other"), "demo")), "a project in a folder of the repository");
    }

    /**
     * Tags and folders in one order, newest first, by version: 1.10.0 after 1.2.0, whenever
     * each tag was made. Dated releases come after numbered ones - OntologyVersion's order
     * across the two conventions, the one the tags and the folders were each sorted by before.
     */
    @Test
    void aMixedListIsNewestFirst(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "2.0.0", demo("Egg", "Pan"));
        tagged(root, "1.2.0", demo("Egg"));
        kept(root, "1.10.0", demo("Egg"));
        kept(root, "0.9.0", demo("Egg"));
        File writtenWithAV = kept(root, "v1.0.0", demo("Egg"));
        kept(root, "2026-10-12", demo("Egg"));

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo");

        assertEquals("[2.0.0, 1.10.0, 1.2.0, 1.0.0, 0.9.0, 2026-10-12]", versionsOf(releases));
        assertEquals("[TAG, FOLDER, TAG, FOLDER, FOLDER, FOLDER]", sourcesOf(releases));
        assertEquals(writtenWithAV.getAbsolutePath(),
                releases.get(3).file().getAbsolutePath(), "read where it is");
    }

    /** The demo ontology with these classes, as Turtle. */
    static byte[] demoTurtle(String... classes) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                new java.io.ByteArrayInputStream(demo(classes)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        manager.saveOntology(ontology, new org.semanticweb.owlapi.formats.TurtleDocumentFormat(),
                out);
        return out.toByteArray();
    }

    /**
     * nfdicore's tags v1.0.0 to v2.0.0 hold nfdicore.ttl at the root, not nfdicore.owl, and
     * History found 7 of its 13 releases (1.155.0). A tag is a release when its root holds the
     * ontology in a syntax the OWL API reads: .owl, .ttl, .obo, .ofn, .owx - in that order.
     */
    @Test
    void aTagHoldingTheOntologyInAnotherSyntaxIsARelease(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        byte[] turtle = demoTurtle("Egg");
        Files.write(new File(root, "demo.ttl").toPath(), turtle);
        git(root, "add", "-f", "demo.ttl");
        git(root, "commit", "-q", "-m", "release(1.0.0) as Turtle");
        git(root, "tag", "-a", "v1.0.0", "-m", "demo 1.0.0");
        tagged(root, "2.0.0", demo("Egg", "Pan"));

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo");

        assertEquals("[2.0.0, 1.0.0]", versionsOf(releases));
        assertArrayEquals(turtle, releases.get(1).bytes(), "the Turtle the tag holds");
        assertEquals(set("Egg"), classesOf(releases.get(1).load()));
        List<ReleaseHistory.Version> history = ReleaseHistory.of(releases, null, null, null);
        assertEquals("+1 class", history.get(0).describeChange());
    }

    /**
     * One release that cannot be read is shown as such, with why; the others are read and
     * compared with each other - before, History stopped at the first it could not read.
     */
    @Test
    void aReleaseThatCannotBeReadStopsNothing(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        tagged(root, "1.1.0", "this is no ontology".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tagged(root, "1.2.0", demo("Egg", "Pan"));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(
                ReleaseHistory.releasesOf(root, "demo"), null, null, null);

        assertEquals("[1.2.0, 1.1.0, 1.0.0]", names(history));
        assertTrue(history.get(0).isReadable());
        assertEquals("+1 class", history.get(0).describeChange(),
                "compared with 1.0.0, the release before it that could be read");
        assertFalse(history.get(1).isReadable());
        assertTrue(history.get(1).describeChange().startsWith("Could not be read"),
                history.get(1).describeChange());
        assertEquals("First release", history.get(2).describeChange());
    }

    /**
     * A release fetched from GitHub earlier - ODK v1.6.1 keeps release files out of git and puts
     * them on the GitHub release - is read from where it was kept, without the network; a version
     * git or releases/ has already is not taken from there.
     */
    @Test
    void aReleaseFetchedFromGitHubIsRead(@TempDir File top) throws Exception {
        File root = new File(top, "project");
        File dotGit = new File(root, ".git");
        assertTrue(dotGit.mkdirs());
        Files.write(new File(dotGit, "config").toPath(), ("[remote \"origin\"]\n"
                + "\turl = https://github.com/o/demo.git\n").getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        File cache = new File(top, "cache");
        File fetched = new File(cache, "o/demo/v1.0.0/demo.owl");
        assertTrue(fetched.getParentFile().mkdirs());
        Files.write(fetched.toPath(), demo("Egg"));
        File other = new File(cache, "o/demo/v1.1.0/demo.owl");
        assertTrue(other.getParentFile().mkdirs());
        Files.write(other.toPath(), demo("Egg", "Lid"));
        kept(root, "1.1.0", demo("Egg", "Pan"));

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo", NO_GIT,
                cache);

        assertEquals("[1.1.0, 1.0.0]", versionsOf(releases));
        assertEquals("[FOLDER, GITHUB]", sourcesOf(releases));
        assertEquals(fetched.getAbsoluteFile(), releases.get(1).file().getAbsoluteFile());
        List<ReleaseHistory.Version> history = ReleaseHistory.of(releases, null, null, null);
        assertEquals("+1 class", history.get(0).describeChange(), "Pan, the folder's");
    }

    /** History reads a release that is only a tag, beside one that is only a folder. */
    @Test
    void theHistoryReadsTaggedReleases(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        tagged(root, "1.0.0", demo("Egg"));
        kept(root, "1.1.0", demo("Egg", "Pan"));
        tagged(root, "1.2.0", demo("Egg", "Pan", "Lid"));

        List<ReleaseHistory.Version> history = ReleaseHistory.of(
                ReleaseHistory.releasesOf(root, "demo"), null, null, null);

        assertEquals("[1.2.0, 1.1.0, 1.0.0]", names(history));
        assertEquals("+1 class", history.get(0).describeChange());
        assertEquals("+1 class", history.get(1).describeChange());
        assertEquals("First release", history.get(2).describeChange());
        assertNull(history.get(0).getFile(), "a tag is no file");
        assertEquals("v1.2.0", history.get(0).getRelease().tag());
        assertEquals("+2 classes",
                ReleaseHistory.compare(history.get(2), history.get(0), null).describeChange());
    }

    /**
     * Protege opens a file. A release that is only a tag is written out for it: outside the
     * project, under a name that says whose release and which, read-only. A folder's own file
     * opens as it is.
     */
    @Test
    void aTaggedReleaseOpensAsACopyOutsideTheProject(@TempDir File root) throws Exception {
        assumeGit();
        git(root, "init", "-q");
        byte[] released = demo("Egg");
        tagged(root, "1.0.0", released);
        File folders = kept(root, "0.9.0", demo());

        List<ReleaseHistory.Entry> releases = ReleaseHistory.releasesOf(root, "demo");
        File copy = releases.get(0).openable();
        try {
            assertArrayEquals(released, Files.readAllBytes(copy.toPath()));
            assertFalse(copy.toPath().toAbsolutePath().startsWith(root.toPath().toAbsolutePath()),
                    copy + " is in the project");
            assertTrue(copy.toPath().toAbsolutePath().startsWith(
                    new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath()),
                    copy + " is not in the temporary folder");
            assertTrue(copy.getName().startsWith("demo-1.0.0-")
                    && copy.getName().contains("copy"), copy.getName());
            assertFalse(copy.canWrite(), "a copy of a release is not to be edited");
            assertEquals(folders.getAbsolutePath(),
                    releases.get(1).openable().getAbsolutePath());
        } finally {
            copy.setWritable(true);
            copy.delete();
        }
    }

    private static void release(File project, String version, OWLAxiom... axioms)
            throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/menu.owl"));
        manager.addAxioms(ontology, new java.util.HashSet<OWLAxiom>(Arrays.asList(axioms)));
        File file = Release.releaseFile(project, "menu", version);
        file.getParentFile().mkdirs();
        manager.saveOntology(ontology, IRI.create(file.toURI()));
    }

    private static OWLAxiom declare(String name) {
        return DF.getOWLDeclarationAxiom(cls(name));
    }

    private static OWLAxiom label(String name, String text) {
        return DF.getOWLAnnotationAssertionAxiom(DF.getRDFSLabel(), cls(name).getIRI(),
                DF.getOWLLiteral(text));
    }

    private static OWLClass cls(String name) {
        return DF.getOWLClass(IRI.create(NS + name));
    }

    private static String names(List<ReleaseHistory.Version> history) {
        StringBuilder names = new StringBuilder("[");
        for (ReleaseHistory.Version version : history) {
            names.append(names.length() == 1 ? "" : ", ").append(version.getName());
        }
        return names.append(']').toString();
    }
}
