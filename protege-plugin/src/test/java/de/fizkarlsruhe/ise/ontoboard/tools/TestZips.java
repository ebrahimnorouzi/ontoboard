package de.fizkarlsruhe.ise.ontoboard.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small zips shaped like the releases, for the tests that unpack them. */
public final class TestZips {

    private TestZips() {
    }

    /**
     * Writes a zip of the given entries, in order.
     *
     * @param entries name, content, name, content... - a name ending in "/" is a folder and its
     *     content is ignored
     */
    public static File write(File zip, String... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (int at = 0; at + 1 < entries.length; at += 2) {
                out.putNextEntry(new ZipEntry(entries[at]));
                if (!entries[at].endsWith("/")) {
                    out.write(entries[at + 1].getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return zip;
    }
}
