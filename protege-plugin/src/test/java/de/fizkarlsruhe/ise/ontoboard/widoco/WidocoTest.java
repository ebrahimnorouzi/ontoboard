package de.fizkarlsruhe.ise.ontoboard.widoco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Generating an ontology's documentation with Widoco.
 *
 * <p>The numbers in these tests came from running it: {@code widoco-1.4.25}, 40,858,575 bytes,
 * on MWO's {@code mwo.owl}, writing a page of 854,425 bytes and exiting 0 with 27 lines of log.
 *
 * <p><b>Two of these tests exist because a plan written without running it was wrong.</b> It
 * said the page lands in {@code <out>/doc/} and that a successful run prints hundreds of error
 * lines. Neither is true with {@code -outFolder} given, and either one encoded would have made
 * every successful run report failure.
 */
class WidocoTest {

    // ------------------------------------------------- the measured output layout

    /**
     * The page is {@code <out>/index-<lang>.html}, with no {@code doc/} between.
     *
     * <p>Measured. Widoco does create a {@code doc/} subfolder when no output folder is given,
     * which is presumably where the wrong belief came from - but this always passes
     * {@code -outFolder}, and then the page is written there directly.
     */
    @Test
    void thePageLandsDirectlyInTheOutputFolder(@TempDir File out) {
        File page = Widoco.indexIn(out, "en");

        assertEquals("index-en.html", page.getName());
        assertEquals(out, page.getParentFile(),
                "no doc/ subfolder: measured on a real run, the page is written here");
    }

    /** Another language changes the file name, not the folder. */
    @Test
    void anotherLanguageIsAnotherFileInTheSamePlace(@TempDir File out) {
        assertEquals("index-de.html", Widoco.indexIn(out, "de").getName());
        assertEquals(out, Widoco.indexIn(out, "de").getParentFile());
        assertEquals("index-en.html", Widoco.indexIn(out, null).getName());
        assertEquals("index-en.html", Widoco.indexIn(out, "  ").getName());
    }

    // ------------------------------------------------- success is the page, not the code

    /** A run that wrote a page succeeded, whatever it printed. */
    @Test
    void theTestIsThePageExisting(@TempDir File out) throws Exception {
        File page = Widoco.indexIn(out, "en");
        Files.write(page.toPath(), new byte[8000]);

        assertTrue(Widoco.outcome(0, Arrays.asList("[main] INFO ok"), page).wrotePage());
    }

    /**
     * A run that wrote nothing failed, even with exit code 0.
     *
     * <p>A template-driven generator can finish cleanly having produced nothing.
     */
    @Test
    void anExitCodeOfZeroWithNoPageIsStillAFailure(@TempDir File out) {
        File page = Widoco.indexIn(out, "en");

        assertFalse(Widoco.outcome(0, Arrays.asList("[main] INFO done"), page).wrotePage());
        assertFalse(Widoco.outcome(0, null, null).wrotePage());
    }

    /** And a stub is not a page. */
    @Test
    void anEmptyFileIsNotDocumentation(@TempDir File out) throws Exception {
        File page = Widoco.indexIn(out, "en");
        Files.write(page.toPath(), "".getBytes(StandardCharsets.UTF_8));

        assertFalse(Widoco.outcome(0, null, page).wrotePage());
    }

    // ------------------------------------------------- the log

    /**
     * The warnings every run prints are not shown; the one worth acting on is.
     *
     * <p>These are the real lines from the measured run.
     */
    @Test
    void onlyTheLogLinesWorthReadingAreKept() {
        List<String> log = Arrays.asList(
                "[main] INFO widoco.gui.GuiController - Processed configuration",
                "[main] WARN widoco.Configuration - Error while reading configuration properties"
                        + " from [C:\\Users\\eno\\Downloads\\config\\config.properties]",
                "[main] WARN org.semanticweb.owlapi.util.SAXParsers - "
                        + "http://www.oracle.com/xml/jaxp/properties/entityExpansionLimit not"
                        + " supported by parser type org.apache.xerces.jaxp.SAXParserImpl",
                "[main] WARN org.semanticweb.owlapi.util.SAXParsers - entityExpansionLimit not"
                        + " supported",
                "[main] INFO widoco.LODEParser - Parsing Complete!");

        List<String> kept = Widoco.worthReading(log);

        assertEquals(1, kept.size(), kept.toString());
        assertTrue(kept.get(0).contains("config.properties"),
                "the configuration warning is the actionable one: " + kept);
    }

