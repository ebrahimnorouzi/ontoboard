package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * ODK's update writes the edit file again, read and written by ROBOT. For most ontologies
 * that gives the bytes it had. Not for all: ODK's own OWL API (4.5.29) writes the atoms of a
 * rule's body in the other order each time it has read them, so an ontology with one SWRL
 * rule gets a "changed" edit file from every update, one line going back and forth - seen
 * on a real project. A file that says what it said, in the syntax it was in, is not a
 * change: it is left as it is, so that "already up to date" is reached, nothing is read
 * again in Protege, and git has nothing to show.
 */
class RewrittenAsItIsTest {

    private static final String BODY_ONE = "ObjectPropertyAtom(<https://example.org/p> "
            + "Variable(<urn:swrl:var#e>) Variable(<urn:swrl:var#d>))";
    private static final String BODY_OTHER = "ClassAtom(<https://example.org/A> "
            + "Variable(<urn:swrl:var#d>))";

    private static String withARule(String first, String second) {
        return "Prefix(:=<https://example.org/r.owl#>)\n"
                + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
                + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n\n"
                + "Ontology(<https://example.org/r.owl>\n\n"
                + "Declaration(Class(<https://example.org/A>))\n"
                + "Declaration(ObjectProperty(<https://example.org/p>))\n"
                + "Declaration(ObjectProperty(<https://example.org/q>))\n"
                + "DLSafeRule(Body(" + first + " " + second + ")"
                + "Head(ObjectPropertyAtom(<https://example.org/q> Variable(<urn:swrl:var#e>) "
                + "Variable(<urn:swrl:var#d>))))\n)\n";
    }

