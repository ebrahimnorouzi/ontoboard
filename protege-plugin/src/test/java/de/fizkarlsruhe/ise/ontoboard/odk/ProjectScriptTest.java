package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;

/**
 * A project's own release script, read and run by OntoBoard itself - no sh, no Docker. The user,
 * 2026-11-19: "i want this limit to be solved: a project released by its own release.sh
 * (nfdicore, MWO) is still released in ODK's container".
 *
 * <p>The scripts are nfdicore's and MWO's own, as Git checks them out on Windows: with CRLF.
 */
class ProjectScriptTest {

    static final String NFDICORE = "\r\n\r\nVERSION=3.0.5\r\nPRIOR_VERSION=3.0.4\r\n"
            + "ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology \r\n"
            + "ANNOTATE_ONTOLOGY_VERSION=\"annotate -V $ONTBASE/$VERSION/\\$@ --annotation "
            + "owl:versionInfo $VERSION\"\r\n\r\n\r\n"
            + "sh run.sh make clean\r\n\r\n"
            + "sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE ANNOTATE_ONTOLOGY_VERSION="
            + "\"$ANNOTATE_ONTOLOGY_VERSION\" prepare_release\r\n"
            + "#sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE prepare_release\r\n\r\n"
            + "sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE ANNOTATE_ONTOLOGY_VERSION="
            + "\"$ANNOTATE_ONTOLOGY_VERSION\" nfdicore-extension.ttl\r\n\r\n"
            + "sh run.sh make VERSION=$VERSION PRIOR_VERSION=$PRIOR_VERSION "
            + "update-ontology-annotations\r\n\r\n"
            + "cp nfdicore-extension.ttl ../../profiles/nfdicore-extension.ttl\r\n\r\n"
            + "# finally refresh imports again, so that version IRIs are updated back to "
            + "\"normal\". \r\n"
            + "#sh run.sh make IMP=false MIR=false refresh-imports\r\n";

    static final String MWO = "set -e\r\n\r\nVERSION=3.0.2\r\nPRIOR_VERSION=3.0.1\r\n"
            + "ONTBASE=http://purls.helmholtz-metadaten.de/mwo \r\n"
            + "ANNOTATE_ONTOLOGY_VERSION=\"annotate -V $ONTBASE/$VERSION/\\$@ --annotation "
            + "owl:versionInfo $VERSION\"\r\n\r\n\r\n"
            + "sh run.sh make clean\r\n\r\n"
            + "sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE ANNOTATE_ONTOLOGY_VERSION="
            + "\"$ANNOTATE_ONTOLOGY_VERSION\" prepare_release\r\n"
            + "#sh run.sh make VERSION=$VERSION ONTBASE=$ONTBASE prepare_release\r\n\r\n"
            + "sh run.sh make VERSION=$VERSION PRIOR_VERSION=$PRIOR_VERSION "
            + "update-ontology-annotations\r\n\r\n\r\n"
            + "# finally refresh imports again, so that version IRIs are updated back to "
            + "\"normal\". \r\n"
            + "# sh run.sh make IMP=false MIR=false refresh-imports\r\n";

    private static List<List<String>> makes(ProjectScript.Plan plan) {
        List<List<String>> makes = new ArrayList<List<String>>();
        for (ProjectScript.Step step : plan.steps()) {
            if (step.kind() == ProjectScript.Kind.MAKE) {
                makes.add(step.make());
            }
        }
        return makes;
    }

    @Test
    void nfdicoresScriptIsReadAsShReadsIt() {
        ProjectScript.Plan plan = ProjectScript.read(NFDICORE, RecipeShell.builtins());

        assertNull(plan.whyNot(), plan.whyNot());
        String annotate = "ANNOTATE_ONTOLOGY_VERSION=annotate -V "
                + "https://nfdi.fiz-karlsruhe.de/ontology/3.0.5/$@ --annotation "
                + "owl:versionInfo 3.0.5";
        assertEquals(Arrays.asList(
                Arrays.asList("make", "clean"),
                Arrays.asList("make", "VERSION=3.0.5",
                        "ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology", annotate,
                        "prepare_release"),
                Arrays.asList("make", "VERSION=3.0.5",
                        "ONTBASE=https://nfdi.fiz-karlsruhe.de/ontology", annotate,
                        "nfdicore-extension.ttl"),
                Arrays.asList("make", "VERSION=3.0.5", "PRIOR_VERSION=3.0.4",
                        "update-ontology-annotations")), makes(plan));
        ProjectScript.Step copy = plan.steps().get(plan.steps().size() - 1);
        assertEquals(ProjectScript.Kind.SHELL, copy.kind());
        assertEquals(18, copy.line());
        assertEquals("3.0.5", plan.variables().get("VERSION"));
        assertEquals("https://nfdi.fiz-karlsruhe.de/ontology", plan.variables().get("ONTBASE"),
                "the blank and the CR after the value end it, as in sh");
    }