    /** A clean run leaves nothing to report. */
    @Test
    void aCleanLogSaysNothing() {
        assertTrue(Widoco.worthReading(Arrays.asList(
                "[main] INFO widoco.gui.GuiController - Generating documentation",
                "[main] INFO widoco.LODEParser - Parsing Complete!")).isEmpty());
        assertTrue(Widoco.worthReading(null).isEmpty());
    }

    // ------------------------------------------------- the command

    /** The flags the real projects run, in order. */
    @Test
    void theCommandCarriesTheMeasuredFlags(@TempDir File dir) {
        File jar = new File(dir, "widoco.jar");
        File ontology = new File(dir, "mwo.owl");
        File out = new File(dir, "out");

        List<String> command = Widoco.command(new File("/usr/bin/java"), jar, ontology, out,
                "en", false);

        assertEquals("-jar", command.get(1));
        assertTrue(command.contains("-ontFile"));
        assertTrue(command.contains("-outFolder"));
        for (String flag : Widoco.flags()) {
            assertTrue(command.contains(flag), flag + " missing from " + command);
        }
        assertFalse(command.contains("-webVowl"));
        assertFalse(command.contains("-lang"), "en is the default and is not passed");
    }

    /** A non-default language is passed; WebVowl is opt-in because it costs time. */
    @Test
    void languageAndWebVowlAreOptional(@TempDir File dir) {
        List<String> command = Widoco.command(null, new File(dir, "w.jar"),
                new File(dir, "o.owl"), new File(dir, "out"), "de", true);

        assertEquals("java", command.get(0), "no java given means whatever PATH resolves");
        assertTrue(command.contains("-lang"));
        assertEquals("de", command.get(command.indexOf("-lang") + 1));
        assertTrue(command.contains("-webVowl"));
    }

    /** Missing pieces are refused here rather than by a process that cannot explain itself. */
    @Test
    void anIncompleteCommandIsRefused(@TempDir File dir) {
        File f = new File(dir, "x");
        assertThrows(IllegalArgumentException.class,
                () -> Widoco.command(null, null, f, f, "en", false));
        assertThrows(IllegalArgumentException.class,
                () -> Widoco.command(null, f, null, f, "en", false));
        assertThrows(IllegalArgumentException.class,
                () -> Widoco.command(null, f, f, null, "en", false));
    }

    // ------------------------------------------------- which file to document

