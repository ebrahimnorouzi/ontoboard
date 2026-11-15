package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command that runs something in ODK's container: what ODK's own {@code run.sh} issues,
 * without the terminal a plugin does not have.
 */
class OdkContainerTest {

    private static final String IMAGE = "obolibrary/odkfull:v1.6.1";
    /** What Docker Desktop reported on the machine this was written on: 15.5 GiB. */
    private static final long MEMORY = 16622047232L;

    private static OdkContainer.Host windows(long memory) {
        return new OdkContainer.Host(true, null, null, memory,
                Collections.<String, String>emptyMap());
    }

    private static OdkContainer.Host linux(long memory, String... proxyNamesAndValues) {
        Map<String, String> proxies = new LinkedHashMap<String, String>();
        for (int i = 0; i < proxyNamesAndValues.length; i += 2) {
            proxies.put(proxyNamesAndValues[i], proxyNamesAndValues[i + 1]);
        }
        return new OdkContainer.Host(false, "1000", "1000", memory, proxies);
    }

    private static String mountOf(File root) {
        return root.getAbsolutePath().replace('\\', '/') + ":/work";
    }

    // ------------------------------------------------------------------ the command

    @Test
    void theCommandIsWhatRunShRunsWithoutATerminal(@TempDir File root) {
        assertEquals(Arrays.asList("docker", "run", "--rm", "-v", mountOf(root), "-w",
                "/work/src/ontology", "-e", "ROBOT_JAVA_ARGS=-Xmx13G", "-e", "JAVA_OPTS=-Xmx13G",
                "-e", "ODK_DEBUG=no", IMAGE, "make", "test"),
                OdkContainer.command("docker", root, IMAGE, windows(MEMORY), "",
                        Arrays.asList("make", "test")));
    }

    /** ODK's entrypoint then works as that user, so nothing in the project ends up root's. */
    @Test
    void onLinuxAndMacTheFilesBelongToTheUser(@TempDir File root) {
        assertEquals(Arrays.asList("podman", "run", "--rm", "-v", mountOf(root), "-w",
                "/work/src/ontology", "-e", "ROBOT_JAVA_ARGS=-Xmx13G", "-e", "JAVA_OPTS=-Xmx13G",
                "-e", "ODK_USER_ID=1000", "-e", "ODK_GROUP_ID=1000", "-e", "ODK_DEBUG=no", IMAGE,
                "make", "test"),
                OdkContainer.command("podman", root, IMAGE, linux(MEMORY), null,
                        Arrays.asList("make", "test")));
    }

    @Test
    void theProjectsOwnMemorySettingWins(@TempDir File root) {
        List<String> command = OdkContainer.command("docker", root, IMAGE, windows(MEMORY),
                " -Xmx8G -XX:+UseG1GC ", Arrays.asList("make", "test"));

        assertTrue(command.contains("ROBOT_JAVA_ARGS=-Xmx8G -XX:+UseG1GC"), command.toString());
        assertTrue(command.contains("JAVA_OPTS=-Xmx8G -XX:+UseG1GC"), command.toString());
    }

    /** Nine tenths of what Docker has, in whole gigabytes, rounded down: run.sh's sum. */
    @Test
    void memoryIsNineTenthsOfDockersInWholeGigabytes() {
        assertEquals("-Xmx13G", OdkContainer.javaOptions("", windows(MEMORY)));
        assertEquals("-Xmx7G", OdkContainer.javaOptions("", windows(8L << 30)));
        assertEquals("-Xmx28G", OdkContainer.javaOptions("", windows(32L << 30)));
        // Unknown: what run.sh takes when it cannot ask Docker.
        assertEquals("-Xmx8G", OdkContainer.javaOptions("", windows(0)));
        // And never a heap of nothing, which Java refuses to start with.
        assertEquals("-Xmx1G", OdkContainer.javaOptions(null, windows(1L << 30)));
    }

    @Test
    void aProxyOnTheHostReachesJava(@TempDir File root) {
        List<String> command = OdkContainer.command("docker", root, IMAGE,
                linux(MEMORY, "HTTPS_PROXY", "http://proxy:3128"), "",
                Arrays.asList("make", "test"));

        String options = "-Xmx13G -Dhttps.proxyHost=proxy -Dhttps.proxyPort=3128";
        assertTrue(command.contains("ROBOT_JAVA_ARGS=" + options), command.toString());
        assertTrue(command.contains("JAVA_OPTS=" + options), command.toString());
    }

