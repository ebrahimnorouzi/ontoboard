package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a set of files held, and when each was written: taken before a run and again after it,
 * to tell which files the run wrote - also those it wrote again as they were - and which of
 * those it changed.
 *
 * <p>Written means another time or another content; changed means another content. Both are
 * needed: Protege goes by a file's time and asks about a file that was written behind its
 * back, whatever it holds, while a person wants to know what is different. The time is the
 * file system's own ({@code Files.getLastModifiedTime}); the content's hash catches a file
 * written twice within the time a file system tells apart.
 *
 * <p>A file is known by its absolute, normalised path; the files come in the order of their
 * paths. A file that is not there is not among them, and one that went during the run was
 * neither written nor changed: there is nothing of it to read again.
 */
public final class FileStamps {

    /** Each file there: its content's hash, and its time. */
    private final Map<File, String[]> stamps;
    /** The files to stamp again, by name; null when a folder is listed again. */
    private final List<File> named;
    private final File folder;
    private final String nameEnding;

    private FileStamps(List<File> named, File folder, String nameEnding) {
        this.named = named;
        this.folder = folder;
        this.nameEnding = nameEnding;
        this.stamps = new TreeMap<File, String[]>();
        for (File file : named != null ? named : listed(folder, nameEnding)) {
            if (file.isFile()) {
                stamps.put(file, new String[] {hashOf(file), timeOf(file)});
            }
        }
    }

    /** These files as they are now; one that is not there yet is stamped once it is. */
    public static FileStamps of(Collection<File> files) {
        List<File> named = new ArrayList<File>();
        for (File file : files) {
            File known = known(file);
            if (!named.contains(known)) {
                named.add(known);
            }
        }
        return new FileStamps(named, null, null);
    }

    /** The files of a folder whose names end so, as they are now; listed again by {@link #now}. */
    public static FileStamps inFolder(File folder, String nameEnding) {
        return new FileStamps(null, known(folder), nameEnding);
    }

    /** The same files - or the folder listed again - as they are now. */
    public FileStamps now() {
        return new FileStamps(named, folder, nameEnding);
    }

    /** The files there, in the order of their paths. */
    public List<File> files() {
        return new ArrayList<File>(stamps.keySet());
    }

    /** The files written since: another time or another content, or there now and not then. */
    public List<File> writtenSince(FileStamps before) {
        List<File> written = new ArrayList<File>();
        for (Map.Entry<File, String[]> now : stamps.entrySet()) {
            String[] then = before.stamps.get(now.getKey());
            if (then == null || !then[0].equals(now.getValue()[0])
                    || !then[1].equals(now.getValue()[1])) {
                written.add(now.getKey());
            }
        }
        return written;
    }

    /** The files changed since: another content, or there now and not then. */
    public List<File> changedSince(FileStamps before) {
        List<File> changed = new ArrayList<File>();
        for (Map.Entry<File, String[]> now : stamps.entrySet()) {
            String[] then = before.stamps.get(now.getKey());
            if (then == null || !then[0].equals(now.getValue()[0])) {
                changed.add(now.getKey());
            }
        }
        return changed;
    }

    private static File known(File file) {
        return file.getAbsoluteFile().toPath().normalize().toFile();
    }

    private static List<File> listed(File folder, String nameEnding) {
        File[] files = folder.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> listed = new ArrayList<File>();
        for (File file : files) {
            if (file.getName().endsWith(nameEnding)) {
                listed.add(known(file));
            }
        }
        return listed;
    }

    private static String timeOf(File file) {
        try {
            return Files.getLastModifiedTime(file.toPath()).toString();
        } catch (IOException | RuntimeException notRead) {
            return String.valueOf(file.lastModified());
        }
    }

    private static String hashOf(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte one : digest.digest()) {
                hex.append(String.format("%02x", one));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            // Unlike any other stamp, before or after: a file that cannot be read counts as
            // written and changed, and whoever reads it again finds out why.
            return "unreadable " + System.nanoTime() + ": " + unreadable.getMessage();
        }
    }
}
