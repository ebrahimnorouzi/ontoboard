package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One task run both ways, compared and timed - with a fake pair of routes, no Docker: what a row
 * says for an agreement, a difference and a refusal, how the counted runs are summed up, and the
 * text of the table the live test writes.
 */
class SideBySideTest {

    @TempDir
    File scratch;

    /** A clock that says these instants, one each time it is read. */
    private static LongSupplier clock(Long... instants) {
        final LinkedList<Long> said = new LinkedList<Long>(Arrays.asList(instants));
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return said.removeFirst();
            }
        };
    }

    /** The project: one file saying which copy it is. */
    private static final SideBySide.Fixture ONE_FILE = new SideBySide.Fixture() {
        @Override
        public void writeTo(File folder) throws Exception {
            Files.createDirectories(folder.toPath());
            Files.write(new File(folder, "demo.owl").toPath(),
                    "Ontology(<https://example.org/demo.owl>)\n".getBytes(StandardCharsets.UTF_8));
        }
    };

    /** A route that answers this text, whatever the project. */
    private static SideBySide.Route<String> saying(final String text) {
        return new SideBySide.Route<String>() {
            @Override
            public String run(File project) {
                return text;
            }
        };
    }

    private static final SideBySide.Comparison<String> TEXTS =
            new SideBySide.Comparison<String>() {
                @Override
                public String firstDifference(String odks, String ontoBoards) {
                    return odks.equals(ontoBoards) ? null
                            : "the text: ODK's \"" + odks + "\", OntoBoard's \"" + ontoBoards
                                    + "\"";
                }
            };

    @Test
    void aRowForAgreement() throws Exception {
        SideBySide.Row row = SideBySide.run("Test, fast", "egg", 0, scratch, ONE_FILE,
                saying("PASS"), saying("PASS"), TEXTS, clock(1000L, 2200L, 3000L, 3300L));

        assertEquals("Test, fast", row.getTask());
        assertEquals("egg", row.getProject());
        assertTrue(row.agrees());
        assertFalse(row.isRefused());
        assertEquals("", row.getDiffers());
        assertEquals("", row.getNote());
        assertEquals(0, row.getRuns());
        assertEquals(1200, row.getOdk().getFirst());
        assertEquals(300, row.getOntoBoard().getFirst());
        assertNull(row.getOdk().getMedian(), "no run was counted");
    }

    @Test
    void aRowForANamedDifference() throws Exception {
        SideBySide.Row row = SideBySide.run("Release, fast", "egg", 0, scratch, ONE_FILE,
                saying("12 axioms"), saying("11 axioms"), TEXTS, clock(0L, 2000L, 2000L, 2500L));

        assertFalse(row.agrees());
        assertFalse(row.isRefused());
        assertEquals("the text: ODK's \"12 axioms\", OntoBoard's \"11 axioms\"", row.getDiffers());
        assertEquals(2000, row.getOdk().getFirst());
        assertEquals(500, row.getOntoBoard().getFirst());
    }

    /** A refusal is a result: the row says why, ODK's time is kept, and nothing is compared. */
    @Test
    void aRowForARefusal() throws Exception {
        SideBySide.Route<String> refusing = new SideBySide.Route<String>() {
            @Override
            public String run(File project) throws SideBySide.Refused {
                throw new SideBySide.Refused("it needs the network");
            }
        };

        SideBySide.Row row = SideBySide.run("Release, full", "egg", 0, scratch, ONE_FILE,
                saying("released"), refusing, TEXTS, clock(0L, 9000L, 9000L, 9001L));

        assertTrue(row.isRefused());
        assertFalse(row.agrees());
        assertEquals("refused: it needs the network", row.getNote());
        assertEquals("", row.getDiffers());
        assertEquals(9000, row.getOdk().getFirst());
        assertNull(row.getOntoBoard(), "a refusal is not timed");
    }

    /** A failure of either route is a disagreement, said in the row, not an error of the test. */
    @Test
    void aRouteThatFailsIsADifference() throws Exception {
        SideBySide.Route<String> failing = new SideBySide.Route<String>() {
            @Override
            public String run(File project) {
                throw new IllegalStateException("no such target");
            }
        };

        SideBySide.Row row = SideBySide.run("Clean", "egg", 0, scratch, ONE_FILE,
                saying("cleaned"), failing, TEXTS, clock(0L, 10L, 10L, 20L));

        assertFalse(row.agrees());
        assertFalse(row.isRefused());
        assertEquals("OntoBoard's route failed: IllegalStateException: no such target",
                row.getDiffers());
    }

    /** A comparison made of assertions: the first that fails is the difference, in its words. */
    @Test
    void aFailedAssertionIsTheDifference() throws Exception {
        SideBySide.Comparison<String> asserting = SideBySide.byAssertions(
                new SideBySide.Check<String>() {
                    @Override
                    public void check(String odks, String ontoBoards) {
                        assertEquals(odks, ontoBoards, "the transcript");
                    }
                });

        assertNull(asserting.firstDifference("same", "same"));
        assertEquals("the transcript ==> expected: <a> but was: <b>",
                asserting.firstDifference("a", "b"));
    }

    /** Every run, counted or not, on fresh copies of the project: a folder of its own each. */
    @Test
    void eachRunIsOnAFreshCopy() throws Exception {
        final List<String> seen = new ArrayList<String>();
        SideBySide.Route<String> noting = new SideBySide.Route<String>() {
            @Override
            public String run(File project) throws Exception {
                File file = new File(project, "demo.owl");
                seen.add(project.getName() + " " + file.isFile());
                Files.write(file.toPath(), "changed by a run\n".getBytes(StandardCharsets.UTF_8));
                return "done";
            }
        };

        SideBySide.run("Clean", "egg", 2, scratch, ONE_FILE, noting, noting, TEXTS,
                clock(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));

        assertEquals(6, seen.size(), "three runs each way: one not counted, two counted");
        assertEquals(6, new java.util.HashSet<String>(seen).size(), "each in a folder of its own: "
                + seen);
        for (String one : seen) {
            assertTrue(one.endsWith(" true"), "and each folder holds the project: " + seen);
        }
    }

    /** The first run apart; of the counted ones the median, the lowest and the highest. */
    @Test
    void theCountedRunsAreSummedUp() throws Exception {
        SideBySide.Row row = SideBySide.run("Test, fast", "egg", 3, scratch, ONE_FILE,
                saying("PASS"), saying("PASS"), TEXTS,
                clock(0L, 5000L, 5000L, 5900L,          // not counted: 5000 and 900
                        10_000L, 11_200L, 20_000L, 20_300L,  // 1200 and 300
                        30_000L, 31_000L, 40_000L, 40_100L,  // 1000 and 100
                        50_000L, 51_400L, 60_000L, 60_200L)); // 1400 and 200

        assertEquals(3, row.getRuns());
        assertEquals(5000, row.getOdk().getFirst());
        assertEquals(Long.valueOf(1200), row.getOdk().getMedian());
        assertEquals(Long.valueOf(1000), row.getOdk().getLowest());
        assertEquals(Long.valueOf(1400), row.getOdk().getHighest());
        assertEquals(900, row.getOntoBoard().getFirst());
        assertEquals(Long.valueOf(200), row.getOntoBoard().getMedian());
        assertEquals("5000 first; median 1200, lowest 1000, highest 1400",
                row.getOdk().describe());
    }

    /** Of an even number of counted runs, the median is the mean of the two in the middle. */
    @Test
    void theMedianOfAnEvenNumberIsTheMeanOfTheMiddleTwo() {
        SideBySide.Times times = new SideBySide.Times(7, Arrays.asList(40L, 10L, 30L, 20L));

        assertEquals(Long.valueOf(25), times.getMedian());
        assertEquals(Long.valueOf(10), times.getLowest());
        assertEquals(Long.valueOf(40), times.getHighest());
        assertEquals("7", new SideBySide.Times(7, new ArrayList<Long>()).describe());
    }

    private List<SideBySide.Row> threeRows() throws Exception {
        SideBySide.Route<String> refusing = new SideBySide.Route<String>() {
            @Override
            public String run(File project) throws SideBySide.Refused {
                throw new SideBySide.Refused("it needs the network");
            }
        };
        SideBySide.Comparison<String> named = new SideBySide.Comparison<String>() {
            @Override
            public String firstDifference(String odks, String ontoBoards) {
                return odks.equals(ontoBoards) ? null
                        : "eggboil.owl: \"its axioms\" differ | 11 of 12";
            }
        };
        return Arrays.asList(
                SideBySide.run("Test, fast", "egg", 0, new File(scratch, "a"), ONE_FILE,
                        saying("PASS"), saying("PASS"), named, clock(0L, 1200L, 2000L, 2300L)),
                SideBySide.run("Release, fast", "egg", 0, new File(scratch, "b"), ONE_FILE,
                        saying("12"), saying("11"), named, clock(0L, 2000L, 2000L, 2500L)),
                SideBySide.run("Release, full", "egg", 0, new File(scratch, "c"), ONE_FILE,
                        saying("released"), refusing, named, clock(0L, 9000L, 9000L, 9001L)));
    }

    @Test
    void theCsvOfThreeRows() throws Exception {
        assertEquals("task,project,odk_first_ms,odk_median_ms,odk_lowest_ms,odk_highest_ms,"
                + "ontoboard_first_ms,ontoboard_median_ms,ontoboard_lowest_ms,"
                + "ontoboard_highest_ms,runs,agrees,refused,differs,note\n"
                + "\"Test, fast\",egg,1200,,,,300,,,,0,true,false,,\n"
                + "\"Release, fast\",egg,2000,,,,500,,,,0,false,false,"
                + "\"eggboil.owl: \"\"its axioms\"\" differ | 11 of 12\",\n"
                + "\"Release, full\",egg,9000,,,,,,,,0,false,true,,refused: it needs the network\n",
                SideBySide.csv(threeRows()));
    }

    @Test
    void theTableOfThreeRows() throws Exception {
        assertEquals("| Task | Project | ODK in Docker, ms | OntoBoard, ms | Counted runs | Agrees "
                + "| First difference | Note |\n"
                + "|---|---|---|---|---|---|---|---|\n"
                + "| Test, fast | egg | 1200 | 300 | 0 | yes |  |  |\n"
                + "| Release, fast | egg | 2000 | 500 | 0 | no | eggboil.owl: \"its axioms\" "
                + "differ \\| 11 of 12 |  |\n"
                + "| Release, full | egg | 9000 | - | 0 | - |  | refused: it needs the network |\n",
                SideBySide.table(threeRows()));
    }

    /** The report: the table, the machine, and what it does not show - the refusal among it. */
    @Test
    void theReportSaysTheMachineAndWhatItDoesNotShow() throws Exception {
        SideBySide.Machine machine = new SideBySide.Machine("Windows 11 10.0 amd64",
                "1.8.0_121 (Oracle Corporation)", "27.3.1", "obolibrary/odkfull:latest",
                "sha256:0123", "4.5.9", "2026-11-13", "1.9.8", "1.9.10");

        String report = SideBySide.report(threeRows(), machine);

        assertTrue(report.contains(SideBySide.table(threeRows())), report);
        assertTrue(report.contains("- Docker's server: 27.3.1\n"), report);
        assertTrue(report.contains("- The image: obolibrary/odkfull:latest (sha256:0123)\n"),
                report);
        assertTrue(report.contains("- The OWL API on the class path: 4.5.9\n"), report);
        int notShown = report.indexOf("## What this does not show");
        assertTrue(notShown > report.indexOf("## The machine"), report);
        String tail = report.substring(notShown);
        assertTrue(tail.contains("- Release, full: OntoBoard's route refused it - it needs the "
                + "network.\n"), tail);
        assertTrue(tail.contains("ROBOT is 1.9.8 inside OntoBoard and 1.9.10 in the image"), tail);
        assertTrue(report.contains("\nNot in the table: Refresh imports.\n"),
                "not among these rows, and the report says so");
    }

    /**
     * With the rows that refresh imports, the report says what they do not show: the network,
     * which neither way reaches here, and an import ROBOT loads by itself.
     */
    @Test
    void theRefreshRowsSayWhatTheyDoNotShow() throws Exception {
        SideBySide.Machine machine = new SideBySide.Machine("os", "java", "docker", "image", "id",
                "owlapi", "date", "1.9.8", "1.9.10");
        List<SideBySide.Row> rows = new ArrayList<SideBySide.Row>(threeRows());
        rows.add(SideBySide.run("Refresh imports", "loim", 0, new File(scratch, "d"), ONE_FILE,
                saying("refreshed"), saying("refreshed"), TEXTS, clock(0L, 7000L, 7000L, 7400L)));

        String report = SideBySide.report(rows, machine);

        assertFalse(report.contains("Not in the table"), report);
        String tail = report.substring(report.indexOf("## What this does not show"));
        assertTrue(tail.contains("\n- Refresh imports from the network: both ways read the "
                + "ontologies to import from local files here (make is given OBOBASE as a file: "
                + "address), so nothing was fetched over the network, and how either does with a "
                + "slow or failing server is not shown.\n"), tail);
        assertTrue(tail.contains("\n- An import with mirror_from, which ROBOT loads by itself "
                + "(robot convert -I): the project here has none.\n"), tail);
    }

    @Test
    void theTableAndTheReportAreWrittenUnderTheFolderGiven() throws Exception {
        SideBySide.Machine machine = new SideBySide.Machine("os", "java", "docker", "image", "id",
                "owlapi", "date", "1.9.8", "1.9.10");
        File folder = new File(scratch, "side-by-side");

        SideBySide.write(folder, threeRows(), machine);

        assertEquals(SideBySide.csv(threeRows()), new String(Files.readAllBytes(
                new File(folder, "results.csv").toPath()), StandardCharsets.UTF_8));
        assertEquals(SideBySide.report(threeRows(), machine), new String(Files.readAllBytes(
                new File(folder, "report.md").toPath()), StandardCharsets.UTF_8));
    }
}
