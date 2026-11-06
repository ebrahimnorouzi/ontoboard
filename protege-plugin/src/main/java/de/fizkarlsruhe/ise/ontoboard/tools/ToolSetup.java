package de.fizkarlsruhe.ise.ontoboard.tools;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the "not installed" dialog says, and what a file the user picked is taken to be.
 *
 * <p>Everything a person reads there is decided here, without Swing or a network, so it can be
 * checked for every kind of machine from one: a Protégé on Java 8, which cannot run Widoco; a
 * Windows without Microsoft's C++ runtime, which the Konclude release imports
 * ({@code msvcp140}, {@code vcruntime140} and their {@code _1} companions - read from its
 * binaries, and not in the zip); a Mac
 * with Apple silicon, for which there is no Konclude build; a machine with no network.
 */
public final class ToolSetup {

    /** The tools OntoBoard can fetch. */
    public enum Tool {
        WIDOCO, KONCLUDE
    }

    /** Where to get a Java new enough for Widoco. */
    public static final String ADOPTIUM_URL = "https://adoptium.net/temurin/releases/";

    /** Microsoft's permanent link to the x64 Visual C++ Redistributable. */
    public static final String VC_REDIST_URL = "https://aka.ms/vs/17/release/vc_redist.x64.exe";

    /** The way out of every failure: the user's browser, which trusts what their system trusts. */
    static final String BY_HAND = "Download it with your browser instead: Copy link or Open "
            + "release page, then Choose file...";

    private ToolSetup() {
    }

    /** What the dialog says when the tool is missing, one paragraph per line. */
    public static List<String> explanation(Tool tool, ToolAsset asset, String osName,
            String osArch, String javaSpecificationVersion, boolean vcRuntimePresent) {
        List<String> lines = new ArrayList<String>();
        String size = megabytes(asset.getSize());
        if (tool == Tool.WIDOCO) {
            lines.add("Widoco is not installed. It writes the HTML page an ODK project publishes, "
                    + "from the ontology's own annotations.");
            lines.add("Download... saves " + asset.getName() + " (" + size + ") from "
                    + asset.getUrl() + " into a folder you choose, checks that it is that "
                    + "release, and fills it in.");
            lines.add("Or download it yourself with Copy link or Open release page, then pick the "
                    + "jar with Choose file...");
            String spec = javaSpecificationVersion == null ? "" : javaSpecificationVersion;
            if (spec.startsWith("1.")) {
                lines.add("Widoco also needs Java 11 or newer, and this Protégé runs on Java "
                        + spec.substring(2) + ". Install one from " + ADOPTIUM_URL
                        + " and choose it in Run it with.");
            }
            return lines;
        }
        lines.add("Konclude is not installed on this machine. OntoBoard does not ship it: "
                + "Konclude is LGPLv3 and this plugin is Apache-2.0.");
        lines.add("Download... saves " + asset.getName() + " (" + size + ") from "
                + asset.getUrl() + " into a folder you choose, checks that it is that release, "
                + "unpacks it there and fills in the path.");
        lines.add("Or download it yourself with Copy link or Open release page, then pick the zip, "
                + "or " + binaryName(asset) + " inside it, with Choose file...");
        if (isWindows(osName) && !vcRuntimePresent) {
            lines.add("Konclude.exe also needs Microsoft's Visual C++ runtime, which this machine "
                    + "does not have. Install the x64 Visual C++ Redistributable from "
                    + VC_REDIST_URL + ".");
        }
        if (isMac(osName) && isArm(osArch)) {
            lines.add("This Mac has Apple silicon and Konclude is built for Intel only, so it runs "
                    + "under Rosetta 2. If Rosetta is not installed, run "
                    + "softwareupdate --install-rosetta in Terminal.");
        }
        return lines;
    }

