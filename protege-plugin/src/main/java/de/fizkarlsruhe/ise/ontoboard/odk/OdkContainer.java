package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The command that runs something in ODK's container.
 *
 * <p>It is the command ODK's own {@code run.sh} issues, composed here because that script cannot
 * be used from a plugin: it passes {@code -ti}, and Docker refuses a terminal that is not there;
 * it needs {@code sh}, which Protégé on Windows does not find; and it refuses a project whose
 * path has a space in it, because its shell would split the path. A list of arguments has none
 * of these troubles, and can be shown in the transcript, ready to paste.
 *
 * <p>What run.sh passes is passed: the repository at {@code /work}, the working directory
 * {@code /work/src/ontology}, the memory for Java ({@code ROBOT_JAVA_ARGS}, {@code JAVA_OPTS}:
 * the project's {@code robot_java_args}, else nine tenths of what Docker has), the user's ids on
 * Linux and macOS so that the files made belong to the user, {@code ODK_DEBUG=no}, and the
 * host's proxy for Java. Not passed: the SSH agent and a GitHub token, which a build does not
 * need and a transcript should not show.
 */
public final class OdkContainer {

    /** What the command depends on beyond the project: the machine it is issued on. */
    public static final class Host {
        /** Windows has no user ids to pass, and Docker Desktop needs none. */
        public final boolean windows;
        /** {@code id -u}, or null. */
        public final String userId;
        /** {@code id -g}, or null. */
        public final String groupId;
        /** The memory the runtime reports, or 0 when it would not say. */
        public final long dockerMemoryBytes;
        /** The proxy variables the host has, by the names they have there. */
        public final Map<String, String> proxyEnvironment;

        public Host(boolean windows, String userId, String groupId, long dockerMemoryBytes,
                Map<String, String> proxyEnvironment) {
            this.windows = windows;
            this.userId = userId;
            this.groupId = groupId;
            this.dockerMemoryBytes = dockerMemoryBytes;
            this.proxyEnvironment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(proxyEnvironment));
        }

        /** A host nothing is known about: no ids, the default memory, no proxy. */
        public static Host unknown() {
            return new Host(MakeRun.isWindows(), null, null, 0,
                    Collections.<String, String>emptyMap());
        }

        /** Asks this machine. Starts a few short commands, so not for the event thread. */
        public static Host detect(String runtime, ProcessRunner.Runner runner) {
            return detect(runtime, runner, MakeRun.isWindows(), System.getenv());
        }

        static Host detect(String runtime, ProcessRunner.Runner runner, boolean windows,
                Map<String, String> environment) {
            String user = windows ? null : number(runner, "id", "-u");
            String group = windows ? null : number(runner, "id", "-g");
            // Docker has the figure at the top of what it reports; Podman one level down.
            boolean podman = "podman".equals(runtime);
            String memory = number(runner, runtime, "info", "--format",
                    podman ? "{{.Host.MemTotal}}" : "{{.MemTotal}}");
            if (memory == null) {
                memory = number(runner, runtime, "info", "--format",
                        podman ? "{{.MemTotal}}" : "{{.Host.MemTotal}}");
            }
            Map<String, String> proxies = new LinkedHashMap<String, String>();
            for (String name : PROXY_VARIABLES) {
                String value = environment.get(name);
                if (value != null && !value.trim().isEmpty()) {
                    proxies.put(name, value.trim());
                }
            }
            long bytes = 0;
            try {
                bytes = memory == null ? 0 : Long.parseLong(memory);
            } catch (NumberFormatException tooLong) {
                bytes = 0;
            }
            return new Host(windows, user, group, bytes, proxies);
        }

        /** The line of a command's answer that is a number and nothing else, or null. */
        private static String number(ProcessRunner.Runner runner, String... command) {
            try {
                ProcessRunner.Outcome outcome = runner.run(null, Arrays.asList(command), 1, null);
                if (outcome.isSuccess()) {
                    for (String line : outcome.getOutput()) {
                        if (line.trim().matches("[0-9]{1,18}")) {
                            return line.trim();
                        }
                    }
                }
                return null;
            } catch (IOException notThere) {
                return null;
            } catch (RuntimeException notThere) {
                return null;
            }
        }
    }

    private static final List<String> PROXY_VARIABLES = Arrays.asList("HTTP_PROXY", "http_proxy",
            "HTTPS_PROXY", "https_proxy", "NO_PROXY", "no_proxy");

    /** Where the pass-through is mounted: over the project's own wrapper, read-only. */
    private static final String RUN_SH_IN_CONTAINER = "/work/src/ontology/run.sh";

    private static final String STUB = "run-passthrough.sh";

    private OdkContainer() {
    }

    /**
     * The command for one run in the container.
     *
     * @param runtime       {@code docker} or {@code podman}
     * @param projectRoot   the repository's root, mounted at {@code /work}
     * @param image         the image with its tag; one that is on the machine, since a runtime
     *                      downloads what it does not have
     * @param robotJavaArgs the project's {@code robot_java_args}, or empty when it has none
     * @param inContainer   the command as the container sees it: {@code make test}
     */
    public static List<String> command(String runtime, File projectRoot, String image, Host host,
            String robotJavaArgs, List<String> inContainer) {
        if (runtime == null || runtime.trim().isEmpty()) {
            throw new IllegalArgumentException("no container runtime to run it with");
        }
        if (projectRoot == null) {
            throw new IllegalArgumentException("no project folder to mount");
        }
        if (image == null || image.trim().isEmpty()) {
            throw new IllegalArgumentException("no ODK image to run it in");
        }
        if (inContainer == null || inContainer.isEmpty()) {
            throw new IllegalArgumentException("no command to run in the container");
        }
        List<String> command = new ArrayList<String>();
        command.add(runtime.trim());
        command.add("run");
        // Without --rm a container per build would pile up unseen.
        command.add("--rm");
        command.add("-v");
        command.add(forMount(projectRoot) + ":/work");
        command.add("-w");
        command.add("/work/src/ontology");
        String java = javaOptions(robotJavaArgs, host);
        command.add("-e");
        command.add("ROBOT_JAVA_ARGS=" + java);
        command.add("-e");
        command.add("JAVA_OPTS=" + java);
        if (!host.windows && host.userId != null && host.groupId != null) {
            command.add("-e");
            command.add("ODK_USER_ID=" + host.userId);
            command.add("-e");
            command.add("ODK_GROUP_ID=" + host.groupId);
        }
        command.add("-e");
        command.add("ODK_DEBUG=no");
        command.add(image.trim());
        command.addAll(inContainer);
        return command;
    }

    /**
     * The same command with a pass-through {@code run.sh} mounted over the project's.
     *
     * <p>For what runs the project's own scripts: a release script says
     * {@code sh run.sh make ...} line after line, and inside the container there is no Docker
     * for run.sh to start. The pass-through runs what it is given, where it stands. It is one
     * file mounted over one file, read-only; the project's run.sh is not touched.
     *
     * <p>Only for a project that has a {@code src/ontology/run.sh}: a runtime asked to mount
     * over a file that is not there makes one, in the project.
     */
    public static List<String> withPassThroughRunSh(List<String> command, File stubOnHost) {
        int mount = command.indexOf("-v");
        if (mount < 0 || mount + 2 > command.size()) {
            throw new IllegalArgumentException("not a container command: " + command);
        }
        List<String> with = new ArrayList<String>(command);
        with.add(mount + 2, "-v");
        with.add(mount + 3, forMount(stubOnHost) + ":" + RUN_SH_IN_CONTAINER + ":ro");
        return with;
    }

    /**
     * The pass-through {@code run.sh} as a file on this machine, written if it is not there.
     *
     * <p>In the user's home folder, not the temporary one: Windows clears that while Protégé is
     * open, and a runtime asked to mount a file that has gone mounts an empty folder instead.
     *
     * @throws UncheckedIOException when it cannot be written
     */
    public static File passThroughStub() {
        return passThroughStub(new File(System.getProperty("user.home"), ".ontoboard"));
    }

    static File passThroughStub(File folder) {
        byte[] bundled = bundledStub();
        File stub = new File(folder, STUB);
        try {
            if (stub.isFile() && Arrays.equals(bundled, Files.readAllBytes(stub.toPath()))) {
                return stub;
            }
            Files.createDirectories(folder.toPath());
            Files.write(stub.toPath(), bundled);
            stub.setExecutable(true, false);
            return stub;
        } catch (IOException cannotWrite) {
            throw new UncheckedIOException("Could not write " + stub.getAbsolutePath()
                    + ", which a project script needs in ODK's container: "
                    + cannotWrite.getMessage(), cannotWrite);
        }
    }

    private static byte[] bundledStub() {
        InputStream in = OdkContainer.class.getClassLoader().getResourceAsStream("odk/" + STUB);
        if (in == null) {
            throw new IllegalStateException("odk/" + STUB + " is not in the plugin.");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        } catch (IOException cannotRead) {
            throw new UncheckedIOException("Could not read odk/" + STUB + " from the plugin",
                    cannotRead);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a class-path resource cannot usefully fail.
            }
        }
    }

    /**
     * A host path as a bind mount names it. Forward slashes: Docker Desktop takes them on
     * Windows, and a backslash inside the colon-separated mount would read as an escape.
     */
    private static String forMount(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }

    /**
     * What Java in the container is started with: {@code ODK_JAVA_OPTS} in run.sh.
     *
     * <p>The project's own setting when it has one, else {@code -Xmx} of nine tenths of the
     * runtime's memory in whole gigabytes (8 when the runtime would not say, as run.sh takes
     * where it cannot ask); then the host's proxy, as Java's properties.
     */
    static String javaOptions(String robotJavaArgs, Host host) {
        StringBuilder options = new StringBuilder();
        if (robotJavaArgs != null && !robotJavaArgs.trim().isEmpty()) {
            options.append(robotJavaArgs.trim());
        } else {
            long gigabytes = host.dockerMemoryBytes <= 0 ? 8
                    : Math.max(1, host.dockerMemoryBytes / 10 * 9 / (1L << 30));
            options.append("-Xmx").append(gigabytes).append('G');
        }
        proxy(options, "http", firstOf(host, "HTTP_PROXY", "http_proxy"));
        proxy(options, "https", firstOf(host, "HTTPS_PROXY", "https_proxy"));
        String none = firstOf(host, "NO_PROXY", "no_proxy");
        if (none != null) {
            options.append(" -Dhttp.nonProxyHosts=").append(none.replace(',', '|'));
        }
        return options.toString();
    }

    private static String firstOf(Host host, String upper, String lower) {
        String value = host.proxyEnvironment.get(upper);
        if (value == null || value.isEmpty()) {
            value = host.proxyEnvironment.get(lower);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * A proxy address as Java's {@code proxyHost} and {@code proxyPort}. run.sh cuts the scheme
     * off the front and the port off the end; this also drops a trailing slash and a name and
     * password, which Java's properties could not carry and a transcript must not show.
     */
    private static void proxy(StringBuilder options, String scheme, String address) {
        if (address == null) {
            return;
        }
        String host = address.replaceFirst("^https?://", "");
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        String port = null;
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.substring(colon + 1).matches("[0-9]+")) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        if (host.isEmpty()) {
            return;
        }
        options.append(" -D").append(scheme).append(".proxyHost=").append(host);
        if (port != null) {
            options.append(" -D").append(scheme).append(".proxyPort=").append(port);
        }
    }
}
