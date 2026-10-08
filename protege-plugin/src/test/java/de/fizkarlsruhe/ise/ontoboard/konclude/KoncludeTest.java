package de.fizkarlsruhe.ise.ontoboard.konclude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Driving a reasoner that lies about whether it succeeded.
 *
 * <p>Konclude exits 0 after failing and still writes a well-formed output file, so the usual
 * checks - exit code, "did it write something", "does it parse" - all pass for a run that never
 * read the ontology. These tests pin the two things that make the difference: the exact command
 * line, and the log as the only success test.
 */
class KoncludeTest {

    private static final File BIN = new File("/opt/Konclude/Konclude");
    private static final File IN = new File("/tmp/in.owl.xml");
    private static final File OUT = new File("/tmp/out.owl");

    // ---------- the command line ----------

    /**
     * The documented line, token for token.
     *
     * <p>Asserted exactly rather than loosely because Konclude matches a flag on its first letter
     * after stripping dashes: {@code -output}, {@code -o} and {@code -oops} are the same flag to
     * it, so a typo is absorbed in silence and the run goes on doing something subtly different.
     * Nothing else in this project catches that.
     */
    @Test
    void theCommandLineIsExactlyTheDocumentedOne() {
        List<String> command = Konclude.commandLine(BIN, Konclude.Task.CLASSIFY_CLASSES,
                IN, OUT, false, null);

        assertEquals(Arrays.asList(
                BIN.getAbsolutePath(), "classification",
                "-i", IN.getAbsolutePath(),
                "-o", OUT.getAbsolutePath(),
                "-w", "AUTO",
                "-u",
                "+Konclude.CLI.Output.WriteDeclarations=false"), command);
    }

    /** Timings are Konclude's -v, added only when asked for. */
    @Test
    void timingsAddTheVerboseFlag() {
        assertFalse(Konclude.commandLine(BIN, Konclude.Task.CLASSIFY_CLASSES, IN, OUT, false, null)
                .contains("-v"));
        assertTrue(Konclude.commandLine(BIN, Konclude.Task.CLASSIFY_CLASSES, IN, OUT, true, null)
                .contains("-v"));
    }

    /** Only satisfiability takes an entity, and it insists on one. */
    @Test
    void onlySatisfiabilityCarriesAnEntity() {
        for (Konclude.Task task : Konclude.Task.values()) {
            if (task.needsEntity()) {
                continue;
            }
            assertFalse(Konclude.commandLine(BIN, task, IN, OUT, false, "http://x#C").contains("-x"),
                    task + " should not pass -x");
        }
        List<String> satisfiability = Konclude.commandLine(BIN, Konclude.Task.SATISFIABILITY,
                IN, OUT, false, "http://x#C");
        assertTrue(satisfiability.contains("-x"));
        assertEquals("http://x#C", satisfiability.get(satisfiability.indexOf("-x") + 1));

        assertThrows(IllegalArgumentException.class, () -> Konclude.commandLine(
                BIN, Konclude.Task.SATISFIABILITY, IN, OUT, false, "  "));
    }

    /**
     * Two things that must never appear, each for a measured reason.
     *
     * <p>{@code Konclude.bat} forwards only {@code %1}-{@code %9} and this line is eleven tokens
     * or more, so the tail - including {@code -o} - would be dropped, turning a classification
     * into a run that computes everything and writes nothing.
     *
     * <p>{@code AbbreviatedIRIs} makes Konclude write {@code IRI="owl:Nothing"} and, for
     * namespaces the input did not declare, {@code IRI=""} - invalid OWL that loses entity
     * identity, so the inferences could not be matched back to the ontology.
     */
    @Test
    void theLineAvoidsTheTwoTrapsThatLoseWork() {
        for (Konclude.Task task : Konclude.Task.values()) {
            List<String> command = Konclude.commandLine(BIN, task, IN, OUT, true, "http://x#C");
            for (String token : command) {
                assertFalse(token.toLowerCase(java.util.Locale.ROOT).contains("konclude.bat"),
                        "the batch wrapper truncates past nine arguments: " + command);
                assertFalse(token.contains("AbbreviatedIRIs"),
                        "abbreviated IRIs are invalid OWL here: " + command);
            }
            assertTrue(command.contains("-o"), task + " must write its output somewhere");
        }
    }

