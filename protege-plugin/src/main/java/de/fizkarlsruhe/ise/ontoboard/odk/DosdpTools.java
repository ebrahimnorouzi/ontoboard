package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.tools.ToolAsset;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * dosdp-tools 0.19.3, the release ODK v1.6.1's image runs for a project's DOSDP patterns
 * (generate, terms, prototype, query), downloaded on first use.
 *
 * <p>It is a Scala program of 192 jars (131 MB unpacked) that cannot be put into OntoBoard's
 * bundle - its libraries clash with ROBOT's - so it runs as a program of its own, on a Java 11
 * or later ({@link DosdpToolsRun}): its Jena is compiled for Java 11, and on Java 8 not one of
 * its commands starts (measured 2026-11-17).
 *
 * <p>The release's archive is unpacked into {@link #home()} only when every library in it is
 * byte for byte the one ODK's image carries - {@code odk/dosdp-tools-0.19.3.sha256}, recorded
 * from the image's {@code /tools/dosdptools/lib} - and no entry would land outside that folder.
 * Otherwise nothing is left behind.
 */
public final class DosdpTools {

    /** The release ODK v1.6.1's image carries. */
    public static final String VERSION = "0.19.3";
    private static final String FOLDER = "dosdp-tools-" + VERSION;
    static final String LAUNCHER = "org.monarchinitiative.dosdp-tools-" + VERSION
            + "-launcher.jar";
    private static final String MANIFEST = "/odk/dosdp-tools-" + VERSION + ".sha256";

    /**
     * How a refusal of OntoBoard's own engine says the release is not installed; Build and
     * Release offer the download on it.
     */
    public static final String NOT_INSTALLED = "DOSDP-Tools " + VERSION + " is not installed";

    /** The download's size as a person reads it: {@link #asset()}'s 118,862,977 bytes. */
    public static final String DOWNLOAD_SIZE = "119 MB";

    private DosdpTools() {
    }

    /** Whether a refusal of the engine is the want of the release, which downloading it cures. */
    public static boolean isMissingIn(String refusal) {
        return refusal != null && refusal.contains(NOT_INSTALLED);
    }

    /** The release's archive as GitHub serves it: 118,862,977 bytes, the SHA-256 measured. */
    public static ToolAsset asset() {
        return new ToolAsset(FOLDER + ".tgz", "https://github.com/INCATools/dosdp-tools/releases/"
                + "download/v" + VERSION + "/" + FOLDER + ".tgz", 118862977L,
                "c8c31420e97dfa9dd2f62a739633d2c8b4fffab4ab7088ea6abb78e3b0262051",
                "https://github.com/INCATools/dosdp-tools/releases/tag/v" + VERSION, false);
    }

    /** Where it is installed: {@code ~/.ontoboard/tools/dosdp-tools-0.19.3}. */
    public static File home() {
        return new File(new File(new File(System.getProperty("user.home"), ".ontoboard"),
                "tools"), FOLDER);
    }

    /** Whether it is there: its launcher in its {@code lib}. The libraries were held at install. */
    public static boolean isInstalled(File home) {
        return home != null && new File(new File(home, "lib"), LAUNCHER).isFile();
    }

