package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/**
 * Where ROBOT's report fails, ODK's report line fails here too: the profile read line by line as
 * ROBOT 1.9.8 reads it ({@code ReportOperation.getProfile}), a rule ROBOT does not ship
 * ({@code getDefaultQueryStrings}), a rule that is a query file of the project's own
 * ({@code getUserQueryStrings}, {@code getReportQueries}), and a query that cannot be run
 * ({@code getViolations}, {@code getReport}) - in ROBOT's words. What cannot be done inside
 * Protege as ROBOT does it is refused before anything runs. None of this is in a recorded run:
 * the bytecode is the authority. The ontology and the files are made up.
 */
class OdkRobotReportRulesTest {

    private static final String PREFIXES = "Prefix(:=<https://example.org/e/>)\n"
            + "Prefix(owl:=<http://www.w3.org/2002/07/owl#>)\n"
            + "Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)\n";

    /** A labelled class and an unlabelled one with a comment. */
    private static final String KITCHEN = ""
            + "Declaration(Class(:Egg))\n"
            + "AnnotationAssertion(rdfs:label :Egg \"boiled egg\"@en)\n"
            + "Declaration(Class(:Pan))\n"
            + "AnnotationAssertion(rdfs:comment :Pan \"no label\")\n";

    private static final String HEADER = "Level\tRule Name\tSubject\tProperty\tValue";

    /** A query of the project's own: every comment, with its property. */
    private static final String COMMENTS = ""
            + "# Comments - a check of the project's own\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
            + "SELECT DISTINCT ?entity ?property ?value WHERE {\n"
            + "  VALUES ?property { rdfs:comment }\n"
            + "  ?entity ?property ?value .\n"
            + "}\n"
            + "ORDER BY ?entity\n";

    private static File ontology(File folder) throws IOException {
        File file = new File(folder, "kitchen.ofn");
        Files.write(file.toPath(), (PREFIXES + "Ontology(<https://example.org/e/kitchen.owl>\n"
                + KITCHEN + ")\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static File file(File folder, String name, String text) throws IOException {
        File file = new File(folder, name);
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String path(File file) {
        return file.getAbsolutePath();
    }

    /** A path ROBOT's report on ODK's Unix would see: forward slashes. */
    private static String slashed(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** ODK's report line on the kitchen, with the profile given. */
    private static List<String> line(File temp, File profile, File report, String failOn)
            throws IOException {
        return new ArrayList<String>(Arrays.asList("report", "--input", path(ontology(temp)),
                "--profile", path(profile), "--labels", "true", "--fail-on", failOn,
                "--print", "5", "--output", path(report)));
    }

    /** The line failed as ROBOT fails before it says anything: nothing printed, no file. */
    private static void failedBeforeAnything(OdkRobot.Result result, File report,
            String failure) {
        failedBeforeItsCounts(result, report, failure);
    }

    /**
     * The line failed as ROBOT fails while it runs its rules: no counts, no file - only what it
     * said of the rules that ran before the one that failed.
     */
    private static void failedBeforeItsCounts(OdkRobot.Result result, File report,
            String failure, String... saidOfRulesBefore) {
        assertFalse(result.ok());
        assertEquals(failure, result.failure());
        assertEquals(Arrays.asList(saidOfRulesBefore), result.output());
        assertFalse(report.exists(), "ROBOT fails before it writes the file");
    }

    // ------------------------------------------------------------------ the profile's lines

    @Test
    void aBlankLineInTheProfileIsAnUnknownLevel(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\tmissing_label\n\nINFO\tmissing_superclass\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report,
                "REPORT LEVEL ERROR '' is not a valid reporting level.");
    }

    @Test
    void aCommentInTheProfileIsAnUnknownLevel(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "# the kitchen's checks\nERROR\tmissing_label\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report,
                "REPORT LEVEL ERROR '# the kitchen's checks' is not a valid reporting level.");
    }

    /** Fields are split at tabs only: a space leaves one field, which is no level. */
    @Test
    void aLevelAndRuleSplitByASpaceIsAnUnknownLevel(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR missing_label\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report,
                "REPORT LEVEL ERROR 'ERROR missing_label' is not a valid reporting level.");
    }

    @Test
    void aLevelRobotDoesNotKnowFails(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\tmissing_label\nFATAL\tmissing_superclass\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report,
                "REPORT LEVEL ERROR 'FATAL' is not a valid reporting level.");
    }

