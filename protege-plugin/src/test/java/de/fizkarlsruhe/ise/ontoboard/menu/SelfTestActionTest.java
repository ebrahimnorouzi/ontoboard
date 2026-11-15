package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkPin;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.protege.editor.core.ui.workspace.WorkspaceTab;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * Closing the tab the self-test opened. Protege's {@code Disposable.dispose} declares Exception,
 * so a tab that cannot be closed has to reach the check's verdict some other way than a checked
 * exception nothing on the event thread catches.
 */
class SelfTestActionTest {

    /** A tab whose dispose throws what it is given. */
    private static WorkspaceTab tabThatThrows(final Exception thrown) {
        return new WorkspaceTab() {
            private static final long serialVersionUID = 1L;

            @Override
            public void initialise() {
            }

            @Override
            public void dispose() throws Exception {
                throw thrown;
            }
        };
    }

    @Test
    void aTabThatCannotBeClosedFailsTheCheckWithTheReason() {
        IOException cause = new IOException("the layout could not be saved");

        IllegalStateException reported = assertThrows(IllegalStateException.class,
                () -> SelfTestAction.close(tabThatThrows(cause)));

        assertEquals("closing it: java.io.IOException: the layout could not be saved",
                reported.getMessage());
        assertSame(cause, reported.getCause());
    }

    /**
     * The result names the OntoBoard it tested, from the jar's own build properties: a study
     * participant is told to read the version here, and the window named none.
     */
    @Test
    void theResultSaysWhichOntoBoardItTested() {
        String which = SelfTestAction.whichOntoBoard();

        assertTrue(which.matches("OntoBoard \\d+\\.\\d+\\.\\d+"), which);
    }

    @Test
    void anUncheckedFailureIsPassedOnAsItIs() {
        IllegalArgumentException thrown = new IllegalArgumentException("no such view");

        assertSame(thrown, assertThrows(IllegalArgumentException.class,
                () -> SelfTestAction.close(tabThatThrows(thrown))));
    }

    /**
     * The quality report's row says its findings are the sample's, and expected: "6 findings, 2
     * of them errors" in a row marked ok read as a failure (1.135.1, Windows study rehearsal).
     */
    @Test
    void theQualityReportRowSaysItsFindingsAreTheSamplesAsExpected() {
        String said = SelfTestAction.reported(SelfTestAction.QUALITY_ROW, "6 findings, 2 of them errors");
        assertEquals("the sample's 6 findings, as expected", said);
        assertTrue(said.length() <= 40, said);
        assertEquals("1 row", SelfTestAction.reported("Check and test > SPARQL...", "1 row"));
        assertEquals("No violations.", SelfTestAction.reported(SelfTestAction.QUALITY_ROW,
                "No violations."));
    }

    /**
     * No findings on the sample are not "as expected" (1.135.1 review): the sample is built to
     * have some, so a report that silently stopped finding anything read "the sample's 0
     * findings, as expected" in a row marked ok.
     */
    @Test
    void noFindingsOnTheSampleAreNotCalledExpected() {
        assertEquals("0 findings, 0 of them errors", SelfTestAction.reported(
                SelfTestAction.QUALITY_ROW, "0 findings, 0 of them errors"));
        assertEquals("the sample's 12 findings, as expected", SelfTestAction.reported(
                SelfTestAction.QUALITY_ROW, "12 findings, 0 of them errors"));
    }

    // ------------------------------------------------------------------ an ODK project

