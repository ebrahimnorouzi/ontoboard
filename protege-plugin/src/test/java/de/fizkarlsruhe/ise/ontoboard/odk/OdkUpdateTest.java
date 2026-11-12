package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * Update project files without ODK's container, held against what ODK's own update left of the
 * recorded projects ({@code update/<case>} among the goldens): every file but the edit file byte
 * for byte, the edit file - which ROBOT writes - by what it says.
 */
class OdkUpdateTest {

    private static final String EDIT = "src/ontology/eggboil-edit.owl";

    @TempDir
    File root;

    @ParameterizedTest
    @ValueSource(strings = {"egg-unchanged", "egg-changed"})
    void anUpdatedProjectIsWhatOdksUpdateLeaves(String name) throws Exception {
        UpdateCases.write(UpdateCases.before(name), root);

        OdkUpdate.apply(root, OdkUpdate.plan(root));

        assertSameProject(UpdateCases.after(name), Goldens.tree(root));
    }

    /** The plan is what a person is shown first: it names what will change and touches nothing. */
    @Test
    void thePlanNamesWhatWillChangeAndWritesNothing() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);

        OdkUpdate.Plan plan = OdkUpdate.plan(root);

        Set<String> changing = new TreeSet<String>();
        for (OdkUpdate.Change change : plan.changes()) {
            if (change.isChanged()) {
                changing.add(change.path());
            }
        }
        Set<String> recorded = new TreeSet<String>(Goldens.tree(new File(Goldens.root(),
                "update/egg-changed/after/files")).keySet());
        recorded.remove(EDIT);
        assertEquals(recorded, changing, "every file ODK's update made or changed, but the edit "
                + "file, which the plan speaks of apart");
        assertTrue(plan.editFileWillBeRewritten());
        assertEquals("v1.6.1", plan.fromVersion());
        assertEquals("v1.6.1", plan.toVersion());
        assertSameTree(before, Goldens.tree(root));
    }

    /** What the update adds and what it replaces can be told apart: one has nothing before. */
    @Test
    void aFileTheProjectDidNotHaveIsNew() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);

        Map<String, OdkUpdate.Change> changes = new TreeMap<String, OdkUpdate.Change>();
        for (OdkUpdate.Change change : OdkUpdate.plan(root).changes()) {
            changes.put(change.path(), change);
        }

        assertTrue(changes.get("src/ontology/imports/ro_terms.txt").isNew());
        assertFalse(changes.get("src/ontology/Makefile").isNew());
        assertTrue(changes.get("src/ontology/Makefile").isChanged());
        assertFalse(changes.containsKey("README.md"), "a file a person writes is not ODK's to "
                + "write again");
    }

    @Test
    void aSecondUpdateChangesNothing() throws Exception {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        OdkUpdate.apply(root, OdkUpdate.plan(root));
        Map<String, byte[]> once = Goldens.tree(root);

        OdkUpdate.Plan again = OdkUpdate.plan(root);
        for (OdkUpdate.Change change : again.changes()) {
            assertFalse(change.isChanged(), change.path());
        }
        assertEquals(new ArrayList<File>(), OdkUpdate.apply(root, again));
        assertSameTree(once, Goldens.tree(root));
    }

    /**
     * ODK's own update stops at its last step when an import of the edit file cannot be loaded,
     * with every other file written already. Here that is found out first.
     */
    @Test
    void anImportThatCannotBeLoadedStopsItBeforeAnythingIsWritten() {
        Map<String, byte[]> before = UpdateCases.before("egg-unloadable");
        UpdateCases.write(before, root);
        OdkUpdate.Plan plan = OdkUpdate.plan(root);

        OdkUpdate.Refused refused = assertThrows(OdkUpdate.Refused.class,
                () -> OdkUpdate.apply(root, plan));

        assertTrue(refused.getMessage().contains("whole.owl"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Nothing was written"), refused.getMessage());
        assertSameTree(before, Goldens.tree(root));
    }

    /** A project of a newer ODK would be moved back to this one's files. */
    @Test
    void aNewerProjectIsNotMovedBack() {
        Map<String, byte[]> project = UpdateCases.before("egg-unchanged");
        String makefile = UpdateCases.text(project, "src/ontology/Makefile");
        String line = "ODK_VERSION_MAKEFILE =      v1.6.1";
        assertTrue(makefile.contains(line), "the fixture's line");
        project.put("src/ontology/Makefile", makefile.replace(line, line.replace("1.6.1",
                "1.7.0")).getBytes(StandardCharsets.UTF_8));
        UpdateCases.write(project, root);

        OdkUpdate.Refused refused = assertThrows(OdkUpdate.Refused.class,
                () -> OdkUpdate.plan(root));

        assertTrue(refused.getMessage().contains("v1.7.0"), refused.getMessage());
        assertTrue(refused.getMessage().contains("v1.6.1"), refused.getMessage());
    }

    @Test
    void withoutOneConfigurationItSaysWhich() {
        Map<String, byte[]> project = UpdateCases.before("egg-unchanged");
        byte[] config = project.remove("src/ontology/eggboil-odk.yaml");
        UpdateCases.write(project, root);
        OdkUpdate.Refused none = assertThrows(OdkUpdate.Refused.class,
                () -> OdkUpdate.plan(root));
        assertTrue(none.getMessage().contains("-odk.yaml"), none.getMessage());

        project.put("src/ontology/eggboil-odk.yaml", config);
        project.put("src/ontology/other-odk.yaml", config);
        UpdateCases.write(project, root);
        OdkUpdate.Refused two = assertThrows(OdkUpdate.Refused.class,
                () -> OdkUpdate.plan(root));
        assertTrue(two.getMessage().contains("eggboil-odk.yaml")
                && two.getMessage().contains("other-odk.yaml"), two.getMessage());
    }

    /**
     * A checkout on Windows has the files with Windows line endings. That alone is no change:
     * the file is left as the checkout has it.
     */
    @Test
    void aFileThatDiffersOnlyInItsLineEndingsIsLeftAlone() throws Exception {
        Map<String, byte[]> project = UpdateCases.before("egg-unchanged");
        for (String path : new String[] {"src/ontology/Makefile", "src/ontology/run.sh",
            "src/sparql/terms.sparql"}) {
            project.put(path, UpdateCases.text(project, path).replace("\n", "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        UpdateCases.write(project, root);

        OdkUpdate.Plan plan = OdkUpdate.plan(root);
        for (OdkUpdate.Change change : plan.changes()) {
            if (change.path().equals("src/ontology/Makefile")) {
                assertFalse(change.isChanged(), "the Makefile, but for its line endings");
            }
        }
        OdkUpdate.apply(root, plan);

        Map<String, byte[]> now = Goldens.tree(root);
        for (String path : new String[] {"src/ontology/Makefile", "src/ontology/run.sh",
            "src/sparql/terms.sparql"}) {
            assertArrayEquals(project.get(path), now.get(path), path);
        }
    }

    /**
     * A project that looks after its import declarations itself: ODK's update then leaves the
     * catalog and the edit file alone and says so.
     */
    @Test
    void aProjectThatManagesItsImportsItselfKeepsItsCatalogAndItsEditFile() throws Exception {
        Map<String, byte[]> project = UpdateCases.before("egg-changed");
        project.put("src/ontology/eggboil-odk.yaml", (UpdateCases.text(project,
                "src/ontology/eggboil-odk.yaml") + "\nmanage_import_declarations: false\n")
                .getBytes(StandardCharsets.UTF_8));
        UpdateCases.write(project, root);

        OdkUpdate.Plan plan = OdkUpdate.plan(root);
        OdkUpdate.apply(root, plan);

        assertFalse(plan.editFileWillBeRewritten());
        Map<String, byte[]> now = Goldens.tree(root);
        assertArrayEquals(project.get(EDIT), now.get(EDIT));
        assertArrayEquals(project.get("src/ontology/catalog-v001.xml"),
                now.get("src/ontology/catalog-v001.xml"));
        assertTrue(String.join("\n", plan.warnings()).contains("XML catalog"),
                plan.warnings().toString());
    }

    // ------------------------------------------------------------------ before ODK's own update

    /**
     * Before ODK's own update runs in its container, what would stop it half way is looked for
     * here. Looking writes nothing, and leaves nothing behind.
     */
    @Test
    void theCheckBeforeAnUpdateLeavesTheProjectAsItWas() {
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, root);

        boolean editFileWouldChange = OdkUpdate.check(root, OdkUpdate.plan(root));

        assertTrue(editFileWouldChange, "it imports something the configuration does not "
                + "declare, which the update takes out");
        assertSameTree(before, Goldens.tree(root));
    }

    /** Once updated, the check says that the edit file would stay as it is. */
    @Test
    void afterAnUpdateTheCheckSaysTheEditFileStaysAsItIs() {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        OdkUpdate.apply(root, OdkUpdate.plan(root));

        assertFalse(OdkUpdate.check(root, OdkUpdate.plan(root)));
    }

    @Test
    void theCheckFindsAnImportThatCannotBeLoaded() {
        Map<String, byte[]> before = UpdateCases.before("egg-unloadable");
        UpdateCases.write(before, root);
        OdkUpdate.Plan plan = OdkUpdate.plan(root);

        OdkUpdate.Refused refused = assertThrows(OdkUpdate.Refused.class,
                () -> OdkUpdate.check(root, plan));

        assertTrue(refused.getMessage().contains("whole.owl"), refused.getMessage());
        assertSameTree(before, Goldens.tree(root));
    }

    /** In a container it is ODK's own program, in the folder ODK runs it in. */
    @Test
    void inAContainerItIsOdksOwnUpdateInTheFolderOdkRunsItIn() {
        UpdateCases.write(UpdateCases.before("egg-unchanged"), root);

        List<String> command = OdkUpdate.containerCommand("docker", "an-image:v1",
                OdkContainer.Host.unknown(), root);

        assertEquals("docker", command.get(0));
        assertEquals(java.util.Arrays.asList("odk.py", "update"),
                command.subList(command.size() - 2, command.size()));
        assertEquals("an-image:v1", command.get(command.size() - 3));
        assertEquals("/work/src/ontology", command.get(command.indexOf("-w") + 1));
    }

    /** A container that does what it is told and says what it is told. */
    private static de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Runner container(
            final List<List<String>> ran, final int status, final Runnable does,
            final String... says) {
        return new de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Runner() {
            @Override
            public de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Outcome run(
                    File workingDirectory, List<String> command, long timeoutMinutes,
                    de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Sink sink) {
                ran.add(command);
                does.run();
                return new de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner.Outcome(status,
                        java.util.Arrays.asList(says), false);
            }
        };
    }

    /**
     * ODK's own update stops at its last step when an import cannot be loaded, with every
     * other file written. So the container is not even started for such a project.
     */
    @Test
    void theContainerIsNotStartedWhenAnImportCannotBeLoaded() {
        UpdateCases.write(UpdateCases.before("egg-unloadable"), root);
        List<List<String>> ran = new ArrayList<List<String>>();
        OdkUpdate.Plan plan = OdkUpdate.plan(root);

        assertThrows(OdkUpdate.Refused.class, () -> OdkUpdate.inContainer("docker",
                "an-image:v1", OdkContainer.Host.unknown(), root, plan,
                container(ran, 0, () -> { })));

        assertEquals(new ArrayList<List<String>>(), ran);
    }

    /** When ODK's own update stops all the same, what it had written by then is named. */
    @Test
    void whenOdksOwnUpdateStopsWhatItWroteIsNamed() throws Exception {
        UpdateCases.write(UpdateCases.before("egg-changed"), root);
        List<List<String>> ran = new ArrayList<List<String>>();
        final File makefile = new File(root, "src/ontology/Makefile");

        OdkUpdate.Ran stopped = OdkUpdate.inContainer("docker", "an-image:v1",
                OdkContainer.Host.unknown(), root, OdkUpdate.plan(root), container(ran, 1,
                        () -> {
                            try {
                                java.nio.file.Files.write(makefile.toPath(),
                                        "# half an update\n".getBytes(StandardCharsets.UTF_8));
                            } catch (java.io.IOException cannotWrite) {
                                throw new java.io.UncheckedIOException(cannotWrite);
                            }
                        }, "INFO:root:Applying templates", "Error: something gave way", ""));

        assertFalse(stopped.isOk());
        assertTrue(stopped.getFailure().contains("Error: something gave way"),
                stopped.getFailure());
        assertTrue(stopped.getFailure().contains("1 file was written before it stopped"),
                stopped.getFailure());
        assertEquals(java.util.Collections.singletonList(makefile), stopped.getWritten());
        assertEquals(1, ran.size());
        assertTrue(stopped.getTranscript().get(0).startsWith("$ docker "),
                stopped.getTranscript().get(0));
    }

    // ------------------------------------------------------------------ comparing

    private void assertSameProject(Map<String, byte[]> expected, Map<String, byte[]> actual)
            throws Exception {
        assertEquals(new TreeSet<String>(expected.keySet()), new TreeSet<String>(actual.keySet()),
                "the files of the project");
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            if (file.getKey().equals(EDIT)) {
                assertSameOntology(file.getValue(), actual.get(EDIT));
            } else {
                assertEquals(new String(file.getValue(), StandardCharsets.UTF_8),
                        new String(actual.get(file.getKey()), StandardCharsets.UTF_8),
                        file.getKey());
                assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
            }
        }
    }

    private static void assertSameTree(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(new TreeSet<String>(expected.keySet()), new TreeSet<String>(actual.keySet()),
                "the files of the project");
        for (Map.Entry<String, byte[]> file : expected.entrySet()) {
            assertArrayEquals(file.getValue(), actual.get(file.getKey()), file.getKey());
        }
    }

    /** The same ontology: its names, its imports, what it says of itself, its axioms. */
    private void assertSameOntology(byte[] expected, byte[] actual) throws Exception {
        OWLOntology theirs = load(expected);
        OWLOntology ours = load(actual);
        assertEquals(theirs.getOntologyID(), ours.getOntologyID());
        assertEquals(sorted(theirs.getImportsDeclarations()),
                sorted(ours.getImportsDeclarations()), "the imports of the edit file");
        assertEquals(sorted(theirs.getAnnotations()), sorted(ours.getAnnotations()));
        assertEquals(sorted(theirs.getAxioms(Imports.EXCLUDED)),
                sorted(ours.getAxioms(Imports.EXCLUDED)));
    }

    /** Reads an ontology by itself: its imports are looked for in a file that is not there. */
    private OWLOntology load(byte[] bytes) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        final IRI nowhere = IRI.create(new File(root, "not-there.owl"));
        manager.getIRIMappers().add(new OWLOntologyIRIMapper() {
            private static final long serialVersionUID = 1L;

            @Override
            public IRI getDocumentIRI(IRI ontologyIri) {
                return nowhere;
            }
        });
        return manager.loadOntologyFromOntologyDocument(
                new StreamDocumentSource(new ByteArrayInputStream(bytes)),
                new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(
                        MissingImportHandlingStrategy.SILENT));
    }

    private static String sorted(Set<?> things) {
        List<String> sorted = new ArrayList<String>();
        for (Object thing : things) {
            sorted.add(thing.toString());
        }
        java.util.Collections.sort(sorted);
        return String.join("\n", sorted);
    }
}