    /**
     * Whether Windows has the C++ runtime {@code Konclude.exe} imports.
     *
     * @param systemRoot the Windows folder, {@code %SystemRoot%}
     */
    public static boolean vcRuntimePresent(File systemRoot) {
        File system32 = new File(systemRoot, "System32");
        for (String library : RUNTIME) {
            if (!new File(system32, library).isFile()) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the Windows release imports from Microsoft's runtime, read from its binaries:
     * {@code Konclude.exe} the first two, and Qt5Core the {@code _1} libraries as well, which a
     * 2015 or 2017 runtime does not have.
     */
    private static final String[] RUNTIME = {
        "vcruntime140.dll", "msvcp140.dll", "vcruntime140_1.dll", "msvcp140_1.dll"
    };

    /** What a file picked with Choose file... is taken to be. */
    public static final class Choice {

        /** Use it as it is; unpack it first; or refuse it, saying why. */
        public enum Kind {
            USE, UNPACK, REFUSE
        }

        private final Kind kind;
        private final File file;
        private final String reason;

        private Choice(Kind kind, File file, String reason) {
            this.kind = kind;
            this.file = file;
            this.reason = reason;
        }

        public Kind getKind() {
            return kind;
        }

        /** The file to use or unpack, or null when refused. */
        public File getFile() {
            return file;
        }

        /** Why it was refused, or empty. */
        public String getReason() {
            return reason;
        }
    }

    /**
     * Reads the file the user picked.
     *
     * <p>A file with the release's exact name is checked against the pin: under that name, any
     * other content is the release cut short or altered, and it is refused rather than run. A
     * file with another name is the user's own choice of version, as typing a path always was.
     */
    public static Choice interpret(Tool tool, ToolAsset asset, File chosen) throws IOException {
        if (chosen == null || !chosen.isFile()) {
            return new Choice(Choice.Kind.REFUSE, null, "Choose a file: " + asset.getName()
                    + (tool == Tool.KONCLUDE ? ", or " + binaryName(asset) + " from inside it."
                            : "."));
        }
        String name = chosen.getName();
        if (name.equals(asset.getName()) && !asset.getSha256().equals(Download.sha256(chosen))) {
            return new Choice(Choice.Kind.REFUSE, null, name + " is not the release OntoBoard "
                    + "knows: its SHA-256 differs, so it was probably cut short or altered on the "
                    + "way. Download it again.");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (tool == Tool.WIDOCO) {
            if (lower.endsWith(".jar")) {
                return new Choice(Choice.Kind.USE, chosen, "");
            }
            return new Choice(Choice.Kind.REFUSE, null, name + " is not a jar. Widoco is "
                    + asset.getName() + ".");
        }
        if (lower.endsWith(".zip")) {
            return new Choice(Choice.Kind.UNPACK, chosen, "");
        }
        if (name.equalsIgnoreCase(binaryName(asset))) {
            return new Choice(Choice.Kind.USE, chosen, "");
        }
        return new Choice(Choice.Kind.REFUSE, null, name + " is not Konclude. Choose the zip you "
                + "downloaded, " + asset.getName() + ", or " + binaryName(asset)
                + " from inside it.");
    }

    /** One sentence on why a download did not work, then the way round it. */
    public static String whyItFailed(Throwable failure, String javaVersion) {
        if (HttpsOpener.isCertificatePathFailure(failure)) {
            return "This Protégé's Java (" + javaVersion + ") does not trust the certificate "
                    + "GitHub's download server uses, so it cannot download from there. "
                    + BY_HAND;
        }
        UnknownHostException unknown = find(failure, UnknownHostException.class);
        if (unknown != null) {
            return "Could not reach " + unknown.getMessage() + ": this computer seems to be "
                    + "offline, or reaches the internet through a proxy that Java has not been "
                    + "told about. " + BY_HAND;
        }
        if (find(failure, SocketTimeoutException.class) != null) {
            return "GitHub did not answer in time. " + BY_HAND;
        }
        ConnectException refused = find(failure, ConnectException.class);
        if (refused != null) {
            return "Could not connect to GitHub: " + refused.getMessage() + ". " + BY_HAND;
        }
        String message = failure == null || failure.getMessage() == null
                ? "The download failed." : failure.getMessage().trim();
        return (message.endsWith(".") ? message : message + ".") + " " + BY_HAND;
    }

    private static <T extends Throwable> T find(Throwable failure, Class<T> kind) {
        Throwable at = failure;
        for (int depth = 0; at != null && depth < 20; depth++) {
            if (kind.isInstance(at)) {
                return kind.cast(at);
            }
            at = at.getCause() == at ? null : at.getCause();
        }
        return null;
    }

    /** The program inside a Konclude release: {@code Konclude.exe} only in the Windows one. */
    static String binaryName(ToolAsset asset) {
        return asset.getName().contains("-Windows-") ? "Konclude.exe" : "Konclude";
    }

    private static String megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0)) + " MB";
    }

    private static boolean isMac(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    private static boolean isWindows(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        return os.contains("win") && !isMac(osName);
    }

    private static boolean isArm(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return arch.equals("aarch64") || arch.equals("arm64");
    }
}