    /** Every task is reachable by its label, and the roster has no duplicates. */
    @Test
    void everyTaskIsReachableByLabel() {
        assertEquals(Konclude.Task.values().length, Konclude.labels().size());
        assertEquals(new LinkedHashSet<String>(Konclude.labels()).size(), Konclude.labels().size());
        for (Konclude.Task task : Konclude.Task.values()) {
            assertEquals(task, Konclude.byLabel(task.getLabel()));
        }
        assertThrows(IllegalArgumentException.class, () -> Konclude.byLabel("Fast mode"));
    }

    /** Consistency and satisfiability answer a word; the rest answer axioms. */
    @Test
    void theYesOrNoTasksAreMarkedAsSuch() {
        assertTrue(Konclude.Task.CONSISTENCY.answersYesOrNo());
        assertTrue(Konclude.Task.SATISFIABILITY.answersYesOrNo());
        assertFalse(Konclude.Task.CLASSIFY_CLASSES.answersYesOrNo());
        assertEquals(".txt", Konclude.outputExtension(Konclude.Task.CONSISTENCY));
        assertEquals(".owl", Konclude.outputExtension(Konclude.Task.CLASSIFY_CLASSES));
    }

    /** Their output file holds the literal word, not OWL. */
    @Test
    void theOneWordAnswerIsReadAsText() {
        assertEquals(Boolean.TRUE, Konclude.yesOrNo("true"));
        assertEquals(Boolean.TRUE, Konclude.yesOrNo("true\n"));
        assertEquals(Boolean.FALSE, Konclude.yesOrNo("FALSE"));
        assertNull(Konclude.yesOrNo(""));
        assertNull(Konclude.yesOrNo(null));
        assertNull(Konclude.yesOrNo("<Ontology/>"),
                "an OWL file is not an answer, and must not be read as one");
    }

    // ---------- the log, which is the only success test ----------

    /** The tagged format -u produces. */
    @Test
    void theTaggedFormatIsParsed() {
        List<KoncludeLog.Line> lines = KoncludeLog.parse(Arrays.asList(
                "{ info } <2026-10-08T05:12:44.901> [::Konclude::Classifier]>> Classification finished."));

        assertEquals(1, lines.size());
        assertEquals("info", lines.get(0).getLevel());
        assertEquals("::Konclude::Classifier", lines.get(0).getDomain());
        assertEquals("Classification finished.", lines.get(0).getMessage());
    }

    /** And the shorter one, with no domain. */
    @Test
    void theUntaggedFormatIsParsed() {
        List<KoncludeLog.Line> lines = KoncludeLog.parse(Arrays.asList(
                "{ info } 05:12:44:901 >> Ontology parsed in 31 ms"));

        assertEquals("info", lines.get(0).getLevel());
        assertEquals("", lines.get(0).getDomain());
        assertEquals(KoncludeLog.Stage.TIMINGS, lines.get(0).getStage());
    }

    /** A line of neither shape is kept, because an unreadable line is still output. */
    @Test
    void anUnrecognisedLineIsKeptRatherThanDropped() {
        List<KoncludeLog.Line> lines = KoncludeLog.parse(Arrays.asList("Segmentation fault"));

        assertEquals(1, lines.size());
        assertEquals("Segmentation fault", lines.get(0).getRaw());
        assertFalse(lines.get(0).isError(), "it has no level, so it must not be read as an error");
    }

