package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Update project files with nothing standing in, both ways on the same project: ODK's own
 * update in ODK's container, and OntoBoard's without one. The two projects that come of it are
 * held against each other - every file byte for byte, the edit file, which ROBOT writes, by
 * what it says.
 *
 * <p>{@code OdkUpdateTest} holds OntoBoard's update against a recording of ODK's. This asks
 * ODK itself, on a machine that has ODK v1.6.1's image; without it the test is skipped and says
 * so. How long each took is written to {@code target/side-by-side/update.txt}.
 */
class OdkUpdateLiveTest {

    private static final String EDIT = "src/ontology/eggboil-edit.owl";

    private static String runtime;
    private static String image;
    private static OdkContainer.Host host;

    @BeforeAll
    static void lookForTheImage() {
        runtime = MakeRun.containerRuntime(ProcessRunner.real());
        if (runtime != null) {
            image = OdkImage.pinned(OdkImage.probe(runtime, ProcessRunner.real()));
            host = OdkContainer.Host.detect(runtime, ProcessRunner.real());
        }
    }

    @Test
    void odksOwnUpdateAndOntoBoardsLeaveTheSameProject(@TempDir File temp) throws Exception {
        assumeTrue(image != null, "ODK v1.6.1's image is not on this machine");
        File theirs = new File(temp, "theirs");
        File ours = new File(temp, "ours");
        Map<String, byte[]> before = UpdateCases.before("egg-changed");
        UpdateCases.write(before, theirs);
        UpdateCases.write(before, ours);

        long started = System.currentTimeMillis();
        OdkUpdate.Ran inContainer = OdkUpdate.inContainer(runtime, image, host, theirs,
                OdkUpdate.plan(theirs), ProcessRunner.real());
        long odkTook = System.currentTimeMillis() - started;
        started = System.currentTimeMillis();
        List<File> written = OdkUpdate.apply(ours, OdkUpdate.plan(ours));
        long ontoBoardTook = System.currentTimeMillis() - started;

        assertTrue(inContainer.isOk(), inContainer.getFailure() + "\n"
                + String.join("\n", inContainer.getTranscript()));
        assertFalse(written.isEmpty());
        Map<String, byte[]> odks = Goldens.tree(theirs);
        Map<String, byte[]> ontoBoards = Goldens.tree(ours);
        assertEquals(new TreeSet<String>(odks.keySet()), new TreeSet<String>(ontoBoards.keySet()),
                "the files of the two projects");
        for (Map.Entry<String, byte[]> file : odks.entrySet()) {
            if (file.getKey().equals(EDIT)) {
                assertSameOntology(temp, file.getValue(), ontoBoards.get(EDIT));
            } else {
                assertArrayEquals(file.getValue(), ontoBoards.get(file.getKey()), file.getKey());
            }
        }
        assertEquals(relative(theirs, inContainer.getWritten()), relative(ours, written),
                "and both say they wrote the same files");

        File times = new File("target/side-by-side/update.txt");
        Files.createDirectories(times.getParentFile().toPath());
        Files.write(times.toPath(), ("Update project files, the recorded project with work in "
                + "it (egg-changed), one run each\nODK's own update in its container (" + image
                + "): " + odkTook + " ms\nOntoBoard's own: " + ontoBoardTook + " ms\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> relative(File root, List<File> files) {
        Set<String> paths = new TreeSet<String>();
        for (File file : files) {
            paths.add(root.toPath().relativize(file.toPath()).toString().replace('\\', '/'));
        }
        return paths;
    }

    private static void assertSameOntology(File temp, byte[] expected, byte[] actual)
            throws Exception {
        OWLOntology theirs = load(temp, expected);
        OWLOntology ours = load(temp, actual);
        assertEquals(theirs.getOntologyID(), ours.getOntologyID());
        assertEquals(sorted(theirs.getImportsDeclarations()),
                sorted(ours.getImportsDeclarations()), "the imports of the edit file");
        assertEquals(sorted(theirs.getAnnotations()), sorted(ours.getAnnotations()));
        assertEquals(sorted(theirs.getAxioms(Imports.EXCLUDED)),
                sorted(ours.getAxioms(Imports.EXCLUDED)));
    }

    /** Reads an ontology by itself: its imports are looked for in a file that is not there. */
    private static OWLOntology load(File temp, byte[] bytes) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        final IRI nowhere = IRI.create(new File(temp, "not-there.owl"));
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
        Collections.sort(sorted);
        return String.join("\n", sorted);
    }
}
