package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.odk.seed.Goldens;
import de.fizkarlsruhe.ise.ontoboard.odk.seed.OdkInstaller;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A test written from a question is one of ODK's checks: a file
 * {@code src/sparql/<name>-violation.sparql} and its name under {@code robot_report:
 * custom_sparql_checks} in the configuration - which ODK's {@code make test} runs, and nothing
 * else in {@code src/sparql}.
 */
class OdkChecksTest {

    /**
     * ODK's own checks open with no comment that says what they hold, so OntoBoard says it:
     * a sentence for every check ODK ships a query for. A new ODK that ships another fails
     * here until its sentence is written.
     */
    @Test
    void everyCheckOdkShipsHasItsRuleInWords() {
        for (String check : OdkChecks.ODK_SHIPS) {
            String rule = OdkChecks.ruleOf(check);
            assertNotNull(rule, check);
            assertTrue(rule.endsWith(".") && Character.isUpperCase(rule.charAt(0)),
                    check + ": " + rule);
        }
        assertEquals("No label contains a web address.", OdkChecks.ruleOf("label-with-iri"));
        assertNull(OdkChecks.ruleOf("check_labels"), "not ODK's: its own file says its rule");
        assertNull(OdkChecks.ruleOf(null));
    }

    private static final List<String> FOUR = Arrays.asList("owldef-self-reference", "iri-range",
            "label-with-iri", "multiple-replaced_by");

    private static final String NAME = "every_dataset_has_part";

    private static List<String> plus(List<String> list, String... more) {
        List<String> all = new ArrayList<String>(list);
        all.addAll(Arrays.asList(more));
        return all;
    }

    /** OntoBoard's configuration with its list cut off: a robot_report block without the key. */
    private static String withoutTheList() {
        String config = Goldens.config("eggboil");
        int at = config.indexOf("  custom_sparql_checks:");
        assertTrue(at > 0, "the list is the last thing in OntoBoard's configuration");
        return config.substring(0, at);
    }

    @Test
    void odksOwnAreItsFourInItsOrder() {
        assertEquals(FOUR, OdkChecks.ODKS_OWN);
    }

    /**
     * The list replaces ODK's default, so the first check added writes the default out first -
     * otherwise adding one check would quietly stop four.
     */
    @Test
    void theFirstQuestionWritesOdksFourBeforeIt() {
        String before = withoutTheList();
        assertEquals(FOUR, OdkChecks.declaredIn(before));

        String after = OdkChecks.add(before, NAME);

        assertEquals(plus(FOUR, NAME), OdkChecks.declaredIn(after));
        assertTrue(after.startsWith(before), "every byte that was there is still there:\n" + after);
        assertEquals("  custom_sparql_checks:\n"
                + "    - owldef-self-reference\n"
                + "    - iri-range\n"
                + "    - label-with-iri\n"
                + "    - multiple-replaced_by\n"
                + "    - " + NAME + "\n", after.substring(before.length()));
    }

    /** On OntoBoard's own configuration the list is there: one line is added, at its end. */
    @Test
    void aSecondQuestionIsAppended() {
        String before = Goldens.config("eggboil");
        assertEquals(plus(FOUR, "check_labels"), OdkChecks.declaredIn(before));

        String after = OdkChecks.add(before, NAME);

        assertEquals(plus(FOUR, "check_labels", NAME), OdkChecks.declaredIn(after));
        assertEquals(Collections.singletonList("    - " + NAME), added(before, after));
    }

    @Test
    void aNameThatIsThereIsNotAddedTwice() {
        String config = Goldens.config("eggboil");
        assertEquals(config, OdkChecks.add(config, "check_labels"));
        assertEquals(config, OdkChecks.add(config, "iri-range"));

        String once = OdkChecks.add(config, NAME);
        assertEquals(once, OdkChecks.add(once, NAME));
        assertEquals(plus(FOUR, "check_labels", NAME), OdkChecks.declaredIn(once));
    }

    /**
     * Without a robot_report block ODK takes all of ReportConfig's defaults: five checks, and
     * the report on the project's own terms. A block replaces all of them, so the block that is
     * added keeps both - measured in the goldens: the seeds of components, nodefaults and
     * oboimports run dc-properties as well.
     */
    @Test
    void withoutAReportBlockTheBlockIsAdded() {
        String before = Goldens.config("nodefaults");
        assertFalse(before.contains("robot_report"));
        assertEquals(plus(FOUR, "dc-properties"), OdkChecks.declaredIn(before));

        String after = OdkChecks.add(before, NAME);

        assertEquals(plus(FOUR, "dc-properties", NAME), OdkChecks.declaredIn(after));
        assertEquals(before
                + "robot_report:\n"
                + "  use_base_iris: TRUE\n"
                + "  custom_sparql_checks:\n"
                + "    - owldef-self-reference\n"
                + "    - iri-range\n"
                + "    - label-with-iri\n"
                + "    - multiple-replaced_by\n"
                + "    - dc-properties\n"
                + "    - " + NAME + "\n", after);
    }