    /** The SHA-256 of each library, by its file name, as ODK's image has them. */
    static Map<String, String> libraries() throws IOException {
        InputStream in = DosdpTools.class.getResourceAsStream(MANIFEST);
        if (in == null) {
            throw new IOException("OntoBoard's record of dosdp-tools' libraries is missing ("
                    + MANIFEST + ")");
        }
        Map<String, String> libraries = new LinkedHashMap<String, String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+", 2);
                libraries.put(parts[1].replaceFirst("^\\*", ""), parts[0].toLowerCase());
            }
        }
        return Collections.unmodifiableMap(libraries);
    }

    /**
     * Unpacks the release's archive into {@code home}, the archive's own first folder left out.
     *
     * @throws IOException when an entry would land outside {@code home}, when a library is not
     *                     the one ODK's image carries or one of those is missing, or when it
     *                     cannot be read or written; nothing is left behind then
     */
    public static void install(File tgz, File home) throws IOException {
        Map<String, String> wanted = libraries();
        File target = home.getAbsoluteFile();
        File parent = target.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        File staging = new File(parent, target.getName() + ".unpacking-" + System.nanoTime());
        Path inside = staging.toPath().toAbsolutePath().normalize();
        boolean installed = false;
        try {
            Set<String> found = new HashSet<String>();
            try (InputStream tar = new GZIPInputStream(new BufferedInputStream(
                    new FileInputStream(tgz)), 1 << 16)) {
                for (Entry entry = Entry.next(tar); entry != null; entry = Entry.next(tar)) {
                    String name = entry.name.replace('\\', '/');
                    int slash = name.indexOf('/');
                    String within = slash < 0 ? "" : name.substring(slash + 1);
                    if (within.isEmpty()) {
                        entry.skip(tar);
                        continue;
                    }
                    Path to = inside.resolve(within).normalize();
                    if (!to.startsWith(inside) || to.equals(inside)) {
                        throw new IOException("'" + name + "' in " + tgz.getName() + " would be "
                                + "written outside " + target + "; nothing was installed.");
                    }
                    if (entry.type == '5') {
                        Files.createDirectories(to);
                        entry.skip(tar);
                        continue;
                    }
                    if (entry.type != '0' && entry.type != 0) {
                        throw new IOException("'" + name + "' in " + tgz.getName() + " is an "
                                + "entry of type '" + entry.type + "' (a link, or a device), "
                                + "which the release has none of; nothing was installed.");
                    }
                    Files.createDirectories(to.getParent());
                    entry.copy(tar, to);
                    if (within.startsWith("lib/") && within.endsWith(".jar")) {
                        String jar = within.substring("lib/".length());
                        String expected = wanted.get(jar);
                        if (expected == null || !expected.equals(sha256(to))) {
                            throw new IOException("lib/" + jar + " in " + tgz.getName()
                                    + " is not the library ODK's image runs"
                                    + (expected == null ? " - the image has no such one" : "")
                                    + "; nothing was installed.");
                        }
                        found.add(jar);
                    }
                }
            }
            Set<String> missing = new TreeSet<String>(wanted.keySet());
            missing.removeAll(found);
            if (!missing.isEmpty()) {
                throw new IOException(tgz.getName() + " lacks " + missing.size() + " of the "
                        + "libraries ODK's image runs, " + missing.iterator().next()
                        + " the first; nothing was installed.");
            }
            if (target.exists()) {
                delete(target);
            }
            moveIntoPlace(staging.toPath(), target.toPath(), (from, to) -> Files.move(from, to),
                    MOVE_PATIENCE_MILLIS);
            installed = true;
        } finally {
            if (!installed) {
                delete(staging);
            }
        }
    }

    /** How long the unpacked folder's move into place is tried for. */
    static final long MOVE_PATIENCE_MILLIS = 30000;

    /** A move of one folder to another name; {@code Files.move} outside the tests. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /**
     * Moves the unpacked folder into place, and tries again while Windows refuses: a folder
     * cannot be renamed while something holds a file in it, and right after 192 libraries are
     * written something on the machine may still be reading them. Inside Protégé on Windows the
     * move was refused every time ("AccessDenied"; reproduced with Protégé's javaw.exe and not
     * with java.exe - 1.154.0's release check). A folder already there, or none to move, is no
     * passing refusal and is said at once.
     *
     * @return how many tries it took
     */
    static int moveIntoPlace(Path staging, Path target, Mover mover, long patienceMillis)
            throws IOException {
        long deadline = System.nanoTime() + patienceMillis * 1_000_000L;
        long pause = 50;
        for (int tries = 1; ; tries++) {
            try {
                mover.move(staging, target);
                return tries;
            } catch (java.nio.file.FileAlreadyExistsException
                    | java.nio.file.NoSuchFileException notPassing) {
                throw notPassing;
            } catch (java.nio.file.FileSystemException refused) {
                if (System.nanoTime() + pause * 1_000_000L > deadline) {
                    throw refused;
                }
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw refused;
                }
                pause = Math.min(pause * 2, 1000);
            }
        }
    }

    /**
     * An entry of a tar archive, read as POSIX ustar writes it, with the GNU long name and the
     * PAX path where an archive has them. OntoBoard reads tar itself: the bundle's
     * commons-compress (1.27.1) needs a newer commons-io than the bundle's (2.4) for tar.
     */
    private static final class Entry {
        private static final int BLOCK = 512;
        final String name;
        final char type;
        final long size;

        private Entry(String name, char type, long size) {
            this.name = name;
            this.type = type;
            this.size = size;
        }

        /** The next file or folder of the archive; null at its end. */
        static Entry next(InputStream tar) throws IOException {
            String longName = null;
            while (true) {
                byte[] block = new byte[BLOCK];
                if (!fill(tar, block)) {
                    return null;
                }
                if (allZero(block)) {
                    return null;
                }
                String name = text(block, 0, 100);
                if (text(block, 257, 5).equals("ustar")) {
                    String prefix = text(block, 345, 155);
                    name = prefix.isEmpty() ? name : prefix + "/" + name;
                }
                char type = (char) (block[156] & 0xff);
                long size = octal(block, 124, 12);
                if (type == 'L' || type == 'x' || type == 'g') {
                    // A GNU long name, or a PAX header: what it says is for the next entry.
                    byte[] data = new byte[(int) size];
                    if (!fill(tar, data)) {
                        throw new IOException("The archive ends inside a header.");
                    }
                    skipPadding(tar, size);
                    if (type == 'L') {
                        longName = new String(data, StandardCharsets.UTF_8).replace("\0", "");
                    } else if (type == 'x') {
                        String path = paxPath(data);
                        longName = path != null ? path : longName;
                    }
                    continue;
                }
                return new Entry(longName != null ? longName : name, type, size);
            }
        }

        void copy(InputStream tar, Path to) throws IOException {
            try (java.io.OutputStream out = Files.newOutputStream(to)) {
                byte[] buffer = new byte[1 << 16];
                long left = size;
                while (left > 0) {
                    int read = tar.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (read < 0) {
                        throw new IOException("The archive ends inside " + name + ".");
                    }
                    out.write(buffer, 0, read);
                    left -= read;
                }
            }
            skipPadding(tar, size);
        }

        void skip(InputStream tar) throws IOException {
            skipBytes(tar, size);
            skipPadding(tar, size);
        }

        private static void skipPadding(InputStream tar, long size) throws IOException {
            long rest = size % BLOCK;
            if (rest != 0) {
                skipBytes(tar, BLOCK - rest);
            }
        }

        private static void skipBytes(InputStream tar, long count) throws IOException {
            byte[] buffer = new byte[BLOCK];
            long left = count;
            while (left > 0) {
                int read = tar.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) {
                    throw new IOException("The archive ends early.");
                }
                left -= read;
            }
        }

        private static boolean fill(InputStream in, byte[] into) throws IOException {
            int at = 0;
            while (at < into.length) {
                int read = in.read(into, at, into.length - at);
                if (read < 0) {
                    if (at == 0) {
                        return false;
                    }
                    throw new IOException("The archive ends inside a block.");
                }
                at += read;
            }
            return true;
        }

        private static boolean allZero(byte[] block) {
            for (byte b : block) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        private static String text(byte[] block, int from, int length) {
            int end = from;
            while (end < from + length && block[end] != 0) {
                end++;
            }
            return new String(block, from, end - from, StandardCharsets.UTF_8);
        }

        private static long octal(byte[] block, int from, int length) throws IOException {
            String digits = text(block, from, length).trim();
            if (digits.isEmpty()) {
                return 0;
            }
            try {
                return Long.parseLong(digits, 8);
            } catch (NumberFormatException notOctal) {
                throw new IOException("An entry's size is not read: '" + digits + "'");
            }
        }

        /** The {@code path} of a PAX header's records ({@code <length> path=<value>\n}). */
        private static String paxPath(byte[] data) {
            String records = new String(data, StandardCharsets.UTF_8);
            for (String record : records.split("\n")) {
                int space = record.indexOf(' ');
                String body = space < 0 ? record : record.substring(space + 1);
                if (body.startsWith("path=")) {
                    return body.substring("path=".length());
                }
            }
            return null;
        }
    }

    /**
     * How ODK starts it: {@code java -jar <the launcher> <arguments>} - with what the image's
     * Java takes from Linux: the encoding of {@code LANG=C.UTF-8}, which on Windows would be
     * windows-1252, and lines that end in LF, which on Windows would end in CRLF - in a term
     * list, say, which is written by println.
     */
    public static List<String> command(File java, File home, List<String> arguments) {
        List<String> command = new ArrayList<String>();
        command.add(java.getPath());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dline.separator=\n");
        command.add("-jar");
        command.add(new File(new File(home, "lib"), LAUNCHER).getPath());
        command.addAll(arguments);
        return command;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            try (InputStream in = Files.newInputStream(file)) {
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("No SHA-256 in this Java", impossible);
        }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        for (File child : children == null ? new File[0] : children) {
            delete(child);
        }
        file.delete();
    }
}
