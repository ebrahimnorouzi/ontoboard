package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeInstall;
import de.fizkarlsruhe.ise.ontoboard.widoco.WidocoInstall;
import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the dialog says when a tool is missing, and what a file the user picked is taken to be.
 *
 * <p>Everything a person reads in that dialog is decided here, so it can be checked for every
 * kind of machine from this one: a Protégé on Java 8, a Windows without Microsoft's C++ runtime,
 * a Mac with Apple silicon, a machine with no network.
 */
class ToolSetupTest {

    private static final ToolAsset WIDOCO = WidocoInstall.asset();
    private static final ToolAsset KONCLUDE_WINDOWS = KoncludeInstall.assetFor("Windows 11");
    private static final ToolAsset KONCLUDE_MAC = KoncludeInstall.assetFor("Mac OS X");

    private static String said(List<String> lines) {
        return String.join("\n", lines);
    }

    @Test
    void widocoOnJava8SaysItNeedsJava11() {
        String onJava8 = said(ToolSetup.explanation(ToolSetup.Tool.WIDOCO, WIDOCO,
                "Windows 11", "amd64", "1.8", true));
        String onJava11 = said(ToolSetup.explanation(ToolSetup.Tool.WIDOCO, WIDOCO,
                "Windows 11", "amd64", "11", true));

        assertTrue(onJava8.contains("Java 11"), onJava8);
        assertTrue(onJava8.contains(ToolSetup.ADOPTIUM_URL), onJava8);
        assertFalse(onJava11.contains(ToolSetup.ADOPTIUM_URL), onJava11);
    }

    @Test
    void koncludeOnWindowsWithoutTheRuntimeNamesIt() {
        String without = said(ToolSetup.explanation(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS,
                "Windows 11", "amd64", "11", false));
        String with = said(ToolSetup.explanation(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS,
                "Windows 11", "amd64", "11", true));

        assertTrue(without.contains(ToolSetup.VC_REDIST_URL), without);
        assertTrue(without.contains("Visual C++"), without);
        assertFalse(with.contains(ToolSetup.VC_REDIST_URL), with);
    }

    @Test
    void koncludeOnAppleSiliconNeedsRosetta() {
        String appleSilicon = said(ToolSetup.explanation(ToolSetup.Tool.KONCLUDE, KONCLUDE_MAC,
                "Mac OS X", "aarch64", "11", true));
        String intel = said(ToolSetup.explanation(ToolSetup.Tool.KONCLUDE, KONCLUDE_MAC,
                "Mac OS X", "x86_64", "11", true));

        assertTrue(appleSilicon.contains("Rosetta 2"), appleSilicon);
        assertTrue(appleSilicon.contains("softwareupdate --install-rosetta"), appleSilicon);
        assertFalse(intel.contains("Rosetta"), intel);
    }

    @Test
    void everyExplanationNamesTheFileItsSizeAndTheLink() {
        String widoco = said(ToolSetup.explanation(ToolSetup.Tool.WIDOCO, WIDOCO,
                "Linux", "amd64", "11", true));
        String konclude = said(ToolSetup.explanation(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS,
                "Windows 11", "amd64", "11", true));

        assertTrue(widoco.contains(WIDOCO.getName()), widoco);
        assertTrue(widoco.contains("39 MB"), widoco);
        assertTrue(widoco.contains(WIDOCO.getUrl()), widoco);
        assertTrue(konclude.contains(KONCLUDE_WINDOWS.getName()), konclude);
        assertTrue(konclude.contains("18 MB"), konclude);
        assertTrue(konclude.contains(KONCLUDE_WINDOWS.getUrl()), konclude);
    }

    @Test
    void aChosenJarIsUsed(@TempDir File dir) throws IOException {
        File jar = new File(dir, "widoco-1.4.26-jar-with-dependencies.jar");
        Files.write(jar.toPath(), new byte[] {1, 2, 3});

        ToolSetup.Choice choice = ToolSetup.interpret(ToolSetup.Tool.WIDOCO, WIDOCO, jar);

        assertEquals(ToolSetup.Choice.Kind.USE, choice.getKind());
        assertEquals(jar, choice.getFile());
    }

    @Test
    void aJarWithTheReleaseNameButAnotherHashIsRefused(@TempDir File dir) throws IOException {
        File cutShort = new File(dir, WIDOCO.getName());
        Files.write(cutShort.toPath(), new byte[] {1, 2, 3});

        ToolSetup.Choice choice = ToolSetup.interpret(ToolSetup.Tool.WIDOCO, WIDOCO, cutShort);

        assertEquals(ToolSetup.Choice.Kind.REFUSE, choice.getKind());
        assertTrue(choice.getReason().contains("SHA-256"), choice.getReason());
    }

