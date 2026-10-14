package de.fizkarlsruhe.ise.ontoboard.bench;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes {@code java -jar ontoboard.jar} work on a bundle built for OSGi.
 *
 * <p>This exists because the obvious thing does not work, and it was found by trying it. The jar
 * is a Protege plugin: {@code Embed-Dependency} puts 104 dependency jars <em>inside</em> it and
 * lists them in {@code Bundle-ClassPath}, which Felix understands and the {@code java} launcher
 * does not. So {@code java -cp ontoboard.jar ...Bench version} prints a version - that needs only
 * our own class - and {@code ...Bench corpus book.xlsx} dies with
 * {@code NoClassDefFoundError: org/obolibrary/robot/exceptions/RowParseException}, because
 * robot-core is a jar within a jar and nothing is looking inside it.
 *
 * <p>A reproduction package that needs a reader to assemble a classpath out of 104 nested jars is
 * not a reproduction package. So: read {@code Bundle-ClassPath} out of our own manifest, unpack
 * exactly those entries once into a cache beside the temporary directory, build a class loader
 * over them, and hand control to {@link Bench} through it. Manifest-driven rather than a list, so
 * that adding a dependency to the pom cannot leave this behind.
 *
 * <p>The cache is keyed on the bundle version and marked complete only when every entry is out,
 * so the first run pays a second or two and later runs pay nothing. Nothing here is specific to
 * the bench: it is a way to run any class in this bundle from a command line.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        File bundle = ourJar();
        List<String> embedded = bundle == null ? null : classPathOf(bundle);
        if (bundle == null || embedded == null || embedded.isEmpty()) {
            // Already a flat classpath - running from target/classes, or from a jar that does
            // not embed anything. Nothing to unpack.
            Bench.main(args);
            return;
        }
        File unpacked = unpack(bundle, embedded);
        List<URL> classpath = new ArrayList<URL>();
        classpath.add(bundle.toURI().toURL());
        File[] jars = unpacked.listFiles();
        if (jars != null) {
            for (File jar : jars) {
                if (jar.getName().endsWith(".jar")) {
                    classpath.add(jar.toURI().toURL());
                }
            }
        }
        URLClassLoader loader = new URLClassLoader(classpath.toArray(new URL[0]),
                Launcher.class.getClassLoader().getParent());
        Thread.currentThread().setContextClassLoader(loader);
        Class<?> bench = loader.loadClass("de.fizkarlsruhe.ise.ontoboard.bench.Bench");
        Method main = bench.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException) {
                System.err.println(whatIsMissing(cause));
                System.exit(3);
            }
            throw thrown;
        }
    }

    /**
     * What to say when a class is missing, which is not the same as what went wrong.
     *
     * <p>The OWL API is not in this bundle, deliberately: Protege provides it, so the dependency
     * is {@code provided} and embedding a second copy would break the plugin inside Protege.
     * Nor are Protege's own jars a substitute for the rest - this was tried, and the bundle
     * embeds logback 1.5.6 against slf4j-api 1.7.12, which only resolves because Protege's OSGi
     * framework wires {@code org.slf4j} to its own 2.x. Reassembling that by hand on a flat
     * classpath is not a supported configuration and should not be pretended into one.
     *
     * <p>So the honest answer is the two commands below, which is how every number in
     * docs/measurements.md was produced.
     */
    private static String whatIsMissing(Throwable cause) {
        return "ontoboard bench: " + cause.getMessage()
                + "\n\nThis jar is a Protege plugin, and Protege provides the OWL API, so it is"
                + "\nnot in here on purpose: a second copy would break the plugin in the host."
                + "\n\nRun the measurements from a checkout instead. Two commands, no Protege:"
                + "\n\n    mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt"
                + "\n    java -cp \"target/classes" + File.pathSeparator
                + "$(cat target/cp.txt)\" \\"
                + "\n         de.fizkarlsruhe.ise.ontoboard.bench.Bench corpus book.xlsx"
                + "\n\nSee docs/measurements.md. The version and --help commands work from"
                + "\nthis jar on its own.";
    }

    /** The jar this class was loaded from, or null when it was not loaded from one. */
    private static File ourJar() {
        try {
            java.security.CodeSource source =
                    Launcher.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File file = new File(source.getLocation().toURI());
            return file.isFile() ? file : null;
        } catch (RuntimeException notAFile) {
            return null;
        } catch (java.net.URISyntaxException notAPath) {
            return null;
        }
    }

    /** The nested jars named by {@code Bundle-ClassPath}, in the order the manifest lists them. */
    private static List<String> classPathOf(File bundle) throws IOException {
        ZipFile zip = new ZipFile(bundle);
        try {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            try {
                Attributes main = new Manifest(in).getMainAttributes();
                String declared = main.getValue("Bundle-ClassPath");
                if (declared == null) {
                    return null;
                }
                List<String> jars = new ArrayList<String>();
                for (String each : declared.split(",")) {
                    String one = each.trim();
                    if (one.endsWith(".jar")) {
                        jars.add(one);
                    }
                }
                return jars;
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    /**
     * The nested jars, on disk, extracted once.
     *
     * <p>Keyed on the bundle's own version and size, so two builds of one version cannot share a
     * stale cache, and marked complete only after the last entry is written - a half-unpacked
     * cache that looked finished would fail in a way nobody could explain.
     */
    private static File unpack(File bundle, List<String> embedded) throws IOException {
        ZipFile zip = new ZipFile(bundle);
        try {
            String version = versionOf(zip);
            File cache = new File(System.getProperty("java.io.tmpdir"),
                    "ontoboard-" + version + "-" + bundle.length() + "-classpath");
            File marker = new File(cache, ".complete");
            if (marker.isFile()) {
                return cache;
            }
            if (!cache.isDirectory() && !cache.mkdirs()) {
                throw new IOException("Could not make " + cache);
            }
            System.err.println("ontoboard: unpacking " + embedded.size()
                    + " embedded jars into " + cache + " (once)");
            for (String name : embedded) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) {
                    continue;
                }
                File out = new File(cache, new File(name).getName());
                InputStream in = zip.getInputStream(entry);
                try {
                    OutputStream to = Files.newOutputStream(out.toPath());
                    try {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            to.write(buffer, 0, read);
                        }
                    } finally {
                        to.close();
                    }
                } finally {
                    in.close();
                }
            }
            Files.write(marker.toPath(), version.getBytes("UTF-8"));
            return cache;
        } finally {
            zip.close();
        }
    }

    private static String versionOf(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
        if (entry == null) {
            return "unknown";
        }
        InputStream in = zip.getInputStream(entry);
        try {
            String said = new Manifest(in).getMainAttributes().getValue("Bundle-Version");
            return said == null || said.trim().isEmpty() ? "unknown" : said.trim();
        } finally {
            in.close();
        }
    }
}
