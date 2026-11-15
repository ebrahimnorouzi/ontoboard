package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.make.MakeFile;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project's questions - the {@code .rq} files in {@code src/sparql}, each a query whose
 * every row breaks something the ontology is to hold - are asked of the ontology WITH its
 * imports, as OntoBoard 1.135.x asked them. ODK's own checks ({@code sparql_test}) read the
 * edit file without its imports, where a question about an imported term has no answer: the
 * recorded 1.135.2 project's "every dataset has part" passes with its import and names two
 * terms without it, in ODK's own container.
 *
 * <p>So the questions have a rule of their own, which OntoBoard keeps in the project's own
 * makefile - where ODK means a project's additions to be - and hangs on ODK's {@code test}.
 * make runs it in ODK's container and in continuous integration; OntoBoard's own engine runs
 * it too, as long as it is the rule as OntoBoard writes it.
 */
class OdkQuestionsTest {

    private static final String SEEDS = "## Customize Makefile settings for eggboil\n## \n"
            + "## If you need to customize your Makefile, make\n"
            + "## changes here rather than in the main Makefile\n";

    /** One time for every file, as the seeds were recorded: make decides by file times. */
    private static final long SAME_TIME = 1_762_862_400_000L;

    private static final BooleanSupplier NEVER = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            return false;
        }
    };

    @TempDir
    File temp;

    // ------------------------------------------------------------------ the rule, as text

    @Test
    void theRuleIsAddedAtTheEndAndWhatWasThereStays() {
        String with = OdkQuestions.with(SEEDS);

        assertEquals(SEEDS + "\n" + OdkQuestions.rule(), with);
        assertTrue(OdkQuestions.isIn(with));
        assertFalse(OdkQuestions.isIn(SEEDS));
        assertFalse(OdkQuestions.isChangedIn(with));
        assertFalse(OdkQuestions.isChangedIn(SEEDS));
    }

    /** What the rule says, for make and for a person who opens the file. */
    @Test
    void theRuleAsksEveryRqFileOfTheOntologyWithItsImportsAndHangsOnTest() {
        String rule = OdkQuestions.rule();

        assertTrue(rule.startsWith("# --- OntoBoard: questions "), rule);
        assertTrue(rule.endsWith("# --- OntoBoard: end of questions ---\n"), rule);
        assertTrue(rule.contains("\nQUESTIONS = $(wildcard ../sparql/*.rq)\n"), rule);
        assertTrue(rule.contains("\n\t$(ROBOT) merge --input $(EDIT_PREPROCESSED) verify "
                + "--queries $(QUESTIONS) -O $(REPORTDIR)\n"), rule);
        assertTrue(rule.contains("\ntest: question_test\n"), rule);
        assertTrue(rule.contains("WITH its imports"), rule);
        for (String line : rule.split("\n")) {
            assertTrue(line.length() <= 100, "no wider than the Makefile's own lines: " + line);
            assertFalse(line.endsWith(" "), "no blank at a line's end: [" + line + "]");
        }
    }

    @Test
    void addingItTwiceChangesNothing() {
        String once = OdkQuestions.with(SEEDS);

        assertEquals(once, OdkQuestions.with(once));
    }

    /** A file that ends without a line break gets one before the rule. */
    @Test
    void aFileWithoutALastLineBreakGetsOne() {
        assertEquals("all: x\n\n" + OdkQuestions.rule(), OdkQuestions.with("all: x"));
        assertEquals(OdkQuestions.rule(), OdkQuestions.with(""));
        assertEquals("all: x\n\n" + OdkQuestions.rule(), OdkQuestions.with("all: x\n\n"));
    }

    @Test
    void aFileWithWindowsLineEndingsKeepsThem() {
        String windows = SEEDS.replace("\n", "\r\n");

        String with = OdkQuestions.with(windows);

        assertEquals(windows + "\r\n" + OdkQuestions.rule().replace("\n", "\r\n"), with);
        assertTrue(OdkQuestions.isIn(with), "the rule is the rule whatever its line endings");
    }

    /** What somebody wrote after the rule stays after it. */
    @Test
    void whatComesAfterTheRuleStays() {
        String more = OdkQuestions.with(SEEDS) + "\nmine:\n\techo mine\n";

        assertTrue(OdkQuestions.isIn(more));
        assertEquals(more, OdkQuestions.with(more));
    }

    /**
     * A rule somebody changed is no longer the rule OntoBoard's engine runs. It is known as
     * changed, and writing it again puts it right - at its place, what is around it kept.
     */
    @Test
    void aRuleSomebodyChangedIsKnownAndIsPutRightAgain() {
        String good = OdkQuestions.with(SEEDS) + "\nmine:\n\techo mine\n";
        String changed = good.replace("$(wildcard ../sparql/*.rq)",
                "$(wildcard ../sparql/cq*.rq)");

        assertFalse(OdkQuestions.isIn(changed));
        assertTrue(OdkQuestions.isChangedIn(changed));
        assertEquals(good, OdkQuestions.with(changed));
    }

    /** The first marker without the last: not touched in between; the rule goes to the end. */
    /**
     * The rule is told by what it says, not by where one starts: a rule with one line
     * changed has as many lines as before, and lines of the project's own after it - and is
     * not the rule (mutant Q01 of the round on this step said it was, and lived).
     */
    @Test
    void aChangedRuleOfTheSameLengthIsNotTheRule() {
        String changed = OdkQuestions.rule().replace("merge --input",
                "merge --catalog other.xml --input");
        assertFalse(changed.equals(OdkQuestions.rule()), "one line of it is another");
        String file = "# mine\n\n" + changed + "\nmine:\n\techo mine\n\techo more\n";

        assertFalse(OdkQuestions.isIn(file));
        assertTrue(OdkQuestions.isChangedIn(file));
        assertNull(OdkQuestions.linesIn(file));
        assertNull(OdkQuestions.alone(file), "a changed rule is the project's own");
        String putRight = OdkQuestions.with(file);
        assertTrue(OdkQuestions.isIn(putRight), putRight);
        assertFalse(putRight.contains("other.xml"), putRight);
        assertTrue(putRight.startsWith("# mine\n\n"), putRight);
        assertTrue(putRight.endsWith("\nmine:\n\techo mine\n\techo more\n"), putRight);
    }

    @Test
    void aRuleCutOffIsLeftAndAWholeOneAdded() {
        String cut = SEEDS + "\n" + OdkQuestions.rule().substring(0,
                OdkQuestions.rule().indexOf("test: question_test"));

        assertTrue(OdkQuestions.isChangedIn(cut));
        String with = OdkQuestions.with(cut);
        assertTrue(with.startsWith(cut), with);
        assertTrue(with.endsWith(OdkQuestions.rule()), with);
        // The whole one is found behind the one cut off - every line that begins a rule is
        // looked at, not only the first - so it is there now, and is not added a second time
        // (mutant Q01 of the round on this step looked at the first alone, and lived).
        assertTrue(OdkQuestions.isIn(with), "the whole rule is found after the one cut off");
        assertFalse(OdkQuestions.isChangedIn(with));
        assertEquals(with, OdkQuestions.with(with), "adding it again changes nothing");
        int[] place = OdkQuestions.linesIn(with);
        assertNotNull(place);
        assertEquals(with.split("\n", -1).length - 1, place[1], "the rule's last line is the "
                + "file's last");
    }

    /** For the engine's comparison: the rule alone, at the lines it has in the file. */
    @Test
    void aloneIsTheRuleAtItsLinesAndNothingElse() {
        String more = "mine:\n\techo mine\n" + OdkQuestions.with(SEEDS) + "\nother = 1\n";

        String alone = OdkQuestions.alone(more);

        String[] all = more.split("\n", -1);
        String[] kept = alone.split("\n", -1);
        assertEquals(all.length, kept.length, "the same number of lines");
        int[] place = OdkQuestions.linesIn(more);
        assertNotNull(place);
        for (int i = 0; i < all.length; i++) {
            boolean inside = i + 1 >= place[0] && i + 1 <= place[1];
            assertEquals(inside ? all[i] : "", kept[i], "line " + (i + 1));
        }
        assertEquals("# --- OntoBoard: end of questions ---", all[place[1] - 1]);
        assertNull(OdkQuestions.linesIn(SEEDS));
        assertNull(OdkQuestions.alone(SEEDS), "no rule: nothing of OntoBoard's in the file");
        assertNull(OdkQuestions.alone(more.replace("*.rq", "cq*.rq")),
                "a changed rule is the project's own, like anything else in the file");
        assertNull(OdkQuestions.alone(null));
    }

    // ------------------------------------------------------------------ the files

    private File project(String seed, String id) throws IOException {
        File root = new File(temp, id);
        for (Map.Entry<String, byte[]> file : Goldens.seedTree(seed).entrySet()) {
            File target = new File(root, file.getKey());
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), file.getValue());
        }
        // What OntoBoard adds to ODK's seed, and the seed's configuration lists.
        write(new File(root, OdkExtras.LABEL_CHECK_FILE), OdkExtras.checkLabels());
        sameAge(root);
        return root;
    }

    private static void sameAge(File folder) {
        File[] children = folder.listFiles();
        for (File child : children == null ? new File[0] : children) {
            if (child.isDirectory()) {
                sameAge(child);
            }
            assertTrue(child.setLastModified(SAME_TIME), child.getPath());
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(SAME_TIME));
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void theQuestionsAreTheRqFilesOfSrcSparql() throws IOException {
        File root = project("eggboil", "eggboil");
        assertEquals(new ArrayList<File>(), OdkQuestions.filesIn(root),
                "ODK's own queries end in .sparql and .ru");

        write(new File(root, "src/sparql/b_question.rq"), "SELECT ?x WHERE { ?x a ?y } LIMIT 0\n");
        write(new File(root, "src/sparql/a_question.rq"), "SELECT ?x WHERE { ?x a ?y } LIMIT 0\n");

        assertEquals(Arrays.asList(new File(root, "src/sparql/a_question.rq").getAbsoluteFile(),
                new File(root, "src/sparql/b_question.rq").getAbsoluteFile()),
                OdkQuestions.filesIn(root));
        assertEquals(new ArrayList<File>(), OdkQuestions.filesIn(new File(temp, "nothing")));
    }

    @Test
    void theRuleIsWrittenIntoTheProjectsOwnMakefileOnce() throws IOException {
        File root = project("eggboil", "eggboil");
        File own = new File(root, "src/ontology/eggboil.Makefile");
        byte[] makefile = Files.readAllBytes(new File(root, "src/ontology/Makefile").toPath());

        assertFalse(OdkQuestions.isKeptIn(root));
        assertEquals(own.getAbsoluteFile(), OdkQuestions.ensure(root));
        assertTrue(OdkQuestions.isKeptIn(root));
        assertEquals(SEEDS + "\n" + OdkQuestions.rule(), text(own));
        assertNull(OdkQuestions.ensure(root), "there already: nothing is written");
        assertArrayEquals(makefile, Files.readAllBytes(new File(root, "src/ontology/Makefile")
                .toPath()), "ODK's Makefile is not touched");
    }

    /** A project whose own makefile is gone gets one with the rule: ODK's Makefile includes it. */
    @Test
    void aProjectWithoutItsOwnMakefileGetsOneWithTheRule() throws IOException {
        File root = project("eggboil", "eggboil");
        File own = new File(root, "src/ontology/eggboil.Makefile");
        Files.delete(own.toPath());

        assertEquals(own.getAbsoluteFile(), OdkQuestions.ensure(root));
        assertEquals(OdkQuestions.rule(), text(own));
    }

    // ------------------------------------------------------------------ OntoBoard's own engine

    private static final String PASSES = "SELECT ?x WHERE { ?x a ?y } LIMIT 0\n";

    @Test
    void theEngineRunsTheProjectsTestWithTheRule() throws IOException {
        File root = project("eggboil", "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        write(new File(root, "src/sparql/a_question.rq"), PASSES);
        OdkQuestions.ensure(root);
        sameAge(root);

        assertNull(FallbackEligibility.why(edit, "test_fast"));
        assertNull(FallbackEligibility.why(edit, "prepare_release_fast"),
                "a release runs the test, and with it the questions");
        List<String> commands = new ArrayList<String>();
        collect(FallbackEligibility.of(edit, "test_fast",
                Collections.<String, String>emptyMap()).plan(), commands);
        String asked = null;
        for (String command : commands) {
            if (command.contains("--queries ../sparql/a_question.rq")) {
                asked = command;
            }
        }
        assertNotNull(asked, commands.toString());
        assertTrue(asked.contains("merge --input tmp/eggboil-preprocess.owl verify"), asked);
        assertTrue(asked.endsWith("-O reports"), asked);
    }

    private static void collect(List<MakeFile.Step> steps, List<String> into) {
        for (MakeFile.Step step : steps) {
            if (step.command() != null) {
                into.add(step.command());
            }
            collect(step.steps(), into);
        }
    }

    /** With the rule and no question, nothing is asked and nothing stops. */
    @Test
    void withTheRuleAndNoQuestionNothingIsAsked() throws IOException {
        File root = project("eggboil", "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        OdkQuestions.ensure(root);
        sameAge(root);

        assertNull(FallbackEligibility.why(edit, "test_fast"));
        List<String> commands = new ArrayList<String>();
        collect(FallbackEligibility.of(edit, "test_fast",
                Collections.<String, String>emptyMap()).plan(), commands);
        for (String command : commands) {
            assertFalse(command.contains("--queries ../sparql/") && command.contains(".rq"),
                    command);
        }
        OdkEngine.Outcome ran = OdkEngine.run(edit, "test_fast",
                Collections.<String, String>emptyMap(), NEVER);
        assertTrue(ran.isOk(), ran.getFailure());
    }

    /** A rule somebody changed is theirs: for make, and named with its line. */
    @Test
    void aChangedRuleIsTheProjectsOwnAndIsNotRunByTheEngine() throws IOException {
        File root = project("eggboil", "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        File own = new File(root, "src/ontology/eggboil.Makefile");
        write(new File(root, "src/sparql/a_question.rq"), PASSES);
        OdkQuestions.ensure(root);
        write(own, text(own).replace("-O $(REPORTDIR)", "-O $(REPORTDIR) --fail-on-violation "
                + "false"));
        sameAge(root);

        String why = FallbackEligibility.why(edit, "test_fast");

        assertNotNull(why);
        assertTrue(why.contains("eggboil.Makefile:"), why);
        assertTrue(why.contains("question_test"), why);
        assertTrue(why.contains("Write a test from a question"), "what puts it right: " + why);
    }

    /** Something else of the project's own that the test reaches is still refused. */
    @Test
    void anotherRuleOfTheProjectsOwnIsStillRefused() throws IOException {
        File root = project("eggboil", "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        File own = new File(root, "src/ontology/eggboil.Makefile");
        write(new File(root, "src/sparql/a_question.rq"), PASSES);
        OdkQuestions.ensure(root);
        write(own, text(own) + "\ntest: mine\nmine:\n\techo mine\n");
        sameAge(root);

        String why = FallbackEligibility.why(edit, "test_fast");

        assertNotNull(why);
        assertTrue(why.contains("rule of the project's own for mine"), why);
    }

    /** A setting of the project's own that ODK's recipes read is still refused, rule or not. */
    @Test
    void aSettingOfTheProjectsOwnIsStillRefused() throws IOException {
        File root = project("eggboil", "eggboil");
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        File own = new File(root, "src/ontology/eggboil.Makefile");
        OdkQuestions.ensure(root);
        write(own, "REPORT_FAIL_ON = none\n" + text(own));
        sameAge(root);

        String why = FallbackEligibility.why(edit, "test_fast");

        assertNotNull(why);
        assertTrue(why.contains("REPORT_FAIL_ON"), why);
    }

    // ------------------------------------------------------------------ what is asked, with imports

    /** The project 1.135.2 made, carried over: its question needs its import to pass. */
    private File carriedOver() throws IOException {
        File root = new File(temp, "eggboil");
        OdkMigrationTest.copy(MigrationFixtureTest.fixture("odk/migration/eggboil-1.135.2"), root);
        OdkMigration.convert(root, OdkMigration.plan(root));
        OdkUpdate.apply(root, OdkUpdate.plan(root));
        return root;
    }

    @Test
    void aQuestionAboutAnImportedTermIsAskedWithTheImport() throws IOException {
        File root = carriedOver();
        File edit = new File(root, "src/ontology/eggboil-edit.owl");

        OdkEngine.Outcome ran = OdkEngine.run(edit, "test_fast",
                Collections.<String, String>emptyMap(), NEVER);

        String said = String.join("\n", ran.getTranscript());
        assertTrue(ran.isOk(), ran.getFailure() + "\n" + said);
        assertTrue(said.contains("PASS Rule ../sparql/every_dataset_has_part.rq: 0 violation(s)"),
                said);
        assertTrue(said.contains("PASS Rule ../sparql/check_labels.rq: 0 violation(s)"), said);
    }

    @Test
    void aQuestionThatIsBrokenStopsTheTestAndNamesWhatBreaksIt() throws IOException {
        File root = carriedOver();
        File edit = new File(root, "src/ontology/eggboil-edit.owl");
        write(new File(root, "src/sparql/nothing_is_an_egg.rq"),
                "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                        + "SELECT ?term WHERE { ?term rdfs:label \"egg\"@en }\n");

        OdkEngine.Outcome ran = OdkEngine.run(edit, "test_fast",
                Collections.<String, String>emptyMap(), NEVER);

        String said = String.join("\n", ran.getTranscript());
        assertFalse(ran.isOk(), said);
        assertTrue(said.contains("FAIL Rule ../sparql/nothing_is_an_egg.rq: 1 violation(s)"),
                said);
        assertTrue(ran.getFailure().contains("question_test"), ran.getFailure());
    }
}