    @Test
    void aChosenKoncludeZipIsUnpacked(@TempDir File dir) throws IOException {
        File zip = new File(dir, "Konclude-v0.7.0-1138-something.zip");
        Files.write(zip.toPath(), new byte[] {1});

        ToolSetup.Choice choice =
                ToolSetup.interpret(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS, zip);

        assertEquals(ToolSetup.Choice.Kind.UNPACK, choice.getKind());
        assertEquals(zip, choice.getFile());
    }

    @Test
    void aChosenKoncludeBinaryIsUsed(@TempDir File dir) throws IOException {
        File exe = new File(dir, "Konclude.exe");
        Files.write(exe.toPath(), new byte[] {1});
        File unix = new File(dir, "Konclude");
        Files.write(unix.toPath(), new byte[] {1});

        assertEquals(ToolSetup.Choice.Kind.USE,
                ToolSetup.interpret(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS, exe).getKind());
        assertEquals(ToolSetup.Choice.Kind.USE,
                ToolSetup.interpret(ToolSetup.Tool.KONCLUDE, KONCLUDE_MAC, unix).getKind());
        assertEquals(ToolSetup.Choice.Kind.REFUSE,
                ToolSetup.interpret(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS, unix).getKind(),
                "on Windows the program is Konclude.exe; 'Konclude' is the release's shell launcher");
    }

    @Test
    void anythingElseIsRefusedWithWhatToChoose(@TempDir File dir) throws IOException {
        File readme = new File(dir, "readme.txt");
        Files.write(readme.toPath(), new byte[] {1});

        ToolSetup.Choice widoco = ToolSetup.interpret(ToolSetup.Tool.WIDOCO, WIDOCO, readme);
        ToolSetup.Choice konclude =
                ToolSetup.interpret(ToolSetup.Tool.KONCLUDE, KONCLUDE_WINDOWS, readme);

        assertEquals(ToolSetup.Choice.Kind.REFUSE, widoco.getKind());
        assertTrue(widoco.getReason().contains(WIDOCO.getName()), widoco.getReason());
        assertEquals(ToolSetup.Choice.Kind.REFUSE, konclude.getKind());
        assertTrue(konclude.getReason().contains("Konclude.exe"), konclude.getReason());
        assertTrue(konclude.getReason().contains(KONCLUDE_WINDOWS.getName()),
                konclude.getReason());
    }

    @Test
    void vcRuntimeIsLookedForInSystem32(@TempDir File windows) throws IOException {
        File system32 = new File(windows, "System32");
        assertTrue(system32.mkdirs());
        assertFalse(ToolSetup.vcRuntimePresent(windows));

        Files.write(new File(system32, "vcruntime140.dll").toPath(), new byte[] {1});
        Files.write(new File(system32, "msvcp140.dll").toPath(), new byte[] {1});
        assertFalse(ToolSetup.vcRuntimePresent(windows), "Qt5Core also imports vcruntime140_1 and "
                + "msvcp140_1, which a 2015 or 2017 runtime does not have");

        Files.write(new File(system32, "vcruntime140_1.dll").toPath(), new byte[] {1});
        assertFalse(ToolSetup.vcRuntimePresent(windows), "Qt5Core and Qt5Xml import msvcp140_1");

        Files.write(new File(system32, "msvcp140_1.dll").toPath(), new byte[] {1});
        assertTrue(ToolSetup.vcRuntimePresent(windows));
    }

    @Test
    void aCertificateFailureSaysWhyAndWhatToDo() {
        IOException failure = new IOException("download failed", new SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path"));

        String said = ToolSetup.whyItFailed(failure, "1.8.0_121");

        assertTrue(said.contains("1.8.0_121"), said);
        assertTrue(said.contains("certificate"), said);
        assertTrue(said.contains("Choose file..."), said);
    }

    @Test
    void noNetworkPointsAtTheManualPath() {
        String said = ToolSetup.whyItFailed(new UnknownHostException("github.com"), "11.0.25");

        assertTrue(said.contains("github.com"), said);
        assertTrue(said.contains("proxy"), said);
        assertTrue(said.contains("Copy link"), said);
        assertTrue(said.contains("Choose file..."), said);
    }
}
