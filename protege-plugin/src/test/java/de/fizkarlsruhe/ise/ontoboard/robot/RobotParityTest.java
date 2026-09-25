package de.fizkarlsruhe.ise.ontoboard.robot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The plugin's in-process report must agree with the real ROBOT a project's CI runs.
 *
 * <p>This is the test that would have caught the defect the unit tests could not. The plugin runs
 * ROBOT's rules itself - {@link ReportQueries} reads ROBOT's profile and SPARQL as streams and
 * {@link RuleRunner} executes them over a Jena model - because {@code ReportOperation} cannot reach
 * its own query files inside an OSGi bundle. That is a reimplementation of ROBOT's plumbing, and a
 * reimplementation is only trustworthy if something checks it against the original. So this runs
 * {@code robot report} inside {@code obolibrary/odkfull} over the same ontology and the same profile
 * and requires the violation sets to be identical.
 *
 * <p><b>The same profile, deliberately.</b> {@code odkfull} tracks a newer ROBOT than the version
 * this bundle embeds, and two ROBOT versions can ship different default profiles. Writing the
 * plugin's own profile out and passing it to the container removes that variable: a difference in
 * the results is then a difference in execution, which is the only thing this test is about.
 *
 * <p>Skipped, not failed, where Docker or the image is absent - a laptop without Docker should be
 * able to run the suite. The receipt in {@code tools/smoke-receipt/} records where it did run.
 */
class RobotParityTest {

    private static final String IMAGE = "obolibrary/odkfull";

    /** Long enough for a cold container start; a hung docker must not hang the suite. */
    private static final int TIMEOUT_SECONDS = 300;

    @Test
    void theReportMatchesRealRobotOnAnOntologyWithErrors(@TempDir File dir) throws Exception {
        // Loaded from the fixture in place, not from the copy in dir. The OWL API keeps the document
        // it loaded open, and on Windows an open handle makes @TempDir's cleanup fail - which is a
        // test error even when every assertion passed. The container gets its own copy.
        File subject = new File(dir, "subject.ttl");
        Files.copy(new File("src/test/resources/fixture-tiny.ttl").toPath(), subject.toPath());
        assertParity(dir, subject.getName(), OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(
                        new File("src/test/resources/fixture-tiny.ttl")));
    }

    /**
     * A second ontology, because the first produces only {@code ERROR} and {@code WARN} rows.
     *
     * <p>The pizza exercises {@code INFO} and a much larger class tree, and the first parity run
     * over it matched real ROBOT 1.9.10 on all 21 findings. One fixture agreeing could be luck about
     * which rules happened to fire.
     */
    @Test
    void theReportMatchesRealRobotOnThePizza(@TempDir File dir) throws Exception {
        // Built in memory and written out only for the container, so nothing holds dir open.
        OWLOntology pizza = de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology.v2();
        File saved = new File(dir, "subject.owl");
        pizza.getOWLOntologyManager().saveOntology(pizza, new RDFXMLDocumentFormat(),
                org.semanticweb.owlapi.model.IRI.create(saved.toURI()));
        assertParity(dir, saved.getName(), pizza);
    }

    /**
     * v5, the release that carries the awkward constructs and three deliberate defects.
     *
     * <p>v1-v4 are classes, object properties, subclass axioms and individuals - the easy quarter of
     * OWL. v5 adds a data property with a domain and range, a property chain, an annotation on an
     * axiom, a definition citing its source, a datatype value on an individual, and three problems
     * ROBOT is supposed to find: a second label, a copied definition, and a reference to the term v3
     * obsoleted.
     *
     * <p>Which makes it the fixture most likely to expose a difference between the plugin's report
     * and real ROBOT's, because it is the first one that goes near the parts of OWL the plugin has
     * never been tested against.
     */
    @Test
    void theReportMatchesRealRobotOnTheAwkwardPizza(@TempDir File dir) throws Exception {
        OWLOntology pizza =
                de.fizkarlsruhe.ise.ontoboard.e2e.PizzaOntology.v5WithCurationMistakes(
                        "https://orcid.org/0000-0002-1825-0097", "2026-09-25");
        File saved = new File(dir, "subject.owl");
        pizza.getOWLOntologyManager().saveOntology(pizza, new RDFXMLDocumentFormat(),
                org.semanticweb.owlapi.model.IRI.create(saved.toURI()));
        assertParity(dir, saved.getName(), pizza);
    }

    // ============================================================================== the comparison

