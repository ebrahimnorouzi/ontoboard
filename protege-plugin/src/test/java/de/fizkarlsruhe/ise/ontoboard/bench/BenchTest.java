package de.fizkarlsruhe.ise.ontoboard.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The measurements, as a command somebody else can run.
 *
 * <p>These are mostly about the properties a reproduction package needs and a GUI does not:
 * that the same input gives the same bytes, that the input is never written to, that an exit
 * code means something, and that the version in the output is the version of the jar. The
 * numbers themselves are the shipped code's, tested where that code is tested.
 */
class BenchTest {

    private static final String MW = "https://nfdi.fiz-karlsruhe.de/matwerk/msekg/";
    private static final String CURLY = String.valueOf((char) 0x201c);

    /** One run, captured. */
    private static final class Run {
        private final int status;
        private final String out;
        private final String problems;

        Run(int status, String out, String problems) {
            this.status = status;
            this.out = out;
            this.problems = problems;
        }

        List<String> lines() {
            List<String> lines = new ArrayList<String>();
            for (String line : out.split("\n")) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
            return lines;
        }

        String valueOf(String name) {
            for (String line : lines()) {
                String[] parts = line.split("\t", -1);
                if (parts.length > 1 && parts[0].equals(name)) {
                    return parts[1];
                }
            }
            throw new AssertionError("no row called " + name + " in:\n" + out);
        }
    }

    private static Run bench(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream problems = new ByteArrayOutputStream();
        PrintStream toOut = new PrintStream(out, true);
        PrintStream toProblems = new PrintStream(problems, true);
        int status = Bench.run(args, toOut, toProblems);
        toOut.flush();
        toProblems.flush();
        return new Run(status, out.toString(), problems.toString());
    }

