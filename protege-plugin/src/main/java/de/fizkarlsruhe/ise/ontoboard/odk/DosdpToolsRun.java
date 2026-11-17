package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import de.fizkarlsruhe.ise.ontoboard.tools.ToolSetup;
import de.fizkarlsruhe.ise.ontoboard.widoco.WidocoInstall;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * {@code dosdp-tools} as ODK's recipes call it: the release ODK v1.6.1's image carries
 * ({@link DosdpTools}), started as the image starts it, in the folder make runs in.
 *
 * <p>Its libraries are compiled for Java 11 - not one subcommand of 0.19.3 starts on Java 8
 * (measured) - so it runs on the first Java 11 or later of those Widoco's is looked for among
 * ({@link WidocoInstall#javaCandidates}): the one chosen for Widoco, the Java running
 * Prot&eacute;g&eacute; (5.6.9's is 11), {@code JAVA_HOME}, the java on the path. One choice
 * serves both programs.
 *
 * <p>What it prints is the line's standard output, as the shell has it; what it says on its
 * error stream goes to the transcript as it comes; its exit status is the line's. A run that is
 * stopped ends the program - asked to end, then ended outright if it is still there five
 * seconds later - and the line's status is 130.
 */
final class DosdpToolsRun implements RecipeShell.Tool {

    /** The least Java the release starts on. */
    static final int LEAST_JAVA = 11;

    /** The status of a line the person stopped, as the shell's for an interrupt. */
    private static final int STOPPED = 130;

    /** The option that names what a call writes - a file, or for a batch a folder. */
    private static final String OUTFILE = "--outfile";

    /** The Java to start the release with, or why there is none. */
    static final class Java {
        private final File executable;
        private final String whyNone;

        private Java(File executable, String whyNone) {
            this.executable = executable;
            this.whyNone = whyNone;
        }

        static Java at(File executable) {
            return new Java(executable, null);
        }

        static Java none(String why) {
            return new Java(null, why);
        }

        /** The java to start, or null when there is none new enough. */
        File executable() {
            return executable;
        }

        /** Why there is none, or null when there is. */
        String whyNone() {
            return whyNone;
        }
    }

    private final File home;
    private final Supplier<Java> java;
    private final BooleanSupplier stopped;
    private Java chosen;

    /**
     * @param home    where the release is installed ({@link DosdpTools#home()})
     * @param java    which Java starts it; asked once, when a call is first asked about
     * @param stopped whether the person has stopped the run; may be null
     */
    DosdpToolsRun(File home, Supplier<Java> java, BooleanSupplier stopped) {
        this.home = home;
        this.java = java;
        this.stopped = stopped;
    }

    /**
     * Why not: a file written outside the project; no Java new enough - downloading would not
     * help then; the release not installed - {@link DosdpTools#NOT_INSTALLED}, which the
     * download cures.
     */
    @Override
    public String whyNot(List<String> arguments) {
        String outside = whyWrittenOutside(arguments);
        if (outside != null) {
            return outside;
        }
        Java with = java();
        if (with.executable() == null) {
            return with.whyNone();
        }
        if (!DosdpTools.isInstalled(home)) {
            return DosdpTools.NOT_INSTALLED + " - OntoBoard downloads it once ("
                    + DosdpTools.DOWNLOAD_SIZE + ", the release ODK's image carries) into "
                    + home.getPath();
        }
        return null;
    }

    @Override
    public int run(RecipeShell.Call call) throws IOException {
        String why = whyNot(call.arguments());
        if (why != null) {
            call.complain("dosdp-tools: " + why);
            return 1;
        }
        ProcessBuilder builder = new ProcessBuilder(DosdpTools.command(java().executable(), home,
                call.arguments()));
        builder.directory(call.folder());
        final Process child = builder.start();
        // It reads nothing; an open pipe would be one more thing for it to wait on.
        child.getOutputStream().close();
        Thread printed = pump(child.getInputStream(), call::say);
        Thread said = pump(child.getErrorStream(), call::complain);
        try {
            while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (stopped != null && stopped.getAsBoolean()) {
                    child.destroy();
                    if (!child.waitFor(5, TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                        child.waitFor(5, TimeUnit.SECONDS);
                    }
                    finish(printed, said);
                    return STOPPED;
                }
            }
            finish(printed, said);
            return child.exitValue();
        } catch (InterruptedException interrupted) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
            return STOPPED;
        }
    }

    private Java java() {
        if (chosen == null) {
            chosen = java.get();
        }
        return chosen;
    }

    /** Reads what a stream says, line by line, as it comes. */
    private static Thread pump(InputStream stream, Consumer<String> line) {
        Thread reading = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(stream,
                    StandardCharsets.UTF_8))) {
                for (String one = lines.readLine(); one != null; one = lines.readLine()) {
                    line.accept(one);
                }
            } catch (IOException closed) {
                // The program ended, and its stream with it.
            }
        }, "dosdp-tools output");
        reading.setDaemon(true);
        reading.start();
        return reading;
    }

    /** Waits for what the program said to be read - not for ever: it has ended. */
    private static void finish(Thread... readers) throws InterruptedException {
        for (Thread reader : readers) {
            reader.join(5000);
        }
    }

    /** Why a call writes outside the project, or null: what {@code --outfile} names. */
    static String whyWrittenOutside(List<String> arguments) {
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String written = argument.startsWith(OUTFILE + "=")
                    ? argument.substring(OUTFILE.length() + 1)
                    : argument.equals(OUTFILE) && i + 1 < arguments.size()
                            ? arguments.get(i + 1) : null;
            String why = written == null ? null : RecipeShell.whyNotWritten(written);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ which Java

    /** The Java to start the release with here: Widoco's candidates, the first 11 or newer. */
    static Java onThisMachine() {
        String environment = System.getenv("JAVA_HOME");
        return first(WidocoInstall.javaCandidates(WidocoInstall.rememberedJava(),
                new File(System.getProperty("java.home")),
                environment == null || environment.trim().isEmpty() ? null : new File(environment),
                null), DosdpToolsRun::majorVersion);
    }

    /** The first of these that is Java 11 or newer, by what each says it is; or why none is. */
    static Java first(List<File> candidates, ToIntFunction<File> majorVersion) {
        List<String> seen = new ArrayList<String>();
        for (File candidate : candidates) {
            int major = majorVersion.applyAsInt(candidate);
            if (major >= LEAST_JAVA) {
                return Java.at(candidate);
            }
            seen.add(candidate.getPath() + (major < 0 ? " cannot be run" : " is Java " + major));
        }
        return Java.none("DOSDP-Tools " + DosdpTools.VERSION + " needs Java " + LEAST_JAVA
                + " or newer - its libraries are compiled for it - and OntoBoard found none: "
                + String.join(", ", seen) + ". Choose one under Build and release > "
                + "Documentation..., 'Run it with' - Widoco and DOSDP-Tools share it - or install "
                + "one from " + ToolSetup.ADOPTIUM_URL);
    }

    /** What each java said it is, this session: asked once, and again only when it changed. */
    private static final Map<String, Integer> VERSIONS = new ConcurrentHashMap<String, Integer>();

    /**
     * The major version a java is, or -1 when it cannot be run: the running Java's is its own;
     * any other is asked ({@code java -version}).
     */
    static int majorVersion(File java) {
        if (java.getAbsoluteFile().equals(running().getAbsoluteFile())) {
            String specification = System.getProperty("java.specification.version", "");
            return Integer.parseInt(specification.startsWith("1.")
                    ? specification.substring(2) : specification);
        }
        String key = java.getPath() + "|" + java.lastModified();
        Integer known = VERSIONS.get(key);
        if (known != null) {
            return known;
        }
        int major;
        try {
            ProcessRunner.Outcome said = ProcessRunner.real().run(null,
                    Arrays.asList(java.getPath(), "-version"), 1, null);
            major = said.timedOut() ? -1 : WidocoInstall.majorVersionIn(said.getOutput());
        } catch (IOException cannotRun) {
            major = -1;
        }
        VERSIONS.put(key, major);
        return major;
    }

    private static File running() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win");
        return new File(new File(System.getProperty("java.home"), "bin"),
                windows ? "java.exe" : "java");
    }
}