    /**
     * @param input the file name inside {@code dir} for the container to read
     * @param ontology the same ontology, already in memory, for the plugin to report on
     */
    private void assertParity(File dir, String input, OWLOntology ontology) throws Exception {
        assumeTrue(imageIsAvailable(), IMAGE + " is not available, so real ROBOT cannot be run");

        // The plugin's own rule set, written where the container can read it.
        Map<String, String> severities = ReportQueries.defaultSeverities();
        File profile = new File(dir, "profile.txt");
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> rule : severities.entrySet()) {
            text.append(rule.getValue()).append('\t').append(rule.getKey()).append('\n');
        }
        Files.write(profile.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));

        TreeSet<String> plugin = rowsFrom(QualityReport.run(ontology));
        TreeSet<String> real = rowsFrom(runRealRobot(dir, input));

        assertFalse(real.isEmpty(), "real ROBOT found nothing, so this fixture proves nothing");
        assertEquals(describe(real), describe(plugin),
                "the plugin's report must be exactly what real ROBOT reports for the same ontology "
                        + "and the same profile. Rows only real ROBOT found: " + difference(real,
                        plugin) + "; rows only the plugin found: " + difference(plugin, real));
    }

    /** {@code LEVEL rule subject}, the three columns that decide what a curator has to fix. */
    private static TreeSet<String> rowsFrom(List<QualityFinding> findings) {
        TreeSet<String> rows = new TreeSet<String>();
        for (QualityFinding finding : findings) {
            rows.add(finding.getSeverity() + "\t" + finding.getRule() + "\t" + finding.getSubject());
        }
        return rows;
    }

    private static TreeSet<String> difference(TreeSet<String> from, TreeSet<String> minus) {
        TreeSet<String> only = new TreeSet<String>(from);
        only.removeAll(minus);
        return only;
    }

    /** Sorted, one row per line, so an inequality reads as a diff instead of a wall of text. */
    private static String describe(TreeSet<String> rows) {
        StringBuilder out = new StringBuilder(rows.size() + " findings\n");
        for (String row : rows) {
            out.append(row).append('\n');
        }
        return out.toString();
    }

    // =================================================================================== real ROBOT

    private List<QualityFinding> runRealRobot(File dir, String input) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        // Docker on Windows takes the native path; Java does no MSYS path rewriting, so unlike the
        // shell this needs no MSYS_NO_PATHCONV.
        command.add("-v");
        command.add(dir.getAbsolutePath() + ":/work");
        command.add("-w");
        command.add("/work");
        command.add(IMAGE);
        command.add("robot");
        command.add("report");
        command.add("--input");
        command.add(input);
        command.add("--profile");
        command.add("profile.txt");
        command.add("--output");
        command.add("real.tsv");
        command.add("--print");
        command.add("0");
        // Otherwise ROBOT exits non-zero the moment it finds an ERROR, which is the normal case
        // here: a non-zero exit would be indistinguishable from the container failing to start.
        command.add("--fail-on");
        command.add("none");

        String output = run(command);
        File tsv = new File(dir, "real.tsv");
        if (!tsv.isFile()) {
            throw new IllegalStateException("real ROBOT wrote no report:\n" + output);
        }
        return parse(new String(Files.readAllBytes(tsv.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * ROBOT's own TSV, read by column name.
     *
     * <p>Column order is not a contract, and reading by position would silently compare the wrong
     * fields - which would make this test agree with itself rather than with ROBOT.
     */
    private static List<QualityFinding> parse(String tsv) {
        List<QualityFinding> findings = new ArrayList<QualityFinding>();
        String[] lines = tsv.split("\\r?\\n");
        if (lines.length == 0) {
            return findings;
        }
        String[] header = lines[0].split("\\t", -1);
        int level = columnStartingWith(header, "level");
        int rule = columnStartingWith(header, "rule");
        int subject = columnStartingWith(header, "subject");
        if (level < 0 || rule < 0 || subject < 0) {
            throw new IllegalStateException("ROBOT's report has no level/rule/subject columns: "
                    + lines[0]);
        }
        for (int i = 1; i < lines.length; i++) {
            String[] row = lines[i].split("\\t", -1);
            if (row.length <= subject || row[level].trim().isEmpty()) {
                continue;
            }
            findings.add(new QualityFinding(QualityFinding.Severity.of(row[level]),
                    row[rule].trim(), row[subject].trim(), ""));
        }
        return findings;
    }

    private static int columnStartingWith(String[] header, String wanted) {
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null && header[i].trim().toLowerCase().startsWith(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean imageIsAvailable() {
        try {
            List<String> command = new ArrayList<String>();
            command.add("docker");
            command.add("image");
            command.add("inspect");
            command.add(IMAGE);
            run(command);
            return true;
        } catch (Exception notThere) {
            return false;
        }
    }

    /** Runs a command, returning its combined output and failing loudly on a non-zero exit. */
    private static String run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        InputStream out = process.getInputStream();
        byte[] chunk = new byte[8192];
        for (int read = out.read(chunk); read >= 0; read = out.read(chunk)) {
            captured.write(chunk, 0, read);
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("timed out after " + TIMEOUT_SECONDS + "s: " + command);
        }
        String output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " exited " + process.exitValue() + ":\n"
                    + output);
        }
        return output;
    }
}
