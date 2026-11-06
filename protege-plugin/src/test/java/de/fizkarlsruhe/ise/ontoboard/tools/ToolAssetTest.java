package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.konclude.KoncludeInstall;
import de.fizkarlsruhe.ise.ontoboard.widoco.WidocoInstall;
import org.junit.jupiter.api.Test;

/**
 * The releases OntoBoard downloads, pinned to what was measured.
 *
 * <p>GitHub publishes no digest for these assets - they predate that - so each SHA-256 here was
 * computed from a fresh download on 2026-11-05, and the two that had been downloaded weeks earlier
 * matched it. One of these values changing means a release was replaced under the same name,
 * which is exactly what the pin is there to notice.
 */
class ToolAssetTest {

    private static final String KONCLUDE_DOWNLOADS =
            "https://github.com/konclude/Konclude/releases/download/v0.7.0-1138/";
    private static final String KONCLUDE_PAGE =
            "https://github.com/konclude/Konclude/releases/tag/v0.7.0-1138";

    @Test
    void widocoIsTheMeasuredRelease() {
        ToolAsset widoco = WidocoInstall.asset();

        assertEquals("widoco-1.4.25-jar-with-dependencies_JDK-11.jar", widoco.getName());
        assertEquals("https://github.com/dgarijo/Widoco/releases/download/v1.4.25/"
                + "widoco-1.4.25-jar-with-dependencies_JDK-11.jar", widoco.getUrl());
        assertEquals(40858575L, widoco.getSize());
        assertEquals("ce3071a90fe73c0860829569e79050f74c9623d6add8be3925cbdf87bedde5a5",
                widoco.getSha256());
        assertEquals("https://github.com/dgarijo/Widoco/releases/tag/v1.4.25",
                widoco.getReleasePage());
        assertFalse(widoco.isArchive(), "the jar is used as it is downloaded");
    }

    @Test
    void koncludeHasOneAssetPerSystem() {
        assertKonclude(KoncludeInstall.assetFor("Windows 11"),
                "Konclude-v0.7.0-1138-Windows-x64-MSVC-Dynamic-Qt5.15.2.zip", 18733629L,
                "52ea75ebac2e996cb6bdb256271d1a772ec7b2ac1cce99c6da13a7d967698f98");
        assertKonclude(KoncludeInstall.assetFor("Mac OS X"),
                "Konclude-v0.7.0-1138-OSX-x64-Clang-Static-Qt5.12.10.zip", 13249589L,
                "b14900399dd373e5af8dbf9e3941914f701fa0ad079df3455e0ce88ba8463943");
        assertKonclude(KoncludeInstall.assetFor("Linux"),
                "Konclude-v0.7.0-1138-Linux-x64-GCC-Static-Qt5.12.10.zip", 13926747L,
                "37216c18f45a80165aec1e76aeefd62d933d6a3f8f134c5aca0707c676fda76e");
    }

    private static void assertKonclude(ToolAsset asset, String name, long size, String sha256) {
        assertEquals(name, asset.getName());
        assertEquals(KONCLUDE_DOWNLOADS + name, asset.getUrl());
        assertEquals(size, asset.getSize(), name);
        assertEquals(sha256, asset.getSha256(), name);
        assertEquals(KONCLUDE_PAGE, asset.getReleasePage(), name);
        assertTrue(asset.isArchive(), name + " is a zip to unpack");
    }

    @Test
    void theLinksStayWhereTheyWere() {
        assertEquals(WidocoInstall.asset().getUrl(), WidocoInstall.downloadUrl());
        assertEquals(KoncludeInstall.asset().getUrl(), KoncludeInstall.downloadUrl());
        assertEquals(KoncludeInstall.asset().getName(), KoncludeInstall.assetForThisMachine());
    }

    @Test
    void aHashIsKeptInLowerCase() {
        ToolAsset asset = new ToolAsset("a.jar", "https://example.org/a.jar", 1L,
                "CE3071A90FE73C0860829569E79050F74C9623D6ADD8BE3925CBDF87BEDDE5A5",
                "https://example.org/", false);

        assertEquals("ce3071a90fe73c0860829569e79050f74c9623d6add8be3925cbdf87bedde5a5",
                asset.getSha256());
    }
}
