package de.fizkarlsruhe.ise.ontoboard.widoco;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finding Widoco, and finding a Java new enough to run it.
 *
 * <p><b>Why it is not embedded, measured rather than assumed.</b> Widoco is not on Maven Central
 * at all - a search for the artifact returns nothing - so there is no coordinate to depend on.
 * Its only distributed form is a shaded fat jar of about 39 MB with some 26,500 entries, and
 * inside it are a second OWL API (1,533 {@code org/semanticweb/owlapi} entries), a second Guava
 * (2,044 {@code com/google/common}), rdf4j and Saxon. Those are packages this bundle imports
 * from the host; embedding a second copy is how an OSGi bundle stops resolving. So Widoco is a
 * tool you install and OntoBoard finds, exactly as {@code KoncludeInstall} treats Konclude.
 *
 * <p><b>And it needs Java 11.</b> The published jar is compiled to class file version 55.
 * Measured on the two hosts this plugin is smoked against: Prot&eacute;g&eacute; 5.6.9 ships
 * Temurin 11.0.25 and can run it; 5.5.0 ships 1.8.0_121 and cannot. That is not a reason to
 * refuse on 5.5.0 - it is a reason to go looking for another JVM and to say plainly which one
 * is being used, because "it failed" and "your Prot&eacute;g&eacute;'s Java is too old, but
 * there is an 11 on your PATH" are different messages.
 */
public final class WidocoInstall {

    private WidocoInstall() {
    }

    /** Where the chosen jar is remembered between sessions. */
    public static final String PREFERENCE_KEY = "ontoboard.widoco.jar";

    /** Where the chosen Java is remembered, when it is not the one running this. */
    public static final String JAVA_PREFERENCE_KEY = "ontoboard.widoco.java";

    /** The release this was built against. */
    public static final String KNOWN_RELEASE = "v1.4.25";

    /** The lowest Java that can load the jar: it is compiled to class file version 55. */
    public static final int LEAST_JAVA = 11;

    /** The one asset to download. There has been no JDK-8 build published since 2022. */
    public static String assetForThisMachine() {
        return "widoco-" + KNOWN_RELEASE.substring(1) + "-jar-with-dependencies_JDK-11.jar";
    }

    public static String downloadUrl() {
        return "https://github.com/dgarijo/Widoco/releases/download/" + KNOWN_RELEASE + "/"
                + assetForThisMachine();
    }

    /** What to tell somebody who has none of it. */
    public static List<String> howToInstall() {
        return Collections.unmodifiableList(Arrays.asList(
                "Download " + assetForThisMachine() + " (about 39 MB)",
                "from " + downloadUrl(),
                "then point the Widoco jar field at it. OntoBoard remembers where it is.",
                "It is not bundled: the jar carries its own OWL API and Guava, and a second copy "
                        + "of those inside this plugin would stop it resolving."));
    }

    // ---------------------------------------------------------------- the jar

    public static File remembered() {
        return rememberedAt(PREFERENCE_KEY);
    }

    public static void remember(File jar) {
        rememberAt(PREFERENCE_KEY, jar);
    }

    /**
     * Where the jar might be, in the order worth looking.
     *
     * <p>Pure, so the order is testable without a filesystem. The remembered path first because
     * it is the one the user chose; then Downloads, where a browser puts it, both loose and
     * inside a folder named after the asset, which is what an unzip produces.
     */
    public static List<File> candidates(File preference, File downloads,
            File nativeEnvironment) {
        List<File> places = new ArrayList<File>();
        if (preference != null) {
            places.add(preference);
        }
        String asset = assetForThisMachine();
        if (downloads != null) {
            places.add(new File(downloads, asset));
            places.add(new File(downloads, "widoco.jar"));
            places.add(new File(new File(downloads, "widoco"), asset));
        }
        if (nativeEnvironment != null) {
            places.add(new File(nativeEnvironment, asset));
            places.add(new File(nativeEnvironment, "widoco.jar"));
        }
        return Collections.unmodifiableList(places);
    }