    private static File folderWith(File dir, String name, String content) throws Exception {
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(Charset.forName("UTF-8")));
        return file;
    }

    /** Two sheets: one clean row, one with two things wrong, and a contradiction between them. */
    private static void twoSheets(File dir) throws Exception {
        folderWith(dir, "organization.tsv",
                "#\tTYPE\tInstitution name\n"
                + "ID\tTYPE\tA rdfs:label\n"
                + MW + "1\towl:NamedIndividual\tMax" + CURLY + "Planck  Society\n"
                + MW + "2\towl:NamedIndividual\tFraunhofer-Gesellschaft\n");
        folderWith(dir, "others.tsv",
                "#\tTYPE\tInstitution name\n"
                + "ID\tTYPE\tA rdfs:label\n"
                + MW + "1\towl:Class\tMax Planck Society\n");
    }

    // ---------- the shape of the thing ----------

    @Test
    void theVersionIsTheJarsVersionAndNotALiteral() {
        Run run = bench("version");

        assertEquals(0, run.status);
        assertNotEquals("unknown", run.out.trim(),
                "the filtered build properties were not on the test classpath, so the number "
                        + "printed beside every measurement would have been a guess");
        assertTrue(run.out.trim().matches("\\d+\\.\\d+\\.\\d+"), run.out);
    }

    @Test
    void everyCommandNamesItselfAndTheInputAtTheTop(@TempDir File dir) throws Exception {
        twoSheets(dir);

        for (String command : Bench.commands()) {
            if ("version".equals(command) || "roundtrip".equals(command)) {
                continue;
            }
            Run run = bench(command, dir.getAbsolutePath());
            assertEquals(0, run.status, command + " should have succeeded: " + run.problems);
            assertTrue(run.lines().get(0).startsWith("# ontoboard "),
                    command + " must say what produced the numbers: " + run.lines().get(0));
            assertTrue(run.lines().get(0).contains(command), run.lines().get(0));
            assertTrue(run.lines().get(0).contains(dir.getName()), run.lines().get(0));
        }
    }

    /** A measurement that changes between runs is not a measurement. */
    @Test
    void thesameInputGivesTheSameBytesTwice(@TempDir File dir) throws Exception {
        twoSheets(dir);

        for (String command : new String[] {"corpus", "summary", "audit", "conflicts", "repair"}) {
            assertEquals(bench(command, dir.getAbsolutePath()).out,
                    bench(command, dir.getAbsolutePath()).out,
                    command + " is not reproducible");
        }
    }

    @Test
    void nothingIsEverWrittenToTheInput(@TempDir File dir) throws Exception {
        twoSheets(dir);
        File sheet = new File(dir, "organization.tsv");
        byte[] before = Files.readAllBytes(sheet.toPath());
        long when = sheet.lastModified();

        bench("repair", dir.getAbsolutePath());

        assertEquals(new String(before, "UTF-8"),
                new String(Files.readAllBytes(sheet.toPath()), "UTF-8"),
                "repair must change only the copy in memory");
        assertEquals(when, sheet.lastModified());
        assertEquals(2, dir.listFiles().length, "and must not leave anything behind either");
    }

    // ---------- the numbers ----------

    @Test
    void corpusCountsTabsRowsCellsAndIdentifiers(@TempDir File dir) throws Exception {
        twoSheets(dir);

        Run run = bench("corpus", dir.getAbsolutePath());

        assertEquals("2", run.valueOf("tabs read"));
        assertEquals("2", run.valueOf("robot template tabs"));
        assertEquals("0", run.valueOf("other tabs"));
        assertEquals("3", run.valueOf("data rows in template tabs"));
        assertEquals("3", run.valueOf("identifiers"));
        assertEquals("3", run.valueOf("identifiers with a label"));
        assertEquals("0", run.valueOf("tabs that could not be opened"));
    }

    @Test
    void summaryCountsFindingsAndHowManyCarryAReplacement(@TempDir File dir) throws Exception {
        twoSheets(dir);

        Run run = bench("summary", dir.getAbsolutePath());

        assertEquals("1", run.valueOf("WHITESPACE"));
        assertEquals("1", run.valueOf("TYPOGRAPHIC_CHARACTER"));
        assertTrue(Integer.parseInt(run.valueOf("CONFLICTING_DEFINITION").split("\t")[0]) >= 2,
                "the two sheets type and name " + MW + "1 differently: " + run.out);
    }

    @Test
    void conflictsListsOnlyTheCrossSheetContradictions(@TempDir File dir) throws Exception {
        twoSheets(dir);

        Run run = bench("conflicts", dir.getAbsolutePath());

        List<String> lines = run.lines();
        assertEquals("kind\tsheet\trow\tcolumn\theading\tcell\tsuggestion\tmessage", lines.get(1));
        for (int at = 2; at < lines.size(); at++) {
            assertTrue(lines.get(at).startsWith("CONFLICTING_DEFINITION"), lines.get(at));
        }
        assertTrue(lines.size() > 2, "there is a contradiction in the fixture: " + run.out);
    }

    @Test
    void repairReportsBeforeAfterAndWhetherASecondRunWouldDoAnything(@TempDir File dir)
            throws Exception {
        twoSheets(dir);

        Run run = bench("repair", dir.getAbsolutePath());

        assertEquals("1", run.valueOf("cells changed"), "one cell, fixed twice over: " + run.out);
        assertEquals("2", run.valueOf("passes before nothing more changed"));
        assertEquals("0", run.valueOf("cells a second run would change"),
                "the repair has to settle, or the number in a paper means nothing");
        assertEquals("0", run.valueOf("left alone, changed by something other than this fix"));
        assertTrue(run.valueOf("one undo for the batch").contains("audit"),
                run.valueOf("one undo for the batch"));
    }

    @Test
    void auditWritesOneLinePerFindingWithNoStrayTabsOrNewlines(@TempDir File dir)
            throws Exception {
        twoSheets(dir);

        Run run = bench("audit", dir.getAbsolutePath());

        List<String> lines = run.lines();
        int columns = lines.get(1).split("\t", -1).length;
        assertEquals(8, columns);
        for (int at = 2; at < lines.size(); at++) {
            assertEquals(columns, lines.get(at).split("\t", -1).length,
                    "a message with a tab in it would shift every column: " + lines.get(at));
        }
    }

    // ---------- being told it cannot do something ----------

    @Test
    void acommandThatDoesNotExistSaysSoAndFails() {
        Run run = bench("measure-everything", ".");

        assertEquals(2, run.status);
        assertTrue(run.problems.contains("no command called measure-everything"), run.problems);
        assertTrue(run.problems.contains("bench corpus"), "and offers the ones there are");
    }

    @Test
    void amissingInputIsNamed(@TempDir File dir) {
        Run run = bench("summary", new File(dir, "nope.xlsx").getAbsolutePath());

        assertEquals(2, run.status);
        assertTrue(run.problems.contains("no such file or folder"), run.problems);
    }

    @Test
    void acommandWithNoInputSaysWhatItNeeded() {
        Run run = bench("summary");

        assertEquals(2, run.status);
        assertTrue(run.problems.contains("needs a workbook or a folder"), run.problems);
    }

    @Test
    void roundtripNeedsTheWorkbookBecauseThatIsWhatItComparesAgainst(@TempDir File dir)
            throws Exception {
        twoSheets(dir);

        Run run = bench("roundtrip", dir.getAbsolutePath());

        assertEquals(2, run.status);
        assertTrue(run.problems.contains(".xlsx"), run.problems);
    }

    @Test
    void noArgumentsPrintsTheCommandsAndFails() {
        Run run = bench();

        assertEquals(2, run.status);
        assertTrue(run.out.contains("bench roundtrip"), run.out);
    }

    @Test
    void askingForHelpIsNotAFailure() {
        Run run = bench("--help");

        assertEquals(0, run.status);
        assertTrue(run.out.contains("bench summary"), run.out);
        assertFalse(run.out.contains("Exception"), run.out);
    }

    /** It has to run where there is no display, which is the only place it will be run. */
    @Test
    void nothingHereNeedsAScreen(@TempDir File dir) throws Exception {
        twoSheets(dir);

        for (String command : new String[] {"corpus", "summary", "audit", "conflicts", "repair"}) {
            assertEquals(0, bench(command, dir.getAbsolutePath()).status,
                    command + " must not need a display");
        }
    }
}