    /** A runner that starts nothing: it notes what it was asked to run, and refuses. */
    private static ProcessRunner.Runner startsNothing(final List<String> asked) {
        return new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                asked.add(String.join(" ", command));
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
        };
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Task O26 (design 6.7): the self-test's build row makes the project OntoBoard makes -
     * ODK's seed from ODK's templates inside OntoBoard, with OntoBoard's extras - and runs
     * ODK's own test on it with OntoBoard's engine: no Docker, no program started. Its sentence
     * says what ran, names the checks that passed, says no program was started, and keeps the
     * PATH part (looked at, never run).
     */
    @Test
    void theSelfTestMakesAnOdkProjectAndRunsItsTest(@TempDir File temp) throws Exception {
        List<String> asked = new ArrayList<String>();

        String row = SelfTestAction.buildsWithNothingInstalled(temp, startsNothing(asked));

        assertTrue(row.startsWith("ok: "), row);
        assertTrue(row.contains("ODK's test ran"), "says what ran: " + row);
        for (String check : Arrays.asList("owldef-self-reference", "iri-range",
                "label-with-iri", "multiple-replaced_by", "check_labels")) {
            assertTrue(row.contains(check), "names the check that passed, " + check + ": " + row);
        }
        assertTrue(row.contains("without starting any program"), row);
        assertTrue(row.contains("on this PATH: robot "), "the PATH part stays: " + row);

        // ODK's seed, made by OntoBoard: the configuration, the edit file, ODK's Makefile
        // of the pinned version, and OntoBoard's label check beside ODK's checks.
        File root = new File(temp, "selftest");
        File ontology = new File(root, "src/ontology");
        assertTrue(new File(ontology, "selftest-odk.yaml").isFile());
        assertTrue(new File(ontology, "selftest-edit.owl").isFile());
        assertEquals(OdkPin.VERSION, OdkImage.versionOfProject(ontology),
                "ODK's own Makefile, of the pinned version");
        assertTrue(new File(root, "src/sparql/check_labels-violation.sparql").isFile());
        assertTrue(new File(root, "src/sparql/iri-range-violation.sparql").isFile());
        // ODK's test was run on it: what its recipes write is there.
        assertTrue(new File(ontology, "reports/validate_profile_owl2dl_selftest.owl.txt")
                .isFile(), "ODK's test wrote its reports");
        assertTrue(new File(ontology, "reports/selftest-edit.owl-obo-report.tsv").isFile());

        // Nothing was started: what was asked was only whether a container program answers,
        // and that was refused - no git, no pull, no make, no robot.
        for (String command : asked) {
            assertTrue(command.equals("docker info") || command.equals("podman info"),
                    "only asked whether a container program answers: " + asked);
        }
    }

    /**
     * The scratch project the other rows run against is the same kind of project: ODK's seed,
     * its edit file in ODK's functional syntax, and the sample's terms saved into it so that the
     * project-aware items find them on disk.
     */
    @Test
    void theScratchProjectIsOdksSeedWithTheSampleInItsEditFile(@TempDir File temp)
            throws Exception {
        List<String> asked = new ArrayList<String>();

        File root = SelfTestAction.odkProject(temp, startsNothing(asked));
        OWLOntology sample = SelfTestAction.withTheSample(root);

        File edit = new File(root, "src/ontology/selftest-edit.owl");
        assertEquals(edit.getAbsoluteFile(), new File(sample.getOWLOntologyManager()
                .getOntologyDocumentIRI(sample).toURI()).getAbsoluteFile(),
                "the sample is the edit file, so the items find the project around it");
        assertTrue(new File(root, "src/ontology/selftest-odk.yaml").isFile());
        assertTrue(new File(root, "src/ontology/catalog-v001.xml").isFile());
        String saved = read(edit);
        assertTrue(saved.startsWith("Prefix("), "still ODK's functional syntax: "
                + saved.substring(0, Math.min(80, saved.length())));
        OWLOntology again = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(edit);
        assertTrue(again.containsClassInSignature(
                IRI.create("http://www.ontoboard.org/self-test#Person")), "the sample is saved");
        assertTrue(asked.size() <= 2, "nothing but whether a container answers: " + asked);
    }

    /** The old scaffold and its in-process targets go (task O27): the self-test uses neither. */
    @Test
    void theSelfTestUsesNeitherTheOldScaffoldNorItsTargets() throws Exception {
        String source = read(new File(
                "src/main/java/de/fizkarlsruhe/ise/ontoboard/menu/SelfTestAction.java"));

        assertTrue(!source.contains("OdkScaffold") && !source.contains("InProcessTargets"),
                "SelfTestAction names OdkScaffold or InProcessTargets");
    }
}
