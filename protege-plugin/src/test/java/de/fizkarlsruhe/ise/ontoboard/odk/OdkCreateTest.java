package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.robot.SparqlQuery;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * New ODK project: ODK's seed, then OntoBoard's extras, then a first commit.
 *
 * <p>The seed is ODK's to the byte (acceptance 1 and 2 hold that); this holds that Create leaves
 * it so, adds only files the seed does not write, and that the defaults a new project gets are
 * ODK's (acceptance 7): its checks with OntoBoard's label check among them, its report with the
 * seeded profile, its CI in {@code odkfull} at the pinned version.
 */
class OdkCreateTest {

    private static final Set<String> EXTRAS = new TreeSet<String>(Arrays.asList(".gitattributes",
            "mkdocs.yaml", "docs/index.md", "docs/editing.md", "docs/release.md",
            "src/sparql/check_labels-violation.sparql"));

    private static OdkProjectConfig egg(File parent) {
        return new OdkProjectConfig("eggboil", "Test kitchen ontology",
                "Application ontology for boiling eggs in a test kitchen",
                "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", parent);
    }

    /** A machine with nothing on it: no Docker, no Podman, no git. */
    private static ProcessRunner.Runner nothingInstalled() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
        };
    }

    /** A machine with git, as this one has it, and no container runtime. */
    private static ProcessRunner.Runner gitOnly() {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!"git".equals(command.get(0))) {
                    throw new IOException("Cannot run program \"" + command.get(0) + "\"");
                }
                return ProcessRunner.real().run(workingDirectory, command, timeoutMinutes, sink);
            }
        };
    }

    private static boolean gitIsInstalled() {
        return ProcessRunner.isAvailable(ProcessRunner.real(), "git", "--version");
    }

    private static List<String> git(File root, String... arguments) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));
        ProcessRunner.Outcome outcome = ProcessRunner.real().run(root, command, 1, null);
        assertTrue(outcome.isSuccess(), command + ": " + outcome.getOutput());
        return outcome.getOutput();
    }

    private static Set<String> pathsOf(File root, List<File> files) {
        Set<String> paths = new TreeSet<String>();
        for (File file : files) {
            paths.add(root.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        }
        return paths;
    }

    private static String text(File root, String path) throws IOException {
        return new String(Files.readAllBytes(new File(root, path).toPath()),
                StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ what is made

    @Test
    void aNewProjectIsTheSeedPlusTheExtras(@TempDir File temp) throws IOException {
        OdkCreate.Created made = OdkCreate.create(egg(temp), null, nothingInstalled());

        File root = new File(temp, "eggboil");
        Map<String, byte[]> seed = Goldens.seedTree("eggboil");
        Set<String> expected = new TreeSet<String>(seed.keySet());
        expected.addAll(EXTRAS);
        assertEquals(expected, pathsOf(root, made.written()));
        assertEquals(expected, new TreeSet<String>(Goldens.tree(root).keySet()));
        for (String path : seed.keySet()) {
            assertArrayEquals(seed.get(path), Files.readAllBytes(new File(root, path).toPath()),
                    path);
        }
        assertEquals(OdkCreate.Route.BUNDLED, made.route());
    }

    /**
     * An extra over a seed file would make the project something other than ODK's seed. For the
     * configuration Create writes there is no such file: it has no {@code documentation:} block,
     * so ODK writes no pages and no {@code mkdocs.yaml} of its own.
     */
    @Test
    void noExtraIsAFileTheSeedWrites() {
        Set<String> extras = OdkExtras.files(egg(new File("anywhere"))).keySet();

        assertEquals(EXTRAS, new TreeSet<String>(extras));
        assertEquals(Goldens.config("eggboil"), OdkConfigWriter.forNewProject(egg(null)),
                "the golden seed is the seed of the configuration Create writes");
        for (String extra : extras) {
            assertFalse(Goldens.seedTree("eggboil").containsKey(extra), "ODK seeds " + extra);
        }
    }

    /**
     * And should a seed ever write one - a configuration with a {@code documentation:} block
     * makes ODK write {@code mkdocs.yaml} - ODK's file stands.
     */
    @Test
    void anExtraNeverReplacesAFileOfTheSeed(@TempDir File root) throws IOException {
        byte[] odks = "site_name: ODK's own\n".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(root, "mkdocs.yaml").toPath(), odks);
        List<File> written = new ArrayList<File>();

        OdkCreate.addExtras(root, OdkExtras.files(egg(root)), written);

        assertArrayEquals(odks, Files.readAllBytes(new File(root, "mkdocs.yaml").toPath()));
        Set<String> added = new TreeSet<String>(EXTRAS);
        added.remove("mkdocs.yaml");
        assertEquals(added, pathsOf(root, written));
    }

    /** Every extra is LF, as everything ODK writes is. */
    @Test
    void theExtrasAreWrittenWithLineFeeds(@TempDir File temp) throws IOException {
        OdkCreate.create(egg(temp), null, nothingInstalled());

        for (String extra : EXTRAS) {
            String text = text(new File(temp, "eggboil"), extra);
            assertFalse(text.contains("\r"), extra);
            assertTrue(text.endsWith("\n"), extra);
        }
    }

    /** Review focus 1. */
    @Test
    void aFolderWithSpacesAndUmlautsHoldsAProject(@TempDir File temp) throws IOException {
        File parent = new File(temp, "José Müller/My Ontologies");
        assertTrue(parent.mkdirs());

        OdkCreate.create(egg(parent), null, nothingInstalled());

        Map<String, byte[]> made = Goldens.tree(new File(parent, "eggboil"));
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            assertArrayEquals(file.getValue(), made.get(file.getKey()), file.getKey());
        }
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    void anAnswerTheFormRefusesWritesNothing(@TempDir File temp) {
        OdkProjectConfig wrongIri = new OdkProjectConfig("eggboil", "Egg", "",
                "https://example.org/eggboil", "https://creativecommons.org/licenses/by/4.0/",
                temp);
        OdkProjectConfig noTitle = new OdkProjectConfig("eggboil", "", "",
                "https://example.org/eggboil/eggboil.owl",
                "https://creativecommons.org/licenses/by/4.0/", temp);

        IllegalArgumentException shape = assertThrows(IllegalArgumentException.class,
                () -> OdkCreate.create(wrongIri, null, nothingInstalled()));
        assertThrows(IllegalArgumentException.class,
                () -> OdkCreate.create(noTitle, null, nothingInstalled()));

        assertTrue(shape.getMessage().contains("/eggboil.owl"), shape.getMessage());
        assertEquals(0, temp.list().length);
    }

    @Test
    void aFolderThatIsThereIsNotWrittenInto(@TempDir File temp) throws IOException {
        File root = new File(temp, "eggboil");
        assertTrue(root.mkdirs());
        Files.write(new File(root, "notes.txt").toPath(), new byte[] {1});

        assertThrows(IllegalArgumentException.class,
                () -> OdkCreate.create(egg(temp), null, nothingInstalled()));

        assertArrayEquals(new String[] {"notes.txt"}, root.list());
    }

    // ------------------------------------------------------------------ stopped on the way

    /**
     * The dialog that waits for Create has a button to stop waiting. Whenever it is pressed,
     * nothing is left of the project: the same answers can be given again.
     */
    @Test
    void stoppedBeforeItStartsItWritesNothing(@TempDir File temp) {
        assertThrows(java.util.concurrent.CancellationException.class, () -> OdkCreate.create(
                egg(temp), null, nothingInstalled(), true, () -> true));

        assertEquals(0, temp.list().length);
    }

    @Test
    void stoppedAfterTheSeedItTakesTheProjectBack(@TempDir File temp) {
        final File seeded = new File(temp, "eggboil/src/ontology/Makefile");

        assertThrows(java.util.concurrent.CancellationException.class, () -> OdkCreate.create(
                egg(temp), null, nothingInstalled(), true, seeded::isFile));

        assertEquals(0, temp.list().length);
    }

    @Test
    void stoppedAfterTheCommitItTakesTheRepositoryBackToo(@TempDir File temp) {
        assumeTrue(gitIsInstalled(), "git is not installed");
        final File repository = new File(temp, "eggboil/.git/HEAD");

        assertThrows(java.util.concurrent.CancellationException.class, () -> OdkCreate.create(
                egg(temp), null, gitOnly(), true, repository::isFile));

        assertEquals(0, temp.list().length);
    }

    /** A seed that was killed is not a seed that failed: the templates do not take over. */
    @Test
    void stoppedWhileTheContainerSeedsNothingElseSeedsInstead(@TempDir File temp) {
        final boolean[] killed = {false};
        ProcessRunner.Runner stoppable = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                String line = String.join(" ", command);
                if (line.equals("docker info") || line.contains("image inspect --format {{.Id}}")) {
                    return new ProcessRunner.Outcome(0, Collections.singletonList("ok"), false);
                }
                if (line.startsWith("docker run")) {
                    killed[0] = true;
                    return new ProcessRunner.Outcome(137, Collections.<String>emptyList(), false,
                            true);
                }
                throw new IOException("Cannot run program");
            }
        };

        assertThrows(java.util.concurrent.CancellationException.class, () -> OdkCreate.create(
                egg(temp), null, stoppable, true, () -> killed[0]));

        assertTrue(killed[0]);
        assertEquals(0, temp.list().length);
    }

    // ------------------------------------------------------------------ git

    @Test
    void withGitThereIsOneCommitOfEverything(@TempDir File temp) throws IOException {
        assumeTrue(gitIsInstalled(), "git is not installed");

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, gitOnly());

        File root = new File(temp, "eggboil");
        assertTrue(made.committed(), String.valueOf(made.notRecorded()));
        assertNull(made.notRecorded());
        assertEquals(Collections.singletonList("1"), git(root, "rev-list", "--count", "HEAD"));
        assertEquals(Collections.singletonList("main"), git(root, "branch", "--show-current"));
        assertEquals(Collections.singletonList("initial commit"),
                git(root, "log", "-1", "--format=%s"));
        Set<String> committed = new TreeSet<String>(git(root, "ls-tree", "-r", "--name-only",
                "HEAD"));
        assertEquals(pathsOf(root, made.written()), committed);
        assertEquals(Collections.<String>emptyList(), git(root, "status", "--porcelain"),
                "nothing is left to commit");
    }

    /** What is committed is what was written: LF, whatever this machine's git is set to. */
    @Test
    void whatIsCommittedIsWhatWasWritten(@TempDir File temp) throws IOException {
        assumeTrue(gitIsInstalled(), "git is not installed");
        OdkCreate.create(egg(temp), null, gitOnly());
        File root = new File(temp, "eggboil");

        // The size of what git holds is the size of what ODK wrote: no line ending was changed
        // on the way in, in any of the files.
        Map<String, String> sizes = new java.util.TreeMap<String, String>();
        for (String line : git(root, "ls-tree", "-r", "-l", "HEAD")) {
            // <mode> blob <hash> <size>TAB<path>
            String[] columns = line.substring(0, line.indexOf('\t')).trim().split("\\s+");
            sizes.put(line.substring(line.indexOf('\t') + 1), columns[3]);
        }
        for (Map.Entry<String, byte[]> file : Goldens.seedTree("eggboil").entrySet()) {
            assertEquals(String.valueOf(file.getValue().length), sizes.get(file.getKey()),
                    file.getKey());
        }
        String endings = git(root, "ls-files", "--eol", "src/ontology/run.sh").get(0);
        assertTrue(endings.startsWith("i/lf") && endings.contains("w/lf")
                && endings.contains("eol=lf"), endings);
    }

    /** Review focus 5: a machine without git still gets its project, and is told. */
    @Test
    void withoutGitTheProjectIsMadeAndSaysWhatWasNotRecorded(@TempDir File temp) {
        OdkCreate.Created made = OdkCreate.create(egg(temp), null, nothingInstalled());

        assertFalse(made.committed());
        assertNotNull(made.notRecorded());
        assertTrue(made.notRecorded().contains("git is not installed"), made.notRecorded());
        assertTrue(made.notRecorded().contains("git init"), made.notRecorded());
        assertFalse(new File(temp, "eggboil/.git").exists());
        assertTrue(new File(temp, "eggboil/src/ontology/eggboil-edit.owl").isFile());
    }

    @Test
    void whoeverDoesNotWantGitGetsNone(@TempDir File temp) {
        final List<List<String>> ran = new ArrayList<List<String>>();
        ProcessRunner.Runner recording = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                ran.add(command);
                throw new IOException("Cannot run program");
            }
        };

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, recording, false);

        assertFalse(made.committed());
        assertNull(made.notRecorded(), "nothing was asked for, so nothing is missing");
        for (List<String> command : ran) {
            assertFalse("git".equals(command.get(0)), command.toString());
        }
    }

    /**
     * A git that knows nobody refuses to commit. The commit is then made in OntoBoard's name,
     * as Convert to ODK does, rather than left out - and the result says so.
     */
    @Test
    void aGitThatKnowsNobodyCommitsInOntoBoardsName(@TempDir File temp) {
        final List<List<String>> ran = new ArrayList<List<String>>();
        ProcessRunner.Runner anonymous = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!"git".equals(command.get(0))) {
                    throw new IOException("Cannot run program");
                }
                ran.add(command);
                boolean asksWho = command.contains("config");
                return new ProcessRunner.Outcome(asksWho ? 1 : 0, asksWho
                        ? Collections.<String>emptyList()
                        : Collections.singletonList("git version 2.47.0"), false);
            }
        };

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, anonymous);

        List<String> commit = ran.get(ran.size() - 1);
        assertTrue(commit.contains("commit"), commit.toString());
        assertTrue(commit.containsAll(Arrays.asList("-c", "user.name=OntoBoard",
                "user.email=ontoboard@localhost")), commit.toString());
        assertTrue(commit.indexOf("user.name=OntoBoard") < commit.indexOf("commit"),
                "git's own options come before its command: " + commit);
        assertTrue(made.committed());
        assertTrue(made.notes().toString().contains("OntoBoard"), made.notes().toString());
    }

    @Test
    void aCommitThatFailsLeavesTheProjectAndSaysWhy(@TempDir File temp) {
        ProcessRunner.Runner failing = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                if (!"git".equals(command.get(0))) {
                    throw new IOException("Cannot run program");
                }
                return command.contains("commit")
                        ? new ProcessRunner.Outcome(1, Arrays.asList("error: gpg failed to sign "
                                + "the data", "fatal: failed to write commit object"), false)
                        : new ProcessRunner.Outcome(0, Collections.singletonList("ok"), false);
            }
        };

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, failing);

        assertFalse(made.committed());
        assertTrue(made.notRecorded().contains("failed to write commit object"),
                made.notRecorded());
        assertTrue(new File(temp, "eggboil/src/ontology/Makefile").isFile());
    }

    // ------------------------------------------------------------------ adopting

    /** ODK's {@code seed --source}: the ontology somebody has becomes the edit file, as it is. */
    @Test
    void adoptingPutsTheOntologyWhereTheSeedsEditFileWas(@TempDir File temp) throws IOException {
        File mine = new File(temp, "Shop-Ontology.owl");
        byte[] ontology = ("<?xml version=\"1.0\"?>\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/"
                + "02/22-rdf-syntax-ns#\" xmlns:owl=\"http://www.w3.org/2002/07/owl#\">\n"
                + "  <owl:Ontology rdf:about=\"https://example.org/Shop-Ontology.owl\"/>\n"
                + "</rdf:RDF>\n").getBytes(StandardCharsets.UTF_8);
        Files.write(mine.toPath(), ontology);
        OdkProjectConfig config = new OdkProjectConfig("shop_ontology", "Shop", "",
                "https://example.org/Shop-Ontology.owl",
                "https://creativecommons.org/licenses/by/4.0/", new File(temp, "projects"))
                .adopting();

        OdkCreate.Created made = OdkCreate.create(config, mine, nothingInstalled());

        File root = new File(temp, "projects/shop_ontology");
        assertArrayEquals(ontology, Files.readAllBytes(
                new File(root, "src/ontology/shop_ontology-edit.owl").toPath()));
        assertArrayEquals(ontology, Files.readAllBytes(mine.toPath()), "left as it was");
        assertTrue(text(root, "src/ontology/Makefile").contains(
                "URIBASE=                    https://example.org\n"));
        // What ODK names under the project's base is under the ontology's own address.
        assertTrue(text(root, "src/ontology/Makefile").contains(
                "ONTBASE=                    https://example.org/Shop-Ontology\n"),
                text(root, "src/ontology/Makefile").substring(0, 1500));
        // What ODK takes for the project's own terms begins where the ontology is: its base
        // release and its report go by that.
        String own = "--base-iri https://example.org/Shop-Ontology "
                + "--base-iri https://example.org/SHOP_ONTOLOGY_ ";
        String makefile = text(root, "src/ontology/Makefile");
        assertTrue(makefile.contains("remove " + own), "the base release keeps them");
        assertTrue(makefile.contains("--fail-on $(REPORT_FAIL_ON) " + own),
                "the report looks at them");
        // The address is the ontology's own, and ODK's release would name another: said.
        assertTrue(made.notes().toString().contains("https://example.org/shop_ontology.owl"),
                made.notes().toString());
        assertTrue(made.notes().toString().contains("https://example.org/Shop-Ontology.owl"),
                made.notes().toString());
    }

    /**
     * An ontology whose terms begin with another address than its own: ODK's base release
     * would hold none of them and its report would look at none. That is said, with the
     * address most of them begin with and where to put it.
     */
    @Test
    void anAdoptedOntologyWhoseTermsAreElsewhereIsToldWhereToNameThem(@TempDir File temp)
            throws IOException {
        File mine = new File(temp, "shop.ofn");
        Files.write(mine.toPath(), ("Ontology(<https://example.org/shop.owl>\n"
                + "Declaration(Class(<https://w3id.org/things#Pizza>))\n"
                + "Declaration(Class(<https://w3id.org/things#Base>))\n"
                + "Declaration(ObjectProperty(<https://w3id.org/other/has>))\n"
                + "SubClassOf(<https://w3id.org/things#Pizza> <http://www.w3.org/2002/07/owl#Thing>)\n"
                + ")\n").getBytes(StandardCharsets.UTF_8));
        OdkProjectConfig config = new OdkProjectConfig("shop", "Shop", "",
                "https://example.org/shop.owl",
                "https://creativecommons.org/licenses/by/4.0/", new File(temp, "projects"))
                .adopting();

        OdkCreate.Created made = OdkCreate.create(config, mine, nothingInstalled());

        assertEquals(1, made.notes().size(), made.notes().toString());
        String note = made.notes().get(0);
        assertTrue(note.startsWith("None of the ontology's 3 terms has an address that "
                + "begins with https://example.org/shop or https://example.org/SHOP_"), note);
        assertTrue(note.contains("most begin with https://w3id.org/things#"), note);
        assertTrue(note.contains("namespaces: in shop-odk.yaml"), note);
        assertTrue(note.contains("OntoBoard > Project > Update project files..."), note);
    }

    /** One term at the ontology's own address is enough: the rest may be others' terms. */
    @Test
    void anAdoptedOntologyWithTermsOfItsOwnNeedsNoSuchNote(@TempDir File temp)
            throws IOException {
        File mine = new File(temp, "shop.ofn");
        Files.write(mine.toPath(), ("Ontology(<https://example.org/shop.owl>\n"
                + "Declaration(Class(<https://example.org/shop#Pizza>))\n"
                + "Declaration(Class(<https://w3id.org/things#Base>))\n"
                + ")\n").getBytes(StandardCharsets.UTF_8));
        OdkProjectConfig config = new OdkProjectConfig("shop", "Shop", "",
                "https://example.org/shop.owl",
                "https://creativecommons.org/licenses/by/4.0/", new File(temp, "projects"))
                .adopting();

        OdkCreate.Created made = OdkCreate.create(config, mine, nothingInstalled());

        assertEquals(Collections.<String>emptyList(), made.notes());
    }

    @Test
    void anAdoptedOntologyWithOdksOwnAddressNeedsNoWarning(@TempDir File temp)
            throws IOException {
        File mine = new File(temp, "eggboil.owl");
        Files.write(mine.toPath(), "Ontology(<https://example.org/eggboil/eggboil.owl>)\n"
                .getBytes(StandardCharsets.UTF_8));

        OdkCreate.Created made = OdkCreate.create(egg(new File(temp, "projects")).adopting(),
                mine, nothingInstalled());

        assertEquals(Collections.<String>emptyList(), made.notes());
    }

    // ------------------------------------------------------------------ ODK's defaults (acceptance 7)

    @Test
    void theLabelCheckIsAnOdkCheck(@TempDir File temp) throws IOException {
        OdkCreate.create(egg(temp), null, nothingInstalled());
        File root = new File(temp, "eggboil");

        assertTrue(text(root, "src/ontology/Makefile").contains("SPARQL_VALIDATION_CHECKS =  "
                + "owldef-self-reference iri-range label-with-iri multiple-replaced_by "
                + "check_labels \n"));
        assertEquals(OdkExtras.checkLabels(),
                text(root, "src/sparql/check_labels-violation.sparql"));
        // ODK's four are files of the seed; the fifth is the extra.
        for (String check : new String[] {"owldef-self-reference", "iri-range", "label-with-iri",
            "multiple-replaced_by", "check_labels"}) {
            assertTrue(new File(root, "src/sparql/" + check + "-violation.sparql").isFile(),
                    check);
        }
    }

    @Test
    void theSeededCiRunsOdkfullAtThePinnedVersion(@TempDir File temp) throws IOException {
        OdkCreate.create(egg(temp), null, nothingInstalled());

        String qc = text(new File(temp, "eggboil"), ".github/workflows/qc.yml");

        assertTrue(qc.contains("container: obolibrary/odkfull:v1.6.1\n"), qc);
        assertTrue(qc.contains("test IMP=false PAT=false MIR=false"), qc);
    }

    @Test
    void theReportUsesTheSeededProfile(@TempDir File temp) throws IOException {
        OdkCreate.create(egg(temp), null, nothingInstalled());
        File root = new File(temp, "eggboil");

        assertTrue(text(root, "src/ontology/Makefile").contains(
                "REPORT_PROFILE_OPTS =       --profile $(ROBOT_PROFILE)\n"));
        assertArrayEquals(Goldens.seedTree("eggboil").get("src/ontology/profile.txt"),
                Files.readAllBytes(new File(root, "src/ontology/profile.txt").toPath()));
    }

    // ------------------------------------------------------------------ the label check

    private static List<List<String>> unlabelled(String functionalSyntaxBody) throws Exception {
        String text = "Prefix(:=<https://example.org/e/>)\n"
                + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                + "Ontology(<https://example.org/e/e.owl>\n" + functionalSyntaxBody + ")\n";
        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new java.io.ByteArrayInputStream(
                        text.getBytes(StandardCharsets.UTF_8)));
        return SparqlQuery.run(ontology, OdkExtras.checkLabels()).getRows();
    }

    /** A new project passes its own check: ODK's placeholder class has a label. */
    @Test
    void odksSeededEditFilePassesTheLabelCheck() throws Exception {
        OWLOntology seeded = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new java.io.ByteArrayInputStream(
                        Goldens.seedTree("eggboil").get("src/ontology/eggboil-edit.owl")));

        assertEquals(Collections.<List<String>>emptyList(),
                SparqlQuery.run(seeded, OdkExtras.checkLabels()).getRows());
    }

    @Test
    void aClassTheOntologyDescribesNeedsALabel() throws Exception {
        assertEquals(Collections.singletonList(Collections.singletonList(
                "https://example.org/e/Egg")), unlabelled(
                        "Declaration(Class(:Egg))\nDeclaration(Class(:Food))\n"
                        + "AnnotationAssertion(rdfs:label :Food \"food\")\n"
                        + "SubClassOf(:Egg :Food)\n"));
        assertEquals(1, unlabelled("Declaration(Class(:Egg))\n"
                + "AnnotationAssertion(rdfs:label :Egg \"\")\n"
                + "AnnotationAssertion(rdfs:comment :Egg \"an empty label is no label\")\n").size());
    }

    /**
     * What ODK's {@code sparql_test} sees: the edit file without its imports, where an imported
     * parent is a class with no label. It is referred to, not described, and is not reported.
     */
    @Test
    void aClassThatIsOnlyReferredToIsNotReported() throws Exception {
        assertEquals(Collections.<List<String>>emptyList(), unlabelled(
                "Declaration(Class(:Egg))\n"
                + "Declaration(Class(<http://purl.obolibrary.org/obo/BFO_0000040>))\n"
                + "AnnotationAssertion(rdfs:label :Egg \"egg\")\n"
                + "SubClassOf(:Egg <http://purl.obolibrary.org/obo/BFO_0000040>)\n"));
    }

    @Test
    void owlsOwnVocabularyAndDeprecatedClassesAreLeftOut() throws Exception {
        assertEquals(Collections.<List<String>>emptyList(), unlabelled(
                "Declaration(Class(:Old))\n"
                + "AnnotationAssertion(owl:deprecated :Old \"true\"^^<http://www.w3.org/2001/"
                + "XMLSchema#boolean>)\n"
                + "AnnotationAssertion(rdfs:comment :Old \"replaced\")\n"
                + "AnnotationAssertion(rdfs:comment owl:Thing \"everything\")\n"
                + "Declaration(Class(owl:Thing))\n"));
    }

    // ------------------------------------------------------------------ with ODK's container

    @Test
    void withTheImageOdkItselfSeedsTheProject(@TempDir File temp) throws IOException {
        String runtime = MakeRun.containerRuntime(ProcessRunner.real());
        assumeTrue(runtime != null && OdkImage.pinned(OdkImage.probe(runtime,
                ProcessRunner.real())) != null, "ODK v1.6.1's image is not on this machine");

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, ProcessRunner.real(), false);

        File root = new File(temp, "eggboil");
        assertEquals(OdkCreate.Route.CONTAINER, made.route());
        Map<String, byte[]> seed = Goldens.seedTree("eggboil");
        Set<String> expected = new TreeSet<String>(seed.keySet());
        expected.addAll(EXTRAS);
        assertEquals(expected, pathsOf(root, made.written()));
        for (String path : seed.keySet()) {
            assertArrayEquals(seed.get(path), Files.readAllBytes(new File(root, path).toPath()),
                    path);
        }
    }

    /**
     * Docker running and the image there, and the seed fails all the same - a drive not shared
     * with Docker Desktop does that. The templates in OntoBoard write the same files, so the
     * project is made, and the result says which of the two made it.
     */
    @Test
    void aContainerThatCannotSeedIsNotTheEndOfIt(@TempDir File temp) {
        ProcessRunner.Runner broken = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                String line = String.join(" ", command);
                if (line.equals("docker info") || line.contains("image inspect --format {{.Id}}")) {
                    return new ProcessRunner.Outcome(0, Collections.singletonList("ok"), false);
                }
                if (line.startsWith("docker run")) {
                    return new ProcessRunner.Outcome(125, Collections.singletonList("docker: Error"
                            + " response from daemon: mounts denied"), false);
                }
                throw new IOException("Cannot run program");
            }
        };

        OdkCreate.Created made = OdkCreate.create(egg(temp), null, broken, false);

        assertEquals(OdkCreate.Route.BUNDLED, made.route());
        assertTrue(made.notes().toString().contains("mounts denied"), made.notes().toString());
        assertTrue(Goldens.tree(new File(temp, "eggboil")).keySet()
                .containsAll(Goldens.seedTree("eggboil").keySet()));
    }
}
