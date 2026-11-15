package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code robot query} inside Protégé: ROBOT's options and ROBOT's loading of the input, and
 * OntoBoard's engine for the query - ROBOT's own needs a part of the OWL API Protégé does not
 * have.
 *
 * <p>Held against what ROBOT 1.9.10 wrote in ODK's image for the four command lines ODK's
 * Makefile has, run in {@code src/ontology} of the fixture after {@code make
 * tmp/merged-eggboil-edit.ofn}. Equal means: the header is the same; the rows are the same as a
 * multiset (without ORDER BY their order is a hash table's, and ODK pipes such files through
 * {@code sort | uniq}); the line ending is the recorded one - CSV and TSV do not have the same;
 * every cell is written as recorded.
 */
class OdkRobotQueryTest {

    @TempDir
    File temp;

    private File ontology;
    private final List<String> said = new ArrayList<String>();

    @BeforeEach
    void theFixture() throws IOException {
        ontology = EggFixture.write(new File(temp, "egg"), true);
    }

    /** A recipe line, through the shell reader and OntoBoard's tools, in src/ontology. */
    private int run(String line) {
        RecipeShell.Script script = RecipeShell.parse(line);
        Map<String, RecipeShell.Tool> tools = OdkTools.forProject();
        assertNull(script.whyNot(tools), line);
        return script.run(tools, ontology, said);
    }

    private String whyNot(String line) {
        return RecipeShell.parse(line).whyNot(OdkTools.forProject());
    }

    /** The file is what ROBOT wrote, in the sense above. */
    private void assertAsRecorded(String golden, String written) throws IOException {
        String expected = new String(Goldens.equivalence("query/" + golden),
                StandardCharsets.UTF_8);
        File file = new File(ontology, written);
        assertTrue(file.isFile(), written + " was not written; said: " + said);
        String actual = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        String ending = expected.contains("\r\n") ? "\r\n" : "\n";
        if (ending.equals("\r\n")) {
            assertFalse(actual.replace("\r\n", "").contains("\n"), "a line of " + written
                    + " ends with LF alone; ROBOT ends every line of it with CRLF");
            assertFalse(actual.replace("\r\n", "").contains("\r"), written);
        } else {
            assertFalse(actual.contains("\r"), written + " has a CR; ROBOT wrote LF alone");
        }
        assertTrue(actual.endsWith(ending), written + " does not end with its line break");

        List<String> theirs = lines(expected, ending);
        List<String> ours = lines(actual, ending);
        assertEquals(theirs.get(0), ours.get(0), "the header of " + written);
        List<String> theirRows = new ArrayList<String>(theirs.subList(1, theirs.size()));
        List<String> ourRows = new ArrayList<String>(ours.subList(1, ours.size()));
        Collections.sort(theirRows);
        Collections.sort(ourRows);
        assertEquals(theirRows, ourRows, "the rows of " + written);
    }

    private static List<String> lines(String text, String ending) {
        String body = text.endsWith(ending) ? text.substring(0, text.length() - ending.length())
                : text;
        return Arrays.asList(body.split(ending.equals("\r\n") ? "\r\n" : "\n", -1));
    }

    // ------------------------------------------------------------------ the four recorded lines