    /** A level in any case, and a line with space around it, are read; the rest is the rule. */
    @Test
    void aLevelInAnyCaseAndALineWithSpaceAroundItAreRead(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "  error\tmissing_label  \r\nInfo\tmissing_superclass\r\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n"
                + "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t\n"
                + "INFO\tmissing_superclass\tboiled egg\trdfs:subClassOf\t\n"
                + "INFO\tmissing_superclass\thttps://example.org/e/Pan\trdfs:subClassOf\t\n",
                text(report));
    }

    /** A level and no rule: ROBOT reads the second field all the same, and the JVM says so. */
    @Test
    void aLevelWithoutARuleFails(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\tmissing_label\nWARN\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertFalse(result.ok());
        assertTrue(Arrays.asList("1", "Index 1 out of bounds for length 1")
                .contains(result.failure()), result.failure());
        assertFalse(report.exists());
    }

    /** A profile that is not there fails; ROBOT's own is not taken instead. */
    @Test
    void aProfileThatIsNotThereFails(@TempDir File temp) throws Exception {
        File profile = new File(temp, "profile.txt");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertFalse(result.ok());
        assertTrue(result.failure().startsWith(path(profile) + " ("), result.failure());
        assertEquals(Collections.<String>emptyList(), result.output());
        assertFalse(report.exists());
    }

    /** An empty profile checks nothing; ROBOT's own is not taken instead. */
    @Test
    void anEmptyProfileChecksNothing(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "ERROR"));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n", text(report));
        assertEquals(Arrays.asList("No violations found.", "", "First 0 violations:"),
                result.output());
    }

    // ------------------------------------------------------------------ rules ROBOT does not ship

    @Test
    void aRuleRobotDoesNotShipFails(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\tmissing_label\nWARN\tkitchen_rule\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report, "UNKNOWN REPORT QUERY one or more rule names "
                + "('kitchen_rule') are not valid default rules");
    }

    /** A rule's name is what follows the tab, as it is: a space before it is part of it. */
    @Test
    void aRuleNameIsNotTrimmed(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\t missing_label\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report, "UNKNOWN REPORT QUERY one or more rule names "
                + "(' missing_label') are not valid default rules");
    }

    /** Several: in the order of ROBOT's HashSet (computed with the JDK's own, see the ledger). */
    @Test
    void severalRulesRobotDoesNotShipAreNamedTogether(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt",
                "ERROR\tmissing_label\nWARN\tzz_rule\nWARN\taa_rule\nWARN\tmm_rule\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report, "UNKNOWN REPORT QUERY one or more rule names "
                + "('aa_rule, zz_rule, mm_rule') are not valid default rules");
    }

    // ------------------------------------------------------------------ rules of the project's own

    /** A rule given as file: and an absolute path is run; its name is the file's, its rows ROBOT's. */
    @Test
    void aRuleOfTheProjectsOwnIsRun(@TempDir File temp) throws Exception {
        File query = file(temp, "sparql/kitchen-comments.rq", COMMENTS);
        File profile = file(temp, "profile.txt",
                "ERROR\tmissing_label\nWARN\tfile:" + slashed(query) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "ERROR"));

        assertFalse(result.ok(), "missing_label is an ERROR");
        assertEquals(HEADER + "\n"
                + "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t\n"
                + "WARN\tkitchen-comments\thttps://example.org/e/Pan\trdfs:comment\tno label\n",
                text(report));
        assertEquals(Arrays.asList("WARN: 'missing_label' query is missing ?value variable",
                "Violations: 2", "-----------------", "ERROR:      1",
                "WARN:       1", "INFO:       0", "", "First 2 violations:",
                "ERROR\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t",
                "WARN\tkitchen-comments\thttps://example.org/e/Pan\trdfs:comment\tno label"),
                result.output());
    }

    /** As a file:/// URL the same, named after the last part of the URL. */
    @Test
    void aRuleOfTheProjectsOwnAsAUrlIsRun(@TempDir File temp) throws Exception {
        File query = file(temp, "sparql/kitchen-comments.rq", COMMENTS);
        File profile = file(temp, "profile.txt", "INFO\t" + query.toURI().toString()
                .replaceFirst("^file:/+", "file:///") + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n"
                + "INFO\tkitchen-comments\thttps://example.org/e/Pan\trdfs:comment\tno label\n",
                text(report));
    }

    @Test
    void aRuleOfTheProjectsOwnThatIsNotThereFails(@TempDir File temp) throws Exception {
        File query = new File(temp, "sparql/kitchen-comments.rq");
        File profile = file(temp, "profile.txt", "WARN\tfile:" + slashed(query) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report, "MISSING QUERY ERROR query at '"
                + new File(slashed(query)).getPath() + "' does not exist.");
    }

    /**
     * ROBOT reads a relative path against the folder it runs in, which inside Protege is not the
     * project's: the line is refused by name before anything runs, and asked about it says so.
     */
    @Test
    void aRuleOfTheProjectsOwnByARelativePathIsRefused(@TempDir File temp) throws Exception {
        file(temp, "sparql/kitchen-comments.rq", COMMENTS);
        File profile = file(temp, "profile.txt",
                "ERROR\tmissing_label\nWARN\tfile:sparql/kitchen-comments.rq\n");
        File report = new File(temp, "report.tsv");
        List<String> line = line(temp, profile, report, "none");

        String why = OdkRobot.whyNot(line);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OdkRobot.run(line));

        assertNotNull(why);
        assertTrue(why.contains("'file:sparql/kitchen-comments.rq'"), why);
        assertEquals(why, refused.getMessage());
        assertFalse(report.exists());
    }

    /** Where ROBOT fails first - here at a rule it does not ship - the line is run and fails so. */
    @Test
    void aRelativeRuleBehindAnEarlierFailureIsNotRefused(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt",
                "WARN\tkitchen_rule\nWARN\tfile:sparql/kitchen-comments.rq\n");
        File report = new File(temp, "report.tsv");
        List<String> line = line(temp, profile, report, "none");

        assertNull(OdkRobot.whyNot(line));
        failedBeforeAnything(OdkRobot.run(line), report, "UNKNOWN REPORT QUERY one or more rule "
                + "names ('kitchen_rule') are not valid default rules");
    }

    /**
     * Told the folder the line runs in - for a recipe, the folder make runs in - a relative
     * path is read against it, as ROBOT reads it against its own folder: the rule is run.
     */
    @Test
    void toldTheFolderTheLineRunsInARelativeRuleIsReadAgainstIt(@TempDir File temp)
            throws Exception {
        File folder = new File(temp, "src/ontology");
        file(temp, "src/sparql/kitchen-comments.rq", COMMENTS);
        File profile = file(folder, "profile.txt",
                "WARN\tfile:../sparql/kitchen-comments.rq\n");
        File report = new File(folder, "report.tsv");
        List<String> line = line(temp, profile, report, "none");

        assertNull(OdkRobot.whyNot(line, folder), "nothing stands in its way");
        OdkRobot.Result result = OdkRobot.run(line, folder);

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(HEADER + "\n"
                + "WARN\tkitchen-comments\thttps://example.org/e/Pan\trdfs:comment\tno label\n",
                text(report));
    }

    /** One that is not there fails as ROBOT fails, naming the path as the profile has it. */
    @Test
    void aRelativeRuleThatIsNotThereFailsWithThePathAsTheProfileHasIt(@TempDir File temp)
            throws Exception {
        File folder = new File(temp, "src/ontology");
        File profile = file(folder, "profile.txt", "WARN\tfile:../sparql/not-there.rq\n");
        File report = new File(folder, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"), folder);

        failedBeforeAnything(result, report, "MISSING QUERY ERROR query at '"
                + new File("../sparql/not-there.rq").getPath() + "' does not exist.");
    }

    // ------------------------------------------------------------------ what ROBOT says of a query

    /**
     * ROBOT in ODK's image (1.9.10) says, once for a rule, that a row of its query binds no
     * {@code ?property}, or no {@code ?value} - on its standard output, as the rules run, so
     * before the counts. The ROBOT inside OntoBoard (1.9.8, the last that runs on Java 8) is
     * the same in everything else of the report and does not say this. ODK's log has the
     * lines, so they are said here as 1.9.10 says them (read from its bytecode:
     * {@code ReportOperation.getViolationsFromResults}).
     */
    @Test
    void aRuleWhoseRowsBindNoPropertyOrNoValueIsSaidOnceForTheRule(@TempDir File temp)
            throws Exception {
        File bare = file(temp, "sparql/every-class.rq", "PREFIX owl: "
                + "<http://www.w3.org/2002/07/owl#>\nSELECT ?entity WHERE { ?entity a owl:Class }\n"
                + "ORDER BY ?entity\n");
        File profile = file(temp, "profile.txt", "INFO\tfile:" + slashed(bare)
                + "\nWARN\tmissing_label\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        assertEquals(Arrays.asList(
                "WARN: 'every-class' query is missing ?property variable",
                "WARN: 'every-class' query is missing ?value variable",
                "WARN: 'missing_label' query is missing ?value variable",
                "Violations: 3", "-----------------", "ERROR:      0", "WARN:       1",
                "INFO:       2", "", "First 1 violations:",
                "WARN\tmissing_label\thttps://example.org/e/Pan\trdfs:label\t"),
                result.output(), "two classes without a property or a value: said once each");
    }

    /**
     * ROBOT looks at a row's property and value only when it reports the row: a row about a
     * term of OWL or RDF Schema is passed over before that, and says nothing.
     */
    @Test
    void aRowThatIsNotReportedSaysNothingOfItsQuery(@TempDir File temp) throws Exception {
        File bare = file(temp, "sparql/only-thing.rq", "PREFIX owl: "
                + "<http://www.w3.org/2002/07/owl#>\n"
                + "SELECT ?entity WHERE { VALUES ?entity { owl:Thing } }\n");
        File profile = file(temp, "profile.txt", "INFO\tfile:" + slashed(bare) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        assertTrue(result.ok(), String.valueOf(result.failure()));
        for (String said : result.output()) {
            assertFalse(said.startsWith("WARN: '"), result.output().toString());
        }
        assertEquals(HEADER + "\n", text(report));
    }

    // ------------------------------------------------------------------ a query that cannot run

    @Test
    void aQueryThatCannotBeParsedFailsTheReport(@TempDir File temp) throws Exception {
        File query = file(temp, "sparql/broken.rq", "SELECT ?entity WHERE { ?entity \n");
        File profile = file(temp, "profile.txt",
                "ERROR\tmissing_label\nWARN\tfile:" + slashed(query) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeItsCounts(result, report,
                "MISSING ENTITY BINDING query 'broken' must include an '?entity'",
                "WARN: 'missing_label' query is missing ?value variable");
    }

    @Test
    void aQueryThatIsNoSelectFailsTheReport(@TempDir File temp) throws Exception {
        File query = file(temp, "sparql/asking.rq", "ASK { ?entity ?p ?o }\n");
        File profile = file(temp, "profile.txt", "WARN\tfile:" + slashed(query) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result result = OdkRobot.run(line(temp, profile, report, "none"));

        failedBeforeAnything(result, report,
                "MISSING ENTITY BINDING query 'asking' must include an '?entity'");
    }

    /** Without ?entity, a query that finds nothing is no failure; one that finds rows is. */
    @Test
    void aQueryWithoutEntityFailsOnlyWhenItFindsRows(@TempDir File temp) throws Exception {
        File nothing = file(temp, "sparql/nothing.rq",
                "SELECT ?x WHERE { ?x <https://example.org/e/never> ?y }\n");
        File some = file(temp, "sparql/some.rq", "SELECT ?x WHERE { ?x ?p ?y }\n");
        File quiet = file(temp, "quiet.txt", "WARN\tfile:" + slashed(nothing) + "\n");
        File loud = file(temp, "loud.txt", "WARN\tfile:" + slashed(some) + "\n");
        File report = new File(temp, "report.tsv");

        OdkRobot.Result passes = OdkRobot.run(line(temp, quiet, report, "none"));
        assertTrue(passes.ok(), String.valueOf(passes.failure()));
        assertTrue(report.delete());
        OdkRobot.Result fails = OdkRobot.run(line(temp, loud, report, "none"));

        failedBeforeAnything(fails, report,
                "MISSING ENTITY BINDING query 'some' must include an '?entity'");
    }

    // ------------------------------------------------------------------ --fail-on

    /** ROBOT prints and writes first, and only then reads --fail-on: so here too. */
    @Test
    void aFailOnRobotDoesNotKnowFailsAfterTheFileIsWritten(@TempDir File temp) throws Exception {
        File profile = file(temp, "profile.txt", "ERROR\tmissing_label\n");
        File report = new File(temp, "report.tsv");
        List<String> line = line(temp, profile, report, "Sometimes");

        assertNull(OdkRobot.whyNot(line));
        OdkRobot.Result result = OdkRobot.run(line);

        assertFalse(result.ok());
        assertEquals("FAIL ON ERROR 'Sometimes' is not a valid fail-on level.", result.failure());
        assertEquals(Arrays.asList("WARN: 'missing_label' query is missing ?value variable",
                "Violations: 1"), result.output().subList(0, 2));
        assertEquals(HEADER + "\n" + "ERROR\tmissing_label\thttps://example.org/e/Pan\t"
                + "rdfs:label\t\n", text(report));
    }
}
