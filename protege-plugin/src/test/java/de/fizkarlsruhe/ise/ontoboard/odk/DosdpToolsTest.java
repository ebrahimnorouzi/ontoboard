package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.tools.ToolAsset;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * dosdp-tools 0.19.3, the release ODK v1.6.1's image runs: downloaded on first use, unpacked into
 * OntoBoard's own folder only when every library is the image's, and started as ODK starts it.
 */
class DosdpToolsTest {

    @Test
    void theAssetIsTheReleaseOdkCarries() {
        ToolAsset asset = DosdpTools.asset();

        assertEquals("dosdp-tools-0.19.3.tgz", asset.getName());
        assertEquals(118862977L, asset.getSize());
        assertEquals("https://github.com/INCATools/dosdp-tools/releases/download/v0.19.3/"
                + "dosdp-tools-0.19.3.tgz", asset.getUrl());
        assertEquals("c8c31420e97dfa9dd2f62a739633d2c8b4fffab4ab7088ea6abb78e3b0262051",
                asset.getSha256());
    }

    /** The libraries an install is held against: the 192 jars of ODK's image. */
    @Test
    void theRecordedLibrariesAreTheImages() throws IOException {
        assertEquals(192, DosdpTools.libraries().size());
        assertTrue(DosdpTools.libraries().containsKey(
                "org.monarchinitiative.dosdp-tools-0.19.3-launcher.jar"));
    }

    /** The entry would land beside the unpacking folder - in this test's own folder. */
    @Test
    void anEntryThatLeavesItsFolderIsRefusedAndNothingIsLeft(@TempDir File temp)
            throws IOException {
        File tgz = TarGz.of(temp, "dosdp-tools-0.19.3/../evil.txt", "x");
        File home = new File(temp, "home");

        IOException refused = assertThrows(IOException.class,
                () -> DosdpTools.install(tgz, home));

        assertTrue(refused.getMessage().contains("'dosdp-tools-0.19.3/../evil.txt'")
                && refused.getMessage().contains("outside"), refused.getMessage());
        assertFalse(home.exists());
        assertFalse(new File(temp, "evil.txt").exists());
        assertEquals(1, temp.list().length, Arrays.toString(temp.list()));
    }

    @Test
    void aLibraryThatIsNotOdksIsRefusedAndNothingIsLeft(@TempDir File temp) throws IOException {
        File tgz = TarGz.of(temp,
                "dosdp-tools-0.19.3/lib/org.scala-lang.scala-library-2.13.8.jar",
                "not the jar ODK runs");
        File home = new File(temp, "home");

        IOException refused = assertThrows(IOException.class,
                () -> DosdpTools.install(tgz, home));

        assertTrue(refused.getMessage().contains("scala-library-2.13.8.jar"),
                refused.getMessage());
        assertFalse(home.exists());
    }

    /**
     * As ODK's image starts it - {@code java -jar} the launcher - with what the image's Java
     * takes from Linux: UTF-8 from {@code LANG=C.UTF-8}, where Windows' would be windows-1252,
     * and lines that end in LF, where Windows' end in CRLF.
     */
    @Test
    void theCommandIsTheLaunchersAsOdkStartsIt() {
        File home = new File("/x/dosdp-tools-0.19.3");

        assertEquals(Arrays.asList(new File("/j/java").getPath(), "-Dfile.encoding=UTF-8",
                "-Dline.separator=\n", "-jar",
                new File(home, "lib/org.monarchinitiative.dosdp-tools-0.19.3-launcher.jar")
                        .getPath(), "terms", "--infile=a.tsv"),
                DosdpTools.command(new File("/j/java"), home,
                        Arrays.asList("terms", "--infile=a.tsv")));
    }

    /** What Build and Release offer the download on: the engine's refusal for want of it. */
    @Test
    void aRefusalForWantOfTheReleaseIsKnown() {
        assertTrue(DosdpTools.isMissingIn("'patterns' needs something OntoBoard's own engine "
                + "does not do: " + DosdpTools.NOT_INSTALLED + " - OntoBoard downloads it once."));
        assertFalse(DosdpTools.isMissingIn("'dosdp' is a program OntoBoard's own engine has no "
                + "stand-in for (dosdp validate -i ../patterns/dosdp-patterns/)"));
        assertFalse(DosdpTools.isMissingIn(null));
    }

    @Test
    void anInstallIsItsLauncherAndItsLibraries(@TempDir File temp) {
        File home = new File(temp, "dosdp-tools-0.19.3");
        assertFalse(DosdpTools.isInstalled(home));
        assertTrue(new File(home, "lib").mkdirs());
        assertFalse(DosdpTools.isInstalled(home), "no launcher");
    }

    /**
     * Found by hand in 1.154.0's release check: inside Protégé on Windows the install failed
     * every time at its last step, the unpacked folder's move into place - "AccessDenied",
     * reproduced outside Protégé with Protégé's javaw.exe and not with java.exe, so something on
     * the machine was still reading the 192 libraries just written. Refused at first, the move
     * is tried again.
     */
    @Test
    void aMoveRefusedAtFirstIsTriedAgain(@TempDir File temp) throws IOException {
        java.nio.file.Path staging = java.nio.file.Files.createDirectories(
                new File(temp, "dosdp-tools-0.19.3.unpacking-1").toPath());
        java.nio.file.Files.write(staging.resolve("lib.jar"), new byte[] {1});
        java.nio.file.Path target = new File(temp, "dosdp-tools-0.19.3").toPath();
        final int[] refusals = {2};

        int tries = DosdpTools.moveIntoPlace(staging, target, (from, to) -> {
            if (refusals[0]-- > 0) {
                throw new java.nio.file.AccessDeniedException(from.toString(), to.toString(),
                        null);
            }
            java.nio.file.Files.move(from, to);
        }, 10000);

        assertEquals(3, tries);
        assertTrue(java.nio.file.Files.isRegularFile(target.resolve("lib.jar")));
        assertFalse(java.nio.file.Files.exists(staging));
    }

    /** Refused for longer than it is given, the move says why; it does not wait for ever. */
    @Test
    void aMoveRefusedForLongerThanItIsGivenSaysWhy(@TempDir File temp) {
        java.nio.file.Path staging = new File(temp, "a").toPath();
        java.nio.file.Path target = new File(temp, "b").toPath();
        long started = System.nanoTime();

        assertThrows(java.nio.file.AccessDeniedException.class,
                () -> DosdpTools.moveIntoPlace(staging, target, (from, to) -> {
                    throw new java.nio.file.AccessDeniedException(from.toString());
                }, 300));

        assertTrue(System.nanoTime() - started < 5_000_000_000L, "it waited too long");
    }

    /** A folder already there is no passing refusal: said at once, not tried again. */
    @Test
    void aFolderAlreadyThereIsNotTriedAgain(@TempDir File temp) {
        final int[] tries = {0};

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> DosdpTools.moveIntoPlace(new File(temp, "a").toPath(),
                        new File(temp, "b").toPath(), (from, to) -> {
                            tries[0]++;
                            throw new java.nio.file.FileAlreadyExistsException(to.toString());
                        }, 10000));

        assertEquals(1, tries[0]);
    }
}