    /**
     * ODK's Makefile says {@code --format --csv}. ROBOT's parser takes {@code --csv}, no option
     * of query, as the format's value; ROBOT knows no format of that name and writes CSV.
     */
    @Test
    void theTermsOfTheOntologyAsOdksMakefileAsksForThem() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query --input "
                + "tmp/merged-eggboil-edit.ofn --format --csv --query ../sparql/terms.sparql "
                + "tmp/terms.format--csv.txt"), said.toString());

        assertAsRecorded("terms.format--csv.txt", "tmp/terms.format--csv.txt");
    }

    @Test
    void theSeedOfTheSimpleProduct() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query -f csv -i "
                + "tmp/merged-eggboil-edit.ofn --query ../sparql/simple-seed.sparql "
                + "tmp/simple-seed.csv.txt"), said.toString());

        assertAsRecorded("simple-seed.csv.txt", "tmp/simple-seed.csv.txt");
    }

    @Test
    void theProjectsOwnTerms() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query -f csv -i "
                + "tmp/merged-eggboil-edit.ofn --query ../sparql/eggboil_terms.sparql "
                + "tmp/eggboil_terms.csv.txt"), said.toString());

        assertAsRecorded("eggboil_terms.csv.txt", "tmp/eggboil_terms.csv.txt");
    }

    /** The custom reports of a release: several queries in one call, as TSV. */
    @Test
    void theReportsOfARelease() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query -f tsv --use-graphs true -i "
                + "tmp/eggboil-preprocess.owl -s ../sparql/basic-report.sparql "
                + "tmp/basic-report.tsv -s ../sparql/edges.sparql tmp/edges.tsv -s "
                + "../sparql/synonyms.sparql tmp/synonyms.tsv"), said.toString());

        assertAsRecorded("basic-report.tsv", "tmp/basic-report.tsv");
        assertAsRecorded("edges.tsv", "tmp/edges.tsv");
        assertAsRecorded("synonyms.tsv", "tmp/synonyms.tsv");
    }

    // ------------------------------------------------------------------ ROBOT's rules, not the cases

    /** The rule behind {@code --format --csv}: a name ROBOT does not know is CSV - even "--tsv". */
    @Test
    void aFormatRobotDoesNotKnowIsCsv() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query -i tmp/merged-eggboil-edit.ofn "
                + "--format --tsv --query ../sparql/simple-seed.sparql tmp/rule.tsv"),
                said.toString());

        assertAsRecorded("simple-seed.csv.txt", "tmp/rule.tsv");
    }

    /** Without --format, ROBOT takes the format from the output's extension. */
    @Test
    void withoutAFormatTheOutputsExtensionSaysWhich() throws IOException {
        assertEquals(0, run("robot --catalog catalog-v001.xml query -i tmp/eggboil-preprocess.owl "
                + "--query ../sparql/edges.sparql tmp/by-name.tsv --query "
                + "../sparql/simple-seed.sparql tmp/by-name.txt"), said.toString());

        assertAsRecorded("edges.tsv", "tmp/by-name.tsv");
        assertAsRecorded("simple-seed.csv.txt", "tmp/by-name.txt");
    }

    /** ROBOT opens the output first and writes only when there are rows: an empty file. */
    @Test
    void aQueryWithoutRowsLeavesAnEmptyFile() throws IOException {
        Files.write(new File(ontology, "tmp/nothing.sparql").toPath(), ("SELECT ?x WHERE { ?x a "
                + "<https://example.org/no-such-type> }\n").getBytes(StandardCharsets.UTF_8));

        assertEquals(0, run("robot query -i tmp/merged-eggboil-edit.ofn -f tsv --query "
                + "tmp/nothing.sparql tmp/nothing.tsv"), said.toString());

        assertTrue(new File(ontology, "tmp/nothing.tsv").isFile(), said.toString());
        assertEquals(0, new File(ontology, "tmp/nothing.tsv").length());
    }

    @Test
    void aQueryFileThatIsNotThereIsAFailure() {
        assertEquals(1, run("robot query -i tmp/merged-eggboil-edit.ofn -f csv --query "
                + "../sparql/not-there.sparql tmp/x.csv"));

        assertTrue(said.toString().contains("not-there.sparql"), said.toString());
    }

    // ------------------------------------------------------------------ what is refused

    /**
     * What ODK's Makefile does not use of query is refused by name before anything runs: the
     * update, the TDB options, the other ways to name queries and outputs, loading from an IRI.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--update ../sparql/x.ru", "--temporary-file true", "--tdb true",
        "--create-tdb true", "--keep-tdb-mappings true", "--tdb-directory .tdb",
        "--output tmp/out.owl", "--output-dir reports", "--queries ../sparql/terms.sparql",
        "--construct ../sparql/terms.sparql tmp/c.ttl", "--input-iri https://example.org/x.owl"})
    void anOptionOdkDoesNotUseIsRefusedByName(String option) {
        String line = "robot --catalog catalog-v001.xml query -i tmp/merged-eggboil-edit.ofn "
                + option + " --query ../sparql/terms.sparql tmp/refused.csv";
        String name = option.split(" ")[0];

        String why = whyNot(line);

        assertNotNull(why, line);
        assertTrue(why.contains(name), why);
        assertFalse(why.contains("needs ODK's container"), "what OntoBoard does not do is "
                + "said; who else does is the engine's to add: " + why);
        RecipeShell.Script script = RecipeShell.parse(line);
        assertEquals(1, script.run(OdkTools.forProject(), ontology, said));
        assertTrue(said.toString().contains(name), said.toString());
        assertFalse(new File(ontology, "tmp/refused.csv").exists(), "nothing ran");
    }

    /** A result format other than CSV and TSV - by --format or by the output's name. */
    @ParameterizedTest
    @ValueSource(strings = {"-f ttl", "-f jsonld", "-f nt", "-f nq", "-f xml", "-f sxml",
        "-f TTL"})
    void aFormatOtherThanCsvAndTsvIsRefusedByName(String format) {
        String line = "robot query -i tmp/merged-eggboil-edit.ofn " + format + " --query "
                + "../sparql/terms.sparql tmp/refused.out";

        String why = whyNot(line);

        assertNotNull(why, line);
        assertTrue(why.toLowerCase().contains(format.substring(3).toLowerCase()), why);
        assertEquals(1, RecipeShell.parse(line).run(OdkTools.forProject(), ontology, said));
        assertFalse(new File(ontology, "tmp/refused.out").exists(), "nothing ran");
    }

    @Test
    void aFormatTakenFromTheOutputsNameIsRefusedToo() {
        String why = whyNot("robot query -i tmp/merged-eggboil-edit.ofn --query "
                + "../sparql/terms.sparql tmp/terms.ttl");

        assertNotNull(why);
        assertTrue(why.contains("ttl"), why);
    }

    /**
     * Only SELECT: what ROBOT writes for the others is not reproduced here. The line names the
     * query file only, so this is found by the run, before it runs anything.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ASK { ?x ?p ?y }", "CONSTRUCT { ?x ?p ?y } WHERE { ?x ?p ?y }",
        "DESCRIBE <https://example.org/eggboil/EGGBOIL_0000001>"})
    void aQueryThatIsNoSelectIsRefusedBeforeAnythingRuns(String query) throws IOException {
        Files.write(new File(ontology, "tmp/other.sparql").toPath(),
                query.getBytes(StandardCharsets.UTF_8));
        String type = query.split(" ")[0];
        String line = "robot query -i tmp/merged-eggboil-edit.ofn -f csv --query "
                + "../sparql/simple-seed.sparql tmp/first.csv --query tmp/other.sparql "
                + "tmp/other.csv";

        assertEquals(1, RecipeShell.parse(line).run(OdkTools.forProject(), ontology, said));

        assertTrue(said.toString().contains(type), said.toString());
        assertFalse(new File(ontology, "tmp/first.csv").exists(), "nothing ran");
        assertFalse(new File(ontology, "tmp/other.csv").exists(), "nothing ran");
        List<String> absolute = RobotPaths.absolute(Arrays.asList(line.substring(
                "robot ".length()).split(" ")), ontology);
        String why = OdkRobot.whyNot(absolute);
        assertNotNull(why);
        assertTrue(why.contains(type), why);
    }

    private static final java.util.regex.Pattern NAMED_QUERY = java.util.regex.Pattern.compile(
            "(?:--query|-q|--select|-s) (\\S+)");

    /**
     * Every query file a {@code robot query} of the 96 recorded dry runs names is a SELECT - so
     * the refusal above costs ODK's recipes nothing.
     */
    @Test
    void everyQueryOdksRecipesNameIsASelect() {
        java.util.Set<String> checked = new java.util.TreeSet<String>();
        for (String seed : Goldens.seedNames()) {
            Map<String, byte[]> project = Goldens.seedTree(seed);
            for (String label : Goldens.makeLabels().keySet()) {
                for (String line : Goldens.makeDryRun(seed, label).replace("\\\n", " ")
                        .split("\n")) {
                    if (!line.contains("robot ") || !line.contains(" query ")) {
                        continue;
                    }
                    java.util.regex.Matcher named = NAMED_QUERY.matcher(line);
                    while (named.find()) {
                        String path = named.group(1);
                        if (!path.endsWith(".sparql")) {
                            continue;
                        }
                        assertTrue(path.startsWith("../sparql/"), line);
                        byte[] query = project.get("src/sparql/"
                                + path.substring("../sparql/".length()));
                        assertNotNull(query, seed + ": " + path);
                        assertTrue(org.apache.jena.query.QueryFactory.create(new String(query,
                                StandardCharsets.UTF_8)).isSelectType(), seed + ": " + path);
                        checked.add(path);
                    }
                }
            }
        }
        assertTrue(checked.size() >= 8, "query files checked: " + checked);
    }
}
