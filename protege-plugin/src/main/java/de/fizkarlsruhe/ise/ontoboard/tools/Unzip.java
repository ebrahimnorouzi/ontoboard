package de.fizkarlsruhe.ise.ontoboard.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a zip into one folder, and refuses to write anywhere else.
 *
 * <p>The Konclude releases are well-formed - every entry measured is relative and inside a single
 * top-level folder - but a zip is data from the network, and an entry named
 * {@code ../something} would otherwise be written wherever that points. Each entry's canonical
 * path is checked against the folder's before anything is written for it.
 */
public final class Unzip {

    private Unzip() {
    }

    /** The names of a zip's entries, in its own order, without unpacking anything. */
    public static List<String> names(File zip) throws IOException {
        List<String> names = new ArrayList<String>();
        try (ZipFile archive = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    /**
     * Unpacks every entry of {@code zip} under {@code into}, keeping the zip's folders.
     *
     * @return the files written, in the zip's order (folders are not listed)
     * @throws IOException for an entry that would land outside {@code into}, naming it; entries
     *     before it have been unpacked, none after it
     */
    public static List<File> unzip(File zip, File into) throws IOException {
        if (!into.isDirectory() && !into.mkdirs()) {
            throw new IOException("Cannot create the folder " + into.getAbsolutePath() + ".");
        }
        String root = into.getCanonicalPath() + File.separator;
        List<File> written = new ArrayList<File>();
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File target = new File(into, entry.getName());
                if (!(target.getCanonicalPath() + File.separator).startsWith(root)) {
                    throw new IOException(zip.getName() + " has an entry that would be written "
                            + "outside " + into.getAbsolutePath() + ": " + entry.getName()
                            + ". Nothing after it was unpacked.");
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Cannot create the folder "
                                + target.getAbsolutePath() + ".");
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Cannot create the folder "
                            + parent.getAbsolutePath() + ".");
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                written.add(target);
            }
        }
        return written;
    }
}