    @Test
    void proxiesAreReadAsRunShReadsThem() {
        assertEquals("-Xmx13G -Dhttp.proxyHost=web.example.org -Dhttp.proxyPort=8080"
                + " -Dhttps.proxyHost=secure.example.org -Dhttp.nonProxyHosts=localhost|.fiz.de",
                OdkContainer.javaOptions("", linux(MEMORY, "http_proxy",
                        "http://web.example.org:8080", "https_proxy", "secure.example.org",
                        "no_proxy", "localhost,.fiz.de")));
        // The capital spelling wins, as in run.sh.
        assertEquals("-Xmx13G -Dhttp.proxyHost=upper",
                OdkContainer.javaOptions("", linux(MEMORY, "HTTP_PROXY", "upper", "http_proxy",
                        "lower")));
    }

    /** The command is shown in the transcript, ready to paste - and to forward to a colleague. */
    @Test
    void aPasswordInTheProxyAddressStaysOutOfTheCommand(@TempDir File root) {
        List<String> command = OdkContainer.command("docker", root, IMAGE,
                linux(MEMORY, "HTTPS_PROXY", "https://jane:s3cret@proxy.example.org:3128/"), "",
                Arrays.asList("make", "test"));

        assertFalse(command.toString().contains("s3cret"), command.toString());
        assertFalse(command.toString().contains("jane"), command.toString());
        assertTrue(command.contains("JAVA_OPTS=-Xmx13G -Dhttps.proxyHost=proxy.example.org "
                + "-Dhttps.proxyPort=3128"), command.toString());
    }

    /**
     * Review focus 1. run.sh refuses a path with a space because its shell would split it; a
     * list of arguments has no such trouble, so a project under "My Ontologies" builds.
     */
    @Test
    void aFolderWithSpacesAndUmlautsIsOneArgument(@TempDir File temp) {
        File root = new File(temp, "José Müller/My Ontologies/eggboil");

        List<String> command = OdkContainer.command("docker", root, IMAGE, windows(MEMORY), "",
                Arrays.asList("make", "test"));

        assertEquals(mountOf(root), command.get(4));
        assertTrue(command.get(4).contains("José Müller/My Ontologies/eggboil:/work"));
        assertEquals(16, command.size());
    }

    /** ODK 1.6 moved the update into the image: the command is the image's own update_repo. */
    @Test
    void updateIsUpdateRepoInTheImage(@TempDir File root) {
        List<String> command = OdkContainer.command("docker", root, IMAGE, windows(MEMORY), "",
                Collections.singletonList("update_repo"));

        assertEquals(Arrays.asList(IMAGE, "update_repo"),
                command.subList(command.size() - 2, command.size()));
    }