    @Test
    void mwosScriptWithSetEIsRead() {
        ProjectScript.Plan plan = ProjectScript.read(MWO, RecipeShell.builtins());

        assertNull(plan.whyNot(), plan.whyNot());
        assertEquals(3, makes(plan).size());
        assertEquals(ProjectScript.Kind.OPTION, plan.steps().get(0).kind());
    }

    @Test
    void aLineItCannotRunIsNamedBeforeAnythingRuns() {
        ProjectScript.Plan plan = ProjectScript.read("VERSION=1.0.0\nsh run.sh make clean\n"
                + "git push origin main\n", RecipeShell.builtins());

        assertNotNull(plan.whyNot());
        assertTrue(plan.whyNot().contains("Line 3"), plan.whyNot());
        assertTrue(plan.whyNot().contains("git"), plan.whyNot());
    }

    @Test
    void runShForAnythingButMakeIsNamed() {
        ProjectScript.Plan plan = ProjectScript.read("sh run.sh robot --version\n",
                RecipeShell.builtins());

        assertNotNull(plan.whyNot());
        assertTrue(plan.whyNot().contains("Line 1"), plan.whyNot());
    }

    @Test
    void quotesAndBracesAreSh() {
        ProjectScript.Plan plan = ProjectScript.read("A=one\nB='$A two'\nC=\"${A}-x\"\n"
                + "make X=\"$B\" Y=$C all\n", RecipeShell.builtins());

        assertNull(plan.whyNot(), plan.whyNot());
        assertEquals(Arrays.asList("make", "X=$A two", "Y=one-x", "all"), makes(plan).get(0));
    }

    @Test
    void stepsRunInOrderEachSaidAndTheFirstFailureStops(@TempDir File top) throws Exception {
        File ontology = new File(top, "src/ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "nfdicore-extension.ttl").toPath(),
                "x".getBytes(StandardCharsets.UTF_8));
        ProjectScript.Plan plan = ProjectScript.read(NFDICORE, RecipeShell.builtins());
        final List<List<String>> ran = new ArrayList<List<String>>();

        ProcessRunner.Outcome outcome = ProjectScript.run(plan, ontology, make -> {
            ran.add(make);
            boolean fails = make.contains("nfdicore-extension.ttl");
            return new ProcessRunner.Outcome(fails ? 2 : 0,
                    Arrays.asList(fails ? "make: *** No rule to make target" : "done"), false);
        }, RecipeShell.builtins(), null);

        assertFalse(outcome.isSuccess());
        assertEquals(3, ran.size(), "the fourth make and the copy did not run");
        String said = String.join("\n", outcome.getOutput());
        assertTrue(said.contains("line 9: make clean"), said);
        assertTrue(said.contains("line 14 failed"), said);
        assertFalse(new File(top, "profiles/nfdicore-extension.ttl").exists());
    }

    @Test
    void aWholeRunCopiesAsTheScriptSays(@TempDir File top) throws Exception {
        File ontology = new File(top, "src/ontology");
        assertTrue(ontology.mkdirs());
        assertTrue(new File(top, "profiles").mkdirs());
        Files.write(new File(ontology, "nfdicore-extension.ttl").toPath(),
                "the extension".getBytes(StandardCharsets.UTF_8));
        ProjectScript.Plan plan = ProjectScript.read(NFDICORE, RecipeShell.builtins());

        ProcessRunner.Outcome outcome = ProjectScript.run(plan, ontology,
                make -> new ProcessRunner.Outcome(0, Arrays.asList("done"), false),
                RecipeShell.builtins(), null);

        assertTrue(outcome.isSuccess(), String.join("\n", outcome.getOutput()));
        assertEquals("the extension", new String(Files.readAllBytes(
                new File(top, "profiles/nfdicore-extension.ttl").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    void stoppedStops(@TempDir File top) throws Exception {
        File ontology = new File(top, "src/ontology");
        assertTrue(ontology.mkdirs());
        ProjectScript.Plan plan = ProjectScript.read(NFDICORE, RecipeShell.builtins());

        ProcessRunner.Outcome outcome = ProjectScript.run(plan, ontology,
                make -> new ProcessRunner.Outcome(0, Arrays.asList("done"), false),
                RecipeShell.builtins(), () -> true);

        assertTrue(outcome.wasCancelled());
    }
}