    /** A file without a final line break keeps having none. */
    @Test
    void aFileWithoutAFinalLineBreakKeepsHavingNone() {
        String before = "id: plain\ntitle: \"Plain\"";

        String after = OdkChecks.add(before, NAME);

        assertTrue(after.startsWith(before + "\nrobot_report:\n"), after);
        assertTrue(after.endsWith("    - " + NAME), after);
        assertEquals(plus(FOUR, "dc-properties", NAME), OdkChecks.declaredIn(after));
    }

    /**
     * What ODK builds from the configuration changes by the new check and nothing else: every
     * file it seeds from it is the same, except the Makefile's hash of the configuration and its
     * list of checks - and, where the key was absent, the queries of ODK's own checks that it
     * does not run, which ODK writes only while no list names its checks.
     */
    @Test
    void whatOdkBuildsFromItChangesByTheNewCheckOnly() {
        for (String before : new String[] {Goldens.config("eggboil"), withoutTheList(),
                Goldens.config("nodefaults"), Goldens.config("oboimports")}) {
            String after = OdkChecks.add(before, NAME);
            Map<String, byte[]> was = OdkInstaller.renderSeed(before).files();
            Map<String, byte[]> is = OdkInstaller.renderSeed(after).files();

            TreeSet<String> gone = new TreeSet<String>(was.keySet());
            gone.removeAll(is.keySet());
            for (String path : gone) {
                String check = path.replaceAll("^src/sparql/(.*)-violation\\.sparql$", "$1");
                assertTrue(!check.equals(path) && OdkChecks.ODK_SHIPS.contains(check)
                        && !OdkChecks.declaredIn(after).contains(check),
                        "only a query of ODK's own that is not run goes: " + path);
            }
            TreeSet<String> come = new TreeSet<String>(is.keySet());
            come.removeAll(was.keySet());
            assertEquals(new TreeSet<String>(), come);

            for (String path : is.keySet()) {
                if (!was.containsKey(path) || path.endsWith("-odk.yaml")) {
                    continue;
                }
                String a = new String(was.get(path), StandardCharsets.UTF_8);
                String b = new String(is.get(path), StandardCharsets.UTF_8);
                if (!path.equals("src/ontology/Makefile")) {
                    if (!OdkChecks.isListed(before) && path.startsWith("src/sparql/")
                            && path.endsWith(OdkChecks.SUFFIX)
                            && OdkChecks.ODK_SHIPS.contains(path.substring(11,
                                    path.length() - OdkChecks.SUFFIX.length()))) {
                        // The template's last line break goes to the last of ODK's queries it
                        // writes - ODK's own eggboil seed ends multiple-replaced_by's with a
                        // blank line - so writing fewer of them moves it. The query is the same.
                        assertEquals(a.replaceAll("\n+$", "\n"), b.replaceAll("\n+$", "\n"),
                                path);
                    } else {
                        assertEquals(a, b, path);
                    }
                    continue;
                }
                String[] linesBefore = a.split("\n", -1);
                String[] linesAfter = b.split("\n", -1);
                assertEquals(linesBefore.length, linesAfter.length);
                for (int at = 0; at < linesBefore.length; at++) {
                    if (linesBefore[at].equals(linesAfter[at])) {
                        continue;
                    }
                    if (linesBefore[at].startsWith("CONFIG_HASH=")) {
                        continue;
                    }
                    assertTrue(linesBefore[at].startsWith("SPARQL_VALIDATION_CHECKS ="),
                            "the Makefile changed at line " + (at + 1) + ": " + linesAfter[at]);
                    assertEquals(linesBefore[at] + NAME + " ", linesAfter[at]);
                }
            }
        }
    }

    /** Where the block goes on, the list goes at its end - before the next key, not in it. */
    @Test
    void theListGoesAtTheEndOfTheReportBlock() {
        String before = "id: x\nrobot_report:\n  fail_on: ERROR\n  report_on:\n    - edit\n\n"
                + "# imports\nimport_group:\n  products: []\n";

        String after = OdkChecks.add(before, NAME);

        assertEquals("id: x\nrobot_report:\n  fail_on: ERROR\n  report_on:\n    - edit\n"
                + "  custom_sparql_checks:\n    - owldef-self-reference\n    - iri-range\n"
                + "    - label-with-iri\n    - multiple-replaced_by\n    - " + NAME + "\n\n"
                + "# imports\nimport_group:\n  products: []\n", after);
    }