    /**
     * The release product, not the edit file.
     *
     * <p>An edit file imports its modules rather than containing them, so a page made from it
     * describes what the project wrote and nothing it reuses.
     */
    @Test
    void theReleaseProductIsPreferred(@TempDir File root) throws Exception {
        File edit = new File(root, "mwo-edit.owl");
        Files.write(edit.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        File product = new File(root, "mwo.owl");
        Files.write(product.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(product, Widoco.inputFor(root, "mwo", edit));
        assertFalse(Widoco.isEditFile(Widoco.inputFor(root, "mwo", edit)));
    }

    /** With no product built, the edit file is what there is - and is flagged as such. */
    @Test
    void withoutAProductTheEditFileIsUsedAndSaidSo(@TempDir File root) throws Exception {
        File edit = new File(root, "mwo-edit.owl");
        Files.write(edit.toPath(), "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));

        assertEquals(edit, Widoco.inputFor(root, "mwo", edit));
        assertTrue(Widoco.isEditFile(edit));
    }

    /** A project whose release is Turtle rather than RDF/XML is found too. */
    @Test
    void aTurtleProductIsFound(@TempDir File root) throws Exception {
        File ttl = new File(root, "nfdicore.ttl");
        Files.write(ttl.toPath(), "# ttl".getBytes(StandardCharsets.UTF_8));

        assertEquals(ttl, Widoco.inputFor(root, "nfdicore", null));
    }

    // ------------------------------------------------- finding it, and finding a java

    /** The asset name and URL are the release this was measured against. */
    @Test
    void theAssetIsTheOneThatWasMeasured() {
        assertEquals("widoco-1.4.25-jar-with-dependencies_JDK-11.jar",
                WidocoInstall.assetForThisMachine());
        assertTrue(WidocoInstall.downloadUrl().endsWith(WidocoInstall.assetForThisMachine()));
        assertTrue(WidocoInstall.downloadUrl().contains("/v1.4.25/"));
    }

    /** The remembered jar is looked at first; Downloads after it. */
    @Test
    void theRememberedJarComesFirst(@TempDir File dir) {
        File chosen = new File(dir, "mine.jar");
        List<File> places = WidocoInstall.candidates(chosen, new File(dir, "Downloads"), null);

        assertEquals(chosen, places.get(0));
        assertTrue(places.size() > 1);
    }

    /**
     * Java 8 and Java 11 are told apart, in both spellings.
     *
     * <p>These are the real banners: Protege 5.5.0's bundled JVM and 5.6.9's.
     */
    @Test
    void theJavaVersionIsReadFromWhatItPrints() {
        List<String> eight = Arrays.asList("java version \"1.8.0_121\"",
                "Java(TM) SE Runtime Environment (build 1.8.0_121-b13)");
        List<String> eleven = Arrays.asList("openjdk version \"11.0.25\" 2024-10-15");

        assertEquals(8, WidocoInstall.majorVersionIn(eight),
                "1.8.0_121 is Java 8, not Java 1");
        assertEquals(11, WidocoInstall.majorVersionIn(eleven));
        assertFalse(WidocoInstall.isNewEnough(eight));
        assertTrue(WidocoInstall.isNewEnough(eleven));
        assertEquals(-1, WidocoInstall.majorVersionIn(Arrays.asList("not a version at all")));
    }

    /** And Java 8 is refused with the reason, not just refused. */
    @Test
    void javaEightIsRefusedWithTheReason() {
        String why = WidocoInstall.whyJavaWillNotDo("Protege's own Java",
                Arrays.asList("java version \"1.8.0_121\""));

        assertNotNull(why);
        assertTrue(why.contains("55"), "it should name the class file version: " + why);
        assertTrue(why.contains("5.5.0"), "and which host this is: " + why);
        assertNull(WidocoInstall.whyJavaWillNotDo("x",
                Arrays.asList("openjdk version \"11.0.25\"")));
    }

    /** Protege's own JVM is tried before anything else, when it will do. */
    @Test
    void protegesOwnJavaIsTriedFirst(@TempDir File dir) {
        File home = new File(dir, "jre");
        List<File> places = WidocoInstall.javaCandidates(null, home, null, null);

        assertTrue(places.get(0).getPath().contains("jre"), places.toString());
        assertTrue(places.get(places.size() - 1).getPath().startsWith("java"),
                "PATH is the last resort: " + places);
    }

    /** Somebody with nothing installed is told the three things they need. */
    @Test
    void thereIsAnAnswerForSomebodyWithNothing() {
        List<String> how = WidocoInstall.howToInstall();

        assertFalse(how.isEmpty());
        assertTrue(how.toString().contains("39 MB"));
        assertTrue(how.toString().contains("github.com/dgarijo/Widoco"));
        assertTrue(how.toString().contains("OWL API"),
                "it should say why it is not bundled: " + how);
    }
}
