package de.fizkarlsruhe.ise.ontoboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A release zip unpacks into the folder chosen for it, and nowhere else.
 *
 * <p>The Konclude releases are well-formed - every entry measured is relative and inside one
 * top-level folder - but a zip is data from the network, and an entry named {@code ../something}
 * would otherwise be written wherever that points.
 */
class UnzipTest {

    private static final String STEM = "Konclude-v0.7.0-1138-Windows-x64-MSVC-Dynamic-Qt5.15.2";

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void unzipsTheReleaseLayout(@TempDir File dir) throws IOException {
        File zip = TestZips.write(new File(dir, "release.zip"),
                STEM + "/", "",
                STEM + "/Binaries/", "",
                STEM + "/Binaries/Konclude.exe", "the program",
                STEM + "/Binaries/Qt5Core.dll", "a library it needs beside it",
                STEM + "/Konclude", "a launcher");
        File into = new File(dir, "tools");

        List<File> written = Unzip.unzip(zip, into);

        assertEquals("the program", read(new File(into, STEM + "/Binaries/Konclude.exe")));
        assertEquals("a library it needs beside it",
                read(new File(into, STEM + "/Binaries/Qt5Core.dll")));
        assertEquals("a launcher", read(new File(into, STEM + "/Konclude")));
        assertEquals(3, written.size(), "the three files, not the folders: " + written);
    }

    @Test
    void refusesAnEntryThatLeavesTheFolder(@TempDir File dir) throws IOException {
        File zip = TestZips.write(new File(dir, "hostile.zip"),
                "../outside.txt", "written where it was never meant to be");
        File into = new File(dir, "tools");

        IOException refused = assertThrows(IOException.class, () -> Unzip.unzip(zip, into));

        assertTrue(refused.getMessage().contains("../outside.txt"), refused.getMessage());
        assertFalse(new File(dir, "outside.txt").exists(), "nothing may be written outside");
    }
}