    /** The indentation a file uses is the one the new lines get. */
    @Test
    void theFilesOwnIndentationIsCopied() {
        String before = "id: x\nrobot_report:\n    fail_on: ERROR\n    report_on:\n    - edit\n";

        String after = OdkChecks.add(before, NAME);

        assertEquals(before + "    custom_sparql_checks:\n    - owldef-self-reference\n"
                + "    - iri-range\n    - label-with-iri\n    - multiple-replaced_by\n"
                + "    - " + NAME + "\n", after);
    }

    /**
     * What cannot be changed safely is refused with the reason, rather than written: a key ODK
     * reads twice over, a list with no value, a robot_report written in braces.
     */
    @Test
    void aFileThatCannotBeChangedSafelyIsRefused() {
        for (String config : new String[] {
                "id: x\nrobot_report:\n  fail_on: ERROR\nrobot_report:\n  fail_on: WARN\n",
                "id: x\nrobot_report:\n  custom_sparql_checks:\n  fail_on: ERROR\n",
                "id: x\nrobot_report: {fail_on: ERROR}\n"}) {
            OdkYaml.UnreadableException refused = assertThrows(
                    OdkYaml.UnreadableException.class, () -> OdkChecks.add(config, NAME), config);
            assertNotNull(refused.getMessage());
        }
    }

    /** An explicitly empty list means no checks; the first one makes it a list of one. */
    @Test
    void anEmptyListBecomesAListOfOne() {
        String before = "id: x\nrobot_report:\n  fail_on: ERROR\n  custom_sparql_checks: []\n"
                + "  report_on:\n    - edit\n";
        assertEquals(Collections.<String>emptyList(), OdkChecks.declaredIn(before));

        String after = OdkChecks.add(before, NAME);

        assertEquals(Collections.singletonList(NAME), OdkChecks.declaredIn(after));
        assertEquals("id: x\nrobot_report:\n  fail_on: ERROR\n  custom_sparql_checks:\n"
                + "    - " + NAME + "\n  report_on:\n    - edit\n", after);
    }

    /** A list written in brackets gets the name inside them. */
    @Test
    void aListInBracketsKeepsItsBrackets() {
        String before = "id: x\nrobot_report:\n  custom_sparql_checks: [iri-range, mine]  # ours\n"
                + "  fail_on: ERROR\n";

        String after = OdkChecks.add(before, NAME);

        assertEquals(Arrays.asList("iri-range", "mine", NAME), OdkChecks.declaredIn(after));
        assertEquals("id: x\nrobot_report:\n  custom_sparql_checks: [iri-range, mine, " + NAME
                + "]  # ours\n  fail_on: ERROR\n", after);
    }

    @Test
    void windowsLineEndingsAreKept() {
        for (String config : new String[] {Goldens.config("eggboil"), withoutTheList(),
                Goldens.config("nodefaults")}) {
            String before = config.replace("\n", "\r\n");

            String after = OdkChecks.add(before, NAME);

            assertTrue(after.contains(NAME + "\r\n"), after);
            assertEquals(-1, after.replace("\r\n", "").indexOf('\n'), "no lone LF:\n" + after);
            assertEquals(-1, after.replace("\r\n", "").indexOf('\r'), "no lone CR:\n" + after);
            assertEquals(OdkChecks.add(config, NAME).replace("\n", "\r\n"), after);
        }
    }