    @Test
    void whatCannotBeRunIsRefused(@TempDir File root) {
        assertThrows(IllegalArgumentException.class, () -> OdkContainer.command("docker", root,
                null, windows(MEMORY), "", Arrays.asList("make", "test")));
        assertThrows(IllegalArgumentException.class, () -> OdkContainer.command("docker", root,
                " ", windows(MEMORY), "", Arrays.asList("make", "test")));
        assertThrows(IllegalArgumentException.class, () -> OdkContainer.command("docker", root,
                IMAGE, windows(MEMORY), "", Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> OdkContainer.command("docker", null,
                IMAGE, windows(MEMORY), "", Arrays.asList("make", "test")));
        assertThrows(IllegalArgumentException.class, () -> OdkContainer.command(null, root,
                IMAGE, windows(MEMORY), "", Arrays.asList("make", "test")));
    }

    // ------------------------------------------------------------------ the pass-through run.sh

    /**
     * A release script says {@code sh run.sh make ...} on every line. Inside the container
     * there is no Docker to start, so the project's run.sh is covered by one that runs what it
     * is given, where it stands. The project's own file is not touched.
     */
    @Test
    void theReleaseScriptGetsAPassThroughRunSh(@TempDir File root) {
        File stub = new File(root, "home/.ontoboard/run-passthrough.sh");
        List<String> plain = OdkContainer.command("docker", root, IMAGE, windows(MEMORY), "",
                Arrays.asList("sh", "release.sh"));

        List<String> command = OdkContainer.withPassThroughRunSh(plain, stub);

        int mount = command.indexOf(stub.getAbsolutePath().replace('\\', '/')
                + ":/work/src/ontology/run.sh:ro");
        assertTrue(mount > 0, command.toString());
        assertEquals("-v", command.get(mount - 1));
        assertTrue(mount < command.indexOf(IMAGE), "options come before the image: " + command);
        assertEquals(Arrays.asList(IMAGE, "sh", "release.sh"),
                command.subList(command.size() - 3, command.size()));
        assertEquals(plain.size() + 2, command.size());
        assertEquals(16, plain.size(), "the command given is not changed");
    }

    @Test
    void theStubIsWrittenOnceAndRunsWhatItIsGiven(@TempDir File home) throws IOException {
        File stub = OdkContainer.passThroughStub(home);

        String text = new String(Files.readAllBytes(stub.toPath()), StandardCharsets.UTF_8);
        assertEquals(new File(home, "run-passthrough.sh"), stub);
        assertTrue(text.startsWith("#!/bin/sh\n"), text);
        assertTrue(text.endsWith("\nexec \"$@\"\n"), text);
        assertFalse(text.contains("\r"), "a shell in the container reads it");

        assertTrue(stub.setLastModified(stub.lastModified() - 60000));
        long before = stub.lastModified();
        OdkContainer.passThroughStub(home);
        assertEquals(before, stub.lastModified(), "the same file is left alone");
    }

    @Test
    void aStubSomebodyChangedIsPutRight(@TempDir File home) throws IOException {
        File stub = OdkContainer.passThroughStub(home);
        byte[] bundled = Files.readAllBytes(stub.toPath());
        Files.write(stub.toPath(), "#!/bin/sh\nrm -rf /work\n".getBytes(StandardCharsets.UTF_8));

        OdkContainer.passThroughStub(home);

        assertArrayEquals(bundled, Files.readAllBytes(stub.toPath()));
    }

    // ------------------------------------------------------------------ the machine

    @Test
    void theHostIsReadFromTheMachine() {
        final List<String> asked = new ArrayList<String>();
        ProcessRunner.Runner machine = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                String line = String.join(" ", command);
                asked.add(line);
                if (line.equals("id -u")) {
                    return said("501");
                }
                if (line.equals("id -g")) {
                    return said("20");
                }
                if (line.equals("docker info --format {{.MemTotal}}")) {
                    return said("WARNING: No swap limit support", "16622047232");
                }
                return new ProcessRunner.Outcome(1, Collections.<String>emptyList(), false);
            }
        };
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("HTTPS_PROXY", "http://proxy:3128");
        environment.put("PATH", "/usr/bin");
        environment.put("no_proxy", "");

        OdkContainer.Host mac = OdkContainer.Host.detect("docker", machine, false, environment);
        OdkContainer.Host pc = OdkContainer.Host.detect("docker", machine, true, environment);

        assertEquals("501", mac.userId);
        assertEquals("20", mac.groupId);
        assertEquals(16622047232L, mac.dockerMemoryBytes);
        assertEquals(Collections.singletonMap("HTTPS_PROXY", "http://proxy:3128"),
                mac.proxyEnvironment);
        assertTrue(pc.windows);
        assertNull(pc.userId, "Windows has no id, and Docker Desktop needs none");
        assertNull(pc.groupId);
        assertEquals(16622047232L, pc.dockerMemoryBytes);
    }

    /** Podman keeps the number one level down. */
    @Test
    void podmanSaysItsMemoryElsewhere() {
        ProcessRunner.Runner machine = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) {
                return String.join(" ", command).equals("podman info --format {{.Host.MemTotal}}")
                        ? said("8589934592")
                        : new ProcessRunner.Outcome(125, Collections.singletonList(
                                "Error: template: info:1:2: can't evaluate field MemTotal"),
                                false);
            }
        };

        OdkContainer.Host host = OdkContainer.Host.detect("podman", machine, true,
                Collections.<String, String>emptyMap());

        assertEquals(8589934592L, host.dockerMemoryBytes);
    }

    @Test
    void aMachineThatAnswersNothingIsStillAHost() {
        ProcessRunner.Runner silent = new ProcessRunner.Runner() {
            @Override
            public ProcessRunner.Outcome run(File workingDirectory, List<String> command,
                    long timeoutMinutes, ProcessRunner.Sink sink) throws IOException {
                throw new IOException("Cannot run program");
            }
        };

        OdkContainer.Host host = OdkContainer.Host.detect("docker", silent, false,
                Collections.<String, String>emptyMap());

        assertNull(host.userId);
        assertEquals(0, host.dockerMemoryBytes);
        assertEquals("-Xmx8G", OdkContainer.javaOptions("", host));
    }

    private static ProcessRunner.Outcome said(String... lines) {
        return new ProcessRunner.Outcome(0, Arrays.asList(lines), false);
    }
}