    /**
     * Read and written again, as ROBOT's convert does it: in the format it was read in, which
     * has the file's prefixes.
     */
    private static String rewritten(String text) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology read = manager.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
        assertEquals(FunctionalSyntaxDocumentFormat.class,
                manager.getOntologyFormat(read).getClass());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manager.saveOntology(read, manager.getOntologyFormat(read), bytes);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static File file(File folder, String name, String text) throws IOException {
        File file = new File(folder, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    // ------------------------------------------------------------------ what the finding rests on

    /**
     * On ODK's own OWL API a rule's body comes out in the other order from each rewrite, and
     * in the first again from the next. (The older one, which Protege 5.5.0 has, keeps the
     * order of this small file. It did not keep a real project's: of its four rules one
     * turned with every rewrite by ROBOT, four times running, on the one OWL API as on the
     * other.)
     */
    @Test
    void odksOwlApiTurnsARulesBodyRoundWithEachRewrite() throws Exception {
        String once = rewritten(withARule(BODY_ONE, BODY_OTHER));
        String twice = rewritten(once);
        String thrice = rewritten(twice);

        assertEquals(once, thrice, "after two rewrites it is what it was");
        if (RunComparisons.onOdksOwlApi()) {
            assertFalse(once.equals(twice), "ODK's OWL API writes the other order: when this "
                    + "fails, it no longer does, and the reason for saysTheSame is gone there");
        }
    }

    /**
     * ROBOT's own rewrite, the step ODK's update ends with: whatever order it gives a rule's
     * body, what it wrote says what it read.
     */
    @Test
    void whatRobotWritesForARuleSaysWhatItRead(@TempDir File folder) throws Exception {
        File read = file(folder, "read.ofn", rewritten(withARule(BODY_ONE, BODY_OTHER)));
        File written = new File(folder, "written.ofn");
        File again = new File(folder, "again.ofn");

        for (File[] step : new File[][] {{read, written}, {written, again}}) {
            OdkRobot.Result result = OdkRobot.run(Arrays.asList("convert", "-i",
                    step[0].getAbsolutePath(), "-f", "ofn", "-o", step[1].getAbsolutePath()));
            assertTrue(result.ok(), result.failure());
        }

        assertTrue(OdkUpdate.saysTheSame(read, written));
        assertTrue(OdkUpdate.saysTheSame(written, again));
    }

    // ------------------------------------------------------------------ the same, or not

    @Test
    void aRuleWithItsBodyTheOtherWayRoundSaysTheSame(@TempDir File folder) throws Exception {
        String one = rewritten(withARule(BODY_ONE, BODY_OTHER));
        String other = one.replace(BODY_ONE + " " + BODY_OTHER, BODY_OTHER + " " + BODY_ONE)
                .replace(BODY_OTHER + " " + BODY_ONE + ")Head", "@@")
                .replace("@@", BODY_OTHER + " " + BODY_ONE + ")Head");
        if (other.equals(one)) {
            // It was written the other way round to begin with: turn that one.
            other = one.replace(BODY_OTHER + " " + BODY_ONE, BODY_ONE + " " + BODY_OTHER);
        }
        assertFalse(other.equals(one), "the two texts differ: " + one);

        assertTrue(OdkUpdate.saysTheSame(file(folder, "one.ofn", one),
                file(folder, "other.ofn", other)));
    }

    /** Lines in another order are the same ontology in the same syntax. */
    @Test
    void theSameLinesInAnotherOrderSayTheSame(@TempDir File folder) throws Exception {
        String one = rewritten(withARule(BODY_ONE, BODY_OTHER));
        String p = "Declaration(ObjectProperty(<https://example.org/p>))\n";
        String q = "Declaration(ObjectProperty(<https://example.org/q>))\n";
        assertTrue(one.contains(p + q), one);

        assertTrue(OdkUpdate.saysTheSame(file(folder, "one.ofn", one),
                file(folder, "other.ofn", one.replace(p + q, q + p))));
    }

    @Test
    void anotherAxiomImportOntologyPrefixOrSyntaxIsNotTheSame(@TempDir File folder)
            throws Exception {
        String once = rewritten(withARule(BODY_ONE, BODY_OTHER));
        File one = file(folder, "one.ofn", once);

        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "a.ofn", once.replace(
                "Declaration(Class(<https://example.org/A>))",
                "Declaration(Class(<https://example.org/A>))\n"
                        + "Declaration(Class(<https://example.org/B>))"))), "one axiom more");
        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "b.ofn", once.replace(
                "Ontology(<https://example.org/r.owl>",
                "Ontology(<https://example.org/r.owl>\nImport(<https://example.org/i.owl>)"))),
                "one import more");
        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "c.ofn", once.replace(
                "Ontology(<https://example.org/r.owl>",
                "Ontology(<https://example.org/other.owl>"))), "another ontology");
        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "d.ofn", once.replace(
                "Ontology(<https://example.org/r.owl>", "Ontology(<https://example.org/r.owl>\n"
                        + "Annotation(rdfs:comment \"said of the ontology\")"))),
                "one more thing said of the ontology itself");
        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "e.ofn", once.replace(
                "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)",
                "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n"
                        + "Prefix(mine:=<https://example.org/mine/>)"))),
                "a prefix more: the file reads otherwise");

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology read = manager.loadOntologyFromOntologyDocument(one);
        ByteArrayOutputStream rdf = new ByteArrayOutputStream();
        manager.saveOntology(read, new org.semanticweb.owlapi.formats.RDFXMLDocumentFormat(), rdf);
        File inRdf = new File(folder, "f.owl");
        Files.write(inRdf.toPath(), rdf.toByteArray());
        assertFalse(OdkUpdate.saysTheSame(one, inRdf), "the same ontology in another syntax");

        assertFalse(OdkUpdate.saysTheSame(one, file(folder, "g.ofn", "not an ontology")),
                "what cannot be read is not the same");
        assertFalse(OdkUpdate.saysTheSame(one, new File(folder, "not-there.ofn")));
        assertEquals(once, new String(Files.readAllBytes(one.toPath()), StandardCharsets.UTF_8),
                "and nothing is written to find out");
    }

    // ------------------------------------------------------------------ in an update

    private static final String EDIT = "src/ontology/eggboil-edit.owl";

    /** The recorded project that ODK's update has nothing to do for, its edit file turned. */
    private static byte[] upToDateWithItsLinesTurned(File root) throws IOException {
        UpdateCases.write(UpdateCases.after("egg-unchanged"), root);
        File edit = new File(root, EDIT);
        String text = new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8);
        List<String> declarations = new ArrayList<String>();
        for (String line : text.split("\n")) {
            if (line.startsWith("Declaration(")) {
                declarations.add(line);
            }
        }
        assertTrue(declarations.size() >= 2, "two declarations to turn round: " + text);
        String first = declarations.get(0);
        String second = declarations.get(1);
        String turned = text.replace(first + "\n", "@@\n").replace(second + "\n", first + "\n")
                .replace("@@\n", second + "\n");
        assertFalse(turned.equals(text));
        Files.write(edit.toPath(), turned.getBytes(StandardCharsets.UTF_8));
        return turned.getBytes(StandardCharsets.UTF_8);
    }

    /** OntoBoard's own update: the rewrite gives other bytes that say the same - left. */
    @Test
    void anUpdateLeavesAnEditFileThatSaysTheSameAsItIs(@TempDir File root) throws IOException {
        byte[] turned = upToDateWithItsLinesTurned(root);

        OdkUpdate.Plan plan = OdkUpdate.plan(root);
        assertFalse(OdkUpdate.check(root, plan), "the look says the edit file stays as it is");
        List<File> written = OdkUpdate.apply(root, plan);

        assertEquals(new ArrayList<File>(), written, "nothing to write: the project was up to "
                + "date but for the order of two lines");
        assertArrayEquals(turned, Files.readAllBytes(new File(root, EDIT).toPath()));
    }

    /**
     * ODK's own update, in its container, writes the edit file whatever it holds. Where what
     * it wrote says the same, the file is put back as it was, and is not among what the
     * update wrote.
     */
    @Test
    void afterOdksOwnUpdateAnEditFileThatSaysTheSameIsPutBackAsItWas(@TempDir final File root)
            throws Exception {
        final byte[] turned = upToDateWithItsLinesTurned(root);
        final File edit = new File(root, EDIT);
        final File makefile = new File(root, "src/ontology/Makefile");
        final String asRobotWritesIt = rewritten(new String(turned, StandardCharsets.UTF_8));
        assertFalse(Arrays.equals(turned, asRobotWritesIt.getBytes(StandardCharsets.UTF_8)));
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                Files.write(makefile.toPath(), "# ODK's own\n".getBytes(StandardCharsets.UTF_8));
                Files.write(edit.toPath(), asRobotWritesIt.getBytes(StandardCharsets.UTF_8));
                return new ProcessRunner.Outcome(0, Collections.singletonList(
                        "Ontology repository update successfully completed."), false);
            }
        };

        OdkUpdate.Ran ran = OdkUpdate.inContainer("docker", "an-image:v1",
                OdkContainer.Host.unknown(), root, OdkUpdate.plan(root), container);

        assertTrue(ran.isOk(), ran.getFailure());
        assertEquals(Collections.singletonList(makefile.getAbsoluteFile()), ran.getWritten());
        assertArrayEquals(turned, Files.readAllBytes(edit.toPath()));
        assertEquals("eggboil-edit.owl: ODK's update wrote it again with the same ontology in "
                + "it, only written otherwise; it was put back as it was.",
                ran.getTranscript().get(ran.getTranscript().size() - 1),
                "and the log says that it was");
        assertEquals(new ArrayList<String>(), leftBeside(edit), "nothing is left beside it");
    }

    /** What an update left in the edit file's folder that it made for itself. */
    private static List<String> leftBeside(File edit) {
        List<String> left = new ArrayList<String>();
        for (String name : edit.getParentFile().list()) {
            if (name.startsWith(".ontoboard-")) {
                left.add(name);
            }
        }
        return left;
    }

    /** What ODK's own update changed in the edit file stays changed. */
    @Test
    void afterOdksOwnUpdateAnEditFileThatSaysSomethingElseStays(@TempDir final File root)
            throws Exception {
        upToDateWithItsLinesTurned(root);
        final File edit = new File(root, EDIT);
        final String more = new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8)
                .replaceFirst("\nDeclaration\\(", "\nDeclaration(Class(<https://example.org/"
                        + "eggboil/EGGBOIL_9999999>))\nDeclaration(");
        ProcessRunner.Runner container = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                Files.write(edit.toPath(), more.getBytes(StandardCharsets.UTF_8));
                return new ProcessRunner.Outcome(0, Collections.singletonList("done"), false);
            }
        };

        OdkUpdate.Ran ran = OdkUpdate.inContainer("docker", "an-image:v1",
                OdkContainer.Host.unknown(), root, OdkUpdate.plan(root), container);

        assertEquals(Collections.singletonList(edit.getAbsoluteFile()), ran.getWritten());
        assertEquals(more, new String(Files.readAllBytes(edit.toPath()), StandardCharsets.UTF_8));
        assertEquals("done", ran.getTranscript().get(ran.getTranscript().size() - 1),
                "nothing was put back, so the log ends with what ODK said");
        assertEquals(new ArrayList<String>(), leftBeside(edit), "nothing is left beside it");
    }
}
