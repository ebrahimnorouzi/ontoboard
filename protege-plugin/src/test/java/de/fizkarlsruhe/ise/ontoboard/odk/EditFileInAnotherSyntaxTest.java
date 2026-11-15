package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * An edit file that is not in functional syntax.
 *
 * <p>The plan's third "review focus", which the review of 1.153.0 found pinned by no test:
 * Protege saves an ontology in the syntax somebody chose, under the name it has, so an edit
 * file called {@code <id>-edit.owl} may be RDF/XML or Turtle. ODK's Makefile converts it before
 * anything else reads it; OntoBoard's own engine runs that Makefile and has to get as far.
 */
class EditFileInAnotherSyntaxTest {

    private static void saveAs(OWLOntology ontology, OWLDocumentFormat syntax, File edit)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ontology.getOWLOntologyManager().saveOntology(ontology, syntax, bytes);
        Files.write(edit.toPath(), bytes.toByteArray());
    }

    /**
     * Waits until nothing holds the file. Seen with this test on Windows: after the run the
     * edit file in Turtle was still open - by the OWL API's reading of it, inside ROBOT -
     * until the JVM collected garbage, and the folder could not be removed. In RDF/XML and
     * in functional syntax it is not. Recorded as a limitation; here it is waited out, so
     * that the test says what it is about.
     */
    private static void released(File edit) throws Exception {
        File aside = new File(edit.getPath() + ".aside");
        for (int tries = 0; tries < 100; tries++) {
            if (edit.renameTo(aside)) {
                assertTrue(aside.renameTo(edit));
                return;
            }
            System.gc();
            Thread.sleep(100);
        }
    }

    private static String start(File edit) throws Exception {
        String text = new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8);
        return text.substring(0, Math.min(200, text.length()));
    }

    @Test
    void anEditFileInRdfXmlPassesOdksTestWithItsQuestion(@TempDir File where) throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, true);
        saveAs(ontology, new RDFXMLDocumentFormat(), edit);
        assertTrue(start(edit).contains("<rdf:RDF") || start(edit).contains("<?xml"), start(edit));

        OdkEngine.Outcome ran = SeededProject.run(edit, "test_fast");

        released(edit);
        assertTrue(ran.isOk(), ran.getFailure() + "\n" + String.join("\n", ran.getTranscript()));
        assertTrue(OdkTargets.passed(ran.getTranscript()).contains(
                de.fizkarlsruhe.ise.ontoboard.robot.QuestionTests.fileNameFor(
                        SeededProject.QUESTION)),
                "the project's question was asked, and passed: "
                        + OdkTargets.passed(ran.getTranscript()));
    }

    @Test
    void anEditFileInTurtleFailsOdksTestOnTheQuestionTheModelDoesNotAnswer(@TempDir File where)
            throws Exception {
        File edit = SeededProject.in(where);
        OWLOntology ontology = SeededProject.t6(edit, false);
        saveAs(ontology, new TurtleDocumentFormat(), edit);
        assertTrue(start(edit).contains("@prefix"), start(edit));

        OdkEngine.Outcome ran = SeededProject.run(edit, "test_fast");

        released(edit);
        assertFalse(ran.isOk(), String.join("\n", ran.getTranscript()));
        String why = OdkTargets.whyItStopped(ran.getTranscript(), edit, ontology);
        assertTrue(why != null && why.contains("egg boiling dataset"),
                "it stops at the question, not at the syntax: " + why + "\n"
                        + String.join("\n", ran.getTranscript()));
    }
}