    /**
     * The whole reason this class exists.
     *
     * <p>Measured: {@code Konclude classification -i NOPE.owl.xml -o out.owl} logs an error,
     * exits <b>0</b>, and writes an 896-byte parseable {@code <Ontology>} with two declarations.
     * So exit code and "the output parses" both say success. Only the log knows.
     */
    @Test
    void failureIsReadFromTheLogBecauseTheExitCodeSaysNothing() {
        List<KoncludeLog.Line> failed = KoncludeLog.parse(Arrays.asList(
                "{ info } <2026-10-08T05:12:44.880> [::Konclude::Main]>> Konclude starting.",
                "{error} <2026-10-08T05:12:44.901> [::Konclude::CLIBatchProcessor]>> "
                        + "File 'NOPE.owl.xml' not found."));

        assertTrue(KoncludeLog.failed(failed));
        assertEquals("File 'NOPE.owl.xml' not found.", KoncludeLog.firstError(failed));

        List<KoncludeLog.Line> fine = KoncludeLog.parse(Arrays.asList(
                "{ info } <2026-10-08T05:12:44.880> [::Konclude::Main]>> Konclude starting.",
                "{ info } <2026-10-08T05:12:45.102> [::Konclude::Classifier]>> Classified."));
        assertFalse(KoncludeLog.failed(fine));
        assertEquals("", KoncludeLog.firstError(fine));
    }

    /** Every spelling of error counts, including the two worse ones. */
    @Test
    void everySpellingOfErrorCounts() {
        for (String level : new String[] {"error", "exceptional error", "catastrophic error"}) {
            assertTrue(KoncludeLog.failed(KoncludeLog.parse(Arrays.asList(
                    "{" + level + "} <t> [::Konclude::Main]>> it broke"))), level);
        }
        assertFalse(KoncludeLog.failed(KoncludeLog.parse(Arrays.asList(
                "{ warn } <t> [::Konclude::Main]>> something odd"))));
    }

    /**
     * A filter that can hide an error is a filter that will hide it when it matters.
     *
     * <p>So errors and warnings survive every combination of the checkboxes, including none.
     */
    @Test
    void errorsAndWarningsSurviveEveryFilter() {
        List<KoncludeLog.Line> lines = KoncludeLog.parse(Arrays.asList(
                "{ info } <t> [::Konclude::Main]>> starting",
                "{ warn } <t> [::Konclude::Parser]>> odd axiom",
                "{error} <t> [::Konclude::Classifier]>> gave up",
                "{ info } <t> [::Konclude::Indexer]>> cache warm"));

        List<String> none = KoncludeLog.filter(lines, new LinkedHashSet<KoncludeLog.Stage>());
        assertEquals(2, none.size(), "the error and the warning, and nothing else: " + none);
        assertTrue(none.get(0).contains("odd axiom"));
        assertTrue(none.get(1).contains("gave up"));

        Set<KoncludeLog.Stage> all = new LinkedHashSet<KoncludeLog.Stage>(KoncludeLog.stages());
        assertEquals(4, KoncludeLog.filter(lines, all).size());
        assertEquals(2, KoncludeLog.filter(lines, null).size(), "null means nothing selected");
    }

    /** An unknown domain falls into Internals rather than vanishing. */
    @Test
    void anUnknownDomainStillReachesSomebody() {
        KoncludeLog.Line line = KoncludeLog.parse(Arrays.asList(
                "{ info } <t> [::Konclude::SomethingNew]>> hello")).get(0);

        assertEquals(KoncludeLog.Stage.INTERNALS, line.getStage());
        Set<KoncludeLog.Stage> all = new LinkedHashSet<KoncludeLog.Stage>(KoncludeLog.stages());
        assertEquals(1, KoncludeLog.filter(Arrays.asList(line), all).size());
    }

    // ---------- finding it ----------

    /** Downloads is in the search path, which is the point of looking there. */
    @Test
    void theDownloadsFolderIsSearched(@TempDir File home) {
        File downloads = new File(home, "Downloads");
        List<File> places = KoncludeInstall.candidates(null, downloads, null);

        assertFalse(places.isEmpty());
        boolean mentionsDownloads = false;
        for (File place : places) {
            if (place.getAbsolutePath().contains("Downloads")) {
                mentionsDownloads = true;
            }
        }
        assertTrue(mentionsDownloads, places.toString());
    }