    /** The first of these that exists, or null. */
    public static File firstPresent(List<File> candidates) {
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the java

    public static File rememberedJava() {
        return rememberedAt(JAVA_PREFERENCE_KEY);
    }

    public static void rememberJava(File java) {
        rememberAt(JAVA_PREFERENCE_KEY, java);
    }

    /**
     * Which {@code java} to try, best first.
     *
     * <p>The JVM running Prot&eacute;g&eacute; comes first when it is new enough, because it is
     * certainly present and needs no configuration. On 5.6.9 that is Temurin 11.0.25 and the
     * search stops there; on 5.5.0 it is 1.8.0_121 and the next candidates matter.
     *
     * @param javaHome the {@code java.home} of the running JVM
     * @param javaHomeEnv the {@code JAVA_HOME} environment variable, or null
     */
    public static List<File> javaCandidates(File preference, File javaHome, File javaHomeEnv,
            File nativeEnvironment) {
        List<File> places = new ArrayList<File>();
        if (preference != null) {
            places.add(preference);
        }
        for (File home : new File[] {javaHome, javaHomeEnv, nativeEnvironment}) {
            if (home == null) {
                continue;
            }
            places.add(new File(new File(home, "bin"), executable()));
        }
        // Last: whatever `java` resolves to on PATH. Named without a directory so the process
        // layer resolves it, which is the same thing `git` and `gh` rely on elsewhere here.
        places.add(new File(executable()));
        return Collections.unmodifiableList(places);
    }

    private static String executable() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
    }

    /** {@code java -version}, which prints to standard error and is read from either. */
    public static List<String> versionCommand(File java) {
        return Collections.unmodifiableList(Arrays.asList(
                java == null ? executable() : java.getAbsolutePath(), "-version"));
    }

    /**
     * The major version {@code java -version} reported, or -1.
     *
     * <p>Both spellings, because they are both in front of us: 1.8.0_121 means 8, and
     * 11.0.25 means 11. Reading the first number only would call Java 8 "Java 1".
     */
    public static int majorVersionIn(List<String> output) {
        if (output == null) {
            return -1;
        }
        for (String line : output) {
            if (line == null) {
                continue;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(line);
            if (!matcher.find()) {
                continue;
            }
            int first = Integer.parseInt(matcher.group(1));
            if (first == 1 && matcher.group(2) != null) {
                return Integer.parseInt(matcher.group(2));
            }
            return first;
        }
        return -1;
    }

    /** Whether that output describes a Java new enough, by {@link #LEAST_JAVA}. */
    public static boolean isNewEnough(List<String> output) {
        return majorVersionIn(output) >= LEAST_JAVA;
    }

    /**
     * Why this Java will not do, or null when it will.
     *
     * @param where how to describe it, for the message
     */
    public static String whyJavaWillNotDo(String where, List<String> output) {
        int major = majorVersionIn(output);
        if (major < 0) {
            return "Could not tell what version " + where + " is.";
        }
        if (major < LEAST_JAVA) {
            return where + " is Java " + major + ", and Widoco's published jar needs "
                    + LEAST_JAVA + " or newer - it is compiled to class file version 55. "
                    + "Protege 5.5.0 ships Java 8, so on that host OntoBoard looks for another "
                    + "one; point the Java field at a JDK 11 if it found none.";
        }
        return null;
    }

    // ---------------------------------------------------------------- shared

    private static File rememberedAt(String key) {
        try {
            String path = java.util.prefs.Preferences
                    .userNodeForPackage(WidocoInstall.class).get(key, "");
            return path.isEmpty() ? null : new File(path);
        } catch (RuntimeException noPreferences) {
            return null;
        }
    }

    private static void rememberAt(String key, File file) {
        try {
            java.util.prefs.Preferences node =
                    java.util.prefs.Preferences.userNodeForPackage(WidocoInstall.class);
            if (file == null) {
                node.remove(key);
            } else {
                node.put(key, file.getAbsolutePath());
            }
        } catch (RuntimeException noPreferences) {
            // Not being able to remember is not a reason to refuse to run.
        }
    }
}