    /**
     * The name becomes a file name and a word in ODK's Makefile, so only what is safe as both is
     * accepted - and a refusal says why.
     */
    @Test
    void aNameThatIsNoNameIsRefused() {
        StringBuilder long81 = new StringBuilder();
        while (long81.length() < 81) {
            long81.append('a');
        }
        for (String bad : new String[] {null, "", "  ", "Has Space", "../x", "a/b", "-dash",
                "_under", "UPPER", long81.toString()}) {
            String why = OdkChecks.whyNotAName(bad);
            assertNotNull(why, "refused: " + bad);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> OdkChecks.add(Goldens.config("eggboil"), bad), String.valueOf(bad));
            assertEquals(why, refused.getMessage());
        }
        assertTrue(OdkChecks.whyNotAName(long81.toString()).contains("80"),
                OdkChecks.whyNotAName(long81.toString()));
        for (String good : new String[] {NAME, "iri-range", "multiple-replaced_by", "007",
                long81.substring(1)}) {
            assertNull(OdkChecks.whyNotAName(good), good);
        }
    }

    /**
     * A name YAML would read as something else - a boolean, a number - is quoted, so ODK reads
     * the name.
     */
    @Test
    void aNameYamlWouldMisreadIsQuoted() {
        String after = OdkChecks.add(Goldens.config("eggboil"), "no");

        assertTrue(after.endsWith("    - \"no\"\n"), after);
        assertEquals(plus(FOUR, "check_labels", "no"), OdkChecks.declaredIn(after));
    }

    @Test
    void withoutTheKeyOdkRunsItsFour() {
        assertEquals(FOUR, OdkChecks.declaredIn(withoutTheList()));
        assertEquals(FOUR, OdkChecks.declaredIn(Goldens.config("artefacts")));
        assertFalse(OdkChecks.isListed(withoutTheList()));
        assertTrue(OdkChecks.isListed(Goldens.config("eggboil")));
        assertEquals(plus(FOUR, "dc-properties"),
                OdkChecks.declaredIn(Goldens.config("components")));
        assertEquals(Arrays.asList("owldef-self-reference", "dc-properties", "iri-range-advanced"),
                OdkChecks.declaredIn(Goldens.config("custombase")));
    }

    /** What declaredIn says is what ODK's own Makefile runs, for every config the goldens have. */
    @Test
    void whatIsDeclaredIsWhatOdksMakefileRuns() {
        for (String name : Goldens.configNames()) {
            String makefile = new String(Goldens.seedTree(name).get("src/ontology/Makefile"),
                    StandardCharsets.UTF_8);
            String line = null;
            for (String each : makefile.split("\n")) {
                if (each.startsWith("SPARQL_VALIDATION_CHECKS =")) {
                    line = each;
                }
            }
            assertNotNull(line, name);
            List<String> runs = Arrays.asList(
                    line.substring(line.indexOf('=') + 1).trim().split("\\s+"));
            assertEquals(runs, OdkChecks.declaredIn(Goldens.config(name)), name);
        }
    }

    @Test
    void aCheckIsAFileInSrcSparql(@TempDir File root) {
        assertEquals(new File(new File(root, "src/sparql"), NAME + "-violation.sparql"),
                OdkChecks.fileFor(root, NAME));
        assertThrows(IllegalArgumentException.class, () -> OdkChecks.fileFor(root, "../x"));
        assertThrows(IllegalArgumentException.class, () -> OdkChecks.fileFor(root, "a b"));
    }

    /**
     * The project's checks are the declared ones whose file is there, in the list's order - a
     * stray query in src/sparql is not one, and nor is a name without its file.
     */
    @Test
    void onlyDeclaredChecksAreTheProjectsChecks(@TempDir File root) throws IOException {
        String config = OdkChecks.add(OdkChecks.add(Goldens.config("eggboil"), "zeta"), "alpha");
        for (String name : new String[] {"alpha", "zeta", "iri-range", "owldef-self-reference",
                "other"}) {
            write(OdkChecks.fileFor(root, name), "SELECT * WHERE { }\n");
        }
        write(new File(root, "src/sparql/mine.rq"), "SELECT * WHERE { }\n");
        write(new File(root, "src/sparql/README.md"), "not a check\n");

        List<String> names = new ArrayList<String>();
        for (File file : OdkChecks.filesIn(root, config)) {
            names.add(file.getName());
        }

        assertEquals(Arrays.asList("owldef-self-reference-violation.sparql",
                "iri-range-violation.sparql", "zeta-violation.sparql", "alpha-violation.sparql"),
                names, "declared, with a file, in the list's order");
        assertEquals(Arrays.asList("label-with-iri", "multiple-replaced_by", "check_labels"),
                OdkChecks.missingIn(root, config), "declared, without a file");
        List<String> undeclared = new ArrayList<String>();
        for (File file : OdkChecks.undeclaredIn(root, config)) {
            undeclared.add(file.getName());
        }
        assertEquals(Arrays.asList("other-violation.sparql"), undeclared,
                "a .rq file is a question, not a check somebody forgot to list");
        assertTrue(OdkChecks.filesIn(new File(root, "nowhere"), config).isEmpty());
    }

    private static void write(File file, String text) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    /** The lines {@code after} has that {@code before} has not, when that is all that changed. */
    private static List<String> added(String before, String after) {
        List<String> was = new ArrayList<String>(Arrays.asList(before.split("\n", -1)));
        List<String> is = new ArrayList<String>(Arrays.asList(after.split("\n", -1)));
        int from = 0;
        while (from < was.size() && from < is.size() && was.get(from).equals(is.get(from))) {
            from++;
        }
        int fromEnd = 0;
        while (fromEnd < was.size() - from && fromEnd < is.size() - from
                && was.get(was.size() - 1 - fromEnd).equals(is.get(is.size() - 1 - fromEnd))) {
            fromEnd++;
        }
        assertEquals(was.size(), from + fromEnd, "nothing that was there is gone or changed");
        return is.subList(from, is.size() - fromEnd);
    }
}
