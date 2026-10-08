package de.fizkarlsruhe.ise.ontoboard.konclude;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finding Konclude on this machine, and saying where to get it when it is not there.
 *
 * <p>OntoBoard ships no Konclude - see {@link Konclude} for the licence reasoning - so the first
 * question the dialog has to answer is "do you have it?", and the second is "where do I get it?".
 * Both are answered here rather than in the dialog, so they can be tested without Swing.
 *
 * <p><b>Looked for in four places, cheapest first.</b> The saved preference; the executable on
 * {@code PATH}; the user's Downloads folder, including inside an unpacked release directory; and
 * the ODK native environment if one is configured. Downloads is in that list because it is where
 * a release zip lands and where it is usually unpacked, so somebody who has just followed the
 * download link should find the dialog has already filled the path in.
 *
 * <p><b>Found means it ran.</b> A file existing is not the same as a binary that starts, and on
 * Windows the two come apart in a way that would otherwise be baffling: the Windows release links
 * Qt <em>dynamically</em>, so {@code Konclude.exe} copied out of the zip on its own cannot start
 * at all - it needs the DLLs that were beside it. {@link #describe} therefore executes the binary
 * and reads its version rather than calling {@code File.canExecute}.
 */
public final class KoncludeInstall {

    private KoncludeInstall() {
    }

    /** Where a remembered path is kept, beside the other tool paths. */
    public static final String PREFERENCE_KEY = "ontoboard.konclude.binary";

    /**
     * The path chosen last time, or null.
     *
     * <p>In {@code java.util.prefs}, the same store {@code Toolchain} keeps the ODK native
     * environment in, so the two external-tool paths live together rather than one of them being
     * somewhere a user would never think to look.
     */
    public static File remembered() {
        try {
            String saved = java.util.prefs.Preferences
                    .userNodeForPackage(KoncludeInstall.class).get(PREFERENCE_KEY, "");
            return saved.trim().isEmpty() ? null : new File(saved.trim());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Remembers where Konclude is, so it is found without asking next time. */
    public static void remember(File binary) {
        try {
            java.util.prefs.Preferences node =
                    java.util.prefs.Preferences.userNodeForPackage(KoncludeInstall.class);
            if (binary == null) {
                node.remove(PREFERENCE_KEY);
            } else {
                node.put(PREFERENCE_KEY, binary.getAbsolutePath());
            }
        } catch (RuntimeException unavailable) {
            // Then it applies to this session only, which beats refusing to run.
        }
    }

    /** What the executable is called, which differs only by Windows's extension. */
    public static String executableName() {
        return isWindows() ? "Konclude.exe" : "Konclude";
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * The release asset for this machine, named exactly as the release page names it.
     *
     * <p>All three are x86-64. There is no arm64 build; on Apple silicon the OSX x64 binary runs
     * under Rosetta 2, which is worth saying out loud rather than letting somebody discover it as
     * "bad CPU type in executable".
     */
    public static String assetForThisMachine() {
        if (isWindows()) {
            return "Konclude-" + Konclude.KNOWN_RELEASE + "-Windows-x64-MSVC-Dynamic-Qt5.15.2.zip";
        }
        if (isMac()) {
            return "Konclude-" + Konclude.KNOWN_RELEASE + "-OSX-x64-Clang-Static-Qt5.12.10.zip";
        }
        return "Konclude-" + Konclude.KNOWN_RELEASE + "-Linux-x64-GCC-Static-Qt5.12.10.zip";
    }

    /** A direct download link for this machine's asset. */
    public static String downloadUrl() {
        return "https://github.com/konclude/Konclude/releases/download/"
                + Konclude.KNOWN_RELEASE + "/" + assetForThisMachine();
    }

    /** The user's Downloads folder, where a release zip lands and is usually unpacked. */
    public static File downloads() {
        return new File(System.getProperty("user.home", "."), "Downloads");
    }

    /**
     * Everywhere worth looking, in order, without running anything.
     *
     * <p>Pure and ordered so a test can assert the order without a filesystem: the caller filters
     * for what exists. The Downloads entries cover both shapes a user ends up with - the binary
     * sitting loose after unzipping into Downloads, and the release folder unpacked whole, which
     * is what double-clicking the zip produces.
     */
    public static List<File> candidates(File preference, File downloads, File nativeEnvironment) {
        List<File> places = new ArrayList<File>();
        if (preference != null) {
            places.add(preference);
        }
        String exe = executableName();
        if (downloads != null) {
            places.add(new File(downloads, exe));
            places.add(new File(new File(downloads, "Konclude"), exe));
            places.add(new File(new File(downloads, "Konclude"), "Binaries" + File.separator + exe));
            // The release unpacks to a directory named after the asset, minus the extension.
            String unpacked = assetForThisMachine();
            if (unpacked.endsWith(".zip")) {
                unpacked = unpacked.substring(0, unpacked.length() - 4);
            }
            places.add(new File(new File(downloads, unpacked), exe));
            places.add(new File(new File(downloads, unpacked),
                    "Binaries" + File.separator + exe));
        }
        if (nativeEnvironment != null) {
            places.add(new File(new File(nativeEnvironment, "bin"), exe));
        }
        return places;
    }

    /** The first candidate that is a file, or null. Does not run anything. */
    public static File firstPresent(List<File> candidates) {
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** What the dialog needs to know about this machine's Konclude. */
    public static final class Found {
        private final File binary;
        private final String version;
        private final String problem;

        Found(File binary, String version, String problem) {
            this.binary = binary;
            this.version = version;
            this.problem = problem;
        }

        /** Where it is, or null when there is none. */
        public File getBinary() {
            return binary;
        }

        /** What it reported when asked, or empty. */
        public String getVersion() {
            return version;
        }

        /** Why it cannot be used, or empty when it can. */
        public String getProblem() {
            return problem;
        }

        /** True when a binary was found AND it ran. */
        public boolean isUsable() {
            return binary != null && problem.isEmpty();
        }

        /** One line for the top of the dialog. */
        public String headline() {
            if (isUsable()) {
                return "Konclude is installed" + (version.isEmpty() ? "" : " — " + version);
            }
            if (binary == null) {
                return "Konclude is not installed on this machine";
            }
            return "Konclude was found but will not run";
        }
    }

    /** Not installed, with nothing found. */
    public static Found absent() {
        return new Found(null, "", "");
    }

    /**
     * Asks the binary what it is, which is the only way to know it will run.
     *
     * <p>{@code File.canExecute} is not the test. The Windows release links Qt dynamically, so an
     * exe lifted out of the zip without its DLLs is executable, present, and unable to start -
     * and the error it produces is a Windows dialog about a missing DLL, not something a reasoning
     * run can report. Running it once here turns that into a sentence in the dialog.
     *
     * @param probe runs the binary and returns its combined output, or null if it would not start
     */
    public static Found describe(File binary, Probe probe) {
        if (binary == null || !binary.isFile()) {
            return absent();
        }
        List<String> output;
        try {
            output = probe.run(binary);
        } catch (RuntimeException didNotStart) {
            output = null;
        }
        if (output == null || output.isEmpty()) {
            return new Found(binary, "", notStarting());
        }
        String version = "";
        for (String line : output) {
            if (line == null) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("konclude") && version.isEmpty()) {
                version = line.trim();
            }
        }
        if (version.isEmpty()) {
            return new Found(binary, "", notStarting());
        }
        // Konclude prints its banner across a few lines; one is enough to prove it started.
        return new Found(binary, version.length() > 90 ? version.substring(0, 90) : version, "");
    }

    private static String notStarting() {
        if (isWindows()) {
            return "It did not start. The Windows release links Qt dynamically, so Konclude.exe "
                    + "needs the DLLs that were beside it in the zip - copying the exe out on its "
                    + "own is the usual cause. Point this at the exe where you unpacked it, with "
                    + "its neighbours intact.";
        }
        if (isMac()) {
            return "It did not start. On Apple silicon the only build is x86-64, so it needs "
                    + "Rosetta 2; macOS may also quarantine a downloaded binary until you allow "
                    + "it in System Settings, or run: xattr -d com.apple.quarantine <path>";
        }
        return "It did not start. Check that the file is executable: chmod +x <path>";
    }

    /** How to run the binary to ask its version. Separated so tests need no process. */
    public interface Probe {
        List<String> run(File binary);
    }

    /** What to tell somebody who has no Konclude, in the order they need it. */
    public static List<String> howToInstall() {
        return Arrays.asList(
                "Download " + assetForThisMachine(),
                "from " + Konclude.RELEASES_URL,
                "Unpack it, then point the Konclude binary field at "
                        + executableName() + " inside it.",
                "Leaving it unpacked in your Downloads folder is enough — OntoBoard looks "
                        + "there and will fill the path in for you next time.");
    }
}
