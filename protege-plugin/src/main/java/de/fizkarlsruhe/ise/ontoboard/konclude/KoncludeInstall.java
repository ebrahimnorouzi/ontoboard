package de.fizkarlsruhe.ise.ontoboard.konclude;

import de.fizkarlsruhe.ise.ontoboard.tools.ToolAsset;
import de.fizkarlsruhe.ise.ontoboard.tools.ToolSetup;
import de.fizkarlsruhe.ise.ontoboard.tools.Unzip;
import java.io.File;
import java.io.IOException;
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
        return asset().getName();
    }

    /** A direct download link for this machine's asset. */
    public static String downloadUrl() {
        return asset().getUrl();
    }

    /** This machine's release asset, pinned. */
    public static ToolAsset asset() {
        return assetFor(System.getProperty("os.name", ""));
    }

    /**
     * The release asset for a system, by its {@code os.name}, pinned to the bytes measured for
     * this version.
     *
     * <p>Length and SHA-256 of each were computed from fresh downloads on 2026-11-05 - GitHub
     * publishes no digest for assets this old - and the Windows zip matched a copy downloaded
     * earlier. macOS is tested before Windows because "Darwin" contains "win".
     */
    public static ToolAsset assetFor(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String name;
        long size;
        String sha256;
        if (os.contains("mac") || os.contains("darwin")) {
            name = "Konclude-" + Konclude.KNOWN_RELEASE + "-OSX-x64-Clang-Static-Qt5.12.10.zip";
            size = 13249589L;
            sha256 = "b14900399dd373e5af8dbf9e3941914f701fa0ad079df3455e0ce88ba8463943";
        } else if (os.contains("win")) {
            name = "Konclude-" + Konclude.KNOWN_RELEASE + "-Windows-x64-MSVC-Dynamic-Qt5.15.2.zip";
            size = 18733629L;
            sha256 = "52ea75ebac2e996cb6bdb256271d1a772ec7b2ac1cce99c6da13a7d967698f98";
        } else {
            name = "Konclude-" + Konclude.KNOWN_RELEASE + "-Linux-x64-GCC-Static-Qt5.12.10.zip";
            size = 13926747L;
            sha256 = "37216c18f45a80165aec1e76aeefd62d933d6a3f8f134c5aca0707c676fda76e";
        }
        return new ToolAsset(name, "https://github.com/konclude/Konclude/releases/download/"
                + Konclude.KNOWN_RELEASE + "/" + name, size, sha256,
                "https://github.com/konclude/Konclude/releases/tag/" + Konclude.KNOWN_RELEASE,
                true);
    }

    /**
     * Unpacks a downloaded release next to where it will stay, and returns the binary.
     *
     * <p>The program is looked for in the zip's own list of entries first, before anything is
     * written: a zip that is not a Konclude release is refused with nothing unpacked from it, a
     * release of another build is used from wherever its {@code Binaries} folder is, and the
     * binary returned is the one this zip contained - never an older copy already lying in the
     * folder.
     *
     * <p>Unpacked whole, never the binary on its own: on Windows it needs the Qt and Boost
     * libraries beside it. Elsewhere a zip carries no executable bit Java can apply, so it is set
     * here on the binary and on the release's launcher script - without that the first run would
     * fail with "permission denied".
     *
     * @throws IOException when the zip has no Konclude where a release keeps it, or cannot be
     *     unpacked
     */
    public static File install(File zip, File into) throws IOException {
        String exe = executableName().toLowerCase(Locale.ROOT);
        String inside = null;
        for (String name : Unzip.names(zip)) {
            String entry = name.replace('\\', '/');
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.equals("binaries/" + exe) || lower.endsWith("/binaries/" + exe)) {
                inside = entry;
                break;
            }
        }
        if (inside == null) {
            throw new IOException(zip.getName() + " has no " + executableName() + " in a "
                    + "Binaries folder, where a Konclude release keeps it, so nothing was "
                    + "unpacked from it.");
        }
        Unzip.unzip(zip, into);
        File binary = new File(into, inside);
        if (!binary.isFile()) {
            throw new IOException("Unpacking " + zip.getName() + " did not produce "
                    + binary.getAbsolutePath() + ".");
        }
        if (!isWindows()) {
            binary.setExecutable(true, false);
            File launcher = new File(binary.getParentFile().getParentFile(), "Konclude");
            if (launcher.isFile()) {
                launcher.setExecutable(true, false);
            }
        }
        return binary;
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

    /** Why a binary that exists did not start, on this machine. */
    private static String notStarting() {
        return notStarting(System.getProperty("os.name", ""), vcRuntimePresent());
    }

    /**
     * Whether this machine has what {@code Konclude.exe} imports from Microsoft's C++ runtime.
     * Always true off Windows, where the releases are static.
     */
    public static boolean vcRuntimePresent() {
        return !isWindows() || ToolSetup.vcRuntimePresent(systemRoot());
    }

    /** {@code %SystemRoot%}, where Windows keeps System32. */
    private static File systemRoot() {
        String root = System.getenv("SystemRoot");
        return new File(root == null || root.trim().isEmpty() ? "C:\\Windows" : root.trim());
    }

    /**
     * Why a binary that exists did not start.
     *
     * <p>On Windows the Visual C++ runtime comes first: {@code Konclude.exe} imports
     * {@code msvcp140.dll} and {@code vcruntime140.dll} (read from the binary), the release zip
     * does not carry them, and without them a correctly unpacked Konclude cannot start either.
     */
    static String notStarting(String osName, boolean vcRuntimePresent) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        boolean mac = os.contains("mac") || os.contains("darwin");
        if (os.contains("win") && !mac && !vcRuntimePresent) {
            return "It did not start. Konclude.exe needs Microsoft's Visual C++ runtime, which "
                    + "this machine does not have: install the x64 Visual C++ Redistributable "
                    + "from " + ToolSetup.VC_REDIST_URL + ", then try again.";
        }
        if (os.contains("win") && !mac) {
            return "It did not start. The Windows release links Qt dynamically, so Konclude.exe "
                    + "needs the DLLs that were beside it in the zip - copying the exe out on its "
                    + "own is the usual cause. Point this at the exe where you unpacked it, with "
                    + "its neighbours intact.";
        }
        if (mac) {
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