    /** A remembered path is tried before anything else. */
    @Test
    void theRememberedPathComesFirst(@TempDir File home) {
        File chosen = new File(home, "elsewhere/Konclude");
        assertEquals(chosen, KoncludeInstall.candidates(chosen, new File(home, "Downloads"),
                null).get(0));
    }

    /** Only a file that exists is offered. */
    @Test
    void onlySomethingPresentIsChosen(@TempDir File home) throws Exception {
        File real = new File(home, KoncludeInstall.executableName());
        assertNull(KoncludeInstall.firstPresent(Arrays.asList(real)));

        assertTrue(real.createNewFile());
        assertEquals(real, KoncludeInstall.firstPresent(Arrays.asList(
                new File(home, "nope"), real)));
    }

    /**
     * Present is not the same as usable, and on Windows that gap is the common case.
     *
     * <p>The Windows release links Qt dynamically, so an exe copied out of the zip without its
     * DLLs exists, is executable, and cannot start. The probe runs it rather than trusting the
     * filesystem, and a binary that answers nothing is reported with a remedy.
     */
    @Test
    void aBinaryThatWillNotStartIsNotUsable(@TempDir File home) throws Exception {
        final File binary = new File(home, KoncludeInstall.executableName());
        assertTrue(binary.createNewFile());

        KoncludeInstall.Found silent = KoncludeInstall.describe(binary,
                new KoncludeInstall.Probe() {
                    @Override
                    public List<String> run(File each) {
                        return null;
                    }
                });
        assertFalse(silent.isUsable());
        assertEquals(binary, silent.getBinary());
        assertFalse(silent.getProblem().isEmpty(), "it has to say what to do about it");
        assertTrue(silent.headline().contains("will not run"), silent.headline());

        KoncludeInstall.Found answers = KoncludeInstall.describe(binary,
                new KoncludeInstall.Probe() {
                    @Override
                    public List<String> run(File each) {
                        return Arrays.asList("Konclude 0.7.0 (build 1138)");
                    }
                });
        assertTrue(answers.isUsable());
        assertTrue(answers.getVersion().contains("Konclude"));
        assertTrue(answers.headline().contains("installed"), answers.headline());
    }

    /** With nothing found, the headline says so and the install steps name a real asset. */
    @Test
    void withNothingFoundThereIsSomewhereToGo() {
        KoncludeInstall.Found absent = KoncludeInstall.absent();

        assertFalse(absent.isUsable());
        assertNull(absent.getBinary());
        assertTrue(absent.headline().contains("not installed"));
        assertTrue(KoncludeInstall.assetForThisMachine().startsWith("Konclude-"));
        assertTrue(KoncludeInstall.assetForThisMachine().endsWith(".zip"));
        assertTrue(KoncludeInstall.downloadUrl().contains(KoncludeInstall.assetForThisMachine()));
        assertNotNull(KoncludeInstall.howToInstall());
        assertFalse(KoncludeInstall.howToInstall().isEmpty());
    }

    /** The asset names the architecture that exists, on every platform. */
    @Test
    void everyPlatformGetsAnX64Asset() {
        String asset = KoncludeInstall.assetForThisMachine();

        assertTrue(asset.contains("x64"), asset + " - upstream publishes no arm64 build");
        assertTrue(asset.contains(Konclude.KNOWN_RELEASE), asset);
    }

    /** What Konclude cannot do is written down once, so the dialog and the docs cannot diverge. */
    @Test
    void theLimitsAreStatedInOnePlace() {
        assertFalse(Konclude.LIMITS.isEmpty());
        String all = Konclude.LIMITS.toString().toLowerCase(java.util.Locale.ROOT);
        assertTrue(all.contains("direct"), "the direct-hierarchy limit is the surprising one");
        assertTrue(all.contains("arm64"), "no Apple silicon build is worth saying");
        assertTrue(all.contains("explanation"), "it offers none, and Explain... does");
    }
}
