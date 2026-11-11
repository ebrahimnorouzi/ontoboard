package de.fizkarlsruhe.ise.ontoboard.odk.seed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ODK's template folder, as the pinned image holds it: {@code /tools/templates}.
 *
 * <p>Twenty-eight files for v1.6.1 - fourteen Jinja2 templates and fourteen files ODK copies as
 * they are, one of them ROBOT's standard report profile, which only the image has. They are read
 * through an index rather than by listing a folder, because a bundle's class path cannot be listed
 * reliably, and because the index is also the record of what each file must hash to and which ones
 * a seeded project marks executable.
 *
 * <p>Nothing here interprets a template. The files are bytes; {@code OdkInstaller} decides what to
 * do with each.
 */
public final class TemplateSet {

    /** One file of the template folder. */
    public static final class Entry {
        private final String path;
        private final String sha256;
        private final boolean executable;
        private byte[] bytes;

        Entry(String path, String sha256, boolean executable) {
            this.path = path;
            this.sha256 = sha256;
            this.executable = executable;
        }

        /** Relative to the template folder, with {@code /}, the {@code .jinja2} suffix kept. */
        public String path() {
            return path;
        }

        /** Whether ODK renders this file; the others are copied byte for byte. */
        public boolean isTemplate() {
            return path.endsWith(SUFFIX);
        }

        /** Whether the image marks it executable, which the seeded file then is too. */
        public boolean isExecutable() {
            return executable;
        }

        /** What the image said this file hashes to when it was copied out. */
        public String sha256() {
            return sha256;
        }

        /** The file. Read once, and the same array every time: callers must not change it. */
        public synchronized byte[] bytes() {
            if (bytes == null) {
                bytes = read(OdkPin.RESOURCES + "templates/" + path);
            }
            return bytes;
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** What ODK calls a template: {@code TEMPLATE_SUFFIX} in odk.py. */
    public static final String SUFFIX = ".jinja2";

    private static TemplateSet bundled;

    private final List<Entry> entries;

    private TemplateSet(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    /**
     * The templates this bundle carries.
     *
     * @throws IllegalStateException when the bundle was built without them
     */
    public static synchronized TemplateSet bundled() {
        if (bundled == null) {
            String index = new String(read(OdkPin.RESOURCES + "templates.index"),
                    StandardCharsets.UTF_8);
            List<Entry> entries = new ArrayList<Entry>();
            for (String line : index.split("\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                // <sha256>  <644|755>  <path>
                String[] fields = line.trim().split("\\s+", 3);
                if (fields.length != 3) {
                    throw new IllegalStateException("templates.index has a line that is not "
                            + "'<sha256>  <mode>  <path>': " + line);
                }
                entries.add(new Entry(fields[2], fields[0], "755".equals(fields[1])));
            }
            bundled = new TemplateSet(entries);
        }
        return bundled;
    }

    /** Every file, in the index's order: sorted by path, so every machine installs alike. */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The file at {@code path}.
     *
     * @throws IllegalArgumentException when the template folder holds no such file
     */
    public Entry entry(String path) {
        for (Entry entry : entries) {
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        throw new IllegalArgumentException("ODK " + OdkPin.VERSION + " has no template file "
                + path);
    }

    private static byte[] read(String resource) {
        InputStream in = TemplateSet.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(resource + " is not in the plugin: it was built "
                    + "without ODK " + OdkPin.VERSION + "'s templates.");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException cannotRead) {
            throw new IllegalStateException("Could not read " + resource + " from the plugin: "
                    + cannotRead.getMessage(), cannotRead);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a class-path resource cannot usefully fail.
            }
        }
    }
}
